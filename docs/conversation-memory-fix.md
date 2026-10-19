# Mr Green 0.10.3: conversation recall fix

Mr Green was saving chat, but saving it did not mean the model received it. This update repairs the path from saved messages to a small local prompt. It keeps the earlier Phase 3 routing fix and remains one `im.mrgreen` app for **Redmi 9 M2006C3MII, Android 11/API 30, armeabi-v7a only**.

The installed phone version is unknown. These defects were found in public main after `5f45bb1`; they are not proof of exactly what happened in an uninspected 0.10.1 personal APK.

## Why recall could appear absent

**Cloud chat was considered before local memory.** Cloud requests intentionally contain only the current typed question, not old private chat. A successful cloud reply therefore skipped the local recall path entirely. A standalone saved Rive reply could also answer without considering history.

**Local retrieval required shared words.** “Tell me more” or “What did I just say?” often shares no useful words with the earlier statement. The old index was lexical, not a neural semantic embedding model, and there was no recent-exchange fallback.

**Useful notes could disappear at context fitting.** The previous code removed whole notes when they exceeded the small prompt budget. At context 512, a retrieved long message could be removed instead of shortened.

**Index freshness was uneven.** Automatic sync occurred on the local-generation path, not after every reply type or at startup. It indexed the oldest missing rows first, so a large backlog could delay recent messages. Hindi combining marks were also split incorrectly. Deletion-only cleanup could leave cached hits stale.

The [before-fix host wiring check](validation-memory/before-wiring.txt) fails four of six contracts against the unchanged controller. This is source-level evidence, not a phone trace or an inference-quality benchmark.

## What changed

Conversation context is now selected before standalone Rive/cloud replies. Recognized recall/follow-up wording or a relevant historical match makes the turn **local-only**. A local GGUF must be available; otherwise the app explains that requirement rather than quietly answering without history. Ordinary unrelated standalone chat can still use cloud. Memory-dependent answers are not saved as context-free learned Rive rules.

The prompt still contains only the current question and **at most two short historical fragments**, not the transcript. Follow-ups can use the latest exchange without shared keywords. Notes have a combined 700-character retrieval cap and are shortened further using the loaded model's tokenizer. The current question is never truncated. Context that cannot fit can still be dropped, and an oversized question is rejected.

Startup and all finalized reply paths now synchronize missing index rows. Each batch splits capacity between recent messages and older backlog. The existing private chat database is retained; there is no database reset or schema-version migration. The Unicode feature change rebuilds only the disposable vector index. User input is a finalized user message even if generation later fails; failed/cancelled assistant fragments are no longer saved as successful memories. Previously saved partial answers cannot be identified retroactively.

The memory screen explains local routing, shows enabled state and indexed count, and retains disable/clear controls. Local completion status and metadata-only `MEMORY` diagnostics show selected and fitted snippet counts. No question or recalled text was added to those logs. Ephemeral screen-context handling is unchanged.

## Check it safely on the Redmi

1. Verify the installed version is **0.10.3-memory-preview**, version code **12**. Use a matching signing key for an in-place update. **Do not uninstall or clear app data to bypass a signature mismatch.**
2. Load the existing compatible GGUF with context **512**, two threads. Leave conversation memory enabled. Turn cloud off for the first test.
3. Send a harmless statement such as `My bicycle is green and I keep it beside the garage.` Let the reply finish. Then ask `Where do I keep my bicycle?` Check that the actual answer uses the statement and status says conversation memory was used.
4. After another normal answer, ask `Tell me more` and `What did I just say?`. Check the answers against the real previous exchange, not merely whether generation happened.
5. Force-stop and reopen the app, reload the model if necessary, then ask about the bicycle again. Verify chat and index counts survived. This tests actual Android persistence, not just native prefix caching.
6. Enable cloud and repeat a recall question. It should stay **Local**. An unrelated question with no recalled match can still use cloud. If no local model is available, recall should explain the local-model requirement; history must not be sent to cloud.
7. Disable memory and verify no snippets are used. Re-enable it, clear only vectors, then retry: chat should remain and bounded indexing should rebuild. Large old histories may need repeated indexing batches.
8. Recheck `What is the time?`, `Calculate 2 plus 3?`, and `set timer`. The Phase 3 deterministic answer/clarification should still precede chat and avoid model generation.

If recall still fails, report the app version, the harmless exact test pair, Local/Cloud mode, memory enabled/indexed state, and the `MEMORY` snippet-count diagnostic. Avoid private chat or screen contents.

## What this still does not promise

