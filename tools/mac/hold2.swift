// hold2: hold two keys together for N ms (e.g. W + Space to walk and jump).
// usage: hold2 <pid> <keycode1> <keycode2> <ms>
import AppKit
let a = CommandLine.arguments
let pid = Int32(a[1])!, k1 = UInt16(a[2])!, k2 = UInt16(a[3])!, ms = UInt32(a[4])!
NSRunningApplication(processIdentifier: pid)?.activate()
usleep(200_000)
let src = CGEventSource(stateID: .hidSystemState)
for k in [k1, k2] { CGEvent(keyboardEventSource: src, virtualKey: k, keyDown: true)?.post(tap: .cghidEventTap) }
usleep(ms * 1000)
for k in [k1, k2] { CGEvent(keyboardEventSource: src, virtualKey: k, keyDown: false)?.post(tap: .cghidEventTap) }
