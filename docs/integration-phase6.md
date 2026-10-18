# Phase 6 Learning / Doctor integration

`im.mrgreen.learning.LearningRuntime` is constructed with the app context and `GreenStore`. This change stays within learning sources/tests and this document.

## Host wiring

- Set `registeredPlanValidator` to the real tool registry/schema validator. `execute(request)` checks exact approved-skill reuse, then only calls `plannerGateway` for command-likely inputs; ordinary chat returns `null`. Plans remain proposals (`executed=false`), never executed here.
- Doctor validation fails closed until the host sets `goldenPlanner: (String) -> JSONArray?` to a synchronous, pure deterministic Router/rule planner (no AI, tool calls, or effects) and sets `goldenIncidentRequest` to the failing request. `proposeDoctor()` compares up to 200 stored `golden_tests` baseline plans exactly to `expected_plan`; simulates candidate operations in a DB transaction; requires incident output to change and all golden outputs to remain identical; then always rolls the simulation back. Actual application still requires explicit approval.
- Tool errors should be fed to `recordIncident(component, errorCode, summary)`. `incidentSink(component, tool, errorCode, summary)` forwards parent dedupe/telemetry; host errors are isolated from local incident recording. Local deduplication remains `(component,errorCode)`.
- Wire `onUserInput` to the host's immediate pause/cancel hook. Scheduling policy is intentionally host-owned: require idle/charging, battery >30%, sufficient free RAM, device cool, one incident at a time, and pause within about one second of user input.

## APIs and safety

- `recordEpisode`, `inbox`, `approveSkill`, `rejectSkill` manage learning episodes and explicit skill approval.
- Doctor APIs: `proposeDoctor`, `doctorProposals`, `approveDoctor`, `rejectDoctor`, `undoDoctor`, `recordIncident`, `maintain`, `createSnapshot`.
- `validSelect` uses conservative SELECT-only policy: allowlisted tables and scalar/aggregate functions only, no semicolons/comments/subqueries/unknown functions. `runReadOnlyQuery` opens a dedicated `OPEN_READONLY` connection, sets `PRAGMA query_only=ON`, caps output to 50 rows and actively cancels at 2 seconds via the requery `CancellationSignal` overload.
- Snapshot uses SQLite `VACUUM INTO` and does not close the shared database helper.
- Implemented Doctor effects include fact add/update/retag/delete/importance, match rule add, skill status/add-trigger, template add, query recipe add, index `REINDEX`, and persisted bug reports. Templates, triggers and report records have inverse operations. `fact.merge` is deliberately rejected until a correct target-row snapshot and exact inverse are implemented. Index rebuild is an idempotent index maintenance action; it changes no logical data.

## True remaining gaps

Scheduling sensor checks, idle queue, charging/battery/RAM/thermal policy and full automatic maintenance remain parent responsibilities; `maintain()` only deduplicates exact facts and runs `ANALYZE`. There is no golden test suite execution in this task (Gradle was intentionally not run); golden behavior depends on host callback correctness. Incident tool-level dedupe needs parent callback wiring. Fact merge remains safely unsupported.
