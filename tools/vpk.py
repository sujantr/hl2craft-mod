"""Minimal reader for Valve VPK v1/v2 directory archives (read-only)."""
import os
import struct


class VPK:
    def __init__(self, dir_path):
        self.dir_path = dir_path
        self.base = dir_path[: -len("_dir.vpk")]
        self.entries = {}
        with open(dir_path, "rb") as f:
            sig, ver, tree_size = struct.unpack("<III", f.read(12))
            if sig != 0x55AA1234:
                raise ValueError("not a VPK: " + dir_path)
            if ver == 2:
                f.read(16)
            self.header_size = f.tell()
            self.tree_size = tree_size
            self._read_tree(f)

    @staticmethod
    def _cstr(f):
        out = bytearray()
        while True:
            c = f.read(1)
            if c in (b"\0", b""):
                return out.decode("utf-8", "replace")
            out += c

    def _read_tree(self, f):
        while True:
            ext = self._cstr(f)
            if not ext:
                break
            while True:
                path = self._cstr(f)
                if not path:
                    break
                while True:
                    name = self._cstr(f)
                    if not name:
                        break
                    crc, preload, arc, off, length, term = struct.unpack("<IHHIIH", f.read(18))
                    pre = f.read(preload)
                    full = (name if path == " " else path + "/" + name) + "." + ext
                    self.entries[full.lower()] = (arc, off, length, pre)

    def read(self, name):
        arc, off, length, pre = self.entries[name.lower()]
        if length == 0:
            return pre
        if arc == 0x7FFF:
            path, off = self.dir_path, off + self.header_size + self.tree_size
        else:
            path = "%s_%03d.vpk" % (self.base, arc)
        with open(path, "rb") as f:
            f.seek(off)
            return pre + f.read(length)


def open_all(hl2_game_dir):
    """All *_dir.vpk archives of a game folder (e.g. .../Half-Life 2/hl2)."""
    return [VPK(os.path.join(hl2_game_dir, n)) for n in sorted(os.listdir(hl2_game_dir)) if n.endswith("_dir.vpk")]


def find(vpks, name):
    for v in vpks:
        if name.lower() in v.entries:
            return v.read(name)
    raise KeyError(name)
