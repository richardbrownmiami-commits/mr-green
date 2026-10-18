package im.mrgreen.rive

import im.mrgreen.engine.GenerationProgress
import org.junit.Assert.*
import org.junit.Test

class RiveLearningPolicyTest {
    @Test fun savedRulesAndRuleFailuresKeepMissesLocal() {
        assertFalse(RiveLearningPolicy.allowCloudFallback(false, RuleStatus(1, 0, null)))
        assertFalse(RiveLearningPolicy.allowCloudFallback(false, RuleStatus(0, 1, null)))
        assertFalse(RiveLearningPolicy.allowCloudFallback(false, RuleStatus(0, 0, "invalid rules")))
        assertFalse(RiveLearningPolicy.allowCloudFallback(false, null))
        assertFalse(RiveLearningPolicy.allowCloudFallback(true, RuleStatus(0, 0, null)))
        assertTrue(RiveLearningPolicy.allowCloudFallback(false, RuleStatus(0, 0, null)))
    }

    @Test fun naturalCompletionIsEligible() {
        assertTrue(RiveLearningPolicy.completedLocalAnswer(true, false, false, GenerationProgress("generate", 20, 128)))
    }
    @Test fun tokenBudgetExhaustionIsNotACompleteAnswer() {
        assertFalse(RiveLearningPolicy.completedLocalAnswer(true, false, false, GenerationProgress("generate", 128, 128)))
    }
    @Test fun cancellationErrorsAndNoOutputAreNotEligible() {
        val good = GenerationProgress("generate", 20, 128)
        assertFalse(RiveLearningPolicy.completedLocalAnswer(false, false, false, good))
        assertFalse(RiveLearningPolicy.completedLocalAnswer(true, true, false, good))
        assertFalse(RiveLearningPolicy.completedLocalAnswer(true, false, true, good))
        assertFalse(RiveLearningPolicy.completedLocalAnswer(true, false, false, GenerationProgress("generate", 0, 128)))
        assertFalse(RiveLearningPolicy.completedLocalAnswer(true, false, false, GenerationProgress("prompt", 20, 128)))
        assertFalse(RiveLearningPolicy.completedLocalAnswer(true, false, false, GenerationProgress()))
    }
}
