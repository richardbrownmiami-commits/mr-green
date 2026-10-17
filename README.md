# Mr Green

An offline-first Android assistant for the owner's Redmi 9 (Android 11, 32-bit ARMv7). This is the **Phase 1 skeleton**, not the finished multi-phase product. SkillBot is not part of this repository.

The owner authorized building before Phase 0 device verification and will check the app on the phone. **Neither Phase 0 nor the Phase 1 offline-chat/frame-rate gate is claimed as passed.** See [Phase 1 status](docs/phase1-status.md), [build results](docs/test-report.md) and the supplied [plan](docs/plan.md).

## 0.1.3 ARMv7 inference fix

Native Debug inference now uses `-O3` throughout llama.cpp and GGML; the earlier Debug configuration had no optimization flag. The APK still contains only ARMv7 native libraries, uses NDK 27.2 and targets Android API 30. CPU-only ARMv7 NEON flags replace the unnecessary VFPv4 assumption. Chat now shows prompt/token counts and elapsed time, writes content-free stage/heartbeat/first-token diagnostics, and requests a cooperative stop after 180 seconds. A native compute call must reach an abort check before that timeout can return; this is not a hard real-time kill.

Start the phone retest with the existing Llama 3.2 1B GGUF, context **512**, **2 threads**, and a short message. Host build checks do not prove phone speed or solve every possible device/model issue. See [the diagnosis and phone checklist](docs/armv7-inference-fix.md). This build is signed with a new sandbox development key: if Android rejects an update, **do not uninstall or clear data**; use the original signing key for a data-preserving update.

## 0.1.2 Violet startup fix

The violet theme supplied literal text colors where Material 1.12.0 bottom navigation expects a color resource ID. Android-host startup tests reproduced `Resources.NotFoundException: Resource ID #0x0` during `MainActivity.buildShell()`. Resource-backed text colors fix that construction failure without changing the palette or native model code. See [the startup diagnosis and tests](docs/startup-fix-report.md). No owner-phone test is claimed.

## 0.1.1 Violet fix

This update fixes zero-token JNI batches, preserves native failure details in diagnostics, replaces the two composer buttons with a single Send/Stop control (including prompt evaluation), fixes fullscreen flags that broke keyboard resizing, and restyles all screens in opaque dark purple. See [the fix report](docs/violet-fix-report.md) for evidence and remaining phone checks.

## In this increment

- Kotlin and Views/Material UI: Chat, Overview, Models, Diagnostics, Library and permission onboarding.
- Original faceted purple sentinel SVG/launcher identity with reduced-motion support.
- Actual CPU-only llama.cpp JNI, streaming local responses and cancellation; no fake AI replies or cloud requests.
- Model import through the system picker; app-private model storage, one model at a time.
- Serialized engine queue, low-RAM context reduction, trim handling and idle unloading.
- App-private bundled SQLite with FTS5 schema, persisted chat and identity, metadata logs and a last-crash report.
- User-visible foreground service, active-task wake lock and Stop action.

Groq, action tools, plugin execution, learning/Doctor, voice, overlay, accessibility automation and document RAG are later phases. Their settings are not presented as completed capabilities. No API key is required for Phase 1.

## Build

Use JDK 17 (JDK 21 also works with this Gradle), Android SDK platform 36, build tools 36.0.0, NDK 27.2.12479018 and CMake 3.22.1. `minSdk=26`, `targetSdk=30`; sideload only. The wrapper uses Gradle 8.11.1.

```sh
sdkmanager 'platforms;android-36' 'build-tools;36.0.0' 'ndk;27.2.12479018' 'cmake;3.22.1'
# Set ANDROID_HOME or add sdk.dir=/absolute/path/to/android-sdk in local.properties.
bash scripts/fetch-llama.sh
./gradlew :app:assembleDebug :app:assembleRelease :app:testDebugUnitTest
python3 scripts/check-apk-abi.py \
  app/build/outputs/apk/debug/app-debug.apk \
  app/build/outputs/apk/release/app-release-unsigned.apk
```

`fetch-llama.sh` retrieves the exact upstream commit recorded in the Phase 0 host report. Vendor source and GGUF weights are not committed. The native build is CPU-only and packages `armeabi-v7a` only; it never builds an arm64 variant.

**Installable development APK:** `app/build/outputs/apk/debug/app-debug.apk`. It uses Android's development signing key. The release output is deliberately **unsigned** (`app-release-unsigned.apk`) until the owner provides their release-signing process. Do not treat it as an installable signed release, and do not commit signing keys. Debug signatures can differ between builders. If Android rejects an update because of a signature mismatch, stop and use the original signing key for a data-preserving update. **Do not uninstall or clear app data as a workaround: this erases private chat and imported models.**

CI builds both variants and retains APKs as workflow artifacts; it does not publish a release or certify phone behavior.

## Try it on the phone

1. Install the debug APK. Open **Models** and import a text-only GGUF through the Android file picker. No all-files permission is necessary for private imports.
2. Start with Bonsai Q2_0 and four threads, 2048 context. Only one local model is loaded. Do not download an mmproj/vision file.
3. Tap Load, wait for the real loading result, then ask a short writing question in Chat. The Phase 1 app cannot perform Android actions.
4. Stop generation with the Stop control. Unload before switching models. The service notification also lets you stop the engine.
5. Use the [owner checklist](docs/user-guide.md) to record real-phone results; nothing about MIUI, speed or FPS is assumed.

Shared `/sdcard/Red` access is optional and requires the owner's explicit all-files grant. Database and imported models stay in private app storage; do not uninstall without backing up wanted files. Logs omit prompts and replies. Chat is stored locally. Native faults cannot be made universally crash-proof by Kotlin; device stability still needs testing.

The original editable identity is [assets/mr-green-violet-sentinel.svg](assets/mr-green-violet-sentinel.svg); matching Android vector paths are used in the launcher and app. No stock image was copied. See [third-party dependencies](docs/third-party.md).
