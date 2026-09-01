#!/usr/bin/env python3
"""
Recover binder interface shapes from a DEX file.

Written for one job: working out what an AIDL interface looks like when the code implementing it
has been through R8. Class and method names are obfuscated, but two things survive because the
binder runtime needs them at runtime -- the interface DESCRIPTOR string, and the transaction codes
in onTransact's switch. Those are enough to write a compatible service.

This reads the DEX structures directly rather than depending on a disassembler, so it runs
anywhere Python does.

Usage:
    dexprobe.py descriptors <file.dex>...
    dexprobe.py probe <descriptor> <file.dex>...
"""

import struct
import sys


def uleb128(buf, off):
    """Returns (value, new_offset). DEX packs most counts and indices this way."""
    result = 0
    shift = 0
    while True:
        byte = buf[off]
        off += 1
        result |= (byte & 0x7F) << shift
        if byte < 0x80:
            return result, off
        shift += 7


class Dex:
    def __init__(self, data):
        self.d = data
        if data[:4] != b"dex\n":
            raise ValueError("not a dex file")
        h = struct.unpack_from("<20I", data, 56)
        (self.string_ids_size, self.string_ids_off,
         self.type_ids_size, self.type_ids_off,
         self.proto_ids_size, self.proto_ids_off,
         self.field_ids_size, self.field_ids_off,
         self.method_ids_size, self.method_ids_off,
         self.class_defs_size, self.class_defs_off) = h[:12]
        self._strings = {}

    def string(self, idx):
        if idx in self._strings:
            return self._strings[idx]
        off = struct.unpack_from("<I", self.d, self.string_ids_off + idx * 4)[0]
        size, off = uleb128(self.d, off)
        end = self.d.index(b"\x00", off)
        # MUTF-8; surrogate pairs are rare in identifiers and errors="replace" keeps us moving.
        s = self.d[off:end].decode("utf-8", errors="replace")
        self._strings[idx] = s
        return s

    def type_name(self, idx):
        return self.string(struct.unpack_from("<I", self.d, self.type_ids_off + idx * 4)[0])

    def proto(self, idx):
        shorty, ret, params_off = struct.unpack_from("<3I", self.d, self.proto_ids_off + idx * 12)
        params = []
        if params_off:
            n = struct.unpack_from("<I", self.d, params_off)[0]
            params = [self.type_name(struct.unpack_from("<H", self.d, params_off + 4 + i * 2)[0])
                      for i in range(n)]
        return params, self.type_name(ret)

    def method(self, idx):
        cls, proto, name = struct.unpack_from("<HHI", self.d, self.method_ids_off + idx * 8)
        return self.type_name(cls), self.string(name), self.proto(proto)

    def classes(self):
        for i in range(self.class_defs_size):
            base = self.class_defs_off + i * 32
            class_idx, _, _, _, _, _, data_off, _ = struct.unpack_from("<8I", self.d, base)
            yield self.type_name(class_idx), data_off

    def class_methods(self, data_off):
        """Yields (method_idx, access_flags, code_off) for every method the class defines."""
        if not data_off:
            return
        o = data_off
        counts = []
        for _ in range(4):
            v, o = uleb128(self.d, o)
            counts.append(v)
        n_sf, n_if, n_dm, n_vm = counts
        for n in (n_sf, n_if):                      # skip field tables
            idx = 0
            for _ in range(n):
                diff, o = uleb128(self.d, o)
                _, o = uleb128(self.d, o)
                idx += diff
        for n in (n_dm, n_vm):
            idx = 0
            for _ in range(n):
                diff, o = uleb128(self.d, o)
                access, o = uleb128(self.d, o)
                code_off, o = uleb128(self.d, o)
                idx += diff
                yield idx, access, code_off

    def insns(self, code_off):
        """Returns the instruction array of a code_item as raw bytes, or None."""
        if not code_off:
            return None
        insns_size = struct.unpack_from("<I", self.d, code_off + 12)[0]
        start = code_off + 16
        return self.d[start:start + insns_size * 2]


