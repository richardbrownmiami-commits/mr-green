#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
[[ -f "$ROOT/vendor/quickjs/quickjs.c" ]] || "$ROOT/scripts/fetch-quickjs.sh"
BUILD="${1:-$ROOT/.quickjs-host-build}"
mkdir -p "$BUILD"
for s in quickjs libregexp libunicode cutils libbf; do cc -O2 -DCONFIG_BIGNUM -DCONFIG_VERSION=\"2024-01-13\" -I"$ROOT/vendor/quickjs" -c "$ROOT/vendor/quickjs/$s.c" -o "$BUILD/$s.o"; done
c++ -std=c++17 -O2 -I"$ROOT/vendor/quickjs" -I"$ROOT/app/src/main/cpp" "$ROOT/scripts/native/quickjs_host_test.cpp" "$ROOT/app/src/main/cpp/quickjs_runtime.cpp" "$BUILD"/*.o -lm -ldl -pthread -o "$BUILD/quickjs-host-test"
"$BUILD/quickjs-host-test"
