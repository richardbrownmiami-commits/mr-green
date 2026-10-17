package im.mrgreen.engine

import org.junit.Assert.*
import org.junit.Test

class GenerationProgressTest {
    @Test fun promptProgressIsDistinctFromGeneration() {
        assertEquals("Reading prompt 32/96 · 15s", GenerationProgress("prompt", 32, 96).status(15))
        assertEquals("Replying · 3 tokens · 20s", GenerationProgress("generate", 3, 128).status(20))
    }
    @Test fun startupAndTimeoutAreExplicit() {
        assertEquals("Starting local inference · 1s", GenerationProgress().status(1))
        assertEquals(180L, GenerationProgress.TIMEOUT_SECONDS)
        assertFalse(EngineResult(false, "Generation timed out after 180 seconds").cancelled)
    }
}
