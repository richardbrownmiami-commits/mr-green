# Mr Green 0.10.1: audit and test report

**Later personal-use exception:** the API 30–33 block described below remains the normal-build default. An owner-requested build flag plus separate local risk acceptance now allows unverified accessibility reads; see the [phone guide](user-guide.md#screen-and-app-recipes). This does not fix protected-window detection or add device-test evidence. The original audit results below describe the audited baseline, not validation of this override.

**0.10.1-audited-preview, versionCode 10.** Changes start from public main `f5530d75fce9becf2a51aa6d35eb85fd5c651afd`. The app remains one **`im.mrgreen`** application for **Redmi 9 M2006C3MII, Android 11/API 30, 32-bit `armeabi-v7a` only**. SkillBot was not touched.

**The build, host regressions and package checks pass. No Redmi or other Android device test was run.** Reviewing the repository and passing these checks does not prove that every roadmap feature is complete or that no further bugs remain.

## Important: screen reading is blocked on Android 11

The audit found an unsafe assumption: a successful display screenshot does not prove that a separately read accessibility tree belongs to a non-secure window. Android's window-targeted screenshot API and explicit secure-window error were added in API 34; see the [official API reference](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService).

**Screen reading now fails closed on API 30–33, including the target Redmi.** The setup screen explains this rather than asking for unusable access. API 34+ code uses window-targeted capture, checks window/package identity and transition events, and discards unsafe results. That newer path is not device-tested and is not an atomic pixel/tree guarantee. A safe Android 11 implementation is still a feature gap. No cloud-screen workaround or privacy bypass was added.

## Fixed and connected in code

- **Action results and cancellation:** typed outcomes separate verified results, external handoffs, failures, confirmation pauses and cancellation. Clock/media handoffs no longer become learned successes or routine failures. Compound steps are serialized across chat, reviewed plans and plugin runtime instances. A chat/service cancellation stops remaining registered steps without claiming earlier effects were undone.
- **Chat and model defaults:** rejected submissions preserve the draft. Model controls and engine defaults agree on 512 context / 2 threads. Negative native token counts fail closed instead of making a prompt appear smaller. Native unload signals abort before waiting for the generation mutex.
- **Runtime and incidents:** connection setup is idempotent. The logger owns incident occurrence counting; the learning notification no longer counts the same incident again. Thrown action failures also avoid a second log/count.
- **Diagnostics and permissions:** new diagnostics always stay app-private, even with broad storage access. Accessibility and notification access checks match exact Android components instead of substring/package approximations. Earlier shared diagnostic files are not automatically deleted.
- **Screen-extracted alarms:** confirmed AM/PM times retain their meaning, including noon/midnight. Invalid input is rejected rather than silently becoming midnight. The Android 11 screen gate above still prevents capture there.
- **Companions:** invocation re-inspects the currently signer-approved service and requires the tool to be advertised as well as reviewed. IPC has bounded waits/replies, rejects oversized UTF-8 results rather than silently truncating them, and refuses blocking exchanges on the main thread. Exact-argument confirmation remains.
- **Knowledge and MCP:** import cancellation is no longer reset after extraction. New imports neither retain source URI paths nor request persistent grants; existing database paths are scrubbed without deleting imported chunks. MCP replies must correlate to the request and contain a successful result. Authorization now carries immutable per-call public arguments and the displayed endpoint rather than shared review flags.
- **Public HTTPS:** web/search/MCP use connection-time public-only DNS validation with normal TLS verification, no redirects/proxies/automatic retries, response caps and a total deadline. Tests cover rebinding answers and mixed public/private address lists. This is not a live traffic or exhaustive network penetration test.
- **Doctor and storage:** applied proposals now expose confirmed Undo. Manual duplicate cleanup shares the automatic ten-row bound. **The original automatic cleanup already preserved unique facts; no existing fact-loss defect was established.** Production-SQL tests cover this bound and metadata preservation. Recursive delete triggers now clean old FTS terms during `REPLACE`, avoiding stale search entries.

Permission checks, important-action confirmations, plugin validation and private-source restrictions remain. **No blanket local reply filter was added.** Local conversation memory remains a bounded lexical-vector index, not neural embeddings; prompts contain the current question plus at most two relevant budgeted snippets. The native identical-prefix cache remains in place.

## What passed on the build computer

| Check | Result |
|---|---|
| Final Gradle gate | **PASS:** `:app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease` |
| JVM/Robolectric | **187 discovered: 184 passed, 0 failures/errors, 3 existing skips.** No skips added. |
| Lint | **0 errors, 258 warnings.** Includes existing platform, UI/localization/accessibility and synchronous-journal warnings; not reported as warning-free. |
| Production SQL | **9 passed:** 3 migration/cache/audit checks and 6 integrated checks for snapshots, vectors, duplicate metadata, incident counts, FTS replacements and legacy URI cleanup. Host SQLite/shadows are not Android/requery evidence. |
| Python extension guards | **3 passed.** Source guardrails, not runtime Binder proof. |
| Native QuickJS host harness | Passed result/host contract, ambient API/dynamic-code restrictions, heap, call quota, loop and serialization deadline checks. Not ARMv7 execution. |
| Real GGUF / production JNI | **PASS on the host:** SmolLM2-135M-Instruct Q4_K_M load, streaming and progress, token cancellation, prompt-stage cancellation, request after cancellation and unload. A separate toy GGUF without an embedded template loaded but correctly failed chat rather than using a fabricated template. |
| Optional companion sample | **PASS:** `:companion:assembleDebug`. No cross-process phone invocation was performed. |
| Native build/package | **PASS:** 83 translation units use ARMv7 Android and effective `-O3`; all four native libraries are ELF32 ARM under `lib/armeabi-v7a/`. Both main APKs pass the ABI check. |
| Privacy/identity package scan | Passed supported credential-pattern/environment-value scans, expected permission set, no packaged private/model files and retained app/platform identity. Heuristic scans do not prove runtime traffic behavior. |
| Signing | Debug APK signature verifies. Release APK is deliberately **unsigned**, awaiting the owner's key. |

The three existing skipped tests are the bundled-requery `GreenStoreTest` migration/cache/audit tests that cannot load the ARM-only SQLite library on this host. Their host-SQL counterparts run, but actual Android SQLite integration remains unverified. Two new test-fixture failures were corrected before the final pass; tests were not removed or newly skipped.

Evidence: [build gate](validation-audit/build-gate.txt), [test/lint summary](validation-audit/test-summary.txt), [SQL](validation-audit/sql-checks.txt), [QuickJS/Python](validation-audit/quickjs-and-extension-tests.txt), [GGUF host smoke](validation-audit/host-gguf-smoke.txt), [companion build](validation-audit/companion-build.txt), [ABI](validation-audit/apk-abi.txt), [native build](validation-audit/native-build-audit.txt), [privacy package audit](validation-audit/privacy-package-audit.txt), [APK identity](validation-audit/apk-identity.txt) and [debug signature](validation-audit/debug-signature.txt).

## Builds and installation safety

The release assets contain the same integrated application in two forms:

- `mr-green-0.10.1-audited-preview-debug.apk`: installable, development-signed test build.
- `mr-green-0.10.1-audited-preview-release-unsigned.apk`: **not installable until signed**; intended for the owner's release-signing process.

Both retain minSdk 26, targetSdk 30 and ARMv7-only libraries. Compile SDK/build-tools 36, NDK 27.2.12479018, JDK 17 and CMake 3.22.1 are build tools, not a change to the target phone.

[SHA-256 checksums](validation-audit/apk-sha256.txt):

```text
15354465f0255efd4fc57265ff91a78c94d64df493694f96c999414f695d8ab8  mr-green-0.10.1-audited-preview-debug.apk
20e025046965e9ace5357eb9f3eef32feb8e390057a3991ef3595ed34745b006  mr-green-0.10.1-audited-preview-release-unsigned.apk
```

**A data-preserving update is not verified.** The development key may differ from the owner's installed app. If Android reports a signature mismatch, stop: **do not uninstall or clear data**. Build/sign with the original key instead. No phone app/data operation was performed.

## Remaining gaps

Clock save/database verification, generated `.m3u` playlists, Android 11 screen assistance and optional cloud-screen vision are not implemented as complete verified features. MCP is a bounded JSON POST client, not a full session/OAuth/Streamable HTTP/SSE implementation; strict servers may not interoperate. Legacy OS-held URI grants from older imports are not automatically revoked.

Redmi/MIUI behavior, real Android Keystore/requery migration, live Groq/provider requests, actual owner-selected GGUF compatibility/performance, third-party recipes, Binder IPC and native replacement-patch loading remain untested. The host SmolLM2 test is **not** certification of Bonsai, Qwen or an uncensored-tuned GGUF. No uncensored-model compatibility or phone-speed result is claimed. Permission/confirmation and plugin/privacy gates were not weakened for model testing.

## A1–A35: phone acceptance is still outstanding

Every phone result is **NOT RUN**; some are also blocked by known missing features. Host tests above must not be relabeled as phone passes.

| Test | What to check / current caveat |
|---|---|
| A1 | Time, arithmetic and date offset run correctly and meet timing; host routing/math is not phone timing. |
| A2 | Clock saves the requested alarm/day and DB state agrees. Current setup is an unverified external handoff. |
| A3 | Note file and DB row read back; `check notes` lists it on Android. |
| A4 | Alarm label and linked note agree with the actual saved Clock entry. |
| A5 | Compound app/time/flashlight actions stay ordered; cancellation prevents remaining steps. |
| A6 | Media clarification and player handoff; verify the actual track. Generated playlists are missing. |
| A7 | Opted-in missed-call/notification events, proactive timing, quiet hours and allowlist. |
| A8 | Review typed plans; verify external effects rather than intent returns. |
| A9 | Verified successful episodes propose routines; handoffs/confirmation pauses do not count as verified success or failure. |
| A10 | Live web answer has a real source; recipes/cache work against current providers. |
| A11 | Representative TXT/PDF/EPUB imports, citations, cancellation and indexing RAM. |
| A12 | Harmless reviewed WhatsApp/Meta AI/Gemini recipes, real recipient/prompt and mismatch stops. |
| A13 | Five-day MIUI/restart survival, RAM caps and crashes. No longevity result exists. |
| A14 | One deduplicated Doctor incident occurrence per failure, with metadata-only reporting. |
| A15 | Misunderstood phrase → working equivalent → validated typed diff → explicit approval/rejection. |
| A16 | Reject identity/permission/code/registry edits, arbitrary SQL, unknown/oversized operations. |
| A17 | Reject repairs that break the golden plans; real native-SQL replay remains pending. |
| A18 | Confirmed Undo restores recorded rows; staged DB snapshot restore works after a full restart. |
| A19 | Idle/battery gating and cancellation latency. Active SQL is not a hard real-time interrupt. |
| A20 | Useful Java/native interruption diagnostics; no guessed repair or invented native backtrace. |
| A21 | Bounded exact-duplicate cleanup, FTS replacement/rebuild, log pruning and snapshots. |
| A22 | Owner-configured Groq online/offline/429 behavior and metadata-only audit. No live key was used here. |
| A23 | Private-source no-send and no credentials in runtime logs/exports/network traffic. Static scans are not traffic proof. |
| A24 | At least 90% of 200 golden phrases route without AI; phone priorities, negatives and rollback. |
| A25 | Reviewed signed flow pack permissions, cap, execution and rollback. |
| A26 | ARMv7 QuickJS execution, deadlines, heap/quota and safe mode. Host harness is separate. |
| A27 | Real Binder signer rejection/pinning, advertised/reviewed tool and exact confirmed arguments. |
| A28 | Correctly signed ARMv7 replacement patch, Android linker/JNI behavior, bad-signature rejection and fallback. |
| A29 | Bubble/panel UX. On API 30, a tap must show unavailable/denied without reading the tree. |
| A30 | Screen summary/extraction/highlights are **blocked on the target Android 11** pending a safe implementation. |
| A31 | API 30 capture fails closed; independently validate API 34+ secure/password/blocklist and window-transition behavior. |
| A32 | No screen network sends. Optional cloud-screen/vision is not implemented. |
| A33 | FPS, cold start, UI/overlay overhead and peak RAM; none is inferred from Robolectric. |
| A34 | Live permission checklist and Android/MIUI deep-link behavior. |
| A35 | Main/overlay themes and accents; separately inspect system/third-party surfaces. |

See the [phone guide](user-guide.md), [implementation limits](implementation-status.md), and [VERIFY register](verify-status.md).
