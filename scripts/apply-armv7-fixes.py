#!/usr/bin/env python3
"""Idempotent, narrow fixes against the pinned native dependency sources.

These fix overflowing size_t constants on ARMv7 and an int64 upper-bound test
whose conversion rounded INT64_MAX up to 2**63. No backend or ABI is enabled.
"""
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]
FIXES = {
    "vendor/llama.cpp/src/llama-model-loader.cpp": [
        ("constexpr size_t auto_min_size = 4ull * 1024 * 1024 * 1024;",
         "constexpr uint64_t auto_min_size = 4ull * 1024 * 1024 * 1024;"),
        ("ggml_nbytes(t) <= auto_min_size", "static_cast<uint64_t>(ggml_nbytes(t)) <= auto_min_size")
    ],
    "vendor/llama.cpp/src/llama-quant.cpp": [
        ("static const size_t LLAMA_QUANT_MAX_BUF_SIZE = 8ull*1024*1024*1024;",
         "static const size_t LLAMA_QUANT_MAX_BUF_SIZE = sizeof(size_t) >= 8 ? static_cast<size_t>(8ull*1024*1024*1024) : static_cast<size_t>(512u*1024*1024);")
    ],
    "vendor/quickjs/quickjs.c": [("d > INT64_MAX", "d >= 0x1p63")],
}


def main():
    requested = set(sys.argv[1:])
    for relative, changes in FIXES.items():
        if requested and not any(relative.startswith(f"vendor/{name}/") for name in requested):
            continue
        path = ROOT / relative
        if not path.is_file():
            raise SystemExit(f"Missing pinned source: {relative}")
        original = content = path.read_text()
        for old, new in changes:
            if old not in content and new not in content:
                raise SystemExit(f"Pinned-source shape changed; review required: {relative}")
            content = content.replace(old, new)
        if content != original:
            path.write_text(content)
        print(f"ARMv7 source fix verified: {relative}", flush=True)


if __name__ == "__main__":
    main()
