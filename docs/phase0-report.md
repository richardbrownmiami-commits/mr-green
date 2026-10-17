# Mr Green Phase 0 Report

Date: 2026-10-17

## Gate status

**NOT PASSED.** The required target-phone checks were not possible in the current workspace. No Phase 1 application features have been started. This report separates the available host test from Android-device verification.

## Target from the plan

The plan specifies a Redmi 9 M2006C3MII (cattail), Android 11/API 30, 32-bit `armeabi-v7a`, Helio G35, and a 1.2 GB peak-RAM target. These specifications and the plan's earlier Qwen speed report are owner-provided; they were not independently checked in this run.

## Results

| Check | Result | Evidence / limitation |
|---|---|---|
| Bonsai 2 2B Q2_0 model loads and answers | **Host smoke test passed** | The exact 546,689,504-byte GGUF with SHA-256 `c2893fa6f951daee8b7263d5f49b455e57989ef56417a22125b03566dc560650` loaded through official llama.cpp commit `f3363593f457169cdfbdfe22ea399b1cc139ba18`. A host-side app-style prompt/tokenization run answered the test question. |
| Host generation speed | **Measured on x86_64 host only** | 11.3 tokens/second with four threads. This is not the target phone's speed. |
| Host memory observation | **Partial host observation only** | The run reserved about 254 MiB for the compute buffer in addition to memory-mapped model weights. This is not an Android peak-RAM measurement. |
| Android NDK/ARMv7 build | **Not run** | No Android SDK/NDK is configured in this workspace. No APK was built. |
| Load, speed, and peak RAM on the Redmi 9 | **Not run** | No physical target phone or ARMv7 emulator is available. The plan's >=2.0 tok/s and <1.2 GB peak-RAM gates remain unverified. |
| Internal-storage versus `/sdcard/Red/models` loading | **Not run** | Requires Android-device timing and storage tests. |
| SQLite FTS5 availability | **Not run** | Requires checking the Android runtime SQLite build. |
| QuickJS ARMv7 build and sandbox behavior | **Not run** | Requires an Android ARMv7 build/device run. |
| Groq call, streaming, JSON mode, 429 fallback | **Not run** | No app gateway exists yet; no Groq test key was supplied. No key is stored in the repository. |
| MIUI overlay, accessibility, screenshot, and UI frame rate | **Not run** | Requires the Redmi 9, user-granted special permissions, and on-device measurement. |

## Decision

The earlier host smoke test confirms that the selected Q2_0 model can answer through the pinned llama.cpp host runtime and that the GGUF provides the model tokenizer/template. It does **not** meet the plan's mandatory real-phone Phase 0 gate. Do not treat host speed, memory, FTS5, QuickJS, or UI results as passed. Before relying on these components or starting Phase 1, repeat the unverified checks on the specified phone; any decision to proceed without them should explicitly waive or revise the plan's Phase 0 gate.
