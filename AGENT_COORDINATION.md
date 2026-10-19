# Mr Green: offline Android agent goal, solo plan and status

## Status: solo planning; implementation and release remain paused

Latest owner instruction: the owner's external second agent has not responded, so the current lead should stop waiting for that response and discuss a single-agent plan. **The Agent B review already recorded below is an internal independent review, not the external second agent's response.** Do not represent it as external participation or approval. This instruction supersedes waiting for an external review/work split, but authorizes only this plan/status update: no app code changes, tests/builds, version bumps or APK publication in this pass.

Repository: https://github.com/richardbrownmiami-commits/mr-green only. Do not modify SkillBot or any other repository.

Starting app source: `8db2615`, release `v0.10.3-memory-preview`. No app implementation changes have been made in this pass. The existing release still has the cloud-chat behavior described below; this file is not a privacy fix.

Starting document commit for this update: `32f9196`. Earlier coordination commits changed this document, not app source. The current lead owns solo planning; neither the external agent nor internal Agent B has an implementation assignment. Implementation requires a subsequent explicit owner instruction; release/build authorization is not inferred from this discussion. Historical review replies below are retained as history, not current work-split instructions.

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

## Proposed single-agent sequence — discussion, not implementation authorization

The current lead would carry each step through implementation, regression checks and integration in order after owner authorization; no external-agent response or two-agent file split is required. Finish the narrow live-chat privacy boundary first. Later capability work must not delay or weaken that boundary. Keep one integrated app and one serialized local inference owner, preserve existing privacy/permission/action safeguards, and never touch SkillBot.

For **every step**, the future verification order is: (a) relevant production-path host tests, regression audits and lint; (b) an authorized build and package inspection for Android 11/API 30 compatibility and ARMv7-only native contents; (c) owner Redmi checks using a data-preserving update with the matching signing identity. Record each gate as pending/pass/fail with evidence. Host fakes are not native GGUF execution, package inspection is not installation, and neither proves Redmi speed, RAM, privacy or MIUI survival. No such tests, builds or phone checks are being performed in this documentation-only pass. No APK publication follows automatically from passing a gate.

### 1. Remove all cloud from live chat

Route existing deterministic actions, local rules and applicable bounded local memory first, then the local GGUF. Never use cloud fallback, including on missing/loading/failed local models, generation errors or cancellation. Audit chat, context, planner, plugin-builder, gateway settings, cached/deferred requests and replay paths. Make live drafting local-only or honestly unavailable; a category name is not a maintenance boundary. Preserve recognized-action failures and memory-aware routing, and update stale wiring tests/docs alongside the eventual fix.

**Verification gates:** host tests must exercise actual production dispatch with fake local inference and a cloud spy: zero cloud calls for ordinary chat, memory turns, gateway-enabled settings, all local failure states and related entry points. Preserve Phase 3 routing/cancellation tests and audit deferred replay. After those pass, inspect the API 30/ARMv7 package; then have the owner check ordinary and memory-dependent chat on Redmi, with internet enabled and disabled, including missing-model behavior and suitable network evidence of no live-chat cloud AI traffic. Host results alone do not establish device privacy.

### 2. Apply a truthful, consistent Mr Green identity and capability prompt

Use the app-controlled identity above consistently across rules/templates and local GGUF prompts. Describe only registered capabilities and available permissions/data; distinguish unavailable actions from completed ones. Never invent device state, execution success or a human identity. Keep safety/identity core rules outside model or Doctor self-rewriting.

**Verification gates:** host prompt/template and response-path tests cover identity, capability honesty, unavailable permissions/tools and evidence-backed success. Then inspect the API 30/ARMv7 package and generated manual for consistency; owner Redmi checks verify the displayed identity and truthful failure/success wording in rules and real local inference.

### 3. Let GGUF draft answers or typed task proposals; keep execution app-controlled

GGUF output is either an answer draft or a typed proposal using registered actions, never executable authority. The app validates schema, names, arguments, permissions, risk and dependencies, asks for missing details/required approvals, executes sequentially with cancellation, and verifies outcomes. Unknown/unsupported actions must be rejected honestly; importing a model does not add Android capabilities.

**Verification gates:** host production-path tests cover malformed/unknown tools, invalid arguments, denied permissions, outbound/destructive confirmations, cancellation, sequencing and verified versus failed outcomes. Then inspect the API 30/ARMv7 package; owner Redmi checks exercise an agreed small set of registered actions through real GGUF proposals, with permission refusal and failure cases. Host typed fixtures do not prove model/tool performance on the phone.

### 4. Preserve bounded local memory and approved data/dialogue learning

Retain existing memory and user data with bounded retrieval, provenance, privacy controls and undo. Store approved preferences, corrections, dialogue/templates and reusable flows over existing tools; distinguish a saved proposal from activation. Validate/test and obtain approval before activating reusable dialogue or action flows. Do not promote unverified generated replies to trusted facts merely through repetition. Do not claim chat/database updates train GGUF weights; no on-device GGUF weight-training pipeline is established or included.

**Verification gates:** host tests cover retrieval budgets, provenance, sensitive-data boundaries, approval/activation separation, rollback and data-preserving migration. Then inspect the API 30/ARMv7 package; owner Redmi checks cover recall, correction, approved activation, deletion/undo, and real memory/RAM behavior. Host storage tests do not prove phone resource use or inference quality.

### 5. Add opt-in background events within Android/MIUI limits

