#!/usr/bin/env python3
"""Host-side compiler/package checks only; does not certify device execution."""
import json
import shlex
import struct
import sys
import zipfile

if len(sys.argv) != 3:
    raise SystemExit("usage: check-inference-build.py compile_commands.json app-debug.apk")
commands = json.load(open(sys.argv[1], encoding="utf-8"))
if not commands:
    raise SystemExit("FAIL: no native compile commands")
for command in commands:
    args = command.get("arguments") or shlex.split(command["command"])
    optimization = [a for a in args if a.startswith("-O")]
    if not optimization or optimization[-1] != "-O3":
        raise SystemExit(f"FAIL: not effectively -O3: {command['file']}")
    if not any("armv7-none-linux-androideabi" in a for a in args):
        raise SystemExit(f"FAIL: not ARMv7 Android: {command['file']}")
    forbidden = ("-mcpu=native", "-march=native", "-march=armv8", "aarch64", "neon-vfpv4", "+dotprod", "+i8mm")
    if any(any(f in a for f in forbidden) for a in args):
        raise SystemExit(f"FAIL: incompatible architecture flag: {command['file']}")
print(f"PASS: {len(commands)} native compile commands use ARMv7 Android and effective -O3")
expected = {f"lib/armeabi-v7a/{name}" for name in ("libmrgreen.so", "libmrgreen_quickjs.so", "libsqlite3x.so", "libc++_shared.so")}
with zipfile.ZipFile(sys.argv[2]) as apk:
    actual = {n for n in apk.namelist() if n.startswith("lib/") and n.endswith(".so")}
    if actual != expected:
        raise SystemExit(f"FAIL: unexpected library set: {sorted(actual)}")
    for name in sorted(actual):
        data = apk.read(name)
        if data[:4] != b"\x7fELF" or data[4] != 1 or data[5] != 1 or struct.unpack_from("<H", data, 18)[0] != 40:
            raise SystemExit(f"FAIL: not ELF32 little-endian ARM: {name}")
        print(f"PASS: ELF32 ARM {name}")
print("Packaging/compile checks passed; Android 11 phone execution remains unverified.")
