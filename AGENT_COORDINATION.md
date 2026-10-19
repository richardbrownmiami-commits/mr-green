# Local-only chat: agent review and work split

## Status: paused for review

Owner instruction: commit this coordination file so a second agent can read it, add suggestions and questions, and then the owner can divide the work. **Do not implement, change app behavior, bump versions, build a new release, or publish an APK until that review has happened and the owner asks to split the work.** This documentation-only commit is authorized; implementation/release pushes are paused.

Repository: https://github.com/richardbrownmiami-commits/mr-green only. Do not modify SkillBot or any other repository.

Starting app source: `8db2615`, release `v0.10.3-memory-preview`. No app implementation changes have been made in this pass. The existing release still has the cloud-chat behavior described below; this file is not a privacy fix.

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

## Proposed future work — not yet assigned or authorized to execute

### Workstream A: app routing and privacy boundary

Proposed files: `AgentController.kt`, relevant gateway policy/provider files, and only directly affected runtime callers.

- Remove cloud invocation, cloud streaming and cloud-response persistence from live chat.
- Keep deterministic routing and memory-aware rule bypass, then use the existing serialized local GGUF engine.
- Keep model-unavailable/load/generation/context failures local; preserve cancellation and memory-safety limits.
- Close context/planner/builder side paths that could carry live user content to cloud; settle builder scope during review.
- Do not create a maintenance feature without an explicit, reviewed scope.

### Workstream B: regression tests, audit and documentation

Proposed files: new/updated chat-routing regression tests, `scripts/check-conversation-wiring.py`, targeted README/manual/user-guide changes and a concise validation record.

- Test ordinary unknown chat, memory-dependent turns, model-unavailable/load failures, gateway-enabled settings and local inference failure/cancellation: zero cloud invocations.
- Test actual local GGUF dispatch using a host fake, with native/device inference verification reported separately.
- Preserve Phase 3 routing tests, memory-budget tests and existing privacy/action safeguards.
- Audit all chat entry points and relevant gateway/deferred/cache paths.
- Document that separate cloud maintenance is unavailable unless source evidence establishes otherwise.

**Overlap rule:** A owns implementation and shared routing interfaces; B owns tests/docs only after both agree on the interface. Neither edits the other's files without recording agreement here. The generated self-manual lives in `app/build.gradle.kts`; reserve edits to that file for the integration owner to avoid version/build conflicts. The owner must assign agents and approve the split first.

### Integration owner, after both workstreams and owner approval

- Review the combined diff for privacy regressions, preservation of existing features, and absence of unrelated changes.
- Run debug/release builds, unit tests and lint; run ABI/inference/storage/memory/wiring audits as applicable.
- Separate host evidence from Redmi evidence: host JVM fakes are not native GGUF execution, and a built APK is not Redmi installation, speed, RAM, MIUI survival or real-device privacy verification.
- Inspect version, target SDK and ARMv7-only APK contents, preserve signing transparency, then publish one coherent update only if checks pass and the release scope remains authorized.

## Reviewer response — second agent to fill in

- Reviewed starting commit:
- Additional entry points / findings:
- Answers to questions 1–8:
- Suggested changes to the proposal:
- Proposed files owned by reviewer:
- Files requiring coordination:
- Blocking questions for owner:
- Review complete: no / yes

## Owner-approved split — leave unassigned until instructed

- Owner approval / instruction:
- Agent A and exact files:
- Agent B and exact files:
- Shared interface agreement:
- Integration / release owner:
- Release/signing plan:

## Handoff log

- First agent: inspected starting source and created this coordination file only. Implementation, builds, tests and release publishing are paused pending second-agent review and the owner's work-split instruction.

## Additional read-only audit notes for the reviewer

These notes are source findings, not the second agent's requested review or owner approval to start implementation.

`HealthMaintenance.kt` performs local deterministic housekeeping (snapshots, bounded pruning, memory sync and FTS rebuild). No distinct owner-authorized cloud maintenance route was found. Current planner, Doctor and knowledge UI callers pass local-only context requests; the generic `answerContext` escape hatch remains a boundary risk. `PluginsActivity.kt` invokes the cloud-capable `draftPlugin` path. `GatewayClient.retryDeferred` has no production caller found, but its replay behavior belongs in the boundary audit.

`ScreenActivity.kt` and its provider interface retain a local-only Boolean and cloud-provider wording despite the runtime forcing screen inference local. Review that API and wording without weakening protected-content, consent or ephemeral-screen safeguards. `RiveLearningPolicyTest.kt` positively expects cloud fallback for some missing-rule states; revise those expectations with the routing fix. `docs/plan.md` also contains old cloud-screen and plugin gateway descriptions that need reconciliation with the final policy.

The initial audit made no file edits, ran no app tests and verified no Redmi behavior. The independent reviewer should record their own findings in the response section above, then wait for the owner to assign the work.
