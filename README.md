# Mr Green

An offline-first Android assistant for **Redmi 9 M2006C3MII, Android 11/API 30, 32-bit ARMv7**. This repository now contains one integrated app spanning the root [roadmap](plan.md), including the previously missing phase modules. GGUF chat, Rive replies and plugin features are retained; model generation is strictly on-device. SkillBot is not part of this repository and was not changed.

**Version: 0.10.7-web-facts-preview.** [Temporary web answers](docs/temporary-web-facts.md) add explicit `ask web QUESTION` requests: bounded public-source retrieval, local OpenNARS checks for a small factual grammar, and cited extractive answers otherwise. This feature needs no GGUF or cloud AI and never saves web questions, material or answers to SQL chat, memory or learned replies. Ordinary local chat/actions remain unchanged. [Built-in conversational Rive rules](docs/bundled-conversation-rules.md), [safe local actions](docs/safe-local-actions.md), and [editable identity/help data](docs/local-identity-integration.md) are retained. No cloud model route or dedicated cloud maintenance service is available.

 The [conversation recall fix](docs/conversation-memory-fix.md) adds memory-aware local routing, recent follow-up context and bounded note fitting. It includes the [Phase 3 routing fix](docs/phase3-routing-fix.md), which keeps recognized incomplete actions out of model generation. The earlier repository-wide audit is in the [test report](docs/test-report.md). This is still phone-testing code, not a declaration that every roadmap gate passed. No Redmi installation, phone benchmark, MIUI longevity or third-party-app automation result is claimed. **Normal builds keep screen reading disabled on Android 11–13.** An explicitly built personal-use override permits local, tap-triggered accessibility reads after separate on-device risk acceptance; it cannot reliably detect protected windows. This does not resolve the audit's secure-window limitation. Read the [phone guide](docs/user-guide.md#screen-and-app-recipes).

## What is integrated

Open **Overview → All capabilities · Phases 3–10** in the same `im.mrgreen` app.

| Roadmap area | Implementation |
|---|---|
| 0–2: foundation and local chat | CPU-only llama.cpp/JNI, bundled SQLite/FTS5, chat, model picker, foreground service, diagnostics, editable local identity/help, deterministic routes then local GGUF; cloud generation disabled |
| 3: actions | Rules-first Understanding Library, bounded fixed tool registry, immutable sequential plans, time/date/math, alarm/timer handoffs, private note save/list/search, launcher app search/open, battery/storage/model readings, redacted diagnostics, settings handoffs and permission-gated flashlight |
| 4: events and media | Opt-in notification/call/media observation, private event history, generic proactive alerts, quiet hours/allowlist/rate controls, MediaStore/MX Player/YouTube handoff, audio-state checks, optional reboot restoration |
| 5: screen assistant | Normal builds fail closed on API 30–33; personal builds require explicit local risk acceptance; API 34+ keeps window-targeted capture. Password/blocklist checks and local Q&A remain; neither path is device-verified |
| 6: planning, learning and Doctor | Local JSON plan review, approved routine reuse, Learning inbox, typed data-only repair proposals, expected-incident/golden replay, explicit approval, undo, snapshots and staged database restore, metadata-only incident reports, idle maintenance |
| 7: plugins | Existing bounded flows, QuickJS sandbox, signed `.redpack` validation, review/permissions/200 MiB cap, rollback and safe mode; approved packs can call registered core actions |
| 8: knowledge | Explicit temporary web questions with local ONA/extractive cited answers; bounded web search/fetch and recipe cache, TXT/PDF/EPUB import with local citations, FTS retrieval, explicit-public-argument HTTPS MCP JSON-RPC review |
| 9: accessibility recipes | Confirmed WhatsApp/Wi-Fi recipes with visible Stop and bounded traversal; personal-use manual tree/tap/scroll/swipe/text/Back/Home controls require explicit consent and one-action confirmation; external AI-app routes remain disabled |
| 10: extensions and polish | Installed-companion signer pinning and exact-argument review; signed ARMv7 engine-patch staging, startup revalidation and crash fallback; voice/theme/wallpaper/Bluetooth controls and live setup checklist |

All these modules share the existing database, application identity, engine queue and one local model. The optional `samples/companion` project demonstrates Layer 3; it is an extension example, **not another phase-specific assistant APK**.

### Common conversation and live status without GGUF

Try `hi`, `hello`, `how are you`, `what is your name`, `who is Mr Green`, or `what can you do` for deterministic local replies. `battery`, `time`, `storage` and `system status` read current Android APIs instead of using saved/model answers. `job status` (also `complete job`) reports only the last recorded registered-action result in this app session, distinguishing verified results from handoffs, failures and cancellation; it never marks a job complete. See [examples and evidence limits](docs/local-conversation-status.md). User-authored Rive rules and existing saved data are not rewritten.

Everyday phrases including `thanks`, `that's good`, `sure`, `okay`, `sorry` and `nice to meet you` also work offline. Open **Rive rules & learning → View built-in Q&A rules** to inspect the defaults or export them separately. Your saved auto-learning on/off choice is preserved; review the switch and test new completed local GGUF answers under **Review learned rules**. See the [Q&A and learning test guide](docs/bundled-conversation-rules.md).

### Fast local conversation memory

