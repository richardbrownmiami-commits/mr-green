# Mr Green: phone setup and first tests

This integrated preview targets **Redmi 9 M2006C3MII, Android 11/API 30, 32-bit ARMv7**. Phone behavior has not been tested on your device.

## Preserve your existing data

If Android reports a signing mismatch, **stop—do not uninstall or clear app data**. Use the original signing key for a data-preserving update. Uninstalling removes private chats, models and imported data. Development-signed APKs are for testing, not owner production signing.

## Open the new dashboard

Go to **Overview → All capabilities · Phases 3–10**. All modules use the same `im.mrgreen` app. The optional companion sample is an extension demonstration, not another phase-specific assistant.

Start with the live setup checklist. Grant only the access you need:

- Accessibility only for the UI recipes you choose. Screen reading is unavailable on Android 11; do not enable access expecting that feature to work.
- Notification access for new notification events. Text retention starts off.
- Optional Call log and local media permissions for those features.
- Camera for the flashlight; microphone/offline voice packs only for voice controls.

The app does not grant special access itself. Cloud is unnecessary for local tools, screen Q&A, books or local-model chat.

## Try these first

```text
what time is it
what is 17% of 2450
what date is 45 days from today
make a note: buy milk and eggs
check notes
set an alarm 7 am with note wake up gym
open calculator and tell me the time and turn on the flashlight
```

Time/math/date should use registered tools, not AI. Notes write and read back a private file and DB row. Missing permissions should produce a clear failure.

Alarm/timer handoffs open the external Clock UI. **Save and verify there before relying on them.** An opened intent is not a verified saved alarm; check future-day behavior yourself.

## Load one local model

Import a compatible text GGUF in Models. Begin with **context 512 and 2 threads**, and a short question. Stop/unload before switching. Do not load a second model or a vision/embedding model.

Chat sends the current question, not the whole transcript. Up to two relevant, budgeted snippets may be recalled locally; native KV caching reuses an identical prefix. Lexical vectors can miss paraphrases. Use the conversation-memory screen to clear or disable the index. Old conversation recalls are not added to cloud requests.

## Events, media and MIUI

Enable monitoring and only the sources you want. A visible foreground service supports monitoring. Proactive alerts start off and have quiet hours, an app allowlist and an interval limit. Notification text stays off unless explicitly enabled.

`play music` asks for a title/artist; give the short follow-up promptly. Local items open MX Player/a compatible player; otherwise YouTube search opens. Active audio does not prove the intended track is playing. Generated playlists are not included.

Review MIUI Autostart, battery “No restrictions,” Recents lock, pop-up and lock-screen permissions manually; names vary by MIUI. Optional reboot restoration must be explicitly enabled and can still be blocked by MIUI. The service Stop action disables monitoring. No task/model is silently replayed after a kill.

## Screen and app recipes

**Normal builds keep Android 11–13 screen reading disabled.** The owner-authorized personal build uses `-PpersonalScreenReading=true`, but still starts with consent off. In **Capability setup**, choose **Enable personal-use screen reading**, read and accept the warning, then choose **Review Screen assistant** and enable the Android accessibility service. Tap its bubble on a harmless screen to read once. Choose **Disable personal-use screen reading** to revoke access; later bubble taps must return a denied result. Android service access can also be revoked in Accessibility settings.

**Protected-window status is unverified in personal mode.** A successful display screenshot cannot establish that a separately read accessibility tree is safe, so the override makes no such claim and does not use screenshot success as proof. Password, filled-editable, blocked-app and changed-window checks remain, but custom controls and other sensitive content can escape detection. This is informed personal risk acceptance, not a passed security gate or a feature enabled for normal users. API 34+ keeps its window-targeted capture path and still needs separate device testing.

Screen questions stay **local-only**; screenshot-to-cloud vision is not implemented. No OCR/vision model is added. WhatsApp sends and external AI-app prompts need explicit review; verify the real recipient/text and outcome. Use harmless test content first. A changed/missing control must stop the recipe, not continue guessing.

## Learning and Doctor

Repeated successful routines can appear in the Learning inbox; approval is required. Unverified external handoffs must not be treated as verified learned routines.

For a misunderstood command, enter the failed phrase and a known working equivalent in **Repair a misunderstood command**. Review the literal rule or optional local-model draft, then validate. Intended-incident and golden replays must pass. Creating a proposal is not approval; review it separately in Doctor. Applied changes have Undo.

Snapshots cover the **SQLite database only**, not models, preferences or separate files. Restore requires explicit confirmation, validates/stages an app-owned snapshot and waits for a full process restart. Force-stop in Android App settings and reopen to activate it. A recovery copy of the previous DB is retained. Phone restore testing remains necessary.

Doctor cards and private bug reports contain bounded metadata. A native-operation interruption is not proof of a native crash; collect Android crash/logcat evidence. User input cancels maintenance between tasks, but an active SQLite statement is not a hard real-time interrupt.

## Knowledge and extensions

Import TXT/PDF/EPUB in Knowledge, ask a question and check the citation. Watch RAM on a large book. Web answers should show a fetched source.

MCP accepts explicitly reviewed **public user-typed arguments**, not automatic forwarding of private documents/events/screens. It is a bounded HTTPS JSON-RPC client, not a universal OAuth/SSE manager.

Companion tools need an independently checked signer and tool review. Native patches need a separately checked owner fingerprint, a signed ARMv7 bundle, code review and explicit approval; restart to activate. A signature establishes origin, not safety. Use rollback/built-in-engine controls when testing.

## Record actual phone results

Use [A1–A35](test-report.md) and the [VERIFY checklist](verify-status.md). Record app/model version, context/threads, steps, expected/actual outcome and private diagnostics. No speed, FPS, peak RAM or five-day survival is assumed from the build.

For an optional ADB system-night-mode test (not a required app permission):

```sh
adb shell cmd uimode night yes
```

Restore your preferred system mode afterward. The app also has its own appearance controls.
