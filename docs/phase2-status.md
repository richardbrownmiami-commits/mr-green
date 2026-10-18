# Phase 2: AI Gateway

Mr Green now has an optional Groq-first text gateway and the existing local llama.cpp fallback in the **same `im.mrgreen` app**. This increment is Phase 2 only. SkillBot, tools, Doctor, plugins and screen automation were not changed or added.

The owner asked to leave the optional Redmi 9 55 fps visual measurement unmeasured. It is **skipped, not passed**. No phone was attached for this work. **A22 and A23 remain pending on the actual Redmi 9**, regardless of host test results.

## What changes

Cloud starts off. In Overview, open **AI Gateway / Cloud**, paste a key in the protected settings screen, enable cloud, discover the live model list and choose fast and smart models. No model name or API key is built into the app. Model availability, JSON support and quota are ultimately determined by the selected provider/model.

With validated internet and cloud enabled, an explicit chat question goes through the cloud policy. Cloud failure, timeout, exhausted quota or a privacy restriction uses the local path. A cloud Stop or kill-switch cancellation does **not** start a local generation. Without a loaded/previously selected local GGUF, the app explains that a local fallback must be set up; it never fabricates an offline reply.

A successful explicit model load remembers its path and settings in app-private preferences. Loss of validated connectivity queues pre-warming of that model on the existing single engine queue, with memory checks and visible loading status. It does not download a model or create a second context. Explicit unload/service stop and memory trim suppress background pre-warm. Local generation still uses the original queue, cancellation, wake lock and idle-unload safety. An existing model imported by an earlier version must be selected and loaded once to establish this preference.

The chat badge shows Cloud or Local. The Cloud screen displays model choices, reported quota/backoff, category-local switches and timestamped metadata audit records.

## What stays private

Only the **current explicitly submitted chat question** and a fixed technical system instruction are cloud-eligible. Previous chat history and the editable owner identity are not silently reclassified and uploaded. Local replies can continue to use local chat history.

Every cloud request goes through typed privacy/provenance checks and redaction. P2 or sensitive-source data is blocked, not downgraded to P1. Each personal-data category can be kept local. Typed secret patterns are handled conservatively. Regex cannot infer every sensitive fact in arbitrary prose: provenance is required for future contacts, notifications, call logs and screen integrations, and those integrations are not connected by this phase. Contact/notification redaction hooks are tested with synthetic inputs, not a claim that the app reads contacts.

The key is encrypted using Android Keystore-backed AES-GCM. The secret field is blank when reopened, excluded from saved view state/autofill and cleared after use; the settings window disallows screenshots. The key is never written to SQLite audit, diagnostic logs or exported reports. Android backup remains disabled. HTTPS redirects are not followed with credentials. Changing the base URL deliberately changes the destination, so use only a provider you trust. Encrypted credentials are bound to the HTTPS origin: changing servers will not forward the old server's saved key. Paste a key for the newly selected server.

Audit rows contain category, time, provider, class and serialized request size—no prompt, reply or credential. A row is written **before** each attempted send, including discovery and JSON retries; it means a send was attempted, not that the provider acknowledged it. Audit failure blocks sending. Retention is bounded. The short-lived private cache uses digest keys and is limited to technical P0 results; personal chat is not cached by this mechanism.

## Data and installation safety

The database remains `mr-green.db`. Version 1 to 2 adds a cache table/index without dropping existing tables, memories, messages or FTS indexes. Imported GGUF files are untouched. A source Git bundle was saved before editing. No phone files, installed app or app data were accessed, deleted, reset or replaced.

Keep the original signing key for a data-preserving update. This sandbox's development signature differs from the previous recorded APK certificate (`7bc216c1…` versus `2789187d…`). It cannot update an app installed with that previous key. **Do not uninstall or clear data to work around a signature mismatch.** The release build stays unsigned until the owner's signing process is available; a debug APK is a build artifact, not proof of safe in-place update compatibility.

## Redmi checklist: not yet performed

1. Install an update signed with the same key as the existing app. Confirm existing history, identity and imported models remain.
2. With internet, configured key and a selected model, send a harmless synthetic question. Confirm a streamed answer, Cloud badge, metadata audit and `Provider=groq` diagnostic.
3. Select/load a local GGUF once. Enable airplane mode. Confirm Local badge, pre-warm/loading status and a real local answer without a crash.
4. Exercise a controlled 429 response. Confirm Retry-After backoff, local answer and provider-reported quota/circuit status; further user requests must not immediately hammer the same paused model.
5. Exercise network loss, timeout, 5xx, invalid JSON twice and Stop. Confirm a clean fallback with no mixed/duplicate answer; Stop must not fall back.
6. Use **synthetic** phone numbers, emails, OTPs, card-like numbers, contact names and notification bodies. Confirm redaction/blocking, zero network sends for P2/prohibited categories, and an audit row for every permitted attempt. No real P2 test data should leave the phone.
7. Inspect the APK, logs and copied diagnostics for the configured key without publishing those files. Confirm no key is present. Turn the cloud switch off and verify no further cloud requests occur.

The optional 55 fps visual measurement is intentionally not included in this checklist.

## Host evidence

Host build, unit-test, SQL-schema and APK audit results are recorded in `docs/validation-phase2/` and summarized in `docs/test-report.md`. Python SQL checks exercise the production schema statements using host SQLite; they do not prove Android's packaged requery runtime or on-phone migration. ARM/requery runtime tests are explicitly skipped on the Linux JVM. No live Groq key/request or Redmi result is represented by fake-transport tests.

## Protocol references

The implementation follows Groq's [Chat Completions/SSE and JSON API](https://console.groq.com/docs/api-reference), [runtime model discovery](https://console.groq.com/docs/models), [rate-limit headers](https://console.groq.com/docs/rate-limits) and [HTTP error guidance](https://console.groq.com/docs/errors). The 15-second timeout is this app's plan requirement, not a universal Groq service promise.
