# Mr Green

An offline-first Android assistant for the owner's Redmi 9 (Android 11, 32-bit ARMv7). This is the **Phase 1 skeleton, Phase 2 AI Gateway and scoped Phase 7 plugins**, not the finished multi-phase product. SkillBot is not part of this repository.

The owner authorized building before Phase 0 device verification and will check the app on the phone. **Neither Phase 0 nor the Phase 1 offline-chat/frame-rate gate is claimed as passed.** See [Phase 1 status](docs/phase1-status.md), [build results](docs/test-report.md) and the supplied [plan](docs/plan.md).

## 0.7.1 Live Rive rules and local answer learning

Open **Overview → Rive rules & learning** in the same `im.mrgreen` app. Rules are checked before cloud or GGUF generation. Import a `.rive` file or edit the imported text; validation and replacement approval happen before an atomic app-private write and live interpreter swap. A streaming answer is never rewritten. Rejected edits or failed reloads retain the working in-memory rules. Export uses the system document picker; no new storage permission is needed.

**Auto-learning starts off.** Enabling it keeps rule misses on the local GGUF path, even if cloud is enabled. A successful, non-cancelled answer that ends before the token limit can become a literal rule immediately after generation. Repeat questions then use that saved answer without loading the model. Incomplete, failed, cancelled, unsafe-to-serialize or oversized answers are not learned. Changing/reloading/removing rules during generation prevents that turn from adding a stale rule. Imported owner rules always win; the model never overwrites them. Review/delete individual learned replies or clear them all in the rule screen.

These are **unverified saved answers, not model training or verified knowledge**. A model mistake can be repeated; answers can become stale or depend on earlier conversation. Matches ignore case, repeated whitespace and trailing `. ! ?`, but do not generalize to paraphrases. Questions with other punctuation/operators are not learned or matched, avoiding arithmetic/identifier collisions. Disable learning or delete an item when inappropriate. Disabling learning stops new saves, not existing rule replies. Exported learned answers become ordinary imported rules if reimported, so review them first.

For safety, this increment accepts a **literal-only RiveScript subset**, compiled with the pinned Java interpreter: `! version = 2.0`, whole-line `//` comments, one lowercase `+ trigger` containing letters/numbers/spaces and one plain `- reply`. Replies may use `\n` for newlines. No wildcard/regex triggers, executable objects, tags, redirects, topics or tools are accepted. For example:

```text
! version = 2.0

+ hello
- Hello! How can I help?
```

The combined imported/learned store is limited to 500 rules and 256 KiB; individual questions to 256 characters and replies to 4096. It lives in private `files/rive/rules.rive`, not the APK. No model, chat database or existing plugin is replaced. Real Redmi model inference and installation still require phone verification; use the original signing key for a data-preserving update.

Host verification for this increment: `:app:testDebugUnitTest :app:assembleDebug :app:lintDebug` succeeded with 101 passing tests, 3 existing skipped tests, and lint reporting 0 errors / 52 warnings. All 28 new Rive engine, learning-policy and UI tests passed. The three production-SQL host checks, 15 native QuickJS checks, APK ABI/security audit and debug signature verification also passed. This is not a phone inference, installation, performance or Android database-migration result.

## 0.7.0 Plugins (Phase 7 directly after Phase 2)

The owner requested skipping directly from Phase 2 to Phase 7. This change starts at `f1b141bfe12d87c9a4d1f612f6265817479c3342`; unfinished Phase 3 work is not included. **Phases 3–6 and 8+ are not implemented.** The same `im.mrgreen` app adds a bounded declarative flow runtime, native QuickJS sandbox, signed `.redpack` validation, mocked tests, explicit permissions/size approval, local/optional AI draft builder, 200 MiB library cap, previous-version rollback and failure safe mode. Open **Overview → Plugins · Phase 7** or **Library → Plugins · Phase 7**.

