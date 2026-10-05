"""Send console commands to a running HL2 through the HL2Craft bridge (same protocol as HL2Bridge.java).

usage: python3 hl2send.py [--hl2 <.../Half-Life 2/hl2>] "<command>" ["<command>" ...]
Prints the ack line once HL2 has run the file.
"""
import argparse
import os
import re
import time

DEFAULT = os.path.expanduser(
    "~/Applications/Sikarugir/Untitled.app/Contents/SharedSupport/prefix/drive_c/Program Files (x86)"
    "/Steam/steamapps/common/Half-Life 2/hl2")


def expected_next(log):
    with open(log, "rb") as f:
        f.seek(max(0, os.path.getsize(log) - 65536))
        tail = f.read().decode("latin-1")
    best, at = -1, -1
    for m in re.finditer(r"'hl2craft/hc_(\d+)' not present", tail):
        best, at = int(m.group(1)), m.start()
    for m in re.finditer(r"HL2CRAFT ack (\d+)", tail):
        if m.start() > at:
            best, at = int(m.group(1)) + 1, m.start()
    if tail.rfind("HL2CRAFT bridge loaded") > at:
        best = 0
    return best


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--hl2", default=DEFAULT)
    ap.add_argument("--timeout", type=float, default=10)
    ap.add_argument("commands", nargs="+")
    a = ap.parse_args()
    log = os.path.join(a.hl2, "console.log")
    d = os.path.join(a.hl2, "cfg", "hl2craft")
    os.makedirs(d, exist_ok=True)
    n = expected_next(log)
    if n < 0:
        raise SystemExit("HL2 bridge not running (no hc_ lines in console.log)")
    body = "\n".join(a.commands) + '\necho "HL2CRAFT ack %d"\nalias hc_next "exec hl2craft/hc_%d"\n' % (n, n + 1)
    tmp = os.path.join(d, ".hc_%d.tmp" % n)
    with open(tmp, "w", newline="\n") as f:
        f.write(body)
    os.replace(tmp, os.path.join(d, "hc_%d.cfg" % n))
    t0 = time.time()
    while time.time() - t0 < a.timeout:
        with open(log, "rb") as f:
            f.seek(max(0, os.path.getsize(log) - 65536))
            if ("HL2CRAFT ack %d" % n).encode() in f.read():
                print("ack %d after %.0f ms" % (n, (time.time() - t0) * 1000))
                return
        time.sleep(0.01)
    raise SystemExit("no ack for hc_%d" % n)


if __name__ == "__main__":
    main()
