package im.mrgreen.core

import org.junit.Assert.*
import org.junit.Test

class RuntimePolicyTest {
    @Test fun reducesContextWhenRamIsLow() {
        assertEquals(1024, RuntimePolicy.contextSize(2048, 599L * 1024 * 1024))
        assertEquals(2048, RuntimePolicy.contextSize(2048, 600L * 1024 * 1024))
    }
    @Test fun neverIncreasesSmallRequestedContext() {
        assertEquals(512, RuntimePolicy.contextSize(512, 200L * 1024 * 1024))
        assertEquals(512, RuntimePolicy.contextSize(-1, 200L * 1024 * 1024))
    }
    @Test fun capsContextForArmv7() {
        assertEquals(2048, RuntimePolicy.contextSize(Int.MAX_VALUE, Long.MAX_VALUE))
    }
    @Test fun rejectsUnsupportedAndEmptyModels() {
        assertFalse(RuntimePolicy.validModel("model.bin", 100))
        assertFalse(RuntimePolicy.validModel("model.gguf", 0))
        assertFalse(RuntimePolicy.validModel("model.gguf", RuntimePolicy.MAX_MODEL_BYTES + 1))
        assertTrue(RuntimePolicy.validModel("Bonsai.GGUF", 546689504))
    }
}