Only safe plugin-local output, namespaced memory and clock APIs are exposed. Android action tools are not connected. Runtime HTTP/AI calls fail closed; the separate user-requested builder uses the existing Phase 2 gateway and local fallback. Active packs/security state are app-private rather than shared SD files; SD/file-picker imports are supported. No automatic GitHub downloader is claimed. See [pack format and signing](docs/phase7-pack-format.md), [flow syntax](docs/phase7-flow-format.md), [QuickJS limits](docs/phase7-quickjs.md) and [Phase 7 checks and phone checklist](docs/phase7-status.md).

**A25/A26 remain pending on the Redmi.** Host tests, native loop/OOM checks and an ARMv7 APK do not establish device acceptance. This sandbox has a different development signing key; if Android rejects an update, **do not uninstall or clear app data**. Use the original signing key for a data-preserving update.

## 0.2.0 AI Gateway (Phase 2)
The owner asked to skip the optional 55 fps visual measurement and proceed with **Phase 2 only**. It remains unmeasured, not passed. The same `im.mrgreen` app now adds Groq-first text chat with local fallback, encrypted user-pasted credentials, runtime model discovery, redaction and P2 blocking, quota/backoff handling, metadata-only cloud audit, Cloud/Local badge and connectivity-loss pre-warm. Cloud starts **off**; offline inference remains available without an account or key. No SkillBot code is involved.

Open **Overview → AI Gateway / Cloud**, paste your own key there, enable cloud and discover models, then choose fast and smart models. Do not put keys in source, Gradle properties or issue reports. Only explicit requests are cloud-eligible; previous unclassified chat history remains local. Sensitive provenance is always local and each personal-data category has a Local only switch. Later-phase actions, screen capture and Doctor are not implemented. Phase 7 adds only the scoped plugins described above.

See [Phase 2 status and phone checklist](docs/phase2-status.md). Build-computer checks are not Redmi evidence: **A22 and A23 remain pending on the actual phone**. Do not uninstall or clear app data to bypass a signing mismatch. A source backup was made before editing; the app database upgrade is additive.

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
- Actual CPU-only llama.cpp JNI, streaming local responses and cancellation; Phase 2 adds optional cloud requests behind the gateway.
- Model import through the system picker; app-private model storage, one model at a time.
- Serialized engine queue, low-RAM context reduction, trim handling and idle unloading.
- App-private bundled SQLite with FTS5 schema, persisted chat and identity, metadata logs and a last-crash report.
- User-visible foreground service, active-task wake lock and Stop action.

Android action tools, broader learning/Doctor, voice, overlay, accessibility automation and document RAG remain later phases. Only the bounded local reply learning above is implemented. No API key is required for local offline operation or local pack building/execution.

## Build

Use JDK 17 (JDK 21 also works with this Gradle), Android SDK platform 36, build tools 36.0.0, NDK 27.2.12479018 and CMake 3.22.1. `minSdk=26`, `targetSdk=30`; sideload only. The wrapper uses Gradle 8.11.1.

```sh
sdkmanager 'platforms;android-36' 'build-tools;36.0.0' 'ndk;27.2.12479018' 'cmake;3.22.1'
# Set ANDROID_HOME or add sdk.dir=/absolute/path/to/android-sdk in local.properties.
bash scripts/fetch-llama.sh
bash scripts/fetch-quickjs.sh
./gradlew :app:assembleDebug :app:assembleRelease :app:testDebugUnitTest
python3 scripts/check-apk-abi.py \
  app/build/outputs/apk/debug/app-debug.apk \
  app/build/outputs/apk/release/app-release-unsigned.apk
```

`fetch-llama.sh` retrieves the exact upstream commit recorded in the Phase 0 host report. `fetch-quickjs.sh` verifies the official source archive SHA-256. Vendor source and GGUF weights are not committed. The native build is CPU-only and packages `armeabi-v7a` only; it never builds an arm64 variant. Run `bash scripts/test-quickjs-host.sh` for the standalone native sandbox harness and `python3 scripts/audit-phase7.py APK` for heuristic source/APK privacy checks.

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
