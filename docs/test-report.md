# Integrated build and phone test status

**Mr Green 0.10.0-integrated-preview, versionCode 9.** One integrated `im.mrgreen` app; root roadmap modules are joined rather than separate phase APKs. Base commit: `92483cc7df3103ecb5f36630004e92ea96fe59a2`. SkillBot was not modified.

**Host build/regression checks pass. No Redmi/on-device acceptance test was run.** This report does not declare every requirement in `plan.md` complete. Clock verification, generated playlists, optional cloud vision and several device-dependent paths remain incomplete or unverified; see [implementation limits](implementation-status.md).

## Build-computer results

| Check | Result |
|---|---|
| Final Gradle gate | **PASS:** `:app:assembleDebug :app:assembleRelease :app:testDebugUnitTest :app:lintDebug` |
| JVM/Robolectric tests | **161 discovered; 158 passed, 0 failures/errors, 3 existing skips.** Includes retained gateway/Rive/plugin tests and new routing, schema, screen, learning, extension crypto/policy, vector-feature and media-clarification regressions. |
| Production SQL | **6 checks passed:** the three retained migration/audit/cache checks plus snapshot/FTS preservation, executable vector-candidate query and exact-dedup metadata preservation. Host SQLite is not Android/requery evidence. |
| QuickJS native host harness | Passed API/dynamic-code restrictions, heap/quota, loop and serialization deadline checks. This is host execution, not ARMv7 execution. |
| Optional companion sample | **PASS:** standalone `:companion:assembleDebug`; Java-only demonstration of the versioned Messenger `demo.echo` contract. Binder execution on phone remains untested. |
| Lint | **0 errors, 255 warnings.** Warnings include legacy/platform deprecations, programmatic UI localization/accessibility/style warnings and deliberate synchronous crash-journal commits. Warnings are not hidden or presented as zero. |
| Native compiler/package audit | **PASS:** 83 ARMv7 Android translation units at effective `-O3`; ELF32 ARM libraries only under `lib/armeabi-v7a/`. No arm64/GPU/OpenMP/KleidiAI backend is intended or enabled. |
| Native libraries | `libmrgreen.so`, `libmrgreen_quickjs.so`, `libsqlite3x.so`, `libc++_shared.so` |
| App/platform identity | `im.mrgreen`; minSdk 26; targetSdk 30; compile SDK/build-tools 36; NDK 27.2.12479018; JDK 17; CMake 3.22.1 |
| Development APK signing | Both delivered APKs verify with Android `apksigner`. The release variant is **development-signed**, not owner production signing. Gradle also produces an unsigned release. |
| Privacy/source/package audit | Heuristic credential-pattern, packaged-private-file and explicit-permission-set audits are retained with an integrated-scope option. These are not proof of runtime traffic behavior. |
| Redmi, real GGUF inference, live Groq, Android Keystore/SQLite migration, MIUI, native patches, Binder, FPS/RAM | **NOT RUN.** No live key was requested or used. |

Evidence is in [final validation](validation-integrated/final/): build logs, test summary, SQL checks, native flags, ABI audit, signatures and [APK SHA-256 hashes](validation-integrated/final/apk-sha256.txt). Historical phase reports remain historical, not current totals.

## A1–A35: run these on the Redmi

Every phone result below is **NOT RUN**. Host coverage is noted separately and must not be relabeled as a device pass.

