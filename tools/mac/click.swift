// click: activate an app by PID and left-click at a global screen point (macOS, needs Accessibility).
// usage: click <pid> <x> <y> [--double | --move | --hold <ms> | --look <dx> <dy>]
// --look sends a relative mouse move (games with a captured cursor read deltas, not positions).
import AppKit
let a = CommandLine.arguments
guard a.count >= 4, let pid = Int32(a[1]), let x = Double(a[2]), let y = Double(a[3]) else {
    print("usage: click <pid> <x> <y> [--double]"); exit(2)
}
NSRunningApplication(processIdentifier: pid)?.activate()
usleep(300_000)
let p = CGPoint(x: x, y: y)
let src = CGEventSource(stateID: .hidSystemState)
CGEvent(mouseEventSource: src, mouseType: .mouseMoved, mouseCursorPosition: p, mouseButton: .left)?.post(tap: .cghidEventTap)
usleep(80_000)
if a.contains("--move") { exit(0) }
if let i = a.firstIndex(of: "--look"), i + 2 < a.count, let dx = Int64(a[i + 1]), let dy = Int64(a[i + 2]) {
    let steps = 10
    for _ in 0..<steps {
        let e = CGEvent(mouseEventSource: src, mouseType: .mouseMoved, mouseCursorPosition: p, mouseButton: .left)!
        e.setIntegerValueField(.mouseEventDeltaX, value: dx / Int64(steps))
        e.setIntegerValueField(.mouseEventDeltaY, value: dy / Int64(steps))
        e.post(tap: .cghidEventTap)
        usleep(16_000)
    }
    exit(0)
}
if let i = a.firstIndex(of: "--hold"), i + 1 < a.count, let ms = UInt32(a[i + 1]) {
    CGEvent(mouseEventSource: src, mouseType: .leftMouseDown, mouseCursorPosition: p, mouseButton: .left)?.post(tap: .cghidEventTap)
    usleep(ms * 1000)
    CGEvent(mouseEventSource: src, mouseType: .leftMouseUp, mouseCursorPosition: p, mouseButton: .left)?.post(tap: .cghidEventTap)
    exit(0)
}
let clicks = a.contains("--double") ? 2 : 1
for i in 1...clicks {
    let d = CGEvent(mouseEventSource: src, mouseType: .leftMouseDown, mouseCursorPosition: p, mouseButton: .left)!
    d.setIntegerValueField(.mouseEventClickState, value: Int64(i)); d.post(tap: .cghidEventTap)
    usleep(50_000)
    let u = CGEvent(mouseEventSource: src, mouseType: .leftMouseUp, mouseCursorPosition: p, mouseButton: .left)!
    u.setIntegerValueField(.mouseEventClickState, value: Int64(i)); u.post(tap: .cghidEventTap)
    usleep(80_000)
}
