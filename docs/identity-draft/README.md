# Mr Green: identity and answer dataset — review draft

This draft describes who Mr Green is, what jobs it can honestly offer, how to answer questions, and how to report progress. **It is not connected to the app.** No app behavior, build settings or APK has changed.

## Start with these three facts

- **Required direction:** deterministic rules and local data first, then one local GGUF helper. **Cloud must never answer live chat.**
- **Current gap:** the preview still contains a conditional live-chat cloud path. Draft rules cannot fix that. Plugin drafting is also cloud-capable, and the generic context API still permits a cloud-selecting option.
- **Not implemented:** a full autonomous GGUF tool loop and a dedicated cloud Doctor route. Local reviewed plan/repair proposals are different features.

## Review files

- [dataset.json](dataset.json): provisional identity, 20 job records with how-to/evidence/limits, 14 proposed status states, and 36 Q&A examples. These are review fixtures and response contracts, not training weights, a runtime schema or an installable `.redpack`.
- [RULES.md](RULES.md): 13 proposed response rules, answer-QA checklist, pass/fail criteria and future regression cases.
- [OWNER_QUESTIONS.md](OWNER_QUESTIONS.md): persona, language, answer depth, job priorities, progress, memory/learning, voice and APK-experience choices.

Every job uses an explicit label: `implemented_preview`, `implemented_limited`, `planned_not_implemented`, or `current_policy_gap`. Source support does not imply the target phone passed acceptance. Availability is about underlying capability; proposed example wording and this dataset itself are not deployed.

## Source of truth and conflicts

The reviewed source is release **0.10.3-memory-preview**, commit `8db2615`, with documentation-only commits through `2c98849`. Mr Green is separate from SkillBot.

The root [plan.md](../../plan.md) is the historical roadmap. Its opening note still says later phases are absent and much of it still specifies Groq-first behavior. Current code contains later integrated preview modules, as described in [implementation status](../implementation-status.md), but that does not make every roadmap promise complete or phone-tested. The newer local-only direction and audit gaps are recorded in [coordination notes](../../AGENT_COORDINATION.md). This draft does not rewrite historical plan text or silently treat it as current behavior.

For this draft: the owner's current local-only requirement defines desired policy; actual source defines implemented capabilities; preview/device limits qualify all claims. Any remaining conflict must stay visible rather than be solved by inventing capability.

Key implementation references:

- [AgentController](../../app/src/main/java/im/mrgreen/core/AgentController.kt): deterministic-first chat, Rive and bounded recall, `tryCloud`, local inference, plugin draft and context routes.
- [ActionRuntime](../../app/src/main/java/im/mrgreen/actions/ActionRuntime.kt): registry, strict argument validation, clarifications, sequential actions and verified-versus-handoff outcomes.
- [RoadmapRuntime](../../app/src/main/java/im/mrgreen/core/RoadmapRuntime.kt): dedicated integrations and reviewed local planner/screen providers. Ordinary execution does not automatically invoke the model planner.
- [ConversationPrompt](../../app/src/main/java/im/mrgreen/core/ConversationPrompt.kt): at most two historical snippets and untrusted-history handling.
- [GenerationProgress](../../app/src/main/java/im/mrgreen/engine/GenerationProgress.kt): observed prompt/token counts and elapsed time, not a made-up task percentage.
- [DoctorRepairActivity](../../app/src/main/java/im/mrgreen/ui/DoctorRepairActivity.kt): local typed proposal review, not a dedicated cloud service.

## What “done” means

A saved note needs exact file and DB readback. An opened Clock screen is a **handoff**, not a saved alarm. Opening Wi-Fi settings is not turning Wi-Fi on. Active audio does not establish which song is playing. Partial failures and cancellations must preserve the truth about earlier completed work.

Status examples are a **proposed display vocabulary**, not newly implemented enums or hooks. The existing executor exposes `VERIFIED`, `HANDED_OFF`, `FAILED`, `PENDING_CONFIRMATION` and `CANCELLED`; the draft's finer labels are review-only mappings.

All example people/content are generic or synthetic. Dynamic placeholders require observed values and must not be displayed literally. No private owner contact information is included.

## Review and next step

Read the identity, job limitations and Q&A examples, then answer the owner questions. Revise and approve the draft before requesting any implementation.

This task only adds files under `docs/identity-draft/`. It does not wire assets/prompts into the APK, change behavior, migrate data, run Android tests, build/sign an APK, bump versions or publish a release. A documentation branch is for review only. Future production-routing tests and Redmi checks remain pending.

Static draft checks passed: JSON parsing, unique record IDs, valid availability/job/rule/evidence references, existing relative Markdown targets, complete newline-terminated files and no email addresses. Content was reviewed against source and an independent read-only routing audit. These checks do not establish model-answer quality, runtime privacy enforcement or Android behavior. All live-app answer-QA cases remain unexecuted.
