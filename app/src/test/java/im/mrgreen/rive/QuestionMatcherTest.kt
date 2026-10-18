package im.mrgreen.rive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QuestionMatcherTest {
    @Test fun caseWhitespaceAndTrailingMixedPunctuationAreDecorative() {
        val matcher = QuestionMatcher(listOf("What is photosynthesis"))
        for (query in listOf("  WHAT   IS photosynthesis?!... ", "what is photosynthesis!?", "What\tis\nphotosynthesis.")) {
            assertEquals(query, QuestionMatcher.Match("What is photosynthesis", false), matcher.match(query))
        }
    }

    @Test fun balancedStraightAndCurlyDoubleOuterQuotesAreSupported() {
        val matcher = QuestionMatcher(listOf("What is photosynthesis"))
        assertEquals(QuestionMatcher.Match("What is photosynthesis", false), matcher.match("\"What is photosynthesis?\""))
        assertEquals(QuestionMatcher.Match("What is photosynthesis", false), matcher.match("“What is photosynthesis?”"))
        assertNull(matcher.match("\"What is photosynthesis"))
        assertNull(matcher.match("“What is photosynthesis?“"))
        assertNull(matcher.match("”What is photosynthesis?”"))
        assertNull(matcher.match("“What is photosynthesis?\""))
    }

    @Test fun greetingAndCourtesyCommaAreDecoration() {
        val matcher = QuestionMatcher(listOf("Please explain photosynthesis"))
        assertEquals(QuestionMatcher.Match("Please explain photosynthesis", false), matcher.match("Please, explain photosynthesis?"))
        val greeting = QuestionMatcher(listOf("Hi explain photosynthesis"))
        assertEquals(QuestionMatcher.Match("Hi explain photosynthesis", false), greeting.match("Hi, explain photosynthesis!"))
    }

    @Test fun contractionsNormalizeWithStraightAndSmartApostrophesWithoutLosingNegation() {
        val matcher = QuestionMatcher(listOf("Why can't plants grow without light"))
        assertEquals(QuestionMatcher.Match("Why can't plants grow without light", false), matcher.match("Why can not plants grow without light?"))
        assertEquals(QuestionMatcher.Match("Why can't plants grow without light", false), matcher.match("Why can’t plants grow without light?"))
        assertNull(matcher.match("Why can plants grow without light?"))
        assertNull(QuestionMatcher(listOf("Why can plants grow without light")).match("Why can't plants grow without light?"))
    }

    @Test fun cannotAndCanNotAreEquivalentButCollidingAliasesAreRefused() {
        val single = QuestionMatcher(listOf("Why cannot plants grow without light"))
        assertEquals(QuestionMatcher.Match("Why cannot plants grow without light", false), single.match("Why can not plants grow without light?"))
        assertEquals(QuestionMatcher.Match("Why cannot plants grow without light", false), single.match("Why cannot plants grow without light?"))
        val collision = QuestionMatcher(listOf("Why cannot plants grow without light", "Why can not plants grow without light"))
        assertTrue(collision.hasEquivalent("Why cannot plants grow without light"))
        assertNull(collision.match("Why cannot plants grow without light"))
        assertNull(collision.match("Why can not plants grow without light"))
    }

    @Test fun oneExplicitlyApprovedTypoCanMatchInALongQuestion() {
        val matcher = QuestionMatcher(listOf("What is the capital city of France"))
        assertEquals(QuestionMatcher.Match("What is the capital city of France", true), matcher.match("Waht is the capital city of France?"))
        assertEquals(QuestionMatcher.Match("What is the capital city of France", true), matcher.match("What is teh capital city of France?"))
        assertEquals(QuestionMatcher.Match("What is the capital city of France", true), matcher.match("What is the captial city of France?"))
    }

    @Test fun shortQueriesAndTwoTyposAreNotCorrected() {
        assertNull(QuestionMatcher(listOf("what")).match("waht"))
        assertNull(QuestionMatcher(listOf("What is the capital city of France")).match("Waht is teh capital city of France?"))
    }

    @Test fun onlyOneCandidateMayResolveAnApprovedTypo() {
        val matcher = QuestionMatcher(listOf("What is the capital city of France", "What is the capital city of Spain"))
        assertEquals(QuestionMatcher.Match("What is the capital city of France", true), matcher.match("Waht is the capital city of France?"))
        val duplicate = QuestionMatcher(listOf("What is the capital city of France", "what is the capital city of france"))
        assertNull(duplicate.match("Waht is the capital city of France?"))
    }

    @Test fun legacyMisspellingsGetReadOnlyAliasesAndConflictingAnswersAreNotChosen() {
        val original = "what is the captial city of france"
        val corrected = "what is the capital city of france"
        val matcher = QuestionMatcher(listOf(original))
        assertEquals(QuestionMatcher.Match(original, true), matcher.match(corrected))
        assertTrue(matcher.hasEquivalent(corrected))
        val collision = QuestionMatcher(listOf(original, corrected))
        assertNull(collision.match(original))
        assertNull(collision.match(corrected))
    }

    @Test fun meaningfulPunctuationNumbersAndAmbiguousApostrophesFallThrough() {
        val matcher = QuestionMatcher(listOf("What is version 2.1 for item 3"))
        for (query in listOf(
            "What is version 2.1 for item 3", "What is version 2/1 for item 3", "What is version 2+1 for item 3",
            "What is version 2.1.0 for item 3", "What is version 2 for item 3? https://example.com",
            "What is \"version 2.1\" for item 3", "What is version 2.1 for item 4"
        )) assertNull("Must fall through: $query", matcher.match(query))

        val words = QuestionMatcher(listOf("What is the answer to the question"))
        for (query in listOf("What is the answer to Sam's question", "What is the answer to Sam’s question", "What is the answer to he'd question")) {
            assertNull("Must fall through: $query", words.match(query))
        }
        assertNull(QuestionMatcher(listOf("what is 22")).match("what is 2²"))
        assertNull(QuestionMatcher(listOf("what is iv")).match("what is Ⅳ"))
        assertNull(QuestionMatcher(listOf("no more money")).match("no, more money"))
        assertNull(QuestionMatcher(listOf("what is the answer")).match("what: is the answer"))
        assertNull(QuestionMatcher(listOf("what is the capital")).match("what's the capital"))
        assertNull(QuestionMatcher(listOf("i would explain the answer")).match("I'd explain the answer"))
    }

    @Test fun realWordPairsReorderingParaphrasesUnknownNounsAndChangedFactsDoNotFuzzyMatch() {
        val matcher = QuestionMatcher(listOf("What form is needed for casual France"))
        for (query in listOf(
            "What from is needed for casual France", "What form is needed for causal France",
            "What form is needed for casual Prance", "Form what is needed for casual France",
            "Which form do I need for casual France", "What form is needed for casual Neptune",
            "What form is not needed for casual France", "What form is needed for casual France in 2025"
        )) assertNull("Must fall through: $query", matcher.match(query))

        assertFalse(matcher.hasEquivalent("What form is needed for casual Prance"))
    }
}
