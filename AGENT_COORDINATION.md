# Mr Green: offline Android agent goal, review and proposed work split

## Status: paused for review

Owner instruction: commit this coordination file so a second agent can read it, add suggestions and questions, and then the owner can divide the work. **Do not implement, change app behavior, bump versions, build a new release, or publish an APK until that review has happened and the owner asks to split the work.** This documentation-only commit is authorized; implementation/release pushes are paused.

Repository: https://github.com/richardbrownmiami-commits/mr-green only. Do not modify SkillBot or any other repository.

Starting app source: `8db2615`, release `v0.10.3-memory-preview`. No app implementation changes have been made in this pass. The existing release still has the cloud-chat behavior described below; this file is not a privacy fix.

Coordination baseline: `44d9d2f` (the two later commits changed this document, not app source). This update clarifies the goal and records separate review replies only. **Neither Agent A nor Agent B has an implementation assignment.** Builds, version changes and APK releases remain paused until the owner reviews the replies and explicitly assigns work.

## Clarified goal and identity

Mr Green is one offline-first Android mobile agent with a consistent identity, text conversation, local memory and permission-controlled phone actions. Proposed user-facing identity prompt:

> You are Mr Green, the user's Android mobile agent. Speak clearly and consistently as Mr Green. Use available app data and registered capabilities; ask when a request is unclear or needs approval. Say when a capability, permission, connection or local model is unavailable. Never invent device state or claim an action succeeded without execution evidence. Do not claim to be human; answer questions about how you work honestly.

The greeting need not gratuitously introduce an AI label, but must not conceal model-assisted operation when asked or misrepresent Mr Green as a person. Identity is app-controlled configuration shared by rules and local prompts, not something a model or Doctor may silently rewrite.

`plan.md` sections 9, 18, 19 and 22 describe routing, learning, Doctor and proactive behavior. Both `plan.md` and `docs/plan.md` also contain older online-first/cloud-screen rules and roadmap ambitions, not proof that every action exists. For this review the owner's latest **no cloud-generated live chat** requirement supersedes those older routing proposals. Plan files remain unchanged in this documentation-only pass.

## Proposed architecture — target, not completed implementation

1. **App first:** text UI → serialized orchestrator → deterministic router, rules/templates, SQLite/FTS memory and registered tools. Known tasks use real app data without generation. Preserve recognized-action handling; unsupported or failed actions must not fall through into fabricated model success.
2. **Local helper when needed:** the existing on-device GGUF may answer an unanswered question or propose a structured task plan using a compact registered-tool schema and bounded relevant memory. Ask clarification questions for missing details. This is a proposed extension where not already wired; importing a GGUF does not connect new Android actions.
3. **APK remains in control:** validate proposed tool names, arguments, permissions, risk and dependencies. Execute only registered actions, sequentially with cancellation and verifiers. Reject unsafe/unknown tools; obtain required approvals, including confirmation for outbound/destructive actions. Model text is not executable authority, arbitrary SQL or installable code.
4. **Memory and improvement inbox:** keep useful context, preferences, episodes and action results locally with bounded retrieval, provenance and user controls. Reusable dialogue/templates/skills require validation, dry-run tests and approval before activation, with versions and undo. Ask about corrections or missing capabilities; generated answers are not automatically verified facts.
5. **Background/event work:** rule-based notification/task events and deterministic housekeeping use Android-compatible scheduled work or an appropriate visible foreground service, with opt-in permissions, quiet hours, battery/RAM limits and user work taking priority. Keep one serialized inference owner; no continuously running model or continuous screen reading. Android 11/MIUI can delay or kill work: always-on survival and timing require real Redmi tests, not promises.
6. **Separate optional cloud Doctor:** owner-triggered maintenance may send only an explicitly approved, minimized app-configuration/incident schema, never live questions, conversation history, private recalled memory or screen content. Outputs are owner-facing suggestions/reports or typed data-change proposals, never live chat replies. Validate, regression-test, obtain approval and retain undo before database/dialogue changes. Design/capability/code improvements become owner/developer proposals requiring a separately authorized build when native changes are needed, not cloud self-modification. No arbitrary DB access, permission escalation or changes to safety/identity core rules.

### Learning is not GGUF weight training

Feasible app-data learning stores approved trigger phrases, dialogue templates, facts/preferences and reusable flows over **existing** tools, then retrieves/reuses them. Saving a proposal differs from activating it; sensitive memory remains local and user-controllable. Existing generated saved replies may be unverified and cannot become trusted actions/facts through repetition.

