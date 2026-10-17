#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
if [[ $# != 2 ]]; then
  echo "usage: JAVA_HOME=/path/to/jdk bash scripts/host-jni/run.sh MODEL.gguf HOST_BUILD_DIR" >&2
  exit 2
fi
MODEL="$(realpath "$1")"
BUILD="$(realpath -m "$2")"
: "${JAVA_HOME:?Set JAVA_HOME to a full host JDK}"
cmake -S "$ROOT/vendor/llama.cpp" -B "$BUILD" \
  -DCMAKE_BUILD_TYPE=Release -DCMAKE_POSITION_INDEPENDENT_CODE=ON \
  -DBUILD_SHARED_LIBS=OFF -DGGML_NATIVE=OFF -DGGML_OPENMP=OFF \
  -DGGML_CUDA=OFF -DGGML_VULKAN=OFF -DGGML_OPENCL=OFF \
  -DGGML_BLAS=OFF -DGGML_RPC=OFF -DGGML_CPU_KLEIDIAI=OFF \
  -DLLAMA_BUILD_COMMON=OFF -DLLAMA_BUILD_TESTS=OFF -DLLAMA_BUILD_TOOLS=OFF \
  -DLLAMA_BUILD_EXAMPLES=OFF -DLLAMA_BUILD_SERVER=OFF -DLLAMA_BUILD_APP=OFF
cmake --build "$BUILD" -j 2
c++ -std=c++17 -O3 -fPIC -shared \
  -I"$JAVA_HOME/include" -I"$JAVA_HOME/include/linux" \
  -I"$ROOT/vendor/llama.cpp/include" -I"$ROOT/vendor/llama.cpp/ggml/include" \
  "$ROOT/app/src/main/cpp/llama_jni.cpp" \
  -Wl,--start-group "$BUILD/src/libllama.a" \
  "$BUILD/ggml/src/libggml.a" "$BUILD/ggml/src/libggml-base.a" \
  "$BUILD/ggml/src/libggml-cpu.a" "$BUILD/vendor/hash/libvendor-hash.a" \
  -Wl,--end-group -pthread -ldl -o "$BUILD/libmrgreen.so"
mkdir -p "$BUILD/jni-classes"
"$JAVA_HOME/bin/javac" -d "$BUILD/jni-classes" "$ROOT/scripts/host-jni/LlamaEngine.java"
"$JAVA_HOME/bin/java" -Djava.library.path="$BUILD" -cp "$BUILD/jni-classes" im.mrgreen.engine.LlamaEngine "$MODEL"
