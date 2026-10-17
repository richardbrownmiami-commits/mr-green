# Mr Green 0.1.3: ARMv7 inference fix

## What was found

The supplied 0.1.2 source builds llama.cpp and GGML in Android's native **Debug** configuration, with **no optimization flag**. The captured ARMv7 compiler commands contain debugging flags but no `-O1`, `-O2` or `-O3`. This is a concrete performance defect for a CPU-only 1B model, not proof of the exact cause of the reported ten-minute wait. [Before/after compiler evidence](validation-armv7-inference/compiler-flags.txt).

No deadlock was established by source review. The app evaluates the full formatted prompt before emitting output, and previously displayed only “Thinking locally…” during that work. Model loading succeeds before prompt evaluation, so “loaded” does not establish usable inference speed or sufficient runtime memory on the phone.

The ARMv7 compiler also exposed a pinned upstream bug: `llama-model-loader.cpp` stores a 4 GiB automatic lazy-row-loading threshold in 32-bit `size_t`, where it wraps to zero. This can incorrectly select the optional lazy path for small marked tensors. The bridge now explicitly disables **lazy row loading**, while retaining ordinary mmap demand paging. This is a supported 32-bit safeguard; its contribution to this particular phone stall is unmeasured. A separate 8 GiB overflow warning is in the unused model-quantization path; the app does not quantize models.

The existing APK's three ARMv7 libraries were not evidence that incompatible dependencies caused the stall. This update retains the same dependency versions and native library set. It does not add a 64-bit library or a GPU/OpenMP/KleidiAI backend.

## What changed

- All native Debug targets, including llama.cpp and GGML, compile with `-O3`. Java debugging and native assertions remain enabled.
- CMake requires `armeabi-v7a`, uses ARMv7-A with baseline NEON, and no longer assumes VFPv4. NEON-capable hardware is still required.
- Inference explicitly uses CPU-only settings. Optional GPU, dynamic-backend, ARM64-only KleidiAI, OpenMP and host-native tuning are disabled.
- Optional upstream lazy row loading is disabled to bypass its 32-bit AUTO threshold overflow; regular mmap model loading remains enabled.
- Chat distinguishes **Reading prompt completed/total** from **Replying token-count**, with elapsed seconds.
- Metadata-only logs record device/API/32-bit ABIs, model size, load settings, stage transitions, heartbeat/PSS every 15 seconds, first visible output latency and final errors. Prompts and responses do not enter these diagnostics.
- A cooperative native deadline requests a stop after **180 seconds** and returns a timeout error with the stage. It checks between batches/tokens and through llama.cpp's CPU abort callback. A long kernel or blocked callback must reach a check before returning: this is **not** a guaranteed hard three-minute kill.
- The concise system prompt is shorter and a reply is capped at 128 tokens instead of 256. This is an output limit, not a claim that model inference is fast.

Version: `0.1.3-armv7-inference-fix`, code `4`, package `im.mrgreen`. Target SDK remains 30, minimum 26; compile SDK 36 is a build tool choice, not a requirement for Android 36 on the phone. The pinned llama.cpp revision remains `f3363593f457169cdfbdfe22ea399b1cc139ba18` with NDK `27.2.12479018`.

## First phone test

1. Attempt a data-preserving APK update. This sandbox generated a new Android debug signing key; the earlier builder's key was not supplied. **If Android reports a signature conflict, stop. Do not uninstall or clear data**, because that removes private chats and imported models. Rebuild with the original signing key instead.
2. Keep the same Llama 3.2 1B **Instruct** text-only GGUF. Record its full filename, byte size and quantization (for example Q4_K_M). Do not substitute an mmproj or vision file.
3. Unload, select **512 context / 2 threads**, then load again. Keep the app visible and the phone screen on. Ask **“Reply with one short greeting.”** Record the exact status and time to first visible output.
4. Check that prompt counts advance, output streams, and Stop works during prompt evaluation and reply generation. Run a second short request. The second request checks reuse, not a guaranteed speedup.
5. If it stops or stays slow, send the Diagnostics entries for that run, especially **Local chat started**, **Inference progress**, **Inference heartbeat**, **First output**, and **Local chat completed/native_error**. A screenshot showing the last prompt/token count is useful.

## Exact information needed if it still stalls

