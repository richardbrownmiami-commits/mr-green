# Phase 7 is ready for the Redmi check

This increment starts from Phase 2 commit `f1b141bfe12d87c9a4d1f612f6265817479c3342`, as the owner requested. It adds **only Phase 7 layers 1 and 2** to the existing Mr Green app. Unfinished Phase 3 changes are not included, and Phases 3–6 and 8+ are not claimed.

**A25/A26 are pending on the Redmi 9.** Build-computer checks are not device acceptance.

## What to try

Open **Overview → Plugins · Phase 7** (also available in Library).

1. Choose **Build a local flow**, leave the editable local template and expected result, then **Validate draft**. It should show a separate review with permissions, layers, tools and size; nothing is installed yet. Reject once and confirm the list stays unchanged.
2. Validate again and approve. Run `flows/main.json`; confirm a successful local result. This works without a cloud key or network.
3. Build the same pack ID with version `2.0.0` and a different message, approve and run it. Use **Rollback** and confirm the original version/message returns. A failed test or malformed pack must not replace the current version.
4. Build a local script. The starter uses only local notification/output; validate, approve and run. Try a wrong expected result and confirm it is rejected before install.
5. To check the sandbox deadline, replace the draft with `while (true) {}`. Validation must stop and reject it, rather than installing it. A memory-exhausting draft must also stop. Do not treat these two checks alone as proof that all A26 crash/safe-mode cases passed.
6. Restart the app and confirm installed versions and permitted plugin memory survive. Safe mode can be enabled explicitly; **Leave safe mode** must be explicit and must not re-enable a disabled failing version.
7. Recheck the existing offline chat/model loading and Phase 2 gateway controls. Only the owner can establish their behavior on the phone.

If Android reports a signature conflict, **do not uninstall or clear app data**. This build uses a development signing key generated in this environment; a data-preserving update needs the original signing key.

## What was implemented

- A bounded, strict JSON flow runtime for `call`, `ask_user`, `if`, `foreach`, `set`, `wait`, `verify`, `ai_write` and `on_error`.
- A native QuickJS bridge: 16 MiB allocator cap, 512 KiB stack cap, maximum 4-second interrupt deadline, 50 aggregate host calls and 64 KiB host JSON limits. No ambient modules/platform/network, dynamic evaluation or imports.
- Signed `.redpack` validation with hashes, owner-key fingerprint review, a separate Android Keystore local signing identity, bounded archive expansion and JSON depth, declared permissions/tools and required expected-output tests.
- Side-effect-free dry-runs before a separate explicit install approval, revalidation before activation, one retained previous version, validated rollback and interrupted-swap recovery journals.
- A 200 MiB library ceiling, 32 MiB per-import expanded-size ceiling, 80% warning, deletion confirmation, and namespaced persistent memory capped at 1 MiB per pack/8 MiB aggregate in the app.
- Tokenized interrupted-run markers, three-consecutive-failure disabling and global safe mode; preparation success does not clear runtime failures.
- Same-app builder/import/review/run/delete/rollback controls. Optional AI drafting uses the existing Phase 2 serialized gateway/local fallback; it never installs its own output.

Only plugin-local output, clock and memory capabilities are wired. Runtime HTTP/AI and later-phase Android action tools fail closed. Active packs and trust state stay app-private; file-picker/SD imports work. There is no automatic GitHub downloader or later-phase engine/companion loader. See [the pack format](phase7-pack-format.md) for exact limitations and signing instructions.

## Host checks

`./gradlew :app:assembleDebug :app:assembleRelease :app:testDebugUnitTest :app:lintDebug` passes. The suite contains **76 tests: 73 passed, 0 failures/errors, 3 pre-existing SQLite-backed tests skipped** because that host path lacks the shipped Android SQLite native library. The separate [Phase 2 SQL host checks](validation-phase7/phase2-storage.txt) pass all three additive-schema/cache/audit checks; these do not replace device database testing. Lint reports **0 errors and 43 warnings**, including existing store-policy/ABI/resource warnings; this is a sideload test build, not a claim of Play Store compliance.

The [native sandbox harness](validation-phase7/quickjs-host.txt) passes loop timeout under five seconds, heap exhaustion, serialization timeout, uncatchable host-call quota, constructor/eval/import restrictions, JSON forwarding and absence of ambient network/platform APIs.

Host pack tests cover owner signatures (including a Python/OpenSSL-signed public-only fixture), tampering, untrusted keys, duplicate/traversal/native payloads, expansion bounds, approval-token mutation/reuse, no activation before approval, update/rollback, corrupt-state safe startup, interrupted-run safe mode, namespaced persistence and full-library rejection. UI tests cover same-app navigation and separate builder/review controls.

Both APK variants are checked for **ELF32 ARM libraries only**: `libmrgreen.so`, `libmrgreen_quickjs.so`, `libsqlite3x.so`, `libc++_shared.so`. The app remains `im.mrgreen`, version `0.7.0-plugins` (code 6), Android target API 30/min API 26, with no new Android permissions and a non-exported plugin screen. The debug native compiler audit passes ARMv7/-O3; release preserves the existing RelWithDebInfo/-O2 configuration. The release artifact is unsigned; the development APK is the installable test artifact.

The source/APK audit checks known credential patterns and configured API values, forbids bundled private-key/keystore/user-database/model assets, and checks backup/export/permission policies. No match was found. This is a **heuristic static audit**, not proof that every possible secret or engine vulnerability is absent. Script contents, output, memory values, prompts and keys are not written to plugin audit logs; only fixed API/run metadata is recorded. The public test fixture includes a public key and signature, never its disposable private key.

Activation/rollback use app-private files, recovery journals and same-filesystem renames. They are not a claim of full power-loss durability or transactional filesystem semantics. Synchronous host callbacks cannot be preempted by QuickJS; the wired APIs are bounded and contain no network/AI/blocking approval callback for scripts. Android/Redmi performance, native execution, process-death recovery and the complete A25/A26 matrix remain the owner's device checks.
