# Roadmap VERIFY checklist

This checklist restates the explicit VERIFY topics in the root [plan](../plan.md). Repeated mentions are combined. Build-computer evidence is not Redmi acceptance.

| Topic | Available result | Still to verify |
|---|---|---|
| Bonsai/Qwen model compatibility and upstream/fork choice | The pinned native source builds for ARMv7; earlier host smoke evidence remains in the Phase 0 report. No new model inference was run for this integration. | The actual GGUF on Redmi, template/token behavior, context, RAM and speed. Do not infer Bonsai support solely from a successful build. |
| QuickJS on ARMv7 (both roadmap mentions) | ARMv7 bridge is packaged; standalone host tests cover API restrictions, heap limit, host-call quota, loop deadline and serialization deadline. | Actual Android 11 execution and A26 failure/safe-mode behavior. Host QuickJS execution is a different machine architecture. |
| Groq live call, streaming, JSON, current model names and 429 | Existing fake-transport/redaction/quota tests pass; runtime discovery remains implemented. No live key or live Groq call was used. | Paste the owner's key in the app, discover models, test streaming/JSON and online/airplane/429 transitions. No fixed model name is certified current. |
| MIUI overlay, API 30 screenshot and node-tree reading | Bubble/panel, one-shot capture, screenshot preflight and node policy compile; host policy/UI tests run. | Permission flows, screenshot support, FLAG_SECURE/password handling, ordinary-app text and capture/panel latency on the phone. |
| MX Player packages and playback | Both `com.mxtech.videoplayer.ad` and `.pro` are considered; generic-player fallback and AudioManager active-audio checks exist. | Installed versions, URI grants, actual requested playback and Android audio state. Track identity is not established by `isMusicActive()`. Generated `.m3u` playlists are not implemented. |
| Free no-key search/fetch providers | Bounded web search/fetch and cached recipes exist. | Live availability and suitability of each proposed provider; Wikipedia/Wikidata/Open-Meteo and every suggested endpoint are not all separately certified. A live network/provider test was not run here. |
| WhatsApp/Meta AI/Gemini packages and recipes | Versioned, explicitly reviewed accessibility recipes and mismatch errors exist. | Exact installed package/version, current UI labels, recipient/prompt handling, send confirmation and read-back. Captured images/vision are not established. |
| Screenshot-to-cloud and Groq vision model | **Not implemented; remains disabled.** Screen questions are deliberately local-only. | A future explicitly consented, redacted vision path would require separate implementation and live model verification; no cloud-vision gate is claimed. |
| Signed native engine loading on Android 11 | Owner-pin/signature/hash/ARMv7 checks, restart-only activation and crash-loop fallback compile. Host crypto/policy simulations pass; signing helper is provided. | Actual linker/JNI compatibility, a correctly signed library loading, bad-signature rejection and recovery/rollback on the Redmi. A valid signature does not prove code safety. |

## Other device gates

The model speed/thread/storage/FTS5 tests, at least 55 fps, cold start, overlay RAM, peak process RAM and five-day MIUI survival remain **not run**. The phase-by-phase and A1–A35 status is in the [test report](test-report.md). No phase was intentionally skipped; unverified results are not silently converted into passes.
