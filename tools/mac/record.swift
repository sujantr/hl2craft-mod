// record: record one window to an .mp4 with system audio (macOS 15+, ScreenCaptureKit SCRecordingOutput).
// A window-only filter records audio from that window's app alone, and HL2's sounds come from Wine, not Java.
// So this captures the window's display filtered to the window's app plus Wine (HL2 sits behind Minecraft and
// is covered by it), cropped to the window.
// usage: record <window id> <seconds> <out.mp4>
import AppKit
import ScreenCaptureKit

NSApplication.shared.setActivationPolicy(.prohibited)
setvbuf(stdout, nil, _IOLBF, 0)
let a = CommandLine.arguments
guard a.count == 4, let wid = UInt32(a[1]), let secs = Double(a[2]) else {
    print("usage: record <window id> <seconds> <out.mp4>"); exit(2)
}
let url = URL(fileURLWithPath: a[3])
try? FileManager.default.removeItem(at: url)

final class Delegate: NSObject, SCStreamDelegate, SCRecordingOutputDelegate {
    func recordingOutputDidFinishRecording(_ r: SCRecordingOutput) { print("record: saved \(url.path)"); exit(0) }
    func recordingOutput(_ r: SCRecordingOutput, didFailWithError e: Error) { print("record: failed \(e)"); exit(1) }
    func stream(_ s: SCStream, didStopWithError e: Error) { print("record: stream stopped \(e)"); exit(1) }
}
let delegate = Delegate()
var stream: SCStream?

Task {
    do {
        let content = try await SCShareableContent.excludingDesktopWindows(false, onScreenWindowsOnly: false)
        guard let win = content.windows.first(where: { $0.windowID == wid }) else { print("no window \(wid)"); exit(1) }
        let scale = NSScreen.main?.backingScaleFactor ?? 2
        let cfg = SCStreamConfiguration()
        cfg.width = Int(win.frame.width * scale) & ~1
        cfg.height = Int(win.frame.height * scale) & ~1
        cfg.minimumFrameInterval = CMTime(value: 1, timescale: 60)
        cfg.showsCursor = false
        cfg.capturesAudio = true
        cfg.excludesCurrentProcessAudio = true
        cfg.sampleRate = 48000
        cfg.channelCount = 2
        let display = content.displays.first { $0.frame.intersects(win.frame) } ?? content.displays[0]
        let apps = content.applications.filter {
            $0.processID == win.owningApplication?.processID || $0.applicationName.lowercased().contains("wine")
        }
        let filter = SCContentFilter(display: display, including: apps, exceptingWindows: [])
        cfg.sourceRect = CGRect(x: win.frame.minX - display.frame.minX, y: win.frame.minY - display.frame.minY,
                                width: win.frame.width, height: win.frame.height)
        let s = SCStream(filter: filter, configuration: cfg, delegate: delegate)
        let rc = SCRecordingOutputConfiguration()
        rc.outputURL = url
        rc.outputFileType = .mp4
        rc.videoCodecType = .h264
        let rec = SCRecordingOutput(configuration: rc, delegate: delegate)
        try s.addRecordingOutput(rec)
        try await s.startCapture()
        stream = s
        print("record: \(cfg.width)x\(cfg.height) for \(secs)s -> \(url.path)")
        try await Task.sleep(nanoseconds: UInt64(secs * 1e9))
        try s.removeRecordingOutput(rec)
        try await s.stopCapture()
    } catch {
        print("record: \(error)"); exit(1)
    }
}
// SCRecordingOutput puts a status item in the menu bar, which AppKit must create on the main thread:
// run the real AppKit loop (dispatchMain() crashes with "NSWindow should only be instantiated on the main thread").
NSApplication.shared.run()
