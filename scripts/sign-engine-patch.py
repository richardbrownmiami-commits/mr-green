#!/usr/bin/env python3
"""Package an independently reviewed mrgreen-jni-v1 ARMv7 bridge for app versionCode 9.

Usage: sign-engine-patch.py --library libmrgreen.so --private-key owner.pem
       --version 9-reviewed-1 --output reviewed-engine.zip
The RSA private key stays local. This utility does not generate or trust keys,
fetch code, bypass review, or prove that the supplied native code is safe.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import tempfile
import zipfile


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--library", type=Path, required=True)
    p.add_argument("--private-key", type=Path, required=True)
    p.add_argument("--version", required=True)
    p.add_argument("--output", type=Path, required=True)
    args = p.parse_args()
    if not re.fullmatch(r"9-[A-Za-z0-9._-]{1,62}", args.version):
        p.error("version must start 9- and contain only letters, digits, dot, underscore and hyphen")
    if not args.library.is_file() or not 1 <= args.library.stat().st_size <= 150 * 1024 * 1024:
        p.error("library must be a file of at most 150 MiB")
    with args.library.open("rb") as f:
        header = f.read(20)
    if len(header) != 20 or header[:6] != b"\x7fELF\x01\x01" or header[16:20] != b"\x03\x00\x28\x00":
        p.error("library is not a little-endian ELF32 ARM shared library")
    digest = hashlib.file_digest(args.library.open("rb"), "sha256").hexdigest()
    canonical = f"mrgreen-engine-patch-v1\n{args.version}\narmeabi-v7a\nlibmrgreen_engine.so\n{digest}\n".encode()
    with tempfile.TemporaryDirectory() as d:
        d = Path(d)
        (d / "payload").write_bytes(canonical)
        subprocess.run(["openssl", "pkey", "-in", str(args.private_key), "-pubout", "-outform", "DER", "-out", str(d / "owner.der")], check=True, stdin=subprocess.DEVNULL)
        subprocess.run(["openssl", "dgst", "-sha256", "-sign", str(args.private_key), "-out", str(d / "signature"), str(d / "payload")], check=True, stdin=subprocess.DEVNULL)
        import base64
        manifest = {"version": args.version, "abi": "armeabi-v7a", "soname": "libmrgreen_engine.so", "sha256": digest,
                    "signature": base64.b64encode((d / "signature").read_bytes()).decode("ascii")}
        args.output.parent.mkdir(parents=True, exist_ok=True)
        with zipfile.ZipFile(args.output, "w", zipfile.ZIP_DEFLATED) as z:
            z.writestr("manifest.json", json.dumps(manifest, separators=(",", ":")))
            z.write(d / "owner.der", "owner.der")
            z.write(args.library, "engine.so")
        print(f"Signed vetted patch bundle: {args.output}")
        print(f"Owner public-key SHA-256: {hashlib.sha256((d / 'owner.der').read_bytes()).hexdigest()}")
        print("Verify the owner fingerprint independently on the phone. Native activation and rollback still need device validation.")


if __name__ == "__main__":
    main()