This is **not model weight training**. No implemented on-device fine-tuning pipeline is established here; chat history and database updates do not silently train the GGUF. Actual weight training would require a separate scope, suitable compute, consented data, evaluation and compatible model deployment. It is outside this paused APK work.

### Feasibility and present gaps

The app-first/local-helper design is feasible within Android permissions and registered tools, not as unrestricted control over every app. Compound actions need supported tools and verifiers; web-dependent tasks still need internet even though chat stays local. Preserve Android 11/API 30, ARMv7-only packaging, one loaded model, bounded context and the plan's 1.2 GB peak design target (2 GB cap). Speed, RAM and MIUI behavior remain device-validation gates.

**0.10.3 is not yet this target:** `replyWithRulesOrModel` can invoke ordinary cloud chat; `draftPlugin` can also call the gateway. Local deterministic `HealthMaintenance` is not a cloud Doctor. No distinct dedicated owner-only cloud-maintenance route is established in current source. Disabling live cloud chat does not create one; keep such maintenance unavailable until separately reviewed, authorized and implemented.

## What the owner wants

Keep one integrated Android app. User-facing replies must follow this order:

1. Existing Phase 3 deterministic action router, local rules and applicable local memory.
2. On-device GGUF generation for unanswered questions, with bounded local context.
3. If a suitable local model cannot be loaded, show an honest local model-required error. Never substitute a cloud reply.

No live question, prior conversation, recalled memory snippet, or user screen content may reach a cloud AI. Gateway-enabled settings must not override this boundary. Retain current permission, privacy, cancellation and action-confirmation safeguards, memory features, Android 11/API 30 compatibility and ARMv7-only native packaging. No second APK or second inference model.

Cloud AI is allowed only through a genuinely separate maintenance route explicitly limited to owner-controlled app data/configuration. Ordinary `/chat/completions` requests do not become maintenance by renaming a category. Do not invent a working maintenance capability or send private data to it. If no appropriate route exists, document that it is not implemented.

## Confirmed source findings

Paths and line numbers below refer to starting commit `8db2615`.

- `app/src/main/java/im/mrgreen/core/AgentController.kt`: `submit` runs `deterministicFirst` and `RoadmapRuntime.execute` before `replyWithRulesOrModel`. Preserve that ordering and recognized-action safeguards.
- `replyWithRulesOrModel` retrieves conversation memory before saved rules. At line 257 it can call `tryCloud(text, runId)` before `ensureLocalReady()`, so ordinary unmatched chat is not local-only today.
- `tryCloud` at lines 422–466 sends the current user question through `GatewayRequest(category = "chat")`, streams cloud text into the UI, and stores the returned text as the assistant reply. Removing only history from that payload does not satisfy the requirement.
- `draftPlugin` at lines 350–388 has another gateway call for builder text with category `packs`. Its separation from live chat and eligibility as owner-controlled maintenance need explicit review; do not assume it is a safe maintenance route.
- `answerContext` at lines 390–419 is local by default, but accepts `localOnly = false` and allows non-screen categories to reach the gateway. Audit all callers and prevent a context/category loophole.
- `RoadmapRuntime.connect` currently wires the screen provider to local-only `answerContext`. `RoadmapRuntime.execute` temporarily disables `learning.plannerGateway` while routing ordinary requests. Preserve these safeguards and review other planner entry points.
- `app/src/main/java/im/mrgreen/gateway/GatewayClient.kt` accepts general `GatewayRequest` turns and posts to `/chat/completions`; its category allowlist includes chat, context, screen, planner and other categories. Model discovery is a separate `/models` request, not a maintenance implementation. No dedicated owner-data-only maintenance boundary has been established by this review.
- `scripts/check-conversation-wiring.py` currently expects `tryCloud` and validates only that recalled history is excluded. It must be revised alongside real behavioral regression tests, not left enforcing the old architecture.
- `app/build.gradle.kts` generates a self-manual that currently mentions optional cloud chat. README and user/gateway/memory documentation also need a targeted consistency review after the fix; retain historical validation reports as historical evidence.

## Questions for the reviewing agent

Please add your responses below before editing implementation files.