This is bounded lexical retrieval with lightweight wording heuristics, not universal conversation understanding or model training. Older paraphrases, uncommon follow-up phrasing, facts beyond the first 600 characters, conflicting statements, unindexed backlog and small-model hallucinations remain possible. Historical assistant text is not verified fact. Prompt instructions to treat history as untrusted are a safeguard, not a guarantee against prompt injection. Native KV caching is not durable memory.

No Redmi installation, Android/requery native database execution, phone answer-quality pass, latency/RAM measurement or MIUI endurance result is claimed. The personal screen-reading build option and its separate on-device consent remain unchanged; this memory fix does not resolve protected-window detection.

## Signing and release choices

The preview contains a development-signed personal debug APK and a personal unsigned release APK. Both use `-PpersonalScreenReading=true`, matching the requested personal-build option; device-local screen-reading consent is still separate. The unsigned APK must be signed before installation.

The new debug certificate SHA-256 is `58424b8262dabf56b0bce79248acc752e3e8c25c379edc07c1a94fda0a11ba40`. The published 0.10.2 debug certificate is `7e8934b84586e6b6b5f2bf2c23ea5aaab60ccb4477ff35b0500b6927230c7e3a`; they **differ**. See the [new](validation-memory/debug-signature.txt) and [previous](validation-memory/previous-signature.txt) certificate checks. The installed 0.10.1/personal signer's identity remains unknown. This environment does not have the original private signing key, and no private key is published.

For an existing installation, use the original signing key with the unsigned output to preserve app-private chat, models and settings. The debug APK is a test artifact, not a guaranteed in-place update. Do not delete an existing installation to make it install.

## Verified on the build host

The final personal-build gate succeeded with JDK 17:

```sh
./gradlew -PpersonalScreenReading=true :app:assembleDebug :app:assembleRelease :app:testDebugUnitTest :app:lintDebug
```

| Check | Final result |
|---|---|
| Full JVM/Robolectric suite | **211 passed, 3 skipped**, zero failures/errors; [summary](validation-memory/test-summary.txt) |
| New prompt/routing tests | **8 passed**: local recall, standalone fallback, disabled memory, small-context note shortening, deduplication/order, oversized questions and tokenizer errors; [results](validation-memory/TEST-im.mrgreen.core.ConversationPromptTest.xml) |
| Vector feature tests | **5 passed**, including Hindi marks/stop words; [results](validation-memory/TEST-im.mrgreen.memory.VectorFeaturesTest.xml) |
| Memory production-SQL regressions | **6 passed**: recent/backlog balance, current-row exclusion/latest exchange, deletion cleanup, bounded/idempotent sync, old FTS lookup and host database reopen; [output](validation-memory/memory-sql.txt) |
| Live-controller source contracts | **6 passed**; these are static wiring guards, not an instrumented phone test; [output](validation-memory/after-wiring.txt) |
| Existing storage checks | **9 passed** across [gateway storage](validation-memory/phase2-storage.txt) and [integrated storage](validation-memory/integrated-storage.txt) |
| Phase 3 routing regressions | All **200 starter phrases** still choose their expected tool; [results](validation-memory/TEST-im.mrgreen.actions.ActionRoutingTest.xml) |
| Extensions and QuickJS | **3 extension tests passed**; existing host sandbox regression passed; [extensions](validation-memory/extensions.txt), [QuickJS](validation-memory/quickjs-host.txt) |
| Android lint | **0 errors/fatals, 266 warnings**; passed, not warning-free; [summary](validation-memory/lint-summary.txt) |
| APK identity and ABI | `im.mrgreen`, version code 12, min 26/target 30, four **armeabi-v7a-only** native libraries per APK; [identity](validation-memory/apk-identity.txt), [ABI](validation-memory/apk-abi.txt) |
| Debug native compile/package audit | All **83 debug** native compilation commands use ARMv7 Android and effective `-O3`; JNI libraries are ELF32 ARM; [output](validation-memory/native-build.txt) |
| Unsigned release native audit | Existing `RelWithDebInfo` configuration uses **83 ARMv7 / `-O2` commands**, not `-O3`; its four libraries are also ELF32 ARM. It does not pass the debug-specific O3 gate. Native settings were not changed by this patch; [output](validation-memory/release-native.txt) |
| Credential/private-asset/permission audit | Existing heuristic source/package scan passed, no permission expansion; [output](validation-memory/package-privacy.txt) |

The full [build gate](validation-memory/build-gate.txt) and [APK checksums](validation-memory/apk-sha256.txt) are retained. The skipped Android/requery tests are not counted as passes. Host SQLite executes SQL but does not execute Android's packaged native SQLite library. These checks verify the tested routing, prompt and packaging contracts—not that a particular GGUF will answer correctly, remember every fact, or run faster on the Redmi.
