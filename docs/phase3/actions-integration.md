# Phase 3 actions module integration

Package: `im.mrgreen.actions` (isolated Kotlin Views-compatible library files; no manifest, Gradle, or shared engine edits).

## Public API

- `ActionRuntime(context: Context, store: GreenStore)`
- `fun plan(request: String): JSONArray?` returns ordered `{tool,args,verify}` steps for confidently matched requests, otherwise `null`.
- `suspend fun execute(request: String, progress: (String)->Unit = {}): String?` returns `null` when unmatched and executes a matching deterministic plan.
- `suspend fun executePlan(steps: JSONArray, progress: (String)->Unit = {}): String` validates tool IDs and argument keys/types-by-schema before sequential execution; caller should present progress events to UI.
- `ActionTools.registry: List<ToolSchema>` publishes each tool's ID, description, argument schema, risk, and permission metadata.

Integrate via `ActionRuntime(appContext, greenStore)`. Route requests before model generation; preserve null as planner fallback. For Phase 6, send validated ordered plan JSON to `executePlan`.

## Starter library and behavior

Twenty deterministic intent groups (time, date, arithmetic, alarm, timer, note, app launch, Wi-Fi, Bluetooth, brightness, volume, battery, airplane mode, general settings, location, accessibility, notifications, storage, date/time settings, app settings) with a 200-phrase host routing fixture (10 examples per group). The fixture is a set of varied example phrasings, not evidence of 200 independent semantic capabilities. `plan` splits simple `and`, `then`, `and then`, `also`, and `after that` compounds and rejects the whole plan if any segment is unrecognized. Basic arithmetic uses a bounded recursive-descent parser. Current time/date use `java.time`; timer units and common numeric clock times are parsed locally.

Notes write to existing `facts` SQLite table using bound values and immediately read back for verification; note text is preserved (including “and” clauses) and capped at 2,000 characters. Arithmetic supports percent-of expressions (for example, 17% of 2450). Date offsets use an injectable local-date provider and Java time. Clock intents are opened using Android alarm/timer intents with UI confirmation requested; result/progress explicitly remains pending external Clock confirmation and does not claim the alarm/timer was actually installed or verified. Flashlight uses Android `CameraManager.setTorchMode` only when CAMERA permission is already granted; otherwise it fails without attempting a toggle. Risk-marked tools have an injectable confirmation callback (default deny). Execution prevalidates the complete bounded plan before any side effect; per-tool values and plan/argument lengths are bounded. Settings-changing intents only open Android settings, never toggle privileged settings directly. App opening uses installed app labels and launch intents. Battery is read from the system broadcast.

No messaging, calling, deletion, posting, cloud, plugin execution, LLM planner, accessibility, or elevated permissions are implemented. They do not map to an automatic action. Missing/ambiguous slots fail closed (`null`) rather than fabricate a plan. The router is deterministic keyword/template matching; the requested user-approved `match_rules` database hot reload, priority/negative rule management, and golden replay API are not implemented in this isolated patch. Built-in matching still comes from compiled templates rather than seeding/reloading a DB rules pack. Number-word normalization, robust natural-language recurrence/date parsing, linked-note alarm records, Android Clock state readback, foreground-state verifiers, and retry/replan orchestration remain pending. Flashlight, Camera permission grant flow, and physical device behavior are not device verified. Test additions are source-only: no Gradle build was run and no device verification is claimed.

## Note storage, listing, dry-run, and rules

`note.save` writes a UTF-8 note in `files/notes/` via temporary-file rename and a SQLite facts row within a transaction; it verifies both the file and database readback before success, deleting the file and rolling back on failure. `note.list` responds to “check notes” and “list notes” with the most recent saved notes. `planDryRun(request)` is the pure planning entry point; `plan` remains its compatibility alias. `refreshRules()` explicitly reloads approved literal-only rules from `match_rules`; regex rule kinds are intentionally ignored. Current Clock alarm launches remain pending user confirmation: Android Clock's generic set-alarm intent does not guarantee a date-specific one-shot alarm or provide installed-alarm readback.