Choose supported events with the owner before implementation. Use Android-compatible scheduled work or an appropriate visible foreground service, opt-in permissions, quiet hours and battery/RAM limits. User work takes priority; keep serialized inference and cancellation. No continuous screen reading/model loop, unrestricted control, guaranteed exact timing or always-on survival claim.

**Verification gates:** host scheduler/event tests cover opt-in/out, permission denial/revocation, quiet hours, cancellation, deduplication and resource bounds. Then inspect the API 30/ARMv7 package and service/permission declarations; owner Redmi checks cover screen-off operation, MIUI battery restrictions, reboot, delayed/killed work and opt-out. Report observed delays and failures; host tests cannot establish MIUI survival.

### 6. Keep any cloud Doctor separate, optional and owner-visible

This is a separately scoped maintenance proposal, not a feature of 0.10.3 and not required for local chat. Keep it unavailable unless the owner separately approves its exact trigger, minimized app-data/configuration schema, outbound preview, transport and review/undo rules. Never include live questions, conversation history, private recalled memory or screen content. Return owner-facing reports or validated typed change proposals, not live replies. No arbitrary DB access, silent code changes, permission escalation or rewriting of core safety/identity rules; native changes require a separately authorized developer build.

**Verification gates:** if separately authorized, host tests must prove the owner trigger/preview/consent boundary, schema rejection of private/live-chat data, isolation from all live-chat paths, and validation/approval/undo. Then inspect the API 30/ARMv7 package; owner Redmi checks verify only the explicitly previewed maintenance payload leaves after approval, cancel/deny sends nothing, and live chat stays local. Until implemented and verified, label cloud Doctor unavailable, not working.

### Integration and release gate

The solo lead must review each future combined diff for privacy regressions and unrelated changes, preserving existing features, package identity, app data and signing transparency. Confirm the original signing key before a data-preserving update; never recommend uninstalling to bypass a signature mismatch. Any later release requires explicit scope/authorization and clear host-versus-Redmi evidence. No code, build, version or APK changes are part of this update.

## Reviewer responses — separate agent turns

Historical review record: Agent A and Agent B are internal review labels only, not the owner's external second agent. The internal independent Agent B reply does not mean that external agent responded. These replies are preserved unchanged below; their earlier requests to wait for a work split are superseded by the solo-planning status above, not by authorization to implement.

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

The following is the internal independent reviewer's response, condensed for this handoff; it made no file edits or implementation changes. It is not a response from the owner's external second agent.

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

The preceding reviews are historical input to the solo plan. Further external-agent replies are not a prerequisite for planning. Completing review does not authorize app changes, builds, version bumps, releases or APK publishing; a subsequent explicit implementation instruction is still required.

## Current ownership and pending owner decisions

- Owner instruction: proceed with a solo plan discussion rather than waiting for the external second agent.
- Current lead: planning/status only; this update touches `AGENT_COORDINATION.md` alone.
- External second agent: no response; no assignment inferred.
- Internal Agent B: independent review already recorded; no implementation assignment.
- Implementation approval and exact files: pending a subsequent owner instruction, starting with step 1's narrow local-only boundary.
- Later scope decisions: first supported action proposals/background events, learning approval rules, and whether to separately scope cloud Doctor.
- Build/release/signing and owner Redmi access: future gates; no build or publish authorization in this pass.

## Handoff log

- Solo-planning update from document baseline `32f9196`: owner reports the external second agent has not responded and asks the current lead to take the plan forward alone. Clarified that internal Agent B is not that external agent; recorded the ordered six-step proposal and test/package/owner-Redmi gates. Updated only this file. No app code changes, app tests, builds, version bumps, phone verification or APK publishing performed; 0.10.3 still has ordinary cloud chat and no distinct cloud Doctor.
- First agent: inspected starting source and created this coordination file only. Implementation, builds, tests and release publishing are paused pending second-agent review and the owner's work-split instruction.
- Goal clarification pass: Agent A updated only this document and recorded the independent Agent B review above. Both review replies are present; owner decisions and exact work assignments remain pending. No app changes, app tests, builds, version bumps or APK releases were performed. Future agents should append numbered replies under their own blocks rather than overwrite this review.

## Additional read-only audit notes for the reviewer

These notes are source findings, not the second agent's requested review or owner approval to start implementation.

`HealthMaintenance.kt` performs local deterministic housekeeping (snapshots, bounded pruning, memory sync and FTS rebuild). No distinct owner-authorized cloud maintenance route was found. Current planner, Doctor and knowledge UI callers pass local-only context requests; the generic `answerContext` escape hatch remains a boundary risk. `PluginsActivity.kt` invokes the cloud-capable `draftPlugin` path. `GatewayClient.retryDeferred` has no production caller found, but its replay behavior belongs in the boundary audit.

`ScreenActivity.kt` and its provider interface retain a local-only Boolean and cloud-provider wording despite the runtime forcing screen inference local. Review that API and wording without weakening protected-content, consent or ephemeral-screen safeguards. `RiveLearningPolicyTest.kt` positively expects cloud fallback for some missing-rule states; revise those expectations with the routing fix. `docs/plan.md` also contains old cloud-screen and plugin gateway descriptions that need reconciliation with the final policy.

The initial audit made no file edits, ran no app tests and verified no Redmi behavior. The internal independent review is recorded in Agent B turn 1 above, not as the external agent's response. Current status is solo planning without waiting for that external response; implementation still requires owner authorization.
