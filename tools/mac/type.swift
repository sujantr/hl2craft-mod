// type: activate an app by PID and type ASCII text as US-layout key presses (macOS, needs Accessibility).
// Real key codes, not Unicode events: GLFW's text input ignored synthetic Unicode-only events.
// usage: type <pid> "<text>" [--enter]
import AppKit
let codes: [Character: UInt16] = [
    "a": 0, "s": 1, "d": 2, "f": 3, "h": 4, "g": 5, "z": 6, "x": 7, "c": 8, "v": 9, "b": 11, "q": 12, "w": 13,
    "e": 14, "r": 15, "y": 16, "t": 17, "1": 18, "2": 19, "3": 20, "4": 21, "6": 22, "5": 23, "9": 25, "7": 26,
    "-": 27, "8": 28, "0": 29, "o": 31, "u": 32, "i": 34, "p": 35, "l": 37, "j": 38, "k": 40, "n": 45, "m": 46,
    " ": 49, "/": 44, ".": 47,
]
let a = CommandLine.arguments
guard a.count >= 3, let pid = Int32(a[1]) else { print("usage: type <pid> <text> [--enter]"); exit(2) }
NSRunningApplication(processIdentifier: pid)?.activate()
usleep(300_000)
let src = CGEventSource(stateID: .hidSystemState)
func press(_ code: UInt16) {
    CGEvent(keyboardEventSource: src, virtualKey: code, keyDown: true)?.post(tap: .cghidEventTap)
    usleep(90_000)
    CGEvent(keyboardEventSource: src, virtualKey: code, keyDown: false)?.post(tap: .cghidEventTap)
    usleep(90_000)
}
for ch in a[2].lowercased() {
    guard let c = codes[ch] else { print("no key for \(ch)"); exit(2) }
    press(c)
}
if a.contains("--enter") { press(36) }
