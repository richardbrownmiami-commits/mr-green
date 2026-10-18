#!/usr/bin/env python3
"""Sign a Phase 7 pack locally with an owner PEM key; never copy the private key.

python3 scripts/sign-redpack.py --content /path/to/payload --manifest manifest.json \
    --key /private/owner.pem --algorithm RSA --output sample.redpack
Payload must include flows/ or scripts/ and tests/*.json. Import the printed PUBLIC
key in the app, compare the fingerprint, then review/approve the signed pack.
"""
import argparse
import base64
import hashlib
import json
import pathlib
import subprocess
import tempfile
import zipfile


def canonical(value):
    def validate(item):
        if isinstance(item, dict):
            for key, child in item.items():
                if not isinstance(key, str):
                    raise ValueError("JSON object keys must be strings")
                validate(child)
        elif isinstance(item, list):
            for child in item:
                validate(child)
        elif isinstance(item, float):
            raise ValueError("Use integer JSON numbers only")
        elif item is not None and not isinstance(item, (str, int, bool)):
            raise ValueError("Unsupported JSON value")
    validate(value)
    def ordered(item):
        if isinstance(item, dict):
            return {key: ordered(item[key]) for key in sorted(item, key=lambda key: key.encode("utf-16-be"))}
        if isinstance(item, list):
            return [ordered(child) for child in item]
        return item
    return json.dumps(ordered(value), separators=(",", ":"), ensure_ascii=False, allow_nan=False).encode("utf-8")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--content", type=pathlib.Path, required=True)
    parser.add_argument("--manifest", type=pathlib.Path, required=True)
    parser.add_argument("--key", type=pathlib.Path, required=True)
    parser.add_argument("--algorithm", choices=["RSA", "EC"], required=True)
    parser.add_argument("--output", type=pathlib.Path, required=True)
    args = parser.parse_args()
    if args.output.exists():
        parser.error("Refusing to overwrite an existing output")
    root = args.content.resolve(strict=True)
    if args.key.resolve(strict=True).is_relative_to(root):
        parser.error("Private signing key must be outside the payload folder")
    manifest = json.loads(args.manifest.read_text(encoding="utf-8"))
    if set(manifest) != {"id", "name", "version", "api_min", "api_max", "layers", "permissions", "tools", "triggers"}:
        parser.error("Template must contain only id/name/version/api_min/api_max/layers/permissions/tools/triggers")
    files = {}
    total = 0
    for path in sorted(root.rglob("*")):
        if path.is_symlink():
            parser.error("Symlinks are not allowed")
        if not path.is_file():
            continue
        name = path.relative_to(root).as_posix()
        if path.suffix not in {".json", ".js", ".md", ".txt"} or name == "manifest.json" or any(part.startswith(".") for part in pathlib.PurePosixPath(name).parts):
            parser.error("Payload contains a forbidden file; never include keys or executables")
        size = path.stat().st_size
        if size > 32 * 1024 * 1024 or total + size > 31 * 1024 * 1024:
            parser.error("Payload exceeds safe signing limits")
        total += size
        files[name] = path.read_bytes()
    if not files or len(files) > 512 or not any(name.startswith("tests/") for name in files):
        parser.error("Payload must include at least one tests/ suite and at most 512 files")
    public = subprocess.check_output(["openssl", "pkey", "-in", str(args.key), "-pubout", "-outform", "DER"], stderr=subprocess.DEVNULL)
    manifest["files"] = {name: hashlib.sha256(data).hexdigest() for name, data in files.items()}
    manifest["signer_algorithm"] = args.algorithm
    manifest["signer_key"] = base64.b64encode(public).decode("ascii")
    manifest["signature_algorithm"] = "SHA256withRSA" if args.algorithm == "RSA" else "SHA256withECDSA"
    with tempfile.TemporaryDirectory() as temp:
        payload = pathlib.Path(temp) / "manifest.bin"
        signature = pathlib.Path(temp) / "signature.bin"
        payload.write_bytes(canonical(manifest))
        subprocess.run(["openssl", "dgst", "-sha256", "-sign", str(args.key), "-out", str(signature), str(payload)], check=True, stderr=subprocess.DEVNULL)
        manifest["signature"] = base64.b64encode(signature.read_bytes()).decode("ascii")
    with zipfile.ZipFile(args.output, "x", compression=zipfile.ZIP_DEFLATED) as archive:
        archive.writestr("manifest.json", canonical(manifest))
        for name, data in files.items():
            archive.writestr(name, data)
    print("Created signed pack:", args.output)
    print("PUBLIC key (import only after comparing fingerprint):", base64.b64encode(public).decode("ascii"))
    print("SHA-256 PUBLIC-key fingerprint:", hashlib.sha256(public).hexdigest())
    print("App validation, dry-run tests and explicit install approval are still required.")


if __name__ == "__main__":
    try:
        main()
    except (ValueError, OSError, subprocess.SubprocessError, json.JSONDecodeError):
        raise SystemExit("Signing failed. No private key material was printed.")
