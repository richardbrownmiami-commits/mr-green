# Mr Green — Purple UI and local-model fix

## Why the model loaded but would not answer

The app contained a confirmed native batching bug, not just an unhelpful error label. It filled the token arrays but never set `llama_batch.n_tokens`. The pinned llama.cpp initializer leaves that field at **zero**, so prompt evaluation received an empty batch and failed before producing text. The same omission existed in the single-token generation path. Both are fixed.

A host regression using the same pinned llama.cpp revision reproduced the underlying error:

```text
decode: n_tokens == 0
llama_decode: failed to decode, ret = -1
empty_batch_n_tokens=0 empty_decode_rc=-1
nonempty_batch_n_tokens=5 prompt_decode_rc=0 generated_tokens=8
```

The test used a [small public stories15M GGUF](https://huggingface.co/ggml-org/models/resolve/main/tinyllamas/stories15M-q4_0.gguf), not your phone or your exact Llama 3.2 1B file. It confirms the code defect and correction, but does not certify phone performance or model compatibility. The [pinned initializer source](https://github.com/ggml-org/llama.cpp/blob/f3363593f457169cdfbdfe22ea399b1cc139ba18/src/llama-batch.cpp) and the included `scripts/native-batch-regression.cpp` make the finding reproducible.

Native errors now retain the failing phase and return code in Diagnostics/runtime logs instead of repeating only `E_LLM_GENERATE`. Cancellation is reported as cancellation, not an inference failure. Prompt and response content are not deliberately added to these diagnostics.

The earlier Android memory-trim warning is a separate concern: memory safety unloading remains enabled. No claim is made that a 1B model can never exceed the phone’s available RAM.

## What changed on screen

- One composer button: **Send** while idle, **Stop** throughout prompt evaluation and generation, then **Send** after completion, cancellation, or failure. Loading/unloading shows disabled **Working**.
- The draft remains editable, expands up to four lines, and uses the resized space above the keyboard. The hero and bottom navigation hide while the keyboard is visible.
- Fixed an inverted system-bar flag that accidentally enabled fullscreen mode, defeating keyboard resizing. Explicit `adjustResize` is also set, following [Android’s guidance](https://developer.android.com/develop/ui/views/touch-and-input/keyboard-input/visibility).
- Opaque dark-purple surfaces, rounded lavender controls, clearer text, custom navigation icons, and **Chat / Overview / Models / Diagnostics / Library** labels across the app and onboarding.
- Original editable **Violet Sentinel** SVG: a purple, faceted, hooded AI helmet with dimensional shading. Matching vector paths replace the green dot in the app and launcher; the notification uses a monochrome silhouette. No Google image was copied.

## What passed

Version **0.1.1-violet-fix**, version code **2**, package `im.mrgreen`.

The debug APK compiled successfully with the pinned Android SDK/NDK and JDK 17. All **12 JVM tests passed**: runtime policy, honest missing-JNI behavior, cancellation classification, and composer states including Stop before the first streamed token. Android lint completed with **0 errors and 25 warnings**; warnings remain, including sideload/Play policy and dependency-update advisories. APK verification passed: **armeabi-v7a only**, three native libraries, valid debug signature.

No physical-phone or emulator UI run was performed. Keyboard behavior, visual fit, real Llama output, memory use, and speed still require the phone retest. The source archive excludes generated build caches, model weights, signing keys, and vendor checkout; the included fetch script retrieves the exact pinned dependency. Only the supplied Mr Green source was changed; SkillBot was untouched. No repository commit or push was made.

## Try this build safely

Install the new debug APK. It uses this builder’s development key, which may differ from the previous APK: **do not uninstall the old app before backing up wanted chat and private imported models** if Android rejects the update.

Open **Models**, load your existing GGUF, and initially try **1024 context / 4 threads** because your logs include memory pressure. Ask a short question, check that Stop appears immediately, cancel one answer, then send another. Type a multiline draft with the keyboard open and check the caret remains visible. If it still fails, copy the new detailed entry from **Diagnostics**, along with the exact GGUF filename/quantization and selected context.

Debug certificate SHA-256: `1708b8679b6e7a109f4a8bdb12596b16a34559c2ac65825114e44a715fd0f3e4`.
