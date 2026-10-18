#!/usr/bin/env python3
"""Host packaging/privacy regression checks; not a proof of absence of all secrets."""
import os
import pathlib
import re
import subprocess
import sys
import zipfile

ROOT = pathlib.Path(__file__).resolve().parents[1]
PATTERNS = [
    re.compile(rb"gsk_[A-Za-z0-9]{24,}"),
    re.compile(rb"sk-(?:proj-)?[A-Za-z0-9_-]{32,}"),
    re.compile(rb"-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----"),
    re.compile(rb"AKIA[A-Z0-9]{16}"),
    re.compile(rb"gh[pousr]_[A-Za-z0-9]{30,}"),
]
# Compare configured API credentials without ever emitting their values.
SECRETS = [value.encode() for name, value in os.environ.items()
           if name.endswith(("API_KEY", "ACCESS_TOKEN", "SECRET_KEY")) and len(value) >= 16]


def inspect(data, label):
    if any(pattern.search(data) for pattern in PATTERNS) or any(value in data for value in SECRETS):
        raise SystemExit(f"FAIL: potential credential in {label}; value intentionally omitted")


paths = subprocess.check_output(["git", "ls-files", "--cached", "--others", "--exclude-standard", "-z"], cwd=ROOT).split(b"\0")
for raw in paths:
    if not raw:
        continue
    path = ROOT / os.fsdecode(raw)
    if path.is_file():
        inspect(path.read_bytes(), str(path.relative_to(ROOT)))
print("PASS: tracked/candidate source scan found no supported credential patterns or configured API values")

integrated = "--integrated" in sys.argv[1:]
for filename in [arg for arg in sys.argv[1:] if arg != "--integrated"]:
    with zipfile.ZipFile(filename) as apk:
        for name in apk.namelist():
            if name.lower().endswith((".pem", ".key", ".jks", ".keystore", ".gguf")) or any(part in {"shared_prefs", "databases", "crashes", ".env"} for part in name.split("/")):
                raise SystemExit(f"FAIL: unexpected private/model asset {name}")
            inspect(apk.read(name), f"APK:{name}")
        native = sorted(name for name in apk.namelist() if name.startswith("lib/") and name.endswith(".so"))
        if not native or any(not name.startswith("lib/armeabi-v7a/") for name in native):
            raise SystemExit("FAIL: native ABI set")
        if "lib/armeabi-v7a/libmrgreen_quickjs.so" not in native:
            raise SystemExit("FAIL: missing QuickJS bridge")
    print(f"PASS: {pathlib.Path(filename).name}: credential-pattern scan, no private/model assets, ARMv7-only QuickJS packaging")
manifest = (ROOT / "app/src/main/AndroidManifest.xml").read_text()
baseline = subprocess.check_output(["git", "show", "f1b141bfe12d87c9a4d1f612f6265817479c3342:app/src/main/AndroidManifest.xml"], cwd=ROOT).decode()
permissions = lambda text: set(re.findall(r'<uses-permission android:name="([^"]+)"', text))
expected = permissions(baseline)
if integrated:
    expected |= {"im.mrgreen.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION", "android.permission.READ_CALL_LOG", "android.permission.RECORD_AUDIO", "android.permission.CAMERA", "android.permission.READ_EXTERNAL_STORAGE", "android.permission.SET_WALLPAPER", "android.permission.BLUETOOTH", "android.permission.BLUETOOTH_ADMIN", "android.permission.RECEIVE_BOOT_COMPLETED", "com.android.alarm.permission.SET_ALARM"}
if permissions(manifest) != expected:
    raise SystemExit("FAIL: Android permission set differs from the explicit reviewed scope")
if 'android:allowBackup="false"' not in manifest or 'android:name="im.mrgreen.ui.PluginsActivity" android:exported="false"' not in manifest:
    raise SystemExit("FAIL: backup/export policy")
build = (ROOT / "app/build.gradle.kts").read_text()
for marker in ('applicationId = "im.mrgreen"', 'targetSdk = 30', 'minSdk = 26', 'abiFilters += "armeabi-v7a"'):
    if marker not in build:
        raise SystemExit("FAIL: app/platform identity changed")
print("PASS: im.mrgreen, Android target 30/min 26, reviewed permission set, non-exported plugin UI, backup disabled")
print("LIMITATION: heuristic static scan and host checks do not establish Redmi A25/A26 acceptance.")
