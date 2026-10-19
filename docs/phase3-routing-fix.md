# Phase 3: actions should not generate model tokens

Version **0.10.2-routing-preview**, based on `53ef2ae`. This is a focused fix in the existing `im.mrgreen` app. Android 11/API 30, 32-bit ARMv7, the single engine queue, local privacy, permission checks and external confirmations are retained. SkillBot was not changed. There is no database migration or reset.

## What was wrong

The chat controller already called the integrated action runtime before saved replies, cloud chat and local GGUF generation. The defect was not a missing router call: several documented phrases did not match, and failed argument extraction used the same `null` result as an ordinary chat question. Those requests could therefore fall through to a model.

A stricter run against the unchanged router reproduced **187 correct routes out of 200 documented phrases**. The previous test accepted 90%, hiding all 13 misses. Examples included `what is the time`, `compute 10 % 3`, `open settings`, `open internal storage`, and app/date settings phrases. A new punctuation/wording test also failed at `What is the time?`. The [before-fix test evidence](validation-routing/before-ActionRoutingTest.xml) contains the exact failures.

These are host-reproduced defects. We do not have the user's exact failing phone prompts or its installed APK/signing certificate, so this does not prove that every possible cause of the reported “always” fallback has been identified.

## What changed

- Every one of the 200 starter phrases must now route to its expected tool, not merely meet an aggregate percentage.
- Missing time, math, timer and settings wording is covered. Explicit app launch and settings routes no longer collide in the documented examples.
- Phrase boundaries accept punctuation without matching inside longer words. Curly apostrophes and terminal question marks are handled in command syntax. Numeric arithmetic without an introductory verb is recognized; `percent of` and `% of` are accepted.
- Command cleanup does not strip punctuation or words from a note's payload. Note prefixes such as `note this:` are removed correctly; polite app-launch suffixes are not treated as part of the app name.
- A recognized action with missing or unsupported arguments returns a local clarification instead of asking a model to invent an answer. An unrecognized segment rejects the whole compound request before any partial action runs. Negated commands do not execute.
- The live controller uses a small tested deterministic-first gate. Only a `null` action reply enters the existing saved-reply/cloud/model path. Successful actions, permission denials, handoffs, failures and clarifications are terminal replies. The extracted fallback logic is otherwise unchanged.
- Handled requests show **Local action · no model generation · review any handoff**. Diagnostics record only `Local action reply; model_generation=false`, not prompts, arguments or source text. Model-screen statistics may still describe the last generation, not the current action.

Ordinary questions, including `what is gravity?` and `what is 5G?`, still use the normal chat fallback. This remains bounded deterministic matching, not general natural-language understanding. Outbound messages, calls and other unregistered operations are not turned into automatic actions.

## Verified on the build host

The final gate passed with `-PpersonalScreenReading=true`:

```sh
./gradlew -PpersonalScreenReading=true :app:assembleDebug :app:assembleRelease :app:testDebugUnitTest :app:lintDebug
```

| Check | Result |
|---|---|
| Full JVM/Robolectric suite | **201 passed, 3 skipped**, zero failures/errors; [summary](validation-routing/test-summary.txt) |
| Starter routing fixture | **200/200 phrases** choose the expected tool; [test evidence](validation-routing/after-ActionRoutingTest.xml) |
| Real action-runtime dispatch plus fallback spies | Actions, missing-slot clarifications, permission/risk denials, approved aliases and ordinary-chat fallback pass; [test evidence](validation-routing/after-Phase3DispatchTest.xml) |
| Shared deterministic-first gate | All five null/non-null/error boundary tests pass; [test evidence](validation-routing/after-ReplyRouterTest.xml) |
| Android lint | Zero errors, 262 warnings; gate passed, not warning-free; [output](validation-routing/lint.txt) |
| APK identity and architecture | `im.mrgreen`, version code 11, min 26/target 30; debug and unsigned release contain only four **armeabi-v7a / ELF32 ARM** libraries |
| Native inference build audit | All 83 checked native compile commands use ARMv7 Android and effective `-O3`; [evidence](validation-routing/inference-build.txt) |
| Storage and extensions | Nine host SQL checks and three extension Python tests pass; Android-only SQLite JVM tests remain skipped |
| QuickJS and package privacy checks | Host sandbox regression and heuristic credential/private-asset/ABI scans pass |

The complete [build gate](validation-routing/build-gate.txt), [package audit](validation-routing/package-privacy.txt), and [checksums](validation-routing/apk-sha256.txt) are retained. These are host parsing, execution, build and packaging checks, not a phone installation or end-to-end Android database/native-runtime test. The fallback spies prove the tested gate does not invoke a generator for those action requests; they are not phone token measurements.

## Check it on the Redmi

The user reported Phase 1 and Phase 2 passing on their phone. Phase 3 still needs a phone re-test after a correctly signed update; host tests are not a substitute for that result.

1. Verify the app version is **0.10.2-routing-preview**. Update in place with the original signing key. **Do not uninstall or clear data if the signer differs.**
2. Turn cloud chat off and use the app's **Unload model** control. This is a stronger test than watching an old tokens/second statistic.
3. Ask `What is the time?`, `What's today's date?`, `Calculate 2 plus 3?`, `compute 10 % 3`, and `what is 17 percent of 2450`. They should answer locally without loading/generating with a GGUF model.
4. Try `set timer` and `set alarm`. They should ask locally for missing details, not generate a model answer. `don't open camera` must not launch Camera.
5. Try `make a note: buy milk and eggs`, then `check notes`. Verify the note is present once and its text is intact. This exercises the actual Android database and app-private file path, which the host JVM cannot verify with the Android-only SQLite library.
6. Try `open settings`, `open calculator`, and `set timer for 30 seconds`. Android Settings, the installed calculator, and Clock are device-mediated outcomes; verify them on the phone. Opening Clock is not proof that a timer or alarm was saved.
7. Load the existing model and try an ordinary question. It should still use normal chat. Then repeat a time/math action: a loaded model must not change deterministic routing.

If a request still generates, report the exact typed phrase, app version, whether it was compound, and the ROUTER/LLM diagnostic entries around that one turn. Do not include private note contents, credentials or screen text.

## Signing and personal screen reading

This build environment does not have the previous signing key. The new development signer **differs from the published 0.10.1 debug signer**; the installed personal APK's signer is unknown. The development APK is not a guaranteed update, and the unsigned release must be signed with the original key for a data-preserving update. No signing key is published in this public repository.

The personal-use build uses `-PpersonalScreenReading=true`; the property is not persisted as a default. Separate on-device consent and existing screen safeguards remain required. Normal builds still disable the Android 11–13 override. This router patch does not claim to fix protected-window detection or verify screen reading on the Redmi.