Local chat sends the current question with at most two short historical snippets, not the transcript. Follow-ups can use the latest exchange without shared keywords; relevant older matches use a persistent app-private lexical index. Oversized notes are shortened to fit the model budget. **Recall-dependent turns bypass standalone saved replies and require the local GGUF model. All generation routes, including plugin drafting and planner/context requests, are local-only; a missing model reports unavailable rather than using cloud.** The word/character index is **not a neural embedding model**, adds no second model, and can still miss older paraphrases. Native KV caching only reuses identical prompt prefixes. Open the memory screen to inspect, disable or clear the index; chat remains saved when vectors are cleared. See the [integration contract](docs/integration-conversation-memory.md). Speed and actual answer quality still need phone measurement.

## Build

Use JDK 17, Android SDK 36, build-tools 36.0.0, NDK 27.2.12479018 and CMake 3.22.1. The Gradle wrapper is 8.11.1. The app remains `minSdk=26`, `targetSdk=30`, for sideloading.

```sh
sdkmanager 'platforms;android-36' 'build-tools;36.0.0' 'ndk;27.2.12479018' 'cmake;3.22.1'
# Configure ANDROID_HOME, or sdk.dir in local.properties.
bash scripts/fetch-llama.sh
bash scripts/fetch-quickjs.sh
./gradlew :app:assembleDebug :app:assembleRelease :app:testDebugUnitTest :app:lintDebug
python3 scripts/check-apk-abi.py app/build/outputs/apk/debug/app-debug.apk app/build/outputs/apk/release/app-release-unsigned.apk
python3 scripts/check-inference-build.py app/.cxx/Debug/*/armeabi-v7a/compile_commands.json app/build/outputs/apk/debug/app-debug.apk
python3 scripts/audit-phase7.py --integrated app/build/outputs/apk/debug/app-debug.apk
python3 scripts/check-phase2-storage.py
python3 scripts/check-integrated-storage.py
python3 scripts/test_conversation_memory_sql.py
python3 scripts/check-conversation-wiring.py
python3 scripts/test-safe-note-search.py
python3 -m pytest -q tests/extensions
bash scripts/test-quickjs-host.sh
```

The fetch scripts pin native dependencies and apply narrow, reproducible ARMv7 overflow/bounds fixes. No arm64, GPU, OpenMP, KleidiAI or host-native instruction selection is enabled. Models and native vendor checkouts are not committed. Build helpers use Python on the computer; there is **no Python runtime or embedding model in the app**.

The small MIT OpenNARS runtime is vendored at a pinned revision under `vendor/ona` with documented capacity and RAM-only embedding patches. Run `bash scripts/test-ona-host.sh` to check actual core inference, unknown answers, reset and absence of source logging. Only simple supported relations are translated, not arbitrary English; see the [web-answer limits](docs/temporary-web-facts.md).

The installable debug output uses the builder's development key. Gradle's release output is unsigned until separately signed. Any distributed development-signed release is labeled as such; it is not owner production signing. **If Android reports a signature mismatch, do not uninstall or clear data. Use the original signing key for a data-preserving update.** Models, chats and imported data are private app data.

### Personal-use Android 11–13 screen reading

Build with `./gradlew -PpersonalScreenReading=true :app:assembleDebug`. Omitting the property (or using any value other than `true`) keeps the override unavailable. The property is deliberately not saved in `gradle.properties`. A personal APK is not device-locked: do not distribute it as a normal release.

On the phone, open **Capability setup → Enable personal-use screen reading**, accept the protected-window limitation, then **Review Screen assistant** to enable its Android accessibility service. Consent starts off, is stored only on the device, and can be revoked in Capability setup. Tap the bubble only on harmless screens. Password, filled-editable, app-blocklist and window-transition checks remain best-effort; secure-window status is unverified. No screenshot proof is fabricated, no cloud-screen route is added, and screen text is not saved. API 34+ retains the existing capture policy.

## Start on the phone

1. Install a correctly signed build without erasing the existing app's data.
2. Open the live setup checklist. Grant only the permissions and special access you need; the app does not grant them itself.
3. Start local-model testing with a small compatible text GGUF, **context 512 and 2 threads**. Do not load two models or a vision/embedding model.
4. Try `what time is it`, `what is 17% of 2450`, `what date is 45 days from today`, `make a note: buy milk and eggs`, and `check notes` before testing automation.
5. Test alarm/timer setup in the external Clock UI before relying on it. An opened intent is not proof an alarm was saved.
6. Run the [A1–A35 phone checklist](docs/test-report.md). Background survival, frame rate, RAM, screenshot protection, Binder and native-patch execution need real-device evidence.

## Important limits

Clock handoffs, playback, third-party layouts and native patch loading must be verified on the target phone. **Android 11 screen reading is personal-use only with explicit risk acceptance; normal builds remain blocked.** Neither the legacy override nor API 34+ capture is device-validated. Screenshot-to-cloud vision and generated playlists are not implemented. Private-source data is not sent to MCP. Web/MCP connections validate the actual public DNS address list used for HTTPS, retain TLS verification and block redirects/proxies. The web client and app recipes are bounded implementations, not universal automation. Read the detailed limits before treating a roadmap gate as passed.

See [pack format](docs/phase7-pack-format.md), [flow syntax](docs/phase7-flow-format.md), [QuickJS limits](docs/phase7-quickjs.md), the [optional companion sample](samples/companion/README.md), [third-party dependencies](docs/third-party.md), and [VERIFY checklist](docs/verify-status.md). The original editable icon remains [the violet sentinel](assets/mr-green-violet-sentinel.svg).
