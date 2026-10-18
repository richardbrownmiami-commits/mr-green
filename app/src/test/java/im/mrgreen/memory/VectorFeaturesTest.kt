package im.mrgreen.memory

import org.junit.Assert.*
import org.junit.Test

class VectorFeaturesTest {
    @Test fun relatedQueriesHaveHigherSimilarityThanUnrelatedTopics() {
        val query = VectorFeatures.encode("where is my green bicycle")
        val related = VectorFeatures.encode("My green bicycle is stored beside the garage")
        val unrelated = VectorFeatures.encode("Please explain photosynthesis and plant cells")
        assertTrue(VectorFeatures.cosine(query, related) > VectorFeatures.cosine(query, unrelated))
        assertTrue(VectorFeatures.cosine(query, related) > 0.2)
    }

    @Test fun vectorsAreNormalizedDeterministicAndRoundTrip() {
        val a = VectorFeatures.encode("Remember my train station is Central")
        val b = VectorFeatures.encode("Remember my train station is Central")
        assertArrayEquals(a, b, 0f)
        assertEquals(1.0, VectorFeatures.norm(a).toDouble(), 1e-5)
        assertArrayEquals(a, VectorFeatures.unpack(VectorFeatures.pack(a)), 0f)
    }

    @Test fun stopWordsDoNotCauseAnEmptyQuestionToMatchEverything() {
        assertTrue(VectorFeatures.terms("and the is") .isEmpty())
        assertEquals(0.0, VectorFeatures.cosine(VectorFeatures.encode("and the"), VectorFeatures.encode("the and")), 0.0)
    }
}
