// key: activate an app by PID and press a key (macOS virtual keycode), optionally held for N ms.
// usage: key <pid> <keycode> [hold_ms]      e.g. E=14, R=15, 1=18, 2=19, Esc=53, T=17, Return=36
import AppKit
let a = CommandLine.arguments
guard a.count >= 3, let pid = Int32(a[1]), let code = UInt16(a[2]) else { print("usage: key <pid> <keycode> [hold_ms]"); exit(2) }
let hold = a.count > 3 ? UInt32(a[3])! : 60
NSRunningApplication(processIdentifier: pid)?.activate()
usleep(250_000)
let src = CGEventSource(stateID: .hidSystemState)
CGEvent(keyboardEventSource: src, virtualKey: code, keyDown: true)?.post(tap: .cghidEventTap)
usleep(hold * 1000)
CGEvent(keyboardEventSource: src, virtualKey: code, keyDown: false)?.post(tap: .cghidEventTap)