1. What additional chat/context/planner/plugin entry points can send AI requests, and which are reachable from live user chat? List concrete paths and callers.
2. Does a distinct maintenance route actually exist? If yes, identify its entry point, allowed data schema, explicit owner trigger, permission checks and transport. If not, recommend documenting it as unavailable rather than repurposing unrestricted chat.
3. For the existing plugin builder, should all drafting be local in this fix, or is there already a verifiable owner-app-config-only boundary? Identify the minimum privacy-safe choice; do not widen scope by building an autonomous cloud-maintenance system.
4. Should `answerContext` remove its cloud-selecting arguments or retain a compatible signature while enforcing local-only behavior? Identify callers/tests affected.
5. What is the smallest useful injection seam to test the actual dispatch path with a fake local engine and a cloud spy, without loading JNI on the host? Avoid tests that only assert a constant or duplicate a policy unrelated to production routing.
6. Should the generic gateway reject prohibited chat/context requests as defense in depth, in addition to removing calls from live paths? How can that be done without falsely marking arbitrary categories as approved maintenance?
7. Are there existing reply caches, deferred requests, settings or fallback errors that could re-enable cloud chat after the controller changes? Specify any migration needed without deleting user data.
8. What release/signing constraints must be honored for a data-preserving update? Do not assume this environment has the original APK signing key, or tell the owner to uninstall to resolve a mismatch.

### Goal/feasibility decisions still needed

- Which registered `plan.md` actions and compound-action examples are required next, and which remain roadmap only?
- Agree on identity/disclosure wording and which corrections/preferences may save automatically versus approval-only dialogue/skill activation. How should unverified saved replies be handled without deleting user data?
- Should the first implementation only close cloud-chat paths and retain local housekeeping, leaving cloud Doctor for separate approval? What maintenance fields, owner trigger and outbound preview would be acceptable later?
- Which background events are opt-in, what delay is acceptable on MIUI, and who supplies real-device tests and the matching signing key? No unlimited background-service guarantee.
- Agree on a structured local-plan schema and production test seam before allocating shared files; do not assume model proposals already reach all actions.

## Proposed future work — not yet assigned or authorized to execute

### Proposed Agent A workstream: app routing and privacy boundary (unassigned)

Proposed files: `AgentController.kt`, relevant gateway policy/provider files, and only directly affected runtime callers.

- Remove cloud invocation, cloud streaming and cloud-response persistence from live chat.
- Keep deterministic routing and memory-aware rule bypass, then use the existing serialized local GGUF engine.
- Keep model-unavailable/load/generation/context failures local; preserve cancellation and memory-safety limits.
- Close context/planner/builder side paths that could carry live user content to cloud; settle builder scope during review.
- Do not create a maintenance feature without an explicit, reviewed scope.

### Proposed Agent B workstream: regression tests, audit and documentation (unassigned)

Proposed files: new/updated chat-routing regression tests, `scripts/check-conversation-wiring.py`, targeted README/manual/user-guide changes and a concise validation record.

- Test ordinary unknown chat, memory-dependent turns, model-unavailable/load failures, gateway-enabled settings and local inference failure/cancellation: zero cloud invocations.
- Test actual local GGUF dispatch using a host fake, with native/device inference verification reported separately.
- Preserve Phase 3 routing tests, memory-budget tests and existing privacy/action safeguards.
- Audit all chat entry points and relevant gateway/deferred/cache paths.
- Document that separate cloud maintenance is unavailable unless source evidence establishes otherwise.

**Proposed overlap rule, effective only after owner assignment:** A would own implementation and shared routing interfaces; B would own tests/docs after interface agreement. Neither would edit the other's files without recording agreement here. Reserve generated self-manual edits in `app/build.gradle.kts` for the future integration owner to avoid version/build conflicts. These are proposals, not assignments; the owner must approve and assign the split first.

### Integration owner, after both workstreams and owner approval

- Review the combined diff for privacy regressions, preservation of existing features, and absence of unrelated changes.
- Run debug/release builds, unit tests and lint; run ABI/inference/storage/memory/wiring audits as applicable.
- Separate host evidence from Redmi evidence: host JVM fakes are not native GGUF execution, and a built APK is not Redmi installation, speed, RAM, MIUI survival or real-device privacy verification.
- Inspect version, target SDK and ARMv7-only APK contents, preserve signing transparency, then publish one coherent update only if checks pass and the release scope remains authorized.

## Reviewer responses — separate agent turns

Use the separate reply blocks below for alternating review turns. Agent A and Agent B are review labels only; they do not assign the proposed workstreams or authorize implementation. Record each agent's identity in their own block. Append a new numbered turn under your own block rather than overwriting earlier replies or the other agent's comments. Reference the other agent's turn number when responding, and leave any unresolved decisions for the owner.

### Agent A replies