| Test | What to check / current caveat |
|---|---|
| A1 | Time, `17% of 2450`, date +45 days use tools in under 1 second. Host routing/arithmetic tests pass; phone timing unknown. |
| A2 | Clock saves the requested alarm/day and DB state agrees. Current handoff is explicitly pending external verification; a Clock intent is not a saved alarm. |
| A3 | Note file and DB row read back; `check notes` lists it. Production code verifies read-back; Android filesystem/SQLite needs testing. |
| A4 | Alarm label and linked note are correct. Note/label planning exists; fully verified Clock/DB linkage is not established. |
| A5 | Calculator → time → flashlight execute in order, with progress and one final result. Grant Camera only for this test. |
| A6 | Media asks for a title, accepts a short follow-up and hands off to a player. Check actual requested playback; active audio is not track identity. Generated playlists are not included. |
| A7 | Opted-in missed-call/notification event creates a generic proactive alert within 10 seconds; check quiet hours/allowlist. |
| A8 | Review a local typed plan with alarm/notification/media steps; verify all external outcomes. Missing arguments require clarification, not guessed defaults. |
| A9 | Three equivalent verified successful episodes propose a routine; approval enables deterministic reuse; rejection remains inactive. |
| A10 | A fetched web answer has a real source and a reusable cached recipe; live providers were not exercised. |
| A11 | Import representative TXT/PDF/EPUB, inspect cited chunks and measure indexing RAM. |
| A12 | Harmless confirmed WhatsApp send and Meta AI/Gemini prompt/result; change a label and confirm a safe mismatch error. |
| A13 | Five days/restarts, MIUI restrictions reviewed, peak RAM <1.2 GB target/<2 GB hard cap, no crashes. No longevity result exists. |
| A14 | Failed registered action creates one deduplicated Doctor card promptly; inspect occurrence count and metadata-only report. |
| A15 | Failed phrase + known working equivalent → reviewed typed diff → intended-plan/golden replay → explicit approval/rejection. |
| A16 | Reject identity/permission/registry/code edits, arbitrary SQL, invalid operations, unknown keys and oversized changes. Host validator tests cover representative rejects. |
| A17 | A candidate that breaks golden plans is rejected; inspect the failure. Actual native-SQL replay remains device pending. |
| A18 | Undo restores exact recorded rows. Test staged snapshot restore at a full restart; database only, not model/preferences/files. Host snapshot SQL check passes. |
| A19 | Idle/battery gating and user-input cancellation; measure latency. An active SQLite statement is not a hard one-second interrupt. |
| A20 | Java/native interruption or permission failure leaves useful diagnostics and no guessed DB fix. Native interruption is not itself a native backtrace. |
| A21 | Automatic exact-duplicate cleanup, FTS maintenance, log pruning, snapshot and disable switch. Host dedup preserves distinct tags/source/importance. |
| A22 | Owner-configured Groq online, local offline and 429 fallback/backoff; inspect the mode badge and metadata-only send log. Synthetic host checks pass. |
| A23 | Redaction/P2 no-send, credentials absent from APK/log/export; synthetic policy tests and heuristic packaging audit are not runtime traffic proof. |
| A24 | At least 90% of the 200 golden phrasings route without AI; host fixture test passes. Test priorities, negatives, pack rollback and cap on phone. |
| A25 | Install a reviewed signed flow pack; inspect permissions/tests/size and rollback. |
| A26 | QuickJS host-only API, loop <5 seconds, heap cap and crash safe mode on ARMv7. Native host harness passes separately. |
| A27 | Wrong companion signer rejected; correct installed signer pinned; reviewed tool invokes with exact confirmed args. Host trust policy/sample build pass; real Binder is untested. |
| A28 | Reviewed signed ARMv7 patch loads after restart; wrong signature rejected; repeated interrupted operations enter fallback/rollback. Host crypto/policy simulations pass; no replacement library was loaded on a phone. |
| A29 | Bubble drag/edge snap, rotation and capture-before-panel; measure <500 ms panel latency. |
| A30 | Local summary, tap list, confirmed extract actions and correct visual highlights. |
| A31 | Secure window/password/blocklist fails closed and yields no usable snapshot. Host policy tests are not API 30 proof. |
| A32 | Local-only screen mode sends no network. **Cloud-screen/vision branch is not implemented**, so the full optional-cloud variant cannot pass yet. |
| A33 | Measure ≥55 fps, cold start <3 s, UI overhead ~80 MB and idle overlay <25 MB. None is inferred from Robolectric. |
| A34 | Live permission checklist/deep links/Test controls; verify actual Android/MIUI state changes. |
| A35 | Main app and overlay theme/accent update without restart; separately inspect system/third-party surfaces. |

## Installation safety

The signing key is a builder development key. This is **not a verified in-place update** of the owner's installed APK. If Android reports a mismatch, do not uninstall, clear data or re-sign the old app; use the original key to build an update. No phone app/data operation was performed.

See the [phone guide](user-guide.md), [VERIFY register](verify-status.md), and [detailed implementation limits](implementation-status.md). Native stack traces, real inference/performance and device acceptance remain the owner's next tests.
