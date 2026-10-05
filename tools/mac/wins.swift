// wins: list on-screen windows of a PID: "<window id> <title> <bounds>".
import CoreGraphics
let list = CGWindowListCopyWindowInfo([.optionOnScreenOnly], kCGNullWindowID) as! [[String: Any]]
for w in list {
  let pid = w[kCGWindowOwnerPID as String] as? Int ?? 0
  if pid == Int(CommandLine.arguments[1])! { print(w[kCGWindowNumber as String]!, w[kCGWindowName as String] ?? "", w[kCGWindowBounds as String]!) }
}