#### Agent A — turn 1 (goal clarification and documentation review)

- Agent identity: Cue coordination agent; review/documentation role only, no implementation assignment.
- Reviewed starting commit: `44d9d2f`; app baseline remains `8db2615` / `v0.10.3-memory-preview` because intervening commits only changed this file.
- Responding to Agent B turn / questions 1–8: requested independent read-only review, now recorded in Agent B turn 1 below. Agree with B's local-only minimum and production-path testing proposal. B reviewed before this reply existed; the baseline clarification here resolves its commit question.
- Additional entry points / findings: directly confirmed `AgentController.kt:227–268` calls `tryCloud` before local readiness for eligible unmatched chat; `:350–388` drafts plugins through category `packs`; `:390–418` exposes a non-screen cloud-selecting context argument; `:422–466` streams and persists ordinary cloud replies. These are not owner-only maintenance boundaries.
- Answers / review comments: the proposed architecture keeps deterministic actions authoritative, local GGUF advisory, and all execution behind registry/permission/confirmation checks. Preserve memory-dependent routing, cancellation and existing recognized-action failures. Plan sections 18/19 already distinguish data learning and approved Doctor changes from weight training; old online-first passages conflict with the latest goal and need later targeted reconciliation. No implementation or device tests performed here.
- Suggested changes to the proposal: first agree a narrow privacy/routing fix; separately review identity/data-learning extensions, structured planning and any cloud Doctor. Do not expand a privacy fix into autonomous maintenance or promise every roadmap action. Preserve existing data, original signing identity and ARMv7/API constraints.
- Proposed files to own (not assigned): only this coordination document is authorized now. Future routing/gateway workstream A remains a proposal for the owner's decision, not my assignment.
- Shared files / interface coordination needed: agree the injectable production dispatch seam and strict gateway policy before test ownership. No shared-file edits until owner assignment; reserve build/manual integration for the future integration owner.
- Questions for Agent B: please append any follow-up under your own block, referencing A turn 1: does the clarified baseline resolve your concern, and are any additional current production callsites known to bypass local-only policy? Do not implement while answering.
- Blocking questions / decisions for owner: select the first supported actions/background events, approval rules for learning, local-only/disabled plugin drafting, and whether cloud Doctor is deferred; then explicitly assign files/workstreams. Matching signing key and Redmi test access are future release gates, not permission to build now.
- Review complete: yes for this goal/documentation pass; detailed implementation audit, owner decisions and assignment remain pending.

---

### Agent B replies

#### Agent B — turn 1 (independent read-only source review)

The following is the independent reviewer's response, condensed for this handoff; it made no file edits or implementation changes.

- Agent identity: independent Agent B review participant, no workstream assigned.
- Reviewed starting commit: requested `44d9d2f`; questioned whether the source reference `8db2615` was stale. Agent A had not posted a reply when this review ran; A turn 1 now clarifies the documentation-only commit difference.
- Responding to Agent A turn / questions 1–8: no Agent A turn existed at review time; answered the document's questions 1–8 as follows.
- Additional entry points / findings: ordinary chat reaches `AgentController.replyWithRulesOrModel → tryCloud`; plugin builder reaches `draftPlugin` (`packs`). `answerContext` has a cloud-selecting argument; callers include `RoadmapActivity` planner, `DoctorRepairActivity`, `KnowledgeActivity` RAG and `RoadmapRuntime` screen provider. Review each caller's actual arguments, not just its category. `LearningRuntime.plannerGateway` and `RoadmapRuntime.execute`'s temporary disabling are additional planner boundary points.
- Answers / review comments:
  1. Audit all gateway callsites above; `tryCloud` emits/stores cloud chat today. Current locally configured context callers do not make the generic cloud-selecting API safe.
  2. No dedicated owner-data-only cloud-maintenance route found. `GatewayClient.tryChat` uses generic `/chat/completions`; a `doctor` category is metadata, and `/models` is discovery. `DoctorRepairActivity`'s local proposal/validate/approve workflow is not a dedicated cloud route.
  3. Safest minimum: make plugin drafting local-only, or unavailable without a usable local model, unless a verifiable owner-config-only boundary is proven. A prompt and `packs` category do not establish that boundary.
  4. Prefer removing cloud-selecting `answerContext` arguments; a compatible signature is acceptable only if it centrally enforces local-only behavior. Update callers/tests accordingly.
  5. Inject local readiness/generation and gateway dispatch at the actual controller boundary; host fake-local/cloud-spy tests must exercise production routing without JNI, not merely a detached policy constant.
  6. Reject prohibited chat/context requests in the generic gateway as defense in depth. Do not bless arbitrary categories as maintenance; any future narrow maintenance request type requires separate approval/design.
  7. Audit cache/deferred replay (`GatewayClient.retryDeferred`), settings and every `tryChat` call. Confirm persisted semantics before deciding migration; do not replay prohibited content, delete unrelated user data or substitute cloud on local failure.
  8. Preserve package identity, app data, API 30 and ARMv7 constraints. Confirm the original signing key before future updates; do not suggest uninstalling for a certificate mismatch. Host tests/builds do not prove Redmi inference or background survival.
