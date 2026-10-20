package im.mrgreen.rive

import org.junit.Assert.*
import org.junit.Test

class RuleQuestionNormalizerTest {
    @Test fun approvedEnglishShorthandAndSubjectTyposAreWholeQuestions() {
        val cases = mapOf(
            "wht is engien?" to "what is engine",
            "what is enigne" to "what is engine",
            "waht is modle" to "what is model",
            "whats language model" to "what is language model",
            "what is englsih" to "what is english",
            "WHT is ur name?!" to "what is your name",
            "who r u" to "who are you", "hw r u" to "how are you"
        )
        cases.forEach { (input, expected) ->
            assertEquals(input, expected, RuleQuestionNormalizer.candidate(input)?.question)
            assertEquals(input, RuleQuestionNormalizer.Form.ENGLISH, RuleQuestionNormalizer.candidate(input)?.form)
        }
    }

    @Test fun approvedHinglishFormsRephraseWithoutGeneralTranslation() {
        for (input in listOf("engine kya hai", "engien kya h?", "enigne kyaa haii", "engine kya he")) {
            assertEquals(input, RuleQuestionNormalizer.Candidate("what is engine", RuleQuestionNormalizer.Form.HINGLISH),
                RuleQuestionNormalizer.candidate(input))
        }
        assertEquals("what is memory", RuleQuestionNormalizer.candidate("memroy kya hai")?.question)
        assertEquals("what is your name", RuleQuestionNormalizer.candidate("aapka naam kya hai")?.question)
        assertEquals("who are you", RuleQuestionNormalizer.candidate("tum kaun ho")?.question)
        assertEquals("how are you", RuleQuestionNormalizer.candidate("tum kaise ho")?.question)
    }

    @Test fun ambiguousUnknownNegatedAndActionTextIsNotGuessed() {
        for (input in listOf(
            "hwo r u", "what is form", "what is causal", "what is Sam", "sam kya hai",
            "what is engin", "engine kya", "engine hai", "m kya h", "kya hai",
            "engine nahi kya hai", "engine kya nahi hai", "engine kya hai nahi",
            "what is not engine", "wht is engine not", "why is engine not working",
            "open settings", "opne settings", "settings kholo", "alarm lagao", "call mom",
            "send engine kya hai", "engine kya hai and open settings", "what is engine and model",
            "engine 2 kya hai", "what is 2+2", "what is 2²", "what is version 2.1",
            "what is engine https://example.com", "what is <call>engine</call>", "engine: kya hai",
            "what is engien's", "इंजन क्या है", "x".repeat(1025)
        )) assertNull(input, RuleQuestionNormalizer.candidate(input))
    }

    @Test fun noCorrectionForAlreadyCanonicalOrUnsupportedQuestion() {
        assertNull(RuleQuestionNormalizer.candidate("what is engine"))
        assertNull(RuleQuestionNormalizer.candidate("what is photosynthesis"))
        assertNull(RuleQuestionNormalizer.candidate("please tell me something about engines"))
    }

    @Test fun normalizationNeedsAnExistingUniqueLiteralRule() {
        val lookup = RuleQuestionLookup(listOf("what is engine"))
        assertEquals(RuleQuestionLookup.Match("what is engine", false, true), lookup.match("engien kya h"))
        assertEquals(RuleQuestionLookup.Match("what is engine", false, false), lookup.match("what is engine"))
        assertNull(RuleQuestionLookup(emptyList()).match("engine kya hai"))
        assertNull(RuleQuestionLookup(listOf("what is model")).match("engine kya hai"))
        assertNull(RuleQuestionLookup(listOf("what is engine", "What is engine")).match("engine kya hai"))
    }

    @Test fun originalRuleWinsAndPreprocessingDoesNotChangeLearningEquivalence() {
        val lookup = RuleQuestionLookup(listOf("engine kya hai", "what is engine"))
        assertEquals(RuleQuestionLookup.Match("engine kya hai", false, false), lookup.match("engine kya hai"))
        val onlyEnglish = RuleQuestionLookup(listOf("what is engine"))
        assertFalse(onlyEnglish.hasEquivalent("engine kya hai"))
        assertTrue(onlyEnglish.hasEquivalent("what is engine"))
    }

    @Test fun ambiguousOriginalAliasesCannotBeBypassedByRephrasing() {
        val lookup = RuleQuestionLookup(listOf("engine kya hai", "Engine kya hai", "what is engine"))
        assertNull(lookup.match("engine kya hai"))
    }

    @Test fun originalMatcherSafetyAndLegacyAliasesRemainIntact() {
        val lookup = RuleQuestionLookup(listOf("what is the capital city of france"))
        assertTrue(lookup.match("what is the captial city of france")!!.typoCorrected)
        assertNull(lookup.match("waht is teh capital city of france"))
        assertNull(lookup.match("what is the capitol city of france"))
        assertNull(QuestionMatcher(listOf("what is engine")).matchLiteral("waht is engine"))
    }

    @Test fun normalizedCandidateCannotUseATypoOnlyRuleOrMaskAnAliasCollision() {
        val typo = "waht is language model"
        val canonical = "what is language model"
        val legacy = RuleQuestionLookup(listOf(typo))
        assertNotNull(legacy.match(canonical)) // Existing legacy matching remains compatible.
        assertNull(legacy.match("language model kya hai")) // New candidate needs a literal rule.
        assertNull(QuestionMatcher(listOf(typo)).matchLiteral(canonical))
        val collision = RuleQuestionLookup(listOf(typo, canonical))
        assertNull(collision.match("language model kya hai"))
        assertNull(collision.match(canonical))
    }
}
