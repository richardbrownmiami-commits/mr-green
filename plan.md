# PLAN v2: "Mr Green" — Offline + Online Android AI Agent (fresh build)

Audience: Manus AI (builder). Read the whole file before writing code.
Items marked **VERIFY** are things the planner could not confirm. Test them in Phase 0 and report the result before relying on them.
This file replaces the earlier `plan.md` and includes every decision made in the planning conversation.

---

## 1. Product in one paragraph

**Mr Green** is a new Android app that is an AI agent with **its own identity**. The APK is a **skeleton**: it contains the engine, the "hands" (tools for Android actions), the safety layer, memory, logger, UI and a plugin runtime. Almost everything else (skills, templates, routines, rules, scripts) is **data or plugins** that are created by AI (local or cloud), validated by the app, approved by the user, and installed **without rebuilding the APK**. The app works **offline** with a local llama.cpp model, and when the internet is on it uses **Groq** first for speed. The user can chat by text or voice, and can ask about **what is on the screen** through a floating overlay bubble. The UI must look premium, smooth and original.

**Name/branding:** app name **Mr Green**, icon file **`mr-green`** (source: `assets/mr-green.svg`, delivered with this plan). The default identity name is "Mr Green" (editable).

## 2. Decisions made by the owner

| # | Topic | Decision |
|---|---|---|
| 1 | AI order | **Online: Groq first. Offline or Groq fails: local model.** App rules and templates always run before any AI |
| 2 | Plugin layers | All four layers approved (data packs, QuickJS scripts, companion APKs, engine patch/full update) |
| 3 | Cloud sending | Automatic (see privacy classes in section 11) |
| 4 | Groq limits | No app-side cap. Use whatever free quota Groq allows, obey 429 / Retry-After, fall back to local |
| 5 | Voice | Android built-in TTS/STT only. **No online speech** (no Groq Whisper) |
| 6 | Pack sources | (a) owner's GitHub releases, (b) import from the SD folder, (c) AI-generated on the phone. All validated and approved |
| 7 | Storage | Pack library capped at **200 MB** on SD |
| 8 | Models (local) | **One local model at a time.** Default Bonsai Q2_0, fallback Qwen 1.8B. No embedding or vision model |
| 9 | Memory | No model for memory. SQLite + FTS5 |
| 10 | Learning | Every new learned skill needs user approval |
| 11 | Doctor | Non-model maintenance runs automatically. Every model-proposed fix needs approval |
| 12 | Voice/TTS | Android TTS with downloadable Google voice pack |
| 13 | Player | **MX Player** for music and video (offline), YouTube as fallback |
| 14 | Accessibility | Accepted, including WhatsApp/Meta AI and the Gemini app (experimental) |
| 15 | Screen reading | Overlay assistant that reads the screen on request (section 16) |

## 3. Target device (fixed, do not generalize)

| Item | Value |
|---|---|
| Phone | Redmi 9 (M2006C3MII "cattail"), SoC MT6765 Helio G35, 8x Cortex-A53 |
| OS | Android 11, API 30, MIUI 12.5, security patch 2022-07 |
| Userspace | **32-bit ARMv7** (kernel reports armv8l). ABI is `armeabi-v7a` only |
| RAM | 3795 MB total, **about 1.4 GB was free when measured**. No root |
| Screen | 720 x 1600, 269 dpi, OpenGL ES 3.2 |
| Storage | 51.8 GB internal, about 18 GB free |
| Known-good | llama.cpp with Qwen 1.8B (~899 MB GGUF) at about **2.8 tok/s** |

**App RAM:** hard cap 2 GB, **design target 1.2 GB peak**. Free RAM can be lower, so the app must degrade gracefully (section 8).

## 4. Golden rules

1. **The app decides, AI assists.** Rules, templates and skills run first. AI is used only when needed.
2. **One local model loaded at a time.** The cloud AI (Groq) is separate and optional.
3. **The core works offline.** Web, Groq, MCP and Gemini features need internet and degrade cleanly.
4. **Stack:** Kotlin for the app, C++ only for the llama.cpp JNI bridge (and QuickJS if used). No Python, no Chroma, no LiteRT.
5. **No unvetted code runs.** AI-written logic runs only as declarative flows or sandboxed scripts that the skeleton validates, tests in dry-run, and the user approves. The AI never writes Kotlin/DEX that is loaded into the app.
6. **Every action is logged** (section 12).
7. **User opt-in for risky things:** accessibility, notification listener, overlay, screen reading, sending messages, calling, deleting, MCP tools, plugins, learned skills.
8. **Privacy first:** the data classes in section 11 decide what may leave the phone.

## 5. Architecture: skeleton + growth layers

```
UI (Chat | Panel | Llama | Logs | Files)   +   Overlay (bubble + screen assistant)
        |
Orchestrator (one coroutine queue; priority: user > planner > background)
  |-- Router + Understanding Library (templates/schemas/rules, no AI)
  |-- Planner (rules -> library flows -> AI planner fallback)
  |-- Executor + Verifier (step by step, retry once, replan once)
  |-- Flow runtime (declarative skills) + Script runtime (QuickJS sandbox)
  |-- Tool registry ("hands": every Android action with schema, risk, permission)
  |-- AI Gateway  ->  GroqProvider (online)  |  LocalLlamaProvider (offline)
  |-- Memory (SQLite) + Learning engine + Doctor + RAG
  |-- Screen assistant (Accessibility reader + overlay)
  |-- Plugin manager (packs, signatures, versions, rollback, safe mode)
  |-- Proactive engine, Web/MCP client, Logger
```

**Skeleton (built once by Manus):** engine, tool registry, safety/approval layer, memory DB, logger, UI, AI Gateway, flow and script runtimes, plugin manager, overlay. **Growth (no rebuild):** packs, flows, templates, scripts, rules, prompts, knowledge.

## 6. llama.cpp native build (armv7)

