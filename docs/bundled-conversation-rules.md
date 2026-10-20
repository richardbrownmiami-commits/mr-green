# Everyday replies in Rive rules

Mr Green now bundles 115 literal Q&A rules: the previous direct greetings, identity and app-help defaults, plus everyday exchanges such as `thanks`, `that's good`, `sure`, `okay`, `nice to meet you`, `sorry`, and `see you later`. The rules are active immediately, offline, without a GGUF. Acknowledgements never approve, start or confirm an action.

## See the questions and answers

Open **Rive rules & learning → View built-in Q&A rules**. The built-in count is shown alongside imported and learned counts. **Export built-in Q&A…** saves the built-in `.rive` text with source attribution. **Edit imported rule text** still edits only your own imported rules, and **Export rules…** still exports only your imported/learned rules. No import is needed to activate built-ins.

The public source is [`app/src/main/assets/rive/builtin-conversation.rive`](../app/src/main/assets/rive/builtin-conversation.rive). Regenerate it from app-owned defaults with `python3 scripts/generate-conversation-rules.py`. A small curated set of greeting exchanges is adapted from [ChatterBot Corpus](https://github.com/gunthercox/chatterbot-corpus/blob/eec45b284424c9784a5baab78368b1a9ff3b656f/chatterbot_corpus/data/english/greetings.yml); its BSD-3-Clause notice is preserved in the repository and APK. Other acknowledgements and app-specific answers are original app text, not a bulk corpus import.

## Auto-learning test

This personal test build defaults **Auto-learn from local answers** on when there is no saved choice. An existing saved on **or off** choice is preserved. Check the switch before testing; if you previously turned it off, turn it on and confirm. You can turn it off again at any time.

To test: load a compatible local GGUF, ask a new non-personal question that is not a built-in or saved rule, and wait for the complete answer. Look for **saved unverified Rive reply**, then open **Review learned rules**. Ask the same question again: it should use the saved local reply rather than generate again. Cancelled, token-budget-exhausted, failed or memory-dependent answers are not learned. Direct replies, live readings, built-in replies and already-saved replies do not create duplicate learned rows. Learning saves unverified answer text, not model weights; inspect and delete incorrect answers.

## What stays protected

Built-ins are a read-only app layer, not seeded into or merged onto the phone's `rules.rive`. Existing imported/learned files load without rewriting their bytes, and chats and identity profiles have no schema or data migration. Imported/learned normalized equivalents override built-ins **inside Rive lookup**. Chat's existing routing priority remains: registered actions, validated editable identity/direct help, then Rive/memory/local GGUF. Mirrored default identity text in the asset is not a replacement for a user's edited identity profile.

Battery, clock, storage, RAM/system status, model status and last-action/job status are **not** canned Rive rules. Their existing Kotlin/Android routes remain in front of Rive. For example, `that's good` and `sure` are ordinary conversational acknowledgements, not evidence that an earlier task completed. Chat/model inference stays on the phone with no cloud fallback.

## APK and device limits

The build retains application ID `im.mrgreen`, minSdk 26, targetSdk 30 and **armeabi-v7a only**, intended for the Redmi 9 M2006C3MII running Android 11/API 30. No physical Redmi test is claimed. Development-signed APKs are not guaranteed to match the currently installed app's signing key; a mismatch requires an APK signed with that original key for a data-preserving update. Do not uninstall or clear app data to work around a mismatch.

Validation results are recorded after the build below.

- `:app:testDebugUnitTest`: 273 discovered, **270 passed**, 3 existing `GreenStoreTest` skips (Android requery native runtime unavailable on the host JVM). All 11 new bundled-Q&A tests passed, including preference preservation, legacy file byte preservation, export portability at the full 500 private-rule limit, and contraction guards.
- `:app:lintDebug` and `:app:assembleDebug`: passed with JDK 17. APK version `0.10.6-rive-qa-preview`, version code 15.
- APK ABI, inference compile/ELF and integrated phase-7 audits: passed. Four ELF32 ARM libraries; only `armeabi-v7a`, minSdk 26 and targetSdk 30; no private/model assets detected by the static audit. Packaged Q&A and license bytes match the source assets.
- Storage, integrated-storage, conversation-memory SQL, conversation wiring and safe note-search host scripts: **29 checks passed**. The rule generator is reproducible.
- APK signature verifies as **Android Debug**, certificate SHA-256 `bbe46cf30cb5f04ab04b3c7fa0587859d4f822c8d2b42773458a8b39fbeebc4f`. This is a development key, not the owner's original update key. Installed-device signature compatibility is unverified.
- No physical Redmi/MIUI installation, benchmark, native-model execution, or end-to-end learning with a real GGUF on the phone was performed. Learned answers remain unverified and may depend on editable profile context; this update is not a guarantee of context-independent learning quality.
