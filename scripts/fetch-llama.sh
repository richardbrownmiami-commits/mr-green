#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEST="$ROOT/vendor/llama.cpp"
PIN="f3363593f457169cdfbdfe22ea399b1cc139ba18"
if [[ -d "$DEST/.git" ]]; then
  git -C "$DEST" fetch --depth 1 origin "$PIN"
else
  mkdir -p "$(dirname "$DEST")"
  git clone --filter=blob:none https://github.com/ggerganov/llama.cpp.git "$DEST"
fi
git -C "$DEST" checkout --detach "$PIN"
[[ "$(git -C "$DEST" rev-parse HEAD)" == "$PIN" ]]
echo "llama.cpp pinned at $PIN in $DEST"
