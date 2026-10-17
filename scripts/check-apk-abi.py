#!/usr/bin/env python3
"""Check APK packaging, not device/runtime compatibility."""
import sys
import zipfile

if len(sys.argv) < 2:
    raise SystemExit("usage: python3 scripts/check-apk-abi.py APK [APK ...]")
for path in sys.argv[1:]:
    with zipfile.ZipFile(path) as apk:
        libraries = sorted(n for n in apk.namelist() if n.startswith("lib/") and n.endswith(".so"))
    if not libraries:
        raise SystemExit(f"FAIL {path}: no native libraries")
    bad = [n for n in libraries if n.split('/')[1] != "armeabi-v7a"]
    if bad:
        raise SystemExit(f"FAIL {path}: unexpected ABI: {bad}")
    if "lib/armeabi-v7a/libmrgreen.so" not in libraries:
        raise SystemExit(f"FAIL {path}: missing JNI bridge")
    print(f"PASS {path}: armeabi-v7a only ({len(libraries)} libraries)")
    for library in libraries:
        print(f"  {library}")
