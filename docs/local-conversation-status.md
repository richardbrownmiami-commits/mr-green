# Common replies and truthful local status

Mr Green answers common conversation without loading a GGUF model. Try `hi`, `hello`, `how are you`, `what is your name`, `who is Mr Green`, `what can you do`, `are you offline`, or `do I need a model`. These are conservative whole-message defaults, not keyword searches through arbitrary text. Identity aliases use the existing validated editable identity profile. Non-default identity data keeps its untrusted-data label.

## Live phone readings

- `battery`, `battery level`, or `battery percentage`: reads Android’s current sticky battery broadcast; absent or invalid readings report unavailable.
- `time`, `tell me time`, or `what time is it`: reads the current device clock and local time zone on each request. An incorrect phone clock will still give an incorrect real-world time.
- `storage`, `storage status`, or `how much space is left`: reads `StatFs` for the app-data volume. Reports binary GiB, not free space on every removable volume. Missing/invalid capacity reports unavailable.
- `status`, `system status`, or `phone status`: reads Android version/API, available/total RAM and the Android low-memory flag through `ActivityManager`, plus app-volume storage. Each unavailable component is labeled; this is not a full device-health assessment.
- Existing `model status`, settings handoffs, arithmetic, note and app actions remain available. `storage settings` still opens settings rather than silently changing storage.

Live readings are Kotlin/Android actions, never RiveScript templates, learned answers or model guesses. No permission, cloud route, additional model/runtime or native ABI was added. The build remains ARMv7-only with the existing minSdk 26 and targetSdk 30.

## Job status means recorded evidence

Ask `job status`, `task status`, `is it done`, `complete job`, or `comple job`. These phrases do not execute or mark a job complete. They report the last **recorded registered-action result** from the shared chat/plan action runtime in this app session, including approved routine execution. No result means no confirmation. Verified results, external handoffs, failures, pending confirmations and cancellation have distinct labels. A Clock/settings handoff is never proof that the external work completed.

This is not durable or named-job tracking. It resets with the runtime/process, does not inspect arbitrary external jobs or infer success from chat, and subsequent registered actions (including readings) replace the recorded result. Plugin-owned runtimes and non-registered screen/recipe actions are not aggregated into this record. Every reply scopes itself to the recorded action, not an unspecified job.

## Data and routing

Registered actions keep first refusal, followed by validated identity and app-owned conversation defaults, then the existing Rive/memory/local-GGUF path. No user-authored `.rive`, editable profile or saved-chat migration is performed. Default replies are not auto-learned into user rules. Arbitrary generation and memory-dependent questions still require the local GGUF; no cloud fallback exists.

## Validation

Focused API-30 Robolectric tests exercise greeting/identity routing without generation, profile preservation, status aliases, current clock readings, changed battery/RAM/storage measurements, unavailable readings and every typed job outcome. Existing action and local-only regression suites remain part of the Gradle validation. Physical Redmi/MIUI behavior, installation/signing compatibility and device measurements require phone testing; no physical-phone result is claimed.

Completed validation for this update:

- `:app:testDebugUnitTest`: 262 tests discovered, 259 passed, 3 pre-existing `GreenStoreTest` tests skipped because the Android requery native runtime cannot run on the host JVM. All 14 new conversation/status tests passed.
- `:app:lintDebug` and `:app:assembleDebug`: passed. The debug APK is development-signed, not signed with the owner's original update key.
- `check-apk-abi.py`, `check-inference-build.py`, and integrated `audit-phase7.py`: passed; four ELF32 ARM libraries, ARMv7-only, targetSdk 30/minSdk 26 and no model/private assets.
- Storage SQL, integrated-storage, conversation-memory SQL, conversation wiring and safe note-search scripts: 29 host checks passed.
- An existing success-path storage test now supplies nonzero Android `StatFs` evidence; the new unavailable-reading test independently verifies that missing capacity is not reported as verified storage.

No release build, physical Redmi installation, MIUI check or owner-key update compatibility check was performed. Native engine execution and the complete controller/service lifecycle were not exercised on a phone; host routing tests use the production action/static reply implementations plus source-order checks.