def const_string_indices(insns):
    """String indices loaded by const-string / const-string/jumbo in an instruction stream.

    Walking Dalvik bytecode properly means knowing every opcode's width. We only need the two
    const-string forms, and both are fixed width, so a linear scan that respects instruction
    widths is enough -- and the widths come from the standard table below.
    """
    out = []
    i = 0
    n = len(insns)
    while i + 1 < n:
        op = insns[i]
        width = OPCODE_WIDTHS[op]
        if width == 0:                              # payload or unknown; stop, we cannot realign
            break
        if i + width * 2 > n:                       # truncated tail; nothing more to read
            break
        if op == 0x1A:                              # const-string vAA, string@BBBB
            out.append(struct.unpack_from("<H", insns, i + 2)[0])
        elif op == 0x1B:                            # const-string/jumbo vAA, string@BBBBBBBB
            out.append(struct.unpack_from("<I", insns, i + 2)[0])
        i += width * 2
    return out


def small_consts(insns):
    """Small integer literals loaded in a method.

    An AIDL proxy method loads exactly one: the transaction code it passes to transact(). That
    makes a method with a single small constant a reliable read, and one with several a hint
    rather than an answer -- which is why the caller prints them rather than picking one.
    """
    out = []
    i = 0
    n = len(insns)
    while i + 1 < n:
        op = insns[i]
        width = OPCODE_WIDTHS[op]
        if width == 0 or i + width * 2 > n:
            break
        if op == 0x12:                              # const/4 vA, #+B
            v = (insns[i + 1] >> 4) & 0xF
            if v > 7:
                v -= 16
            out.append(v)
        elif op == 0x13:                            # const/16 vAA, #+BBBB
            out.append(struct.unpack_from("<h", insns, i + 2)[0])
        i += width * 2
    return [v for v in out if 1 <= v <= 200]


def switch_keys(insns):
    """Transaction codes from any packed- or sparse-switch payload in the stream."""
    keys = []
    i = 0
    n = len(insns)
    while i + 3 < n:
        ident = struct.unpack_from("<H", insns, i)[0]
        if ident == 0x0100 and i + 8 <= n:          # packed-switch-payload
            size, first = struct.unpack_from("<Hi", insns, i + 2)
            if i + 8 + size * 4 > n:
                break
            keys.extend(range(first, first + size))
            i += 8 + size * 4
            continue
        if ident == 0x0200 and i + 4 <= n:          # sparse-switch-payload
            size = struct.unpack_from("<H", insns, i + 2)[0]
            if i + 4 + size * 8 > n:
                break
            keys.extend(struct.unpack_from("<%di" % size, insns, i + 4))
            i += 4 + size * 8
            continue
        i += 2
    return sorted(set(keys))


# Instruction width in 16-bit code units, indexed by opcode. 0 marks opcodes we must not walk past.
OPCODE_WIDTHS = [1] * 256
for _op in range(0x00, 0x100):
    OPCODE_WIDTHS[_op] = 1
for _op in list(range(0x01, 0x0D)) + list(range(0x0E, 0x12)):
    OPCODE_WIDTHS[_op] = 1
_W = {
    0x00: 1, 0x1A: 2, 0x1B: 3, 0x1C: 2, 0x1F: 2, 0x20: 2, 0x22: 2, 0x23: 2,
    0x24: 3, 0x25: 3, 0x26: 3, 0x2B: 3, 0x2C: 3, 0x2D: 2, 0x2E: 2, 0x2F: 2,
    0x30: 2, 0x31: 2, 0x13: 2, 0x14: 3, 0x15: 2, 0x16: 2, 0x17: 3, 0x18: 5,
    0x19: 2, 0x21: 1, 0x27: 1, 0x28: 1, 0x29: 2, 0x2A: 3,
}
for _op, _w in _W.items():
    OPCODE_WIDTHS[_op] = _w
