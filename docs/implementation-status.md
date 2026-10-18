# Integrated preview: what to test

The missing roadmap modules are now in **one `im.mrgreen` app**, alongside the existing GGUF chat, gateway, Rive and plugin features. The target remains **Redmi 9 M2006C3MII, Android 11/API 30, armeabi-v7a only**. SkillBot was not modified.

This is an implementation preview for the owner to test. **No phase's real-device gate is marked passed here.** Passing the build computer's tests does not establish Clock behavior, MIUI background survival, GGUF speed, secure-screen behavior, third-party layouts or native-patch execution on the phone.

## Start here

Open **Overview → All capabilities · Phases 3–10**. The dashboard links to the live setup checklist, action/planner review, events, screen assistant, learning, Doctor, knowledge import, MCP, recipes, extensions, engine patches and conversation-memory controls.

The modules share the existing app identity, bundled SQLite database and engine queue. No second local model, embedding model, Python runtime, arm64 library, GPU, OpenMP or KleidiAI backend is introduced.

## Roadmap coverage

| Phase | Integrated code | Evidence still needed |
|---|---|---|
| 0 | Existing native spike, storage/FTS and host reports retained; reproducible ARMv7 source fixes added | Bonsai compatibility, phone benchmarks, Groq, overlay and UI gate |
| 1 | Existing UI/chat/model picker/JNI/service retained; permission checklist, theme and memory controls joined | Offline inference, start time, UI RAM and frame rate |
| 2 | Existing encrypted-key gateway, redaction, local fallback, quota/circuit behavior retained | Owner-key live call and phone networking; no test key was requested |
| 3 | Typed registry, deterministic library, compound execution, verified notes, calculations and device handoffs | Actual Clock save/DB linkage, Android permissions, timing and flashlight state |
| 4 | Opt-in events, proactive controls, media lookup/player handoff, clarification, audio-state check and reboot opt-in | MIUI service survival, delivery latency, player behavior and media permissions |
| 5 | API 30–33 screen reading deliberately disabled; API 34+ window-targeted capture, transition and protection checks, local Q&A code retained | Safe screen assistance on the Redmi remains unimplemented; newer-API behavior also needs device validation |
| 6 | Local JSON proposal review, routine inbox, typed Doctor edits, intended-plan/golden replay, approval, undo, snapshots/restore, incidents and maintenance | Native SQLite integration, real incidents, restore/undo and cancellation latency on the phone |
| 7 | Existing signed packs, flows, QuickJS, cap, review, rollback and safe mode preserved; reviewed core-action bridge added | Device A25/A26 and app-private file behavior |
| 8 | Bounded web/recipe cache, local TXT/PDF/EPUB import and citations, reviewed HTTPS MCP JSON-RPC | Live sites/servers, representative large books and indexing RAM |
| 9 | Explicitly reviewed WhatsApp/Meta AI/Gemini recipes and experimental Wi-Fi settings automation | Exact app versions/layouts and confirmed external sends/results |
| 10 | Companion pin/review/execute path, buildable sample, signed native-patch review/activation/fallback, platform controls and live main/overlay theme | Actual cross-process Binder execution, Android linker/rollback, voice packs, theme and long-run RAM |

## Honest behavior instead of false success

**Alarms and timers:** Android Clock setup is opened for the user to confirm. An intent returning is not proof an alarm exists, and OEM Clock behavior for a specific future day needs checking. The app does not report that handoff as a verified saved alarm or use it to learn a verified routine.

**Media:** A missing title asks for clarification, and a short follow-up is treated as the requested title. Local items are handed to MX Player when available, otherwise a compatible chooser; a missing item opens a YouTube search. Active audio can be checked, but this is not proof the requested track is playing. Generated `.m3u` playlist support is not included.

**Screen:** Reading is deliberately unavailable on Android 11/API 30 through API 33. A display screenshot does not establish that a separately read accessibility tree is non-secure. API 34 adds window-targeted screenshot capture and explicit secure-window rejection; this code now checks window/package identity and transition events before and after reading. That path is not device-tested and is not an atomic pixel/tree guarantee. Screen questions remain local-only; no OCR, vision model or screenshot-to-cloud branch is added. See [Android's API documentation](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService#takeScreenshotOfWindow(int,java.util.concurrent.Executor,android.accessibilityservice.AccessibilityService.TakeScreenshotCallback)).

**Planning and routines:** AI output is a reviewed typed proposal, never executable code. Only registry tools are accepted. Registered notification/media steps use the same sequential executor as other actions. Missing required arguments need clarification; the app does not invent an alarm time or song. Ordinary chat does not silently execute an unreviewed model-generated plan.

**Doctor:** Repairs are bounded typed data operations, not arbitrary SQL. The repair screen binds a failed phrase to the exact plan from a working example; candidate edits must improve that incident and preserve the golden set. Approval rechecks the stored evidence. Model-drafted JSON is untrusted and still needs validation and approval. Unsupported repairs are rejected. Undo restores recorded rows.

**Snapshots:** These cover the SQLite database, not models, preferences, plugin files or imported source files. Restore requires an explicit destructive-data confirmation, validates an app-owned snapshot, stages it and applies it before SQLite opens on the next full process start. The previous DB/WAL is retained as a recovery copy. This avoids swapping a database under live connections. Phone restore testing is still required.

**Native failures:** A persisted operation marker can report an interrupted process and stage. It cannot distinguish a native crash from force-stop/OS death, and is not an Android native backtrace. Use Android crash/logcat evidence. Java last-crash stacks and metadata-only Doctor reports stay private. No guessed database fix is applied.

**Maintenance:** Automatic work is opt-out, battery/temperature/RAM gated, and yields between bounded tasks. User input cancels the job, but an already-running SQLite statement is not a hard real-time interrupt; the roughly one-second gate needs device measurement. Heavy manual maintenance/snapshot work is off the UI thread.

**Conversation memory:** Current question plus at most two budgeted snippets replaces transcript replay. Lexical vectors are deterministic hashed features, not trained semantic embeddings. They can miss paraphrases; the feature does not promise faster answers on every question. The old transcript and recalled notes are not sent to cloud chat. Clear/disable controls are available.

**Web/MCP:** This is a bounded fetch/search/cache and HTTPS JSON-RPC implementation, not a universal browser or full MCP OAuth/session/SSE manager. The HTTPS socket uses a checked public-only DNS address list, normal TLS verification, no redirects/proxies and a total deadline. MCP authorization now binds immutable per-call public arguments to the displayed endpoint; another call cannot borrow a singleton review flag. JSON-RPC responses must match the request and contain no error. Private documents/events/screens are not forwarded automatically. Old imported document URI paths are scrubbed on database open; new imports no longer request persistent grants. Existing Android-held URI grants from older builds are not automatically revoked.

**Recipes and platform controls:** Third-party layouts are brittle. Wi-Fi automation is experimental and cannot grant its own accessibility permission. Voice depends on the user's installed Android offline services/packs; offline preference is not proof the Android provider never uses network. Native patches require separately reviewed owner trust and code review; a valid signature does not make code safe.

## Build and signing

See the [test report](test-report.md) for the exact host results and APK hashes. Both main variants are the same integrated application, not phase-specific APKs. The companion sample is optional Layer 3 contract code.

A development-signed APK is for testing. Production release signing remains the owner's responsibility. If an update reports a signer mismatch, **do not uninstall or clear data**: use the original key to preserve chats and imported models.
