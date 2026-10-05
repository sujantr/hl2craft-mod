// hl2capture: stream the Half-Life 2 window into a memory-mapped RGBA file for the HL2Craft Minecraft mod.
//
// The HL2 key room is pure green (0,255,0); green becomes transparent, with spill suppression on the edges.
// File layout (little-endian), two slots so the reader never sees a half-written frame:
//   0  "HC2F"   4 u32 version=1   8 u32 width   12 u32 height
//   16 u64 seq  (latest completed frame; slot = seq & 1)   24 u64 unix-ms of that frame
//   32 slot0 RGBA (w*h*4)   32+w*h*4 slot1 RGBA
//
// build: swiftc -O -o hl2capture hl2capture.swift
// run:   ./hl2capture [--width 960] [--height 540] [--out ~/Library/Caches/hl2craft/frame.rgba]
import AppKit
import CoreMedia
import CoreVideo
import Foundation
import ScreenCaptureKit

// A command-line tool must connect to the window server before ScreenCaptureKit will run.
NSApplication.shared.setActivationPolicy(.prohibited)
setvbuf(stdout, nil, _IOLBF, 0)

var width = 960, height = 540
var outPath = NSString(string: "~/Library/Caches/hl2craft/frame.rgba").expandingTildeInPath
var args = CommandLine.arguments.dropFirst().makeIterator()
while let a = args.next() {
    switch a {
    case "--width": width = Int(args.next()!)!
    case "--height": height = Int(args.next()!)!
    case "--out": outPath = NSString(string: args.next()!).expandingTildeInPath
    default: print("unknown arg \(a)"); exit(2)
    }
}

let header = 32
let slotSize = width * height * 4
let fileSize = header + 2 * slotSize
try FileManager.default.createDirectory(atPath: (outPath as NSString).deletingLastPathComponent,
                                        withIntermediateDirectories: true)
let fd = open(outPath, O_RDWR | O_CREAT, 0o644)
guard fd >= 0, ftruncate(fd, off_t(fileSize)) == 0 else { print("cannot open \(outPath)"); exit(1) }
guard let map = mmap(nil, fileSize, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0), map != MAP_FAILED else {
    print("mmap failed"); exit(1)
}
let base = map.assumingMemoryBound(to: UInt8.self)
memcpy(base, "HC2F", 4)
base.advanced(by: 4).withMemoryRebound(to: UInt32.self, capacity: 3) { p in
    p[0] = 1; p[1] = UInt32(width); p[2] = UInt32(height)
}
let seqPtr = UnsafeMutableRawPointer(base.advanced(by: 16)).assumingMemoryBound(to: UInt64.self)
let timePtr = UnsafeMutableRawPointer(base.advanced(by: 24)).assumingMemoryBound(to: UInt64.self)
var seq: UInt64 = 0

/// BGRA (capture) -> RGBA with chroma key. Green-dominant pixels go transparent; edges lose their green spill.
func keyFrame(_ pb: CVPixelBuffer, into dst: UnsafeMutablePointer<UInt8>) {
    CVPixelBufferLockBaseAddress(pb, .readOnly)
    defer { CVPixelBufferUnlockBaseAddress(pb, .readOnly) }
    let w = min(CVPixelBufferGetWidth(pb), width), h = min(CVPixelBufferGetHeight(pb), height)
    let stride = CVPixelBufferGetBytesPerRow(pb)
    let src = CVPixelBufferGetBaseAddress(pb)!.assumingMemoryBound(to: UInt8.self)
    for y in 0..<h {
        let s = src.advanced(by: y * stride)
        let d = dst.advanced(by: y * width * 4)
        for x in 0..<w {
            let b = Int(s[x * 4]), g = Int(s[x * 4 + 1]), r = Int(s[x * 4 + 2])
            let m = max(r, b)
            var a = 255
            if g > m {
                // how much greener than the other channels: 0 = neutral, 255+ = pure key
                let k = (g - m) * 2
                a = max(0, 255 - k)
            }
            d[x * 4] = UInt8(r)
            d[x * 4 + 1] = UInt8(min(g, m + 8))  // spill suppression
            d[x * 4 + 2] = UInt8(b)
            d[x * 4 + 3] = UInt8(a)
        }
    }
}

final class Output: NSObject, SCStreamOutput, SCStreamDelegate {
    var frames = 0
    var last = Date()
    func stream(_ stream: SCStream, didOutputSampleBuffer sb: CMSampleBuffer, of type: SCStreamOutputType) {
        guard type == .screen, sb.isValid, let pb = sb.imageBuffer else { return }
        let next = seq + 1
        keyFrame(pb, into: base.advanced(by: header + Int(next & 1) * slotSize))
        OSMemoryBarrier()
        timePtr.pointee = UInt64(Date().timeIntervalSince1970 * 1000)
        seqPtr.pointee = next
        seq = next
        frames += 1
        if Date().timeIntervalSince(last) >= 5 {
            print(String(format: "hl2capture: %.1f fps, seq %llu", Double(frames) / Date().timeIntervalSince(last), seq))
            frames = 0; last = Date()
        }
    }
    func stream(_ stream: SCStream, didStopWithError error: Error) {
        print("hl2capture: stream stopped: \(error)"); exit(3)
    }
}

let output = Output()
var stream: SCStream?

func findWindow() async throws -> SCWindow? {
    let content = try await SCShareableContent.excludingDesktopWindows(false, onScreenWindowsOnly: false)
    // HL2's own title is upper-case ("HALF-LIFE 2 - Direct3D 9") and its owner is the wine process; a loose
    // match also hits editors or browsers whose titles mention Half-Life 2.
    return content.windows.first {
        ($0.title ?? "").hasPrefix("HALF-LIFE 2 - ")
            && ($0.owningApplication?.applicationName ?? "").lowercased().contains("wine")
    }
}

Task {
    do {
        var win = try await findWindow()
        while win == nil {
            print("hl2capture: waiting for the HALF-LIFE 2 window...")
            try await Task.sleep(nanoseconds: 2_000_000_000)
            win = try await findWindow()
        }
        let cfg = SCStreamConfiguration()
        cfg.width = width
        cfg.height = height
        cfg.pixelFormat = kCVPixelFormatType_32BGRA
        cfg.minimumFrameInterval = CMTime(value: 1, timescale: 60)
        cfg.showsCursor = false
        cfg.queueDepth = 3
        cfg.capturesAudio = false
        let filter = SCContentFilter(desktopIndependentWindow: win!)
        let s = SCStream(filter: filter, configuration: cfg, delegate: output)
        try s.addStreamOutput(output, type: .screen, sampleHandlerQueue: DispatchQueue(label: "hl2capture"))
        try await s.startCapture()
        stream = s
        print("hl2capture: capturing '\(win!.title ?? "")' \(width)x\(height) -> \(outPath)")
    } catch {
        print("hl2capture: \(error)"); exit(1)
    }
}
dispatchMain()
