package im.mrgreen.rive

import im.mrgreen.engine.GenerationProgress

/** A successful native return alone can also mean the token budget was exhausted. */
object RiveLearningPolicy {
    fun allowCloudFallback(autoLearning: Boolean, rules: RuleStatus?): Boolean =
        !autoLearning && rules != null && rules.warning == null && rules.imported == 0 && rules.learned == 0

    fun completedLocalAnswer(ok: Boolean, cancelled: Boolean, errorPresent: Boolean,
                             progress: GenerationProgress): Boolean =
        ok && !cancelled && !errorPresent && progress.stage == "generate" &&
            progress.completed > 0 && progress.total > 0 && progress.completed < progress.total
}
