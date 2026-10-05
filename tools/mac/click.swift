// click: activate an app by PID and left-click at a global screen point (macOS, needs Accessibility).
// usage: click <pid> <x> <y> [--double | --move]
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
let clicks = a.contains("--double") ? 2 : 1
for i in 1...clicks {
    let d = CGEvent(mouseEventSource: src, mouseType: .leftMouseDown, mouseCursorPosition: p, mouseButton: .left)!
    d.setIntegerValueField(.mouseEventClickState, value: Int64(i)); d.post(tap: .cghidEventTap)
    usleep(50_000)
    let u = CGEvent(mouseEventSource: src, mouseType: .leftMouseUp, mouseCursorPosition: p, mouseButton: .left)!
    u.setIntegerValueField(.mouseEventClickState, value: Int64(i)); u.post(tap: .cghidEventTap)
    usleep(80_000)
}
