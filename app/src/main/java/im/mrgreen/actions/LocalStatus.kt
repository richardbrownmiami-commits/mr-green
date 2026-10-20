package im.mrgreen.actions

import im.mrgreen.identity.IdentityStore

/** Whole-request aliases: never rewrites note text, compound actions or arbitrary substrings. */
internal object LocalStatusRequests {
    fun canonical(request: String): String = when (IdentityStore.normalize(request)) {
        "battery", "battery percentage", "what is my battery", "what is the battery level", "tell me battery", "tell me the battery" -> "battery level"
        "time", "tell time", "tell me time" -> "what time is it"
        "storage", "how much space is left", "how much storage is left", "check storage", "tell me storage" -> "storage status"
        "status", "system", "system status", "phone status", "device status", "check system", "check system status", "how is my phone" -> "system status"
        else -> request
    }

    fun isJobStatus(request: String): Boolean = IdentityStore.normalize(request) in setOf(
        "job status", "task status", "work status", "complete job", "comple job", "is the job complete",
        "is my job complete", "is the task complete", "did you finish", "have you finished", "is it done",
        "did you complete the job", "has the job finished"
    )
}

/** In-memory typed evidence only, never reconstructed from chat, a rule or a model answer. */
internal class LastActionStatus {
    @Volatile private var last: PlanExecutionResult? = null

    fun record(result: PlanExecutionResult) { last = result }

    fun reply(): String {
        val result = last ?: return "No registered-action result is recorded in this app session. I can’t confirm a job completed. Tell me which job you mean."
        val label = when (result.outcome) {
            PlanOutcome.VERIFIED -> "verified result"
            PlanOutcome.HANDED_OFF -> "handed off; external completion is unverified"
            PlanOutcome.FAILED -> "failed; completion is not confirmed"
            PlanOutcome.PENDING_CONFIRMATION -> "pending confirmation; not completed"
            PlanOutcome.CANCELLED -> "cancelled; remaining work was not completed"
        }
        return "Last recorded registered action in this app session — $label: ${result.message} This does not verify any other or external job."
    }
}
