// type: activate an app by PID and type text as Unicode key events (macOS, needs Accessibility).
// usage: type <pid> "<text>"
import AppKit
let a = CommandLine.arguments
guard a.count >= 3, let pid = Int32(a[1]) else { print("usage: type <pid> <text>"); exit(2) }
NSRunningApplication(processIdentifier: pid)?.activate()
usleep(250_000)
let src = CGEventSource(stateID: .hidSystemState)
for ch in a[2].utf16 {
    var c = ch
    for down in [true, false] {
        let e = CGEvent(keyboardEventSource: src, virtualKey: 0, keyDown: down)!
        e.keyboardSetUnicodeString(stringLength: 1, unicodeString: &c)
        e.post(tap: .cghidEventTap)
        usleep(12_000)
    }
}
