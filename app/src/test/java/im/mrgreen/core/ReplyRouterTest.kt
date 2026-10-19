package im.mrgreen.core

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ReplyRouterTest {
    @Test fun actionResultSkipsFallback() = runBlocking {
        var fallbackCalls = 0
        val result = deterministicFirst({ "Action completed" }, { fallbackCalls++; "generated" })
        assertEquals("Action completed", result)
        assertEquals(0, fallbackCalls)
    }

    @Test fun clarificationSkipsFallback() = runBlocking {
        var fallbackCalls = 0
        val result = deterministicFirst({ "Which date did you mean?" }, { fallbackCalls++; "generated" })
        assertEquals("Which date did you mean?", result)
        assertEquals(0, fallbackCalls)
    }

    @Test fun denialSkipsFallback() = runBlocking {
        var fallbackCalls = 0
        val result = deterministicFirst({ "Permission denied; no action was taken." }, { fallbackCalls++; "generated" })
        assertEquals("Permission denied; no action was taken.", result)
        assertEquals(0, fallbackCalls)
    }

    @Test fun fallbackRunsOnlyWhenActionIsNull() = runBlocking {
        var fallbackCalls = 0
        val result = deterministicFirst<String>({ null }, { fallbackCalls++; "saved/model fallback" })
        assertEquals("saved/model fallback", result)
        assertEquals(1, fallbackCalls)
    }

    @Test fun actionErrorPropagatesWithoutFallback() = runBlocking {
        var fallbackCalls = 0
        val failure = IllegalStateException("action failed")
        val thrown = assertThrows(IllegalStateException::class.java) {
            runBlocking {
                deterministicFirst<String>({ throw failure }, { fallbackCalls++; "generated" })
            }
        }
        assertEquals(failure, thrown)
        assertEquals(0, fallbackCalls)
    }
}