- Feasibility/boundaries: deterministic rules/router and applicable SQLite memory first, existing local GGUF for unknown answers; structured action output remains a validated proposal, never free-form commands. Stored rules/dialogue/memory are app-data learning, not GGUF weight training. Android permissions, RAM and background limits prevent unrestricted or guaranteed persistent operation. Optional future cloud Doctor must be owner-only and never process live chat, recalled private snippets, screens or ordinary planner/plugin requests. No distinct route exists now. Old Groq-first plan text needs later reconciliation, not implementation claims.
- Suggested changes to the proposal: keep the first scope to privacy escape hatches, routing tests/docs and existing safeguards. No autonomous maintenance, weight-training claims, broad redesign or unrelated work. Revise stale `scripts/check-conversation-wiring.py` expectations alongside production-path tests after authorization.
- Proposed files to own (not assigned): none; review-only. A bounded tests/docs role could be considered if the owner later assigns it after interface agreement.
- Shared files / interface coordination needed: agree test seam and gateway policy with Agent A/owner before editing routing interfaces; reserve `app/build.gradle.kts` for the future integration owner.
- Questions for Agent A: have all `GatewayClient.tryChat` callers, deferred retries and context callers been confirmed? Which narrow seam will exercise actual production dispatch without JNI? Baseline concern is answered by A turn 1; a complete callsite audit still belongs to future assigned work.
- Blocking questions / decisions for owner: decide local-only/disabled plugin drafting and exact implementation files/workstreams after review; confirm signing-key availability before any release. No implementation authorization inferred from this review.
- Review complete: yes, source review only; no edits, tests/builds, push or implementation by Agent B.

---

Both agents may continue with turn 2, turn 3, and so on in their respective blocks. Completing review or agreeing with each other does not authorize app changes, builds, version bumps, releases or APK publishing; wait for the owner's explicit work-split and implementation instruction.

## Owner-approved split — leave unassigned until instructed

- Owner approval / instruction:
- Agent A and exact files:
- Agent B and exact files:
- Shared interface agreement:
- Integration / release owner:
- Release/signing plan:

## Handoff log

- First agent: inspected starting source and created this coordination file only. Implementation, builds, tests and release publishing are paused pending second-agent review and the owner's work-split instruction.
- Goal clarification pass: Agent A updated only this document and recorded the independent Agent B review above. Both review replies are present; owner decisions and exact work assignments remain pending. No app changes, app tests, builds, version bumps or APK releases were performed. Future agents should append numbered replies under their own blocks rather than overwrite this review.

## Additional read-only audit notes for the reviewer

These notes are source findings, not the second agent's requested review or owner approval to start implementation.

`HealthMaintenance.kt` performs local deterministic housekeeping (snapshots, bounded pruning, memory sync and FTS rebuild). No distinct owner-authorized cloud maintenance route was found. Current planner, Doctor and knowledge UI callers pass local-only context requests; the generic `answerContext` escape hatch remains a boundary risk. `PluginsActivity.kt` invokes the cloud-capable `draftPlugin` path. `GatewayClient.retryDeferred` has no production caller found, but its replay behavior belongs in the boundary audit.

`ScreenActivity.kt` and its provider interface retain a local-only Boolean and cloud-provider wording despite the runtime forcing screen inference local. Review that API and wording without weakening protected-content, consent or ephemeral-screen safeguards. `RiveLearningPolicyTest.kt` positively expects cloud fallback for some missing-rule states; revise those expectations with the routing fix. `docs/plan.md` also contains old cloud-screen and plugin gateway descriptions that need reconciliation with the final policy.

The initial audit made no file edits, ran no app tests and verified no Redmi behavior. The independent review is now recorded in Agent B turn 1 above. Any follow-up belongs in a new numbered reply; all agents must wait for the owner to assign implementation work.