Send the phone's manufacturer/model, Android/MIUI version, total RAM and available RAM before loading; the complete GGUF filename/size/quantization and whether it is Instruct; selected context and threads; the last progress count and elapsed time; whether the screen stayed on; and whether Stop returned control. Record whether the issue happens on a first short request or only after chat history grows.

The app's Diagnostics screen lets you copy entries. For a debug build with ADB, the following collects the app's content-free rolling runtime log and native/system logs. The system log can contain other app/device details: review it before sharing.

```sh
adb shell getprop ro.product.model
adb shell getprop ro.build.version.release
adb shell getprop ro.build.version.sdk
adb shell getprop ro.product.cpu.abilist32
adb shell cat /proc/cpuinfo
adb shell dumpsys meminfo im.mrgreen > mrgreen-memory.txt
adb shell run-as im.mrgreen cat files/logs/runtime.log > mrgreen-runtime.log
adb logcat -d -v threadtime > mrgreen-logcat.txt
```

If optional all-files access is enabled, the rolling runtime log is instead at `/sdcard/Red/logs/runtime.log`; collect it with `adb pull /sdcard/Red/logs/runtime.log`. No all-files grant is needed to use the model.

## What remains unverified

No owner-phone installation, Android 11 runtime test, frame-rate test, thermal test or speed guarantee is claimed. Device memory pressure, CPU features, thermal throttling, a particular quantization/kernel, model correctness and long-history latency remain possible causes. Existing cancellation/maintenance edge races were identified in review but were not established as the cause of this report; this correction does not claim a general concurrency rewrite.

## Validation results

- **Android debug build: passed.** `:app:assembleDebug`, `:app:testDebugUnitTest` and `:app:lintDebug` completed successfully with JDK 17 and the pinned SDK/NDK. [Final build output](validation-armv7-inference/final-build.txt).
- **24 host/JVM/Android-host tests passed**, with zero failures, errors or skips. These include the existing API 26/API 30 screen-startup tests and two new progress/timeout-policy tests. These are not phone instrumentation tests.
- **Lint: zero errors, 25 warnings.** The warnings include existing sideload-only permission/ABI policies, available dependency updates and UI/style suggestions. Dependencies were deliberately not broadly upgraded. [Lint output](validation-armv7-inference/lint.txt).
- **All 76 actual Android native compile commands use ARMv7 Android and effective `-O3`.** The APK contains exactly three libraries, each verified as ELF32 ARM: `libmrgreen.so`, `libsqlite3x.so`, and `libc++_shared.so`, all under `lib/armeabi-v7a/`. There are no ARM64 libraries. [APK/manifest audit](validation-armv7-inference/apk-audit.txt).
- **Native dependency/API notes checked.** The JNI library's Android build note is API 26; SQLite and libc++ notes are API 21. Dynamic dependencies are the packaged libc++ and Android platform libraries, not extra third-party/GPU libraries. These checks establish build/package properties, not full Android 11 runtime certification. [ELF evidence](validation-armv7-inference/elf-dependencies.txt).
- **APK signature verification passed** with Android debug RSA signing and APK v2 signing. This is a new development signer, not a guaranteed update match for the previous APK. [Signature evidence](validation-armv7-inference/signature.txt).
- **The final JNI source passed a real x86-64 Linux host inference smoke test** using [SmolLM2-135M-Instruct-Q4_K_M.gguf](https://huggingface.co/bartowski/SmolLM2-135M-Instruct-GGUF/resolve/main/SmolLM2-135M-Instruct-Q4_K_M.gguf): model load, 21 streamed callbacks, both progress stages, cancellation after three streamed callbacks, cancellation at prompt-stage entry, a successful subsequent request, and unload. This is a smaller different model, not the owner's Llama 3.2 1B or ARMv7 phone. The asserting harness is included at `scripts/host-jni/`; it is separate from the Android CMake build. [Host smoke-test output](validation-armv7-inference/host-jni-summary.txt).

The 180-second timeout's full wall-clock expiry was **not** exercised; only the deadline code and policy were reviewed/tested. Request-after-cancel success does not prove all queue races are resolved or directly measure prefix-cache reuse. No phone-validation claim is made.

The public repository was not updated in this session because GitHub authorization was not enabled. The source archive and a patch against public HEAD `29b321b35c719cdf7a0002980826d4ab2f8cfd34` are provided for the coordinator's authorized push.
