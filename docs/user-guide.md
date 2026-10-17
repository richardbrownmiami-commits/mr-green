# Try Mr Green on your Redmi 9

This build is an early offline chat skeleton. You check the phone; the build environment does not certify device behavior.

## First chat

Install the debug APK, open the app and review the onboarding checklist. All-files access and overlay access are optional; the Phase 1 chat can import a model without either. Accessibility and notification-reading services are not implemented in this increment, and overlay permission alone does not create a bubble.

Open **Llama**, import the text-only `.gguf` from your file picker, then load it. Start with Bonsai Q2_0, four threads and 2048 context. If free RAM is below 600 MiB, the app reduces the requested context to at most 1024. Loading can take a while. A failed load must show an error, not a fabricated response.

Ask a short writing question such as “Write a two-line greeting.” Check that tokens stream and the completed reply remains after reopening the app. Try Stop, then try another request. Switch to airplane mode to confirm no internet is needed. Phase 1 has no cloud provider.

Only one local model can be loaded. Use Unload before switching. The foreground notification has a Stop action that releases the engine; an Android process kill is not treated as a completed request. Idle models are unloaded after about ten minutes.

## MIUI setup

If you want the service to survive while another app is open, review these settings yourself:

- Autostart: on.
- Battery saver for Mr Green: No restrictions.
- Lock Mr Green in Recents.
- For future overlay work: Display pop-up windows while running in the background and Show on lock screen.

MIUI varies by version. Where no reliable deep link exists, the onboarding item is a manual check, not an automatically verified success. Reopen onboarding from Panel. Do not grant permissions for later features just to complete a checklist.

## What to record

Keep Bonsai's measured generation speed and peak process RAM: the plan requires at least 2.0 tokens/second and less than 1.2 GB peak RAM. Compare thread counts 3, 4, 6 and 8; no winning count is assumed. The Benchmark button is not a completed automated benchmark in this increment.

Also record cold start, orb/chat-scroll frame rate, real FTS5 initialization, load timing for private and shared storage, cancellation behavior, MIUI process survival and any errors. **None of these checks is marked passed by this handoff.** Use Qwen as fallback only after your measured Phase 0 decision, not based on host speed.

## Logs and data

Chat and identity are in app-private SQLite. Imported models are in private `files/models`. Uninstalling removes private app data. Do not uninstall a build with wanted data without a backup plan. Shared `/sdcard/Red/models` is only browsed if all-files access is explicitly granted.

Logs keep action/runtime metadata, not full prompts or replies. They use a 20,000-row database ring and rolling files. Without all-files access, files stay private; with it, the logger can use `/sdcard/Red/logs`. A managed crash leaves a last-crash metadata report for the next start. Native hard crashes still need Android system diagnostics.

## Later phases

Groq, cloud redaction, tools and alarms, voice, screen reading, overlays, plugin execution, Doctor, RAG and app automation are not working features of this skeleton. Do not use the app to control another app or expect it to read a screen yet.

The later system-theme feature needs a one-time ADB grant:

```sh
adb shell pm grant im.mrgreen android.permission.WRITE_SECURE_SETTINGS
```

That permission is not requested by this Phase 1 manifest and the command is not expected to work yet. Do not run it for this build. It is retained here as the plan's future setup requirement, not a completed capability.