for _op in range(0x32, 0x3E):                       # if-test / if-testz
    OPCODE_WIDTHS[_op] = 2
for _op in range(0x44, 0x52):                       # aget / aput
    OPCODE_WIDTHS[_op] = 2
for _op in range(0x52, 0x6E):                       # iget / iput / sget / sput
    OPCODE_WIDTHS[_op] = 2
for _op in range(0x6E, 0x79):                       # invoke-*
    OPCODE_WIDTHS[_op] = 3
for _op in range(0x7B, 0x90):                       # unop
    OPCODE_WIDTHS[_op] = 1
for _op in range(0x90, 0xAF):                       # binop
    OPCODE_WIDTHS[_op] = 2
for _op in range(0xAF, 0xCF):                       # binop/2addr
    OPCODE_WIDTHS[_op] = 1
for _op in range(0xD0, 0xE3):                       # binop/lit
    OPCODE_WIDTHS[_op] = 2
for _op in (0xFA, 0xFB):                            # invoke-polymorphic
    OPCODE_WIDTHS[_op] = 4
for _op in (0xFC, 0xFD):                            # invoke-custom
    OPCODE_WIDTHS[_op] = 3
for _op in (0xFE, 0xFF):                            # const-method-handle / const-method-type
    OPCODE_WIDTHS[_op] = 2


def load(paths):
    for p in paths:
        with open(p, "rb") as f:
            yield p, Dex(f.read())


def cmd_descriptors(paths):
    """Every string that looks like a binder interface descriptor."""
    seen = set()
    for _, dex in load(paths):
        for i in range(dex.string_ids_size):
            s = dex.string(i)
            if (32 < len(s) < 120 and s.count(".") >= 3 and " " not in s
                    and "/" not in s and "$" not in s):
                part = s.rsplit(".", 1)[-1]
                if part.startswith("I") and len(part) > 1 and part[1].isupper():
                    seen.add(s)
    for s in sorted(seen):
        print(s)


def cmd_probe(descriptor, paths):
    for path, dex in load(paths):
        target = None
        for i in range(dex.string_ids_size):
            if dex.string(i) == descriptor:
                target = i
                break
        if target is None:
            continue

        for cls, data_off in dex.classes():
            methods = list(dex.class_methods(data_off))
            hits = []
            for midx, access, code_off in methods:
                insns = dex.insns(code_off)
                if insns and target in const_string_indices(insns):
                    hits.append((midx, code_off, insns))
            if not hits:
                continue

            print("=" * 74)
            print("%s\n  in %s\n  class %s" % (descriptor, path.split("/")[-1], cls))
            codes = sorted({k for _, _, insns in hits for k in switch_keys(insns)})
            plausible = [c for c in codes if 1 <= c <= 200]
            if plausible:
                print("  transaction codes: %s" % plausible)
            print("  methods (%d):" % len(methods))
            for midx, access, code_off in methods:
                _, name, (params, ret) = dex.method(midx)
                insns = dex.insns(code_off)
                consts = sorted(set(small_consts(insns))) if insns else []
                # One constant in range is almost certainly the transaction code; several means
                # the method does other arithmetic and the read is not trustworthy.
                code = ("txn=%d" % consts[0]) if len(consts) == 1 else (
                    "consts=%s" % consts if consts else "")
                print("    %-6s (%s)%-28s  %s"
                      % (name, ", ".join(params), ret, code))


if __name__ == "__main__":
    if len(sys.argv) < 3:
        print(__doc__)
        sys.exit(1)
    if sys.argv[1] == "descriptors":
        cmd_descriptors(sys.argv[2:])
    elif sys.argv[1] == "probe":
        cmd_probe(sys.argv[2], sys.argv[3:])
    else:
        print(__doc__)
        sys.exit(1)
