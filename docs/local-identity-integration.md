# Local identity and offline conversation

Version **0.10.4-local-identity-preview** integrates editable on-device identity/help data. This is **local data and prompt integration, not model training**: GGUF files and weights are unchanged. Understanding new paraphrases still depends on the chosen local model and needs testing on the Redmi.

## What changed

Open **Overview → Local identity & help data**. The JSON profile contains a display name, persona, stable Q&A, capability/help labels and supplemental notes. Save validates the schema before replacing the app-private file atomically. Import and export use Android's document picker; invalid or oversized content must not replace the existing profile. Export contains this profile only, not chat history, keys or memory. Review the destination chosen in the picker; a document provider can be remote even though the app does not upload the profile to an AI service.

The starter profile adapts the stable identity/help material from `docs/identity-draft/dataset.json` at commit `af0e699`. The original review draft remains untouched as historical evidence. It is not imported wholesale: its synthetic battery values, alarm results, status examples and other evidence-dependent fixtures are not live answers. The runtime schema accepts only code-allowlisted stable identity/help question keys. Other questions cannot be added as direct-reply triggers through this profile. A profile edit grants no permission, registers no tool and executes no code.

Mr Green is software, not conscious or alive. Phone/body, battery/energy, camera/eyes and microphone/ears are metaphors. Hardware or a CAMERA permission for flashlight is not camera understanding. Sensor use requires an implemented, explicitly invoked, permissioned and consented feature. Core safeguards are code-owned, not editable. Edited direct answers are clearly quoted as configured data, not observations or action evidence.

## Conversation order

1. Validated deterministic actions, calculations and real app data, including incomplete-action clarification and approved routine reuse.
2. Exact normalized stable identity/help matches, then existing local Rive rules when the turn does not depend on recalled conversation.
3. One on-device GGUF helper for unmatched conversation. If it is missing, cannot load, fails or is cancelled, there is **no cloud fallback**.

Battery-energy and lifeline questions have explicit deterministic aliases to `device.battery`. The response uses the Android battery reading, not the draft's synthetic 18% or 42%. Missing or invalid level/scale readings are unavailable rather than guessed. Unsupported camera/audio understanding remains unavailable; persona text does not add sensors.

The model prompt contains an immutable concise identity/safety instruction and optional bounded, relevant local profile data. It never includes the whole Q&A fixture dataset. Optional profile data is dropped before shortening memory when the context is tight; immutable safeguards and the current question are never silently truncated. Existing bounded memory recall stays local, with at most two snippets, quoted as data rather than instructions or verified facts.

## No cloud model exception

Live chat, remembered context, screen/document Q&A, planner proposals, Doctor draft requests and plugin drafting use the local native queue only. Legacy gateway model discovery and chat APIs fail closed regardless of persisted provider settings, saved credentials, connectivity, category or cache. The controller has no cloud request call or Cloud mode branch. External Meta AI/Gemini prompt/result recipes are blocked before opening an app or reading accessibility data. Confirmed WhatsApp and settings actions remain separate non-model tools. Generic injectable planner-gateway dispatch was removed; approved local routine reuse and explicitly reviewed local plan proposals remain.

There is **no distinct cloud maintenance route**. Local Doctor/maintenance behavior is not a reason to send ordinary questions, chats, private notes or screen content to a provider. Historical cloud database rows and encrypted settings are not erased or used to answer new questions.

Explicit public web search/fetch and reviewed public-argument MCP tools are separate internet features, not AI fallback. They retain their own opt-in, destination, argument and privacy gates. Keeping Android's INTERNET permission for these tools does not enable cloud conversation. Offline conversation should therefore be distinguished from a promise that the entire app never makes any network connection.

Registered tool schemas, per-step validation, approvals, cancellation, local memory, Rive import/learning rules, ARMv7-only packaging, API30 target and the normal Android 11 screen-reading block are retained. A generated proposal is still not approval or proof that an action ran.

## Verification and installation

Host gates include all Android unit tests, debug/release builds, lint, storage/memory SQL checks, extension tests, QuickJS host limits, source privacy contracts and ARMv7 native/package audits. The build's validation record reports actual outcomes; source or host checks alone are not Redmi evidence.

The [final validation record](validation-local-identity/README.md) includes test counts, skipped host database tests, nonfatal lint warnings, signing fingerprints and the delivered APK checksum.

Not established here: installation on the owner's Redmi 9, real GGUF paraphrase/answer quality, battery/sensor behavior on that phone, permission flows, MIUI survival, latency, RAM use or data-preserving upgrade. The delivered normal build does not enable the separate personal-use screen-reading override.

A newly created development signing key may differ from the installed APK's signer. Android will reject a mismatched update. **Do not uninstall or clear app data to work around it.** A data-preserving update needs an APK signed with the original key. No owner production key or successful Redmi update is claimed.
