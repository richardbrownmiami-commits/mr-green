package im.mrgreen.engine

import org.junit.Assert.*
import org.junit.Test

/** Host JVM deliberately has no Android JNI library; these test honest failure behavior only. */
class LlamaEngineTest {
    @Test fun defaultLoadBudgetIsConservativeForRedmi9() {
        assertEquals(512, LoadParams().nCtx)
        assertEquals(2, LoadParams().threads)
        assertEquals(128, LoadParams().nBatch)
        assertEquals(64, LoadParams().nUbatch)
    }

    @Test fun nativeCancellationIsNotClassifiedAsInferenceFailure() {
        assertTrue(EngineResult(false, "Generation cancelled").cancelled)
        assertFalse(EngineResult(false, "Prompt llama_decode failed (rc=1)").cancelled)
        assertFalse(EngineResult(true).cancelled)
    }

    @Test fun missingNativeRuntimeIsAnExplicitLoadFailure() {
        val result = LlamaEngine().load("/does-not-exist.gguf")
        assertFalse(result.ok)
        assertFalse(result.error.isNullOrBlank())
    }
    @Test fun missingNativeRuntimeDoesNotStreamFabricatedReplies() {
        var streamed = false
        val engine = LlamaEngine()
        val result = engine.generate(listOf(ChatTurn("user", "hello"))) { streamed = true }
        assertFalse(result.ok)
        assertFalse(streamed)
    }
    @Test fun unavailableStatsAndCleanupDoNotThrow() {
        val engine = LlamaEngine()
        engine.cancel()
        engine.unload()
        assertNull(engine.stats().loadedModel)
        assertEquals(-1, engine.countTokens("hello"))
    }
}