- Use a **recent upstream llama.cpp master** (needed for Qwen3.5 hybrid attention and the `Q2_0` type with the Hadamard runtime). If upstream cannot load the Bonsai file, use the **PrismML fork, branch `prism`** (https://github.com/PrismML-Eng/llama.cpp). **VERIFY** in Phase 0.
- Build with the NDK via CMake. Build only `libllama`, `ggml` and a small JNI file. No server, examples or tests.
- Required CMake flags:
  - `ANDROID_ABI=armeabi-v7a`, `ANDROID_PLATFORM=android-23` (or higher), `CMAKE_BUILD_TYPE=Release`
  - `GGML_NATIVE=OFF`, `GGML_OPENMP=OFF`, `GGML_LLAMAFILE=OFF`, and all GPU backends OFF (CPU only)
  - Generic ARMv7 with NEON (`-mfpu=neon-vfpv4`, softfp). **Never** enable arm64, dotprod, fp16-arith or host-native flags.
- `abiFilters` = `armeabi-v7a` only. The release APK must contain `lib/armeabi-v7a/*.so` only (check this).
- `targetSdk = 30`, `minSdk = 26`, `compileSdk` = latest stable. Sideload only.
- `use_mmap=true`, `use_mlock=false`.
- **Threads:** benchmark 3, 4, 6, 8 in Phase 0 and keep the fastest. Start with 4.

### JNI facade (`LlamaEngine`)

```
load(path, params): Result          unload()
generate(prompt, opts, onToken): Result   // opts: maxTokens, temp, topK, topP, minP, penalties, grammar?, stop[]
cancel()                                  // must stop within about 1 token
stats(): { loadedModel, nCtx, promptTokPerSec, genTokPerSec, kvBytes, nativeHeapBytes }
countTokens(text): Int
```

- Single native context, single mutex. All callers go through the Orchestrator queue.
- No hard 96-token cap. `maxTokens` is per request (chat 256, planner 160, note-writing 400). Stream tokens to the UI.
- **Prefix (KV) reuse** for the fixed system prefix (identity + tool list).
- Support GBNF grammar for JSON output.
- Native errors must never crash the app. Return a Result and log it.

## 7. Local model configuration

**Primary:** Ternary Bonsai 2 2B, `Ternary-Bonsai-2-2B-Q2_0.gguf` (547 MB), https://huggingface.co/prism-ml/Ternary-Bonsai-2-2B-gguf (Apache-2.0). Text only. **Do not download the mmproj files.**
**Fallback:** Qwen 1.8B GGUF (~899 MB).

| Setting | Default |
|---|---|
| n_ctx | 2048 |
| n_batch / n_ubatch | 128 / 64 (tune in Phase 0) |
| KV type | f16 (try q8_0 if memory is tight) |
| Flash attention | off (test on/off) |
| Thinking mode | **OFF** (`enable_thinking=false`; else pre-fill an empty `<think></think>`) |
| Chat sampling | temp 0.7, top_p 0.8, top_k 20, min_p 0, presence_penalty 1.5 |
| Planner sampling | temp 0.2, top_k 20, **JSON grammar** |
| Writer (notes) | temp 0.6, other values as chat |

Use the chat template embedded in the GGUF. Keep presets for both models.

### Phase 0 gate (must pass before building features)

1. Load Bonsai Q2_0 on the real phone. Measure prompt and generation tok/s at several thread counts and peak RAM. **Pass:** >= 2.0 tok/s generation and peak RAM < 1.2 GB. Otherwise make Qwen 1.8B the default and report.
2. Compare model loading from `/sdcard/Red/models` (FUSE) and from app-private storage. Pick the faster one (copying is acceptable).
3. Confirm SQLite **FTS5**. If the system SQLite lacks it, bundle `io.requery:sqlite-android` (armeabi-v7a).
4. **QuickJS** builds and runs on armv7 (or choose a fallback engine). **VERIFY.**
5. **Groq test call** works from the app with a pasted key (streaming + JSON output), and the 429 path is handled. **VERIFY** current model names.
6. **Overlay + Accessibility test:** `TYPE_APPLICATION_OVERLAY` bubble works on MIUI 12.5; `AccessibilityService.takeScreenshot` works on API 30; a node-tree read of a normal app returns text. **VERIFY.**
7. **UI frame-rate test:** the orb animation plus a scrolling chat list reach >= 55 fps.
8. Write all results to `docs/phase0-report.md`.

## 8. Memory budget

| Part | MB |
|---|---|
| Local model (mmap, mostly page cache) | 550 (Bonsai) / 900 (Qwen), loaded only when needed |
| KV cache + compute buffers | 150 to 250 |
| App process (ART, UI) | 250 |
| SQLite + caches | 50 |
| Overlay (idle) | < 25 |
| RAG / web / screen buffers | 100 |
| **Total peak target** | **about 1.2 GB** |

- Register `onTrimMemory`. Check `ActivityManager.MemoryInfo` before loading. If available RAM < 600 MB, reduce n_ctx to 1024 and warn the user.
- **Lazy local model:** when online-first is active, the local model does not need to be loaded. **Pre-warm** it when connectivity drops or Groq fails, and unload after N idle minutes (default 10).
- Show the loading state in the UI.

## 9. Core engine

### 9.1 Router and Understanding Library (no AI)

The **Understanding Library** is a set of data packs stored in the database and fast indexes. Most requests are understood here without any AI call.

Contents:
- **Intent templates:** many phrasings per intent, synonyms, Hinglish keywords, slot placeholders (time, date, app, artist, number, contact, note).
- **Schemas:** tool argument schemas, entity types, plan format, answer formats.
- **Answer templates:** reply patterns filled from memory, RAG, device state or web results.
- **Flows and scripts:** the plugin content (section 17).
- **Tests:** each intent has test phrases and expected results (these become `golden_tests`).

How a request is handled:
1. Normalize the text (lowercase, trim, number words to digits).
2. Match templates using an FTS5 keyword index and token patterns, with **priorities** and **negative phrases** to avoid conflicts.
3. Extract slots with own parsers: a **time/date parser** ("7 am", "in 10 minutes", "tomorrow 6:30", "every Monday"), a math evaluator, and java.time date arithmetic.
4. Validate slots against the schema. Ask the user when something is missing (for example "Which artist, song or playlist?").
5. Run the tool or flow, or build the answer from a template plus real data.
6. Only if there is no confident match, go to the AI planner (9.2).
7. Split compound requests on "and / then / also / after that" into sub-intents.

The library is stored in the database (not in prompts), so RAM stays low. Starter pack ships in the APK (at least 20 core intents with 20 or more phrasings each). Packs come from the sources in section 2, row 6.

### 9.2 Planner

1. Rule/library path: sub-intents map to skills or flows, ordered by dependency (for example check Wi-Fi before a web step).
2. AI path (only if the rule path fails): prompt = identity (about 100 tokens) + compact tool list + 2 to 4 relevant memories/skills + the request. Reply is **JSON** (grammar-constrained locally; JSON mode for Groq):

```json
{"steps":[{"tool":"alarm.set","args":{"time":"07:00","label":"gym"},"verify":"alarm.exists"}],
 "ask_user":null}
```

3. Validate against the tool registry (known tools, argument types, permissions, risk). Reject invalid plans and re-ask once with the error.
4. A successful AI plan is offered to the Learning engine so next time it needs no AI.

### 9.3 Executor + Verifier

- Run steps **sequentially**. Each tool returns `Result(ok, data, evidence, error)`.
- After each step run its verifier. One retry. If it still fails, AI **re-plans once**. If that fails, report honestly.
- UI shows a live progress timeline (pending, running, done, failed).
- The final confirmation comes from a **template** ("Done: alarm 7:00 set, 3 notifications read, music playing"). The AI may rephrase it only if idle.
- Destructive or outbound actions (delete, send message, call, post) **always ask for confirmation**.
- Runs inside the foreground service so a chain survives when another app comes to the front. Bring the app back at the end.

Verifier examples: alarm = own record plus the intent resolved; music = `AudioManager.isMusicActive()`; app open = foreground package (accessibility or UsageStats); note = readback contains the text.

### 9.4 AI roles (only these)

Writer, fallback planner, memory summarizer, skill/pack proposer, persona rephraser, Doctor analyst, screen explainer. The AI must never be the only source for facts the app can compute or look up itself (time, date, math, device state, memory, RAG, web).

## 10. AI Gateway (Groq + local)

One interface, `AiProvider { id, isAvailable(), chat(req): stream, supportsVision, supportsJsonMode }`.

| Provider | Use |
|---|---|
| `GroqProvider` | Online. OpenAI-compatible API at `https://api.groq.com/openai/v1`, Bearer key. Made generic (configurable base URL and model) so another OpenAI-compatible provider can be added later |
| `LocalLlamaProvider` | Offline fallback (section 6/7) |

### Policy

1. Rules and library first (no AI).
2. If online (validated network) and Groq is enabled: use **Groq**. Else use **local**.
3. On Groq failure (timeout 15 s, 5xx, 429, invalid JSON twice): fall back to local for that request.
4. **Rate limits:** read the response rate-limit headers and `Retry-After`. Track quota per model and show it in the Cloud screen. Back off with jitter, use a circuit breaker (pause Groq for the retry time), queue background jobs (Doctor, pack generation) when the quota is low, and always keep quota for user requests.
5. Models are **settings, not constants.** On first run list `/models`, let the user pick a "fast" model and a "smart" model (**VERIFY** current names; models rotate). Use the smart model for Doctor, plugin building, pack generation, and screen questions; use the fast model for chat and planning.
6. Streaming (SSE) to the UI. Cache identical prompt results in the DB for a short time.
7. **Key storage:** user pastes the key in Settings. Store it encrypted with Android Keystore. Never include it in the APK, repo, logs or exports.
8. Vision (screenshots to Groq) is **off by default**, and only available if the user turns it on (section 16).

### Pre-warm and offline

Use `ConnectivityManager` network callbacks. On connectivity loss, pre-load the local model in the background. The UI shows a clear **mode badge**: "Cloud" (green cloud) or "Local" (green chip).

## 11. Privacy classes (what may leave the phone)

| Class | Examples | Rule |
|---|---|---|
| **P0 technical** | incident cards, error codes, tool names, manual pages | Send automatically |
| **P1 personal** | the user's own question, chat context, facts, notes, screen text the user asked about, RAG chunks | Send automatically **only when the user asked**, after **redaction** (below). Each category has a "Local only" switch in Settings |
| **P2 sensitive** | OTPs, passwords, card numbers, bank apps, messages and notifications from other people, call logs, contacts list | **Never leaves the device** |

**Redaction pipeline (before every cloud call):** remove or mask phone numbers, emails, card-like numbers, OTP patterns, URLs with tokens, contact names (matched against the contacts), notification bodies, file paths with user names. Unit-test it. Keep a **"Sent to cloud" log** (what, when, provider, size, class) viewable in the Cloud screen, and a **kill switch** to turn the cloud off.

## 12. Logger (build early)

- One `Log` API. Fields: timestamp, level, component (ROUTER, LIBRARY, PLANNER, LLM, GATEWAY, TOOL, ACCESS, SCREEN, PLUGIN, MEM, WEB, RAG, DOCTOR, SYS, UI), plan id, step id, app state (foreground/background), message, optional stack trace.
- SQLite ring buffer (about 20k rows) and rolling files in `/sdcard/Red/logs/`.
- Log every tool call (input, output, duration, verify result) and every AI call (provider, model, tokens, tok/s, duration, fallback reason). Full prompts only with a debug setting.
- Every error/warning has a **stable error code** from one enum (for example `E_TOOL_PERMISSION`, `E_VERIFY_FAIL`, `E_LLM_TIMEOUT`, `E_GROQ_429`, `E_ACCESS_NODE_NOT_FOUND`, `E_SCREEN_SECURE`, `E_PLUGIN_SANDBOX_KILL`, `E_DB_RECALL_EMPTY`, `E_OOM_TRIM`). Doctor depends on these.
- Global crash handler writes a report. Show a "Last crash" card on next start.
- Logger screen: filter by level and component, search, expand rows, highlight errors, "copy error", export, and a **"Copy for Manus"** button that builds a compact bug text.

## 13. Memory database ("brain") and storage

Database in **app-private storage** (speed, reliability), with automatic backups to `/sdcard/Red/db/` (daily and on demand). Location is a setting.

| Table | Purpose |
|---|---|
| `identity` | name, persona, owner profile, tone, rules (editable) |
| `messages` | chat history |
| `facts` | key, value, tags, importance, source, last_used |
| `tasks` | to-do items |
| `episodes` | each executed plan with results |
| `skills` | triggers, slots, `steps_json`, status, counters, version, origin |
| `library_templates`, `library_schemas`, `library_answers` | the Understanding Library |
| `packs` | installed packs: id, version, source, hash, signature, status |
| `match_rules` | synonyms, triggers, slot aliases, negative phrases |
| `golden_tests` | request-to-expected-plan pairs |
| `web_recipes` | source, URL pattern, how/when/why, success count |
| `query_recipes` | approved read-only SELECT queries |
| `incidents`, `doctor_changes`, `self_manual` | Doctor mode |
| `notif_log` | notifications and missed calls seen |
| `cloud_log` | what was sent to cloud |
| `rag_docs`, `rag_chunks` | imported documents |
| `fts_*` | FTS5 indexes (messages, facts, rag_chunks, library_templates, self_manual) |
| `logs` | ring buffer |

Search = FTS5 BM25 + recency + importance + tag match. **No embeddings.** Old chat is summarized into facts by AI when idle (user can edit/delete). "Remember that ..." / "forget that ..." supported.

**Identity:** fixed name, persona and rules in `identity`, with a short identity prompt in every AI call. The AI is "Mr Green's helper", never the identity.

### Storage layout

```
/sdcard/Red/
  models/   GGUF files          files/   files the app creates / user drops
  notes/    notes (.md)         packs/   downloaded and generated packs (cap 200 MB)
  rag/inbox/  rag/library/      logs/    db/ (backups)   skills/ (JSON export)
  wallpapers/  exports/  exports/bugreports/
```

Needs **All Files Access** (`MANAGE_EXTERNAL_STORAGE`). The `packs/` folder is capped at **200 MB**: show usage in the Files screen, warn at 80 percent, refuse new packs at the cap, and let the user delete or auto-clean unused packs (oldest unused first, after confirmation). RAG books are separate from this cap.

## 14. Foreground service, MIUI and Android 11 gotchas

- Run a **foreground service** (persistent notification) for the agent and the overlay. No special type is required at targetSdk 30.
- **MIUI 12.5** kills apps unless the user sets: Autostart ON, Battery saver = **No restrictions**, app locked in Recents, **"Display pop-up windows while running in the background" ON**, "Show on lock screen" ON. Build an **Onboarding wizard** (section 23) with a button for each setting (deep links where possible) and a live checklist.
- Android 10+ blocks starting activities from the background. Request **`SYSTEM_ALERT_WINDOW`** (also needed for the overlay), which gives the exemption. Use full-screen-intent notifications for proactive pop-ups.
- Add `QUERY_ALL_PACKAGES` to find/open any app (sideload only).
- Partial wake lock only while a task runs. Debounce background work. Do not call AI per notification.

## 15. Tools ("hands")

Each tool: name, JSON args schema, risk level (safe / confirm), permissions, verifier, unit test. Tools can be added by plugins (layer 3) but their **risk levels are decided by the skeleton**, not by the plugin.

**Own logic (no AI):** `time.now`, `date.calc`, `math.eval`, `device.info` (battery, RAM, storage, network), `memory.remember/forget/recall`, `tasks.add/list/done`.

**Alarm/timer/notes**
- `alarm.set` / `timer.set`: `AlarmClock.ACTION_SET_ALARM` / `ACTION_SET_TIMER` with `EXTRA_SKIP_UI` (reliable on MIUI). Also record in the app DB and set an own `AlarmManager.setAlarmClock` backup.
- `notes.*`: **own Notes module** (files in `/Red/notes` + DB): read, search, create, append, delete (confirm). "Alarm with a note" links a note id. Writing flow: app creates the note, the AI writes the text, the app verifies it was saved. The MIUI Notes app has no API.

**Media**
- `music.play` / `video.play`: if artist, song and playlist are all missing, **ask which one**. Search `MediaStore` and the SD card first, then open with **MX Player** (`ACTION_VIEW` + content URI; **VERIFY** packages `com.mxtech.videoplayer.ad` / `com.mxtech.videoplayer.pro`). Playlists via generated `.m3u`. Fallback: YouTube (app search intent, else web URL). Verify with `isMusicActive()`.
- `media.control` (media keys), `volume.set`.

**Phone events (read):** `notifications.read` (`NotificationListenerService`), `calls.missed` (`READ_CALL_LOG`).

**Apps and system**
- `app.open(name|package)`, `app.list`.
- **Generic `intent.send`** (action, data URI, extras, package): a whitelist-controlled tool that lets flows open many app screens/actions without a new APK. Risk level depends on the action (view = safe; send/call = confirm).
- `bluetooth.toggle`, `wifi.open_panel` (`Settings.Panel.ACTION_INTERNET_CONNECTIVITY`) and `wifi.toggle` through accessibility on that panel (Android 11 forbids direct toggling).
- `wallpaper.set`, `brightness.set` (`WRITE_SETTINGS`), `flashlight.toggle`.
- **Theme:** the app's own light/dark/AMOLED/accent changes freely. System dark mode via `Settings.Secure ui_night_mode` needs `WRITE_SECURE_SETTINGS` granted once by ADB (`pm grant <pkg> android.permission.WRITE_SECURE_SETTINGS`); document this. MIUI's theme store cannot be controlled.
- `call.dial` (confirm), `sms.compose` (confirm).

**Web (online)**
- `web.search`, `web.fetch`: free no-key sources (**VERIFY** each): DuckDuckGo HTML/Instant Answer, Wikipedia REST/API, Wikidata, Open-Meteo, plus direct page fetch with Jsoup text extraction. 10 s timeout, about 200 KB cap per page, cache in DB.
- A working source is saved as a `web_recipe` (how/when/why). The planner reads recipes first.
- Answers from the web: the app extracts the 2 to 3 best snippets, AI composes a short answer, and the source is shown.

**MCP client (online):** remote MCP servers over HTTP (streamable HTTP/SSE). User adds URL and headers; credentials in Keystore-backed storage. Cache tool lists. MCP tools default to risk = confirm. stdio MCP is out of scope.

**App-to-app via Accessibility (experimental)**
- Primitives: read UI tree, click by text/id, set text, scroll, back/home, wait-for-text, `takeScreenshot`.
- Recipes: **WhatsApp** (open chat, send text with confirm, read latest message), **Meta AI in WhatsApp** (send prompt, wait, capture result by screenshot/text, show it in the app), **Gemini app** (`com.google.android.apps.bard`, **VERIFY**: open, type, read reply, capture images; needs internet).
- The user enables the service manually. A floating **Stop** button shows during automation. A missing UI element is logged, the recipe is marked "needs repair", and the user is told.

## 16. Screen assistant and overlay (new)

The user can ask about whatever is on the screen, from any app, through a floating bubble.

### Components

- `OverlayService` using `WindowManager` with `TYPE_APPLICATION_OVERLAY`: **bubble**, **panel**, and a non-touchable **highlight layer**.
- `ScreenReader` using the AccessibilityService: builds a `ScreenSnapshot` (package, window title, nodes with text, contentDescription, hint, role/class, clickable/editable/checked, bounds, `isPassword`). Serialize to compact text in reading order (top to bottom, left to right), drop invisible and duplicate nodes, cap at about **600 tokens for local** and about **3000 for cloud**.
- Accessibility service config: `canRetrieveWindowContent`, `canTakeScreenshot`, interactive windows, view ids. Listen only to `typeWindowStateChanged` by default (battery). Enable content-change events only while an automation runs.

### Triggers

Tap the bubble (**capture the screen first, then open the panel**, so the panel is not in the snapshot), a **Quick Settings tile**, a notification action, and the accessibility button. Always an explicit user action. No continuous reading, no background reading.

### Modes (chips in the panel)

Explain, Summarize, Translate, Ask (free question or voice), What can I do here? (lists tappable elements), Extract, Do (guided action), Read aloud, Copy text.
- **Extract** finds phone numbers, dates/times, links, emails, addresses with local regex and offers action chips: set alarm for the time, save to notes, call, open link, add task (each goes through the normal safety layer).
- **Do** (guided action): "tap Send", "scroll down" via accessibility, with the highlight layer showing the target. Outbound/risky actions need confirmation.
- Highlight: when the answer points to an element, draw a box around it.

### How it answers

1. Router handles simple questions without AI (find a word, extract numbers, read aloud, copy).
2. Otherwise build a prompt: question + `ScreenSnapshot` + a rule that it only sees what is visible, and send it through the AI Gateway (**Groq first**, local fallback, with redaction and the P1 rules).
3. **Local fallback is slow** (prompt processing at about 600 tokens takes tens of seconds). Show a progress state, and keep the snapshot compact.
4. **Pages longer than the screen:** an optional "read whole page" that scrolls and collects text up to N screens, with confirmation.
5. If the app exposes few nodes (WebView, games, image-only screens): say so, and offer the **screenshot** path only if the user has enabled it (screenshot is downscaled to about 1024 px, JPEG, sent to Groq vision; **VERIFY** vision model). Off by default.

### Privacy and safety

- **Blocklist** (editable, defaults on): banking/finance apps, password managers, authenticator apps, payment screens, this app's own settings. Password fields are never read. OTP patterns are redacted (class P2).
- Windows with `FLAG_SECURE` return no content: show a clear "I can't read this screen" message (`E_SCREEN_SECURE`).
- A visible indicator (bubble pulse + status chip "Reading screen") while reading.
- Screen text is **not stored** after the session unless the user taps "Save". Logs keep only metadata (package, node count, tokens, local or cloud).
- Setting **"Screen questions: local only"** (never send screen text to cloud) and **"Screenshots to cloud"** (default off).
- Never read the screens of apps the user has not allowed when the blocklist mode is "allow-list".

### Overlay behavior

- Bubble: the Mr Green orb, 52 dp, draggable, snaps to the edge, half-hides after 3 s idle, pulses while reading/thinking. `FLAG_NOT_FOCUSABLE`.
- Panel: opens in under 500 ms as a rounded sheet (about 60 percent height), translucent dark surface (no blur on Android 11), focusable only when typing (handle the keyboard), swipe down to dismiss. Handles rotation, hides on the lock screen, and does not break system gestures.
- MIUI needs the overlay and "pop-up windows in the background" permissions (covered by onboarding).
- Exclude the app's own windows from the snapshot.

## 17. Plugin system (add features without rebuilding the APK)

### Layers

| Layer | What | Who creates | Needs APK rebuild? |
|---|---|---|---|
| **1. Data packs** | skills/flows, templates, rules, prompts, routines, knowledge, themes (JSON) | AI on the phone, Manus, or the owner | No |
| **2. Script plugins** | sandboxed JavaScript (**QuickJS**, **VERIFY** on armv7) | AI or Manus | No |
| **3. Companion APKs** | a small separate APK for new Android permissions/tools, bound by service | Manus (on a PC) | No change to the main APK |
| **4. Engine patch / full update** | signed replacement `libllama`/engine `.so`, or a new full APK | Manus | Patch: no. Full: yes |

### Pack format (`.redpack`, a zip)

```
manifest.json   { id, name, version, api_min, api_max, layers[], permissions[], tools[], triggers[], files{hashes}, signature }
flows/*.json  templates/*.json  rules/*.json  prompts/*.json  scripts/*.js  tests/*.json  docs/*.md (become self-manual pages)
```

### Flow runtime (declarative skills)

JSON steps: `call` (tool), `ask_user`, `if`, `foreach`, `set` (variable), `wait` (bounded), `verify`, `ai_write`, `on_error`. Limits: 50 steps, 20 loop iterations, 5 minutes per run. Risk and confirmation are enforced by the skeleton on every `call`.

### Script runtime (QuickJS sandbox)

- Scripts have **no direct Android or network access.** They only get a host API: `tool.call(name,args)`, `mem.get/set` (namespaced per plugin), `log()`, `http.get(url)` (through the gateway, allowed domains only, size/time caps), `ai.ask(prompt, opts)` (through the AI Gateway with redaction), `time.now()`, `notify()`.
- Limits: 16 MB heap, 5 s CPU per call (interrupt handler), 50 tool calls per run, no dynamic code loading, no imports. Secrets (API keys) are never visible.

### Layer 3: companion APKs

- Bound service with a defined interface. The skeleton **checks the signing certificate** (must match the owner's key) before binding.
- The companion APK registers tools; the skeleton assigns risk levels and applies the same confirmation rules. Manus builds these on a PC; AI cannot compile Kotlin on the phone.

### Layer 4: engine patch / full update

- Patch: a signed `.so` bundle placed in app-private storage and loaded with `System.load` after signature and hash verification (**VERIFY** loading works on Android 11). Keep the previous version, and roll back after repeated crashes.
- Full update: the app can fetch the new APK from the owner's GitHub release and ask the user to install it (`REQUEST_INSTALL_PACKAGES`); Android enforces that the signature matches.
- Never load Kotlin/DEX code from packs into the main app.

### Lifecycle

1. Created (AI Pack Builder on the phone, or Manus).
2. **Validate:** manifest schema, API range, forbidden calls, hashes, signature.
3. **Dry-run** with mocked tools using the pack's own tests and the `golden_tests`.
4. **Approval screen** shows what it does, which tools and permissions it uses, and size.
5. Install with a version number. Doctor monitors it. Three consecutive failures disable it. **Rollback** to the previous version is one tap.
6. **Safe mode:** if the app crashes repeatedly after a pack/patch, the next start disables all plugins and tells the user.

### Trust rules

- **Signing:** the owner signs packs with the owner's key (layers 1 to 4). AI-generated packs on the phone are signed with a **device-local key** and are limited to **layers 1 and 2**. Layers 3 and 4 require the owner's key.
- Sources: owner's GitHub releases (HTTPS, hash checked; the owner supplies the repo URL), the SD folder, and AI generation. **Nothing installs without user approval.** Enforce the 200 MB cap.

## 18. Learning system (how the app grows)

No model training. Learning = stored skills, templates and memory.

1. **Record** every executed plan as an `episode`.
2. **Reuse:** a successful plan is stored with trigger phrases; the Router runs it later without AI.
3. **Detect patterns** without AI (same plan 3 or more times, or "remember this / make this a skill").
4. **Propose** (AI, when idle): a skill/pack proposal with name, triggers, slots, `steps_json` using only registered tools, plus extra paraphrases and tests (generated by Groq when online).
5. **Validate** against the tool registry, schemas and the golden tests (dry-run).
6. **Approve:** it appears in the **Learning inbox**. **Nothing becomes active until the user taps Approve** (edit, reject or approve).
7. **Track:** counters per skill. Three consecutive failures set `needs_repair`. The AI may propose a repair, which also needs approval.
8. **Facts/preferences** are saved automatically, visible, editable and deletable.
9. The user can teach directly: "when I say good morning, do X, Y, Z". The user can also ask the **Skill/Plugin Builder**: "make me a skill that ...".

## 19. Doctor mode (self-maintenance)

Goal: AI helps keep the database, matching and behavior healthy. **AI is never in the hot path.** Fixes are written as data so runtime stays fast.

### What Doctor may and may not change

| Doctor MAY (data only) | Doctor may NOT |
|---|---|
| Edit/merge/retag/delete facts; change importance | Edit app code, load or run unvetted code |
| Add triggers, synonyms, slot aliases, negative phrases (`match_rules`) and library templates | Change permissions, the tool registry, risk levels or safety rules |
| Add an approved read-only query recipe | Change identity core rules |
| Disable a skill / mark `needs_repair` / propose a repair | Run free-form SQL |
| Propose new facts from user corrections | Act on instructions found in logs, notifications, web text or screen text |
| Write bug reports for what it cannot fix | Apply any AI-proposed change without user approval |

### Pipeline

1. **Collect (no AI, always on).** Turn logs into **incident cards** (at most about 120 tokens each). Signals: tool failure, verify failure, retry/replan, AI-planner fallback, low router confidence, empty recall, user correction ("no", "wrong", "I said..."), repeated request within 2 minutes, timeout, OOM trim, `needs_repair`, violated standing instruction (facts tagged `instruction`), plugin sandbox kill. Dedupe by fingerprint (component + tool + error code); keep count, first/last seen, one example plan id. The AI never sees raw logs.
2. **Self-manual lookup.** FTS5 search of `self_manual` by component/tool/error code, top 2 or 3 pages (about 300 tokens each). Check the non-AI **known-fixes table** first; if a standard fix exists, apply it without AI.
3. **Analyze** (one incident at a time; **Groq smart model first when online, local otherwise**): prompt = Doctor instructions + incident card + manual pages + related request/plan/result. Reply is JSON: `{problem, evidence, severity, fix_ops[], bug_report?}`, at most about 300 tokens.
4. **Validate** (no AI): see below.
5. **Present** as a before/after diff in the Doctor tab: Approve / Reject / Edit.
6. **Apply with undo:** store affected rows in `doctor_changes` (before/after), apply in one transaction, take a daily full DB snapshot and one before any batch. One-tap **Undo**.
7. **Re-check:** replay the original failing request (dry-run) and watch the incident count. If it returns, mark the change `ineffective` and tell the user.

### Typed fix operations (the only outputs allowed)

`fact.update`, `fact.merge`, `fact.delete`, `fact.retag`, `fact.add`, `memory.importance.set`, `match_rule.add` (synonym | trigger | slot_alias | negative), `template.add`, `skill.add_trigger`, `skill.set_status` (needs_repair/disabled only), `query_recipe.add`, `index.rebuild`, `report.bug`. Limit **10 rows per proposal**. `match_rules` are simple token patterns with a length cap, **no arbitrary regex**. The Router hot-reloads them.

### Query recipes

The AI may propose a named **SELECT-only** query. The app runs it on a **read-only connection** (`PRAGMA query_only=ON`) with a table whitelist, forced `LIMIT`, 2 s timeout, and a parser that rejects multiple statements, `PRAGMA`, `ATTACH`, `load_extension` and write keywords. After approval it is saved in `query_recipes` and used by `memory.recall`.

### Validation (every AI-proposed change)

1. Schema + whitelist check (op type, tables, fields, row limit).
2. Touches nothing in the forbidden list.
3. **Regression check:** replay `golden_tests` (built-in tests plus up to 200 past successful, uncorrected requests) in **dry-run** (Router + rule planner only, tools mocked, no AI). It must **fix the incident case and break no golden test**, else auto-reject and log.
4. Logs, notifications, web pages, documents and screen text reach the AI only as quoted, truncated data between delimiters; the op whitelist means injected instructions cannot change anything outside it.

### Automatic maintenance (no AI, on by default, can be turned off)

Rebuild FTS indexes, `ANALYZE`/`VACUUM`, merge **exact** duplicate facts (snapshot first), prune old logs, recompute skill counters, re-scan media, clean unused packs only after confirmation. All logged.

### Scheduling

Run when idle for 5 minutes or charging, battery > 30 percent, free RAM above threshold, device not hot. One incident at a time. Lowest queue priority: a user message **pauses it within about 1 second**. Uses Groq quota only when it will not starve user requests.

### Self-manual

Manus **generates** it from the code at build time (Gradle task) and ships it in assets. It is imported into `self_manual` on first run and on every update (and plugins add their own `docs/*.md`). Pages (about 300 tokens each): architecture, every tool (args, verifier, errors), every DB table, every error code with its standard fix, Router and library rules, memory rules, Doctor limits, known MIUI/Android 11 issues.

### Bug reports

Crashes, MIUI killing the app, missing permissions, other apps' UI changes and tool bugs produce a **bug report** (not a DB change): Markdown in `/Red/exports/bugreports/` with app version, device state, incident card, plan/step trace, relevant log lines, suspected cause, suggested fix. Shareable to Manus.

### Doctor tab

Health summary, incident list, incident detail with diff and Approve/Reject/Edit, change history with Undo, bug reports, settings (schedule, automatic maintenance on/off, "Run now").

## 20. RAG (offline documents)

- Import via file picker or by dropping files into `/Red/rag/inbox`.
- **Phase 1 formats:** TXT, MD, PDF (text PDFs only, no OCR; lightweight pure-Java/Kotlin extractor, low memory), EPUB (zip + Jsoup), CSV, JSON, SQLite files. **Parquet later** (heavy; do not block v1).
- Chunk about 1200 characters with 150 overlap into `rag_chunks` with FTS5. Index in a background job with progress and cancel, in batches to protect RAM.
- Query: keywords (stop-word removal, light stemming), top 2 chunks (configurable up to 4, about 600 tokens), then AI answers **only from the context** and cites doc and page/section. Groq first when online (P1 rules and redaction apply, "Local only" switch available); local otherwise, with a "reading" indicator.
- No text-to-SQL in v1. For SQLite/CSV, search row text and let the user browse tables.

## 21. Voice

- **TTS:** Android `TextToSpeech`, preferring the Google engine, selectable voice and speed, a button for `ACTION_INSTALL_TTS_DATA` (natural voice pack). Speak sentence by sentence while text streams.
- **STT:** `SpeechRecognizer` with `EXTRA_PREFER_OFFLINE`. Check `isRecognitionAvailable()`; if offline recognition fails show a guide to install the Google offline language pack. Typing always works. **No online speech service.**
- Settings: speak replies on/off, hands-free mode (optional), press-and-hold mic.

## 22. Proactive engine

Rule-based, no AI per event: missed call, notification from chosen apps, alarm/timer finished, task due, daily summary. The app speaks or shows a notification ("You have a missed call from X"). Per-rule on/off, quiet hours, rate limits. Optionally AI rephrases a daily summary once when idle. Notification/call content is class P2 and stays on the device.

## 23. UI/UX design (premium, smooth, original)

### Brand and look

- **Name Mr Green, icon `mr-green`** (adaptive icon + monochrome themed icon; derive from `assets/mr-green.svg`; the same mark is used for the overlay bubble and a monochrome notification icon).
- **Dark default** (deep forest green-black), with Light (mint white) and **AMOLED black** options, and an **accent picker** (8 colors). Theme and accent apply to the app **and the overlay** instantly, without restart.
- Suggested palette: background `#07130C`, surface `#0E1F15`, surface-high `#15301F`, primary `#2BE37A`, accent lime `#B6FF3B`, text `#E8F5EC`, muted `#8FB19B`, warning `#FFB020`, error `#FF5C5C`.
- Typography: system font with clear weight hierarchy (optionally one bundled font under about 200 KB). Rounded 20 to 28 dp cards, soft layered surfaces, subtle gradients and 1 dp translucent strokes. **No heavy blur or large shadows** (low-end GPU, Android 11).

### Stack decision

Use **Views/XML with Material Components + custom Views** for the whole app (the overlay must be Views anyway, which keeps one consistent system). Use Compose only if Phase 0/1 measurements show >= 55 fps and < +50 MB RAM. Animations use property animators and springs; support a **Reduce motion / low-RAM mode** that simplifies them.

### The Mr Green orb (signature element)

A custom-drawn orb (gradient core + soft rings) with clear states: **idle** (slow breathing), **listening** (ripples that follow mic level), **thinking** (rotating arc), **acting** (segmented progress), **speaking** (waveform bars), **error** (short shake + red tint). A small **mode badge** shows "Cloud" or "Local". It pauses drawing when not visible.

### Screens

1. **Chat (home):** orb header, message bubbles with a streaming caret, collapsible **plan timeline cards** (live status per step), **rich cards** (alarm, note, music with controls, notification digest, web source with citation chips, screen-extract chips), quick chips above the keyboard, press-and-hold voice with a waveform, message actions (copy, speak, save to memory, retry, "why?" shows the plan trace), empty-state suggestions, haptics.
2. **Control Panel:** a dashboard of cards (Health ring: RAM, tok/s, online/offline; Today: alarms and tasks; badges for Learning inbox and Doctor) leading to tabs: Actions (tools on/off + permission status), Skills, **Learning inbox**, Memory, **Doctor**, **Plugins/Packs** (installed, versions, size vs the 200 MB cap, install, rollback), **AI Gateway / Cloud** (Groq key, models, live quota, mode, sent-to-cloud log, kill switch, privacy switches), Web recipes, MCP servers, Screen assistant (blocklist, local-only, screenshot toggle), Settings (theme, accent, TTS, proactive rules, quiet hours, permissions checklist, MIUI guide).
3. **Llama:** model cards from `/Red/models`, load/unload, loaded-model info (name, size, quant, architecture, n_ctx), **live RAM gauge**, prompt and generation tok/s with a sparkline, settings sliders (section 7), presets (Bonsai, Qwen), **Benchmark** button, reset.
4. **Logs:** color-coded levels, component filter chips, search, expandable rows, "copy error", "Copy for Manus", export.
5. **Files:** the `/Red` folder as list/grid (open, rename, delete, share), storage rings (packs vs 200 MB cap, models, logs).
6. **Onboarding wizard (first run):** one step per permission/setting with plain-language reasons, deep links, live checkmarks and a "Test" button each (TTS, speech, overlay, accessibility, notification access, storage, MIUI autostart/battery/pop-ups). Re-openable from Settings.
7. **Overlay:** bubble + screen-assistant panel (section 16): a header chip with the source app and "read N items", a streaming answer, chips (Explain / Summarize / Translate / What can I do? / Extract), an input with mic, a highlight toggle, and result actions.

### Motion and quality bar

- 200 to 300 ms easing, spring for the bubble and sheets, shared-element feel between list and detail. Skeleton/shimmer placeholders while the AI is working. Cold start under 3 s. Idle UI RAM overhead under about 80 MB.
- Minimum touch target 48 dp, content descriptions, system font scale supported, strings in resources (English default).

## 24. Permissions (request only when needed, explain why)

`FOREGROUND_SERVICE`, `MANAGE_EXTERNAL_STORAGE`, `READ_CALL_LOG`, `CALL_PHONE` (optional), `RECORD_AUDIO`, `SET_WALLPAPER`, `WRITE_SETTINGS`, `BLUETOOTH` / `BLUETOOTH_ADMIN`, `INTERNET`, `ACCESS_NETWORK_STATE`, `SYSTEM_ALERT_WINDOW`, `QUERY_ALL_PACKAGES`, `RECEIVE_BOOT_COMPLETED`, `REQUEST_INSTALL_PACKAGES` (for full-update flow), `PACKAGE_USAGE_STATS` (optional). Special access: Notification listener, Accessibility service, overlay. One **Permissions checklist** screen with a button for each.

## 25. Build phases and gates

| Phase | Deliver | Gate |
|---|---|---|
| **0** | Spike: native build, Bonsai, benchmarks, FTS5, storage test, QuickJS, Groq call, overlay + a11y screenshot, UI frame-rate test | Section 7 gate passed; report written |
| **1** | Skeleton: project, JNI engine, DB, logger, foreground service, **design system, orb, chat UI, Llama screen, onboarding wizard** | Chat works offline; UI >= 55 fps |
| **2** | **AI Gateway** (Groq + local, redaction, quota handling, cloud log, mode badge, pre-warm) | Tests A22, A23 |
| **3** | Router + **Understanding Library** (starter pack), tools (time/date/math/alarm/timer/notes/app/device), planner, executor/verifier, progress cards | Tests A1 to A5, A24 |
| **4** | Music/video via MX Player + YouTube, notifications, missed calls, proactive engine | Tests A6, A7 |
| **5** | **Screen assistant + overlay** | Tests A29 to A32 |
| **6** | AI planner (JSON), learning engine + Learning inbox, **Doctor mode** | Tests A8, A9, A14 to A21 |
| **7** | **Plugin system layers 1 and 2** (flow runtime, QuickJS, pack manager, builder, 200 MB cap) | Tests A25, A26 |
| **8** | Web search/fetch/recipes, MCP client, RAG | Tests A10, A11 |
| **9** | Accessibility app recipes (WhatsApp, Meta AI, Gemini), Wi-Fi toggle (experimental) | Test A12 |
| **10** | **Layers 3 and 4** (companion APK, engine patch), wallpaper/Bluetooth/theme, UI polish, memory tuning, release APK | Tests A27, A28, A33 to A35, A13 |

## 26. Acceptance tests (run on the real device)

- **A1** "what time is it", "what is 17% of 2450", "what date is 45 days from today": answered **without AI**, under 1 s.
- **A2** "set alarm 6:30 tomorrow": alarm exists in the Clock app and the DB; confirmation shown.
- **A3** "make a note: buy milk and eggs": note file and DB row exist and read back correctly. "Check notes" lists it.
- **A4** "set an alarm 7 am with note wake up gym": linked, both verified.
- **A5** "open calculator and tell me the time and turn on the flashlight": three steps in order, progress card, one final confirmation.
- **A6** "play music": asks artist, song or playlist; then plays in MX Player and verifies audio is active. Video: plays a local file, else YouTube search.
- **A7** A missed call and a notification trigger a proactive message within 10 s.
- **A8** "set an alarm, read my notifications and play music": planned, run, verified step by step, with one final confirmation.
- **A9** After the same custom routine 3 times a skill proposal appears in the Learning inbox. After approval it runs without AI. A rejected proposal is never saved.
- **A10** With internet: a web question is answered with a source, and a `web_recipe` is saved and reused.
- **A11** Import a TXT/PDF/EPUB book: a question returns an answer with a citation. Memory stays under budget during indexing.
- **A12** WhatsApp: open a chat and send a message (with confirmation). Meta AI: prompt, then result shown in the app. A UI mismatch gives a clear logged error, not a crash.
- **A13** After 5 days of idle and restarts, the app still runs in the background (MIUI settings applied), peak RAM stays under 1.2 GB (2 GB hard cap), no crash in Logs.
- **A14 to A21 (Doctor)**
  - A14 A failing tool call creates one deduped incident card within about 1 minute, with no AI.
  - A15 A missed request that the user corrects leads to a proposed `match_rule`/template shown as a diff; the replay passes; after Approve it routes correctly without AI; after Reject nothing changes.
  - A16 Unsafe proposals are rejected (identity/permissions/tool registry edits, delete without filter, multi-statement or non-SELECT query, more than 10 rows). An injected instruction in a notification, web page or screen text cannot cause any change.
  - A17 A proposal that breaks a golden test is auto-rejected and logged.
  - A18 Undo restores the exact previous rows; the daily snapshot restores the whole DB.
  - A19 Doctor runs only when idle or charging, pauses within about 1 second on user input.
  - A20 A native crash or missing-permission error produces a complete bug-report file and no DB "fix".
  - A21 Automatic maintenance merges exact duplicate facts, rebuilds FTS, prunes old logs, logs each action, and can be turned off.
- **A22 (Gateway)** Online: the log shows Groq used. Airplane mode: the local model answers without a crash and the badge shows "Local". A 429 triggers backoff and a fallback to local. The Groq key does not appear in the APK, logs or exports.
- **A23 (Redaction)** Unit tests: phone numbers, emails, OTPs, card numbers, contact names and notification bodies are masked or blocked; P2 data is never sent; the cloud log records every send.
- **A24 (Library)** At least 90 percent of 200 varied phrasings across 20 intents resolve without AI; conflicts are resolved by priority and negative phrases; the 200 MB cap is enforced; pack rollback works.
- **A25 (Layer 1)** A pack installs after validation, dry-run and the approval screen; the permissions list is shown; rollback restores the previous version.
- **A26 (Layer 2)** A QuickJS script runs through the host API only; an infinite loop is killed within 5 s; the memory cap is enforced; repeated crashes trigger safe mode.
- **A27 (Layer 3)** A companion APK with a wrong signature is rejected; a correctly signed one registers its tools, and the skeleton sets their risk levels.
- **A28 (Layer 4)** A correctly signed engine patch loads; a bad signature is rejected; repeated crashes roll back to the previous engine.
- **A29 (Overlay)** The bubble shows over other apps, can be dragged and snaps to the edge, survives rotation, and the panel opens in under 500 ms with the screen captured before the panel appears.
- **A30 (Screen Q&A)** On a chat or web page: "summarize this" is correct; "what can I tap" lists the buttons; Extract chips (phone, time) lead to call or alarm only after confirmation; the highlight layer marks the right element.
- **A31 (Secure screen)** A `FLAG_SECURE` app, a password field and a blocklisted app give a clear "can't read" message and no content.
- **A32 (Local-only)** With "Screen questions: local only", no network traffic is sent and the log shows local. With cloud allowed, redaction is applied and the cloud log shows the send.
- **A33 (UI performance)** >= 55 fps for the orb and chat scrolling; cold start under 3 s; UI RAM overhead under about 80 MB; idle overlay under 25 MB.
- **A34 (Onboarding)** The wizard completes all permissions with deep links and an accurate live checklist, and each "Test" button works.
- **A35 (Theme)** Theme and accent changes apply to the app and the overlay without restart.

## 27. Known limits (be honest in the UI and docs)

- At about 2.8 tok/s every local AI call is slow. That is why rules, the library and Groq come first.
- The model cannot be retrained on the phone. Learning = stored skills, templates and memory.
- Flows and scripts can only combine the skeleton's tools. New permissions or hardware access need a companion APK or an update (layers 3 and 4, built by Manus).
- Groq's free tier is rate-limited, models rotate, and one review states the free tier carries no privacy guarantees (check Groq's terms). That is why redaction and P2 rules exist.
- Accessibility automation (WhatsApp, Meta AI, Gemini, Wi-Fi toggle) can break when those apps update. Gemini, web, Groq and MCP need internet.
- Screen reading does not work on `FLAG_SECURE` windows, and some apps (WebView, games, images) expose little text. Local screen answers are slow.
- Voice quality depends on the Google TTS voice pack. Offline speech recognition needs the Google offline language pack.
- Free RAM can be about 1.4 GB. The app must degrade (smaller context, unload model) rather than crash.
- Doctor fixes data, not code. Real bugs become bug reports for the next build. A small model is often wrong, so every AI proposal is validated, tested against golden tests and approved.
- QuickJS scripts are slower than Kotlin (fine for orchestration). AI-written scripts will have bugs, which is why dry-run tests and Doctor are required.

## 28. Do not do

- Do not use two local models at once, and do not add an embedding, vision or speech model.
- Do not use arm64 or host-native build flags.
- Do not auto-enable accessibility, overlay or any special permission.
- Do not load AI-written Kotlin/DEX code into the app, and do not save or install a skill, pack, script or patch without approval and validation.
- Do not let AI answer time, date, math, device state, memory or document facts that the app can compute or look up itself.
- Do not send P2 data to the cloud, and do not put the Groq key in the APK, repo, logs or exports.
- Do not read the screen continuously or in the background, and do not store screen text without a user "Save".
- Do not let Doctor run free-form SQL, skip the golden-test check, apply a proposal without approval, or touch identity core rules, permissions, the tool registry or safety rules.
- Do not feed raw logs to the AI. Use incident cards and manual pages only.
- Do not add Python, Chroma, LiteRT or online speech.

## 29. Deliverables

1. Source repository with README and build instructions (Gradle + NDK + CMake).
2. `app-release.apk` (armeabi-v7a only) and a debug APK, using the `mr-green` icon.
3. `docs/phase0-report.md` (tok/s, RAM, threads, storage, FTS5, QuickJS, Groq, overlay/a11y, UI fps).
4. `docs/test-report.md` covering A1 to A35 with pass/fail and logs.
5. `docs/user-guide.md` with the MIUI setup guide and the ADB command for system theme control.
6. A list of every **VERIFY** item with its result.
7. The generated self-manual, the error-code list with standard fixes, the built-in `golden_tests`, and unit tests for the Doctor validator and the redaction pipeline.
8. The starter Understanding Library pack, the `.redpack` format spec, the pack-signing tool, and sample plugins for layers 1 and 2 (and a sample companion APK for layer 3).

## 30. Assumptions and open items for the owner

1. The app name is **Mr Green** and the icon file is `mr-green` (assumed from the owner's message). Change the identity name in Settings if needed.
2. "Layer 4" is read as approval of **all four layers**.
3. Screen text to Groq is sent **only when the user asks**, after redaction, with a "local only" switch. Screenshot-to-cloud is **off** by default.
4. The owner must provide the GitHub repo URL for pack releases, and the Groq API key (pasted in Settings).
5. Groq model names are chosen at first run from the live `/models` list (they change often).
