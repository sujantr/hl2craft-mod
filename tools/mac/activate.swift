// activate: bring an app to the front by PID (forcefully; Wine apps ignore a polite activate()).
import AppKit
guard CommandLine.arguments.count > 1, let pid = Int32(CommandLine.arguments[1]) else { exit(2) }
let app = NSRunningApplication(processIdentifier: pid)
app?.unhide()
_ = app?.activate(options: [.activateAllWindows, .activateIgnoringOtherApps])
usleep(400_000)
print("front:", NSWorkspace.shared.frontmostApplication?.processIdentifier ?? -1)
