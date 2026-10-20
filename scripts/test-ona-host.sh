#!/bin/sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
cat > "$TMP/harness.c" <<'C'
#include "NAR.h"
int main(void) {
    NAR_INIT();
    NAR_AddInputNarsese("<swan --> bird>.");
    NAR_AddInputNarsese("<<$x --> bird> ==> <$x --> animal>>.");
    if (!NAR_AnswerGround("<swan --> bird>?", 0)) return 1;
    if (!NAR_AnswerGround("<swan --> animal>?", 32)) return 2;
    if (NAR_AnswerGround("<swan --> robot>?", 32)) return 3;
    NAR_INIT();
    if (NAR_AnswerGround("<swan --> animal>?", 32)) return 4;
    if (NAR_AnswerGround("<swan --> bird>?", 0)) return 5;
    if (NAR_AnswerGround("<swan --> bird>?", 129)) return 6;
    return 0;
}
C
SRC=$(find "$ROOT/vendor/ona/src" -name '*.c' ! -name main.c ! -name Shell.c -print)
cc -std=c99 -O2 -DSTAGE=1 -D_POSIX_C_SOURCE=199506L -Wno-unused-parameter -Wno-unused-variable -I"$ROOT/vendor/ona/src" "$TMP/harness.c" $SRC -lm -lpthread -o "$TMP/ona-test"
"$TMP/ona-test" > "$TMP/output" 2>&1
if [ -s "$TMP/output" ]; then echo 'FAIL: native runtime printed source data'; cat "$TMP/output"; exit 7; fi
size "$TMP/ona-test"
echo 'PASS: actual ONA direct belief, one-step deduction, unknown query, request reset, cycle bounds and quiet RAM-only query.'
