package im.mrgreen.reasoning

import org.junit.Assert.*
import org.junit.Test

class WebFactReasonerTest {
    private fun source(text: String) = listOf("https://example.org/facts" to text)

    @Test fun directAffirmativeClaimIsNotAPreparedQuestionAnswerPair() {
        val plan = WebFactBridge.plan("Is Robin a bird?", source("Robin is a bird."))!!
        assertEquals("<robin --> bird>", plan.target)
        assertEquals(listOf("<robin --> bird>."), plan.inputs)
        assertEquals("Robin is a bird", plan.used.single().claim)
    }

    @Test fun explicitUniversalAndSubjectFactProduceOneStepPlanWithBothSources() {
        val plan = WebFactBridge.plan("Is Robin an animal?", listOf(
            "https://example.org/one" to "Robin is a bird.",
            "https://example.org/two" to "All birds are animals."
        ))!!
        assertEquals("<robin --> animal>", plan.target)
        assertEquals(listOf("<robin --> bird>.", "<<\$x --> bird> ==> <\$x --> animal>>."), plan.inputs)
        assertEquals(2, plan.used.size)
        assertTrue(plan.inputs.none { it.endsWith('!') })
    }

    @Test fun negativeHedgedAndContradictorySourceNeverBecomesAffirmativeAnswer() {
        for (text in listOf("Robin is not a bird.", "Robin may be bird.",
            "Robin is a bird. Robin is not a bird.",
            "Robin is a bird. All birds are animals. Robin might be an animal.")) {
            assertNull(WebFactBridge.plan(if ("animals" in text) "Is Robin an animal?" else "Is Robin a bird?", source(text)))
        }
    }

    @Test fun questionsCompoundClaimsUnknownRelationsAndLongAtomsAreRejected() {
        assertNull(WebFactBridge.plan("What is Robin?", source("Robin is a bird.")))
        assertNull(WebFactBridge.plan("Is Robin bird?", source("Robin is bird?")))
        assertNull(WebFactBridge.plan("Is Robin bird?", source("Robin is bird and animal.")))
        assertNull(WebFactBridge.plan("Is Robin mortal?", source("Robin is a bird.")))
        assertNull(WebFactBridge.plan("Is " + "a".repeat(40) + " bird?", source("Robin is bird.")))
    }

    @Test fun noNativeLibraryMeansNoReasoningClaim() {
        // Pure grammar above is exercised separately; no native library is loaded by this JVM.
        assertNull(WebFactReasoner.reason("Is Robin a bird?", source("Robin is a bird.")))
    }
}
