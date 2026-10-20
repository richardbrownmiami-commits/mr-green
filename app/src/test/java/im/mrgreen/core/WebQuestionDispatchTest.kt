package im.mrgreen.core

import im.mrgreen.knowledge.WebQuestion
import org.junit.Assert.*
import org.junit.Test

class WebQuestionDispatchTest {
    @Test fun ordinaryChatDoesNotRetrieveEvenWhenWebEnabled() {
        var calls = 0
        val dispatcher = WebQuestionDispatch { _, _ -> calls++; "unwanted" }
        listOf("hi", "what is photosynthesis?", "search notes plants", "open settings").forEach {
            assertNull(dispatcher.handle(it, true) { false })
        }
        assertEquals(0, calls)
    }

    @Test fun disabledWebAndMalformedCommandsNeverReachNetwork() {
        var calls = 0
        val dispatcher = WebQuestionDispatch { _, _ -> calls++; "unwanted" }
        assertTrue(dispatcher.handle("ask web What is a planet?", false) { false }!!.answer.contains("Web access is off"))
        assertTrue(dispatcher.handle("ask web", true) { false }!!.answer.contains("Use:"))
        assertTrue(dispatcher.handle("ask web " + "x".repeat(301), true) { false }!!.answer.contains("Use:"))
        assertEquals(0, calls)
    }

    @Test fun onlyExplicitCurrentQuestionIsPassedToRetrieval() {
        val sent = mutableListOf<WebQuestion>()
        val dispatcher = WebQuestionDispatch { request, _ -> sent.add(request); "Source-based result" }
        val result = dispatcher.handle("ASK WEB Why do plants need sunlight?", true) { false }!!
        assertEquals(listOf(WebQuestion("Why do plants need sunlight?")), sent)
        assertEquals("Source-based result", result.answer)
        // The dispatcher has no history, screen, database, Rive, GGUF or cloud-model dependency.
        assertEquals("ASK WEB Why do plants need sunlight?", result.question)
    }

    @Test fun cancellationAndRetrievalFailureAreHandledWithoutOtherFallback() {
        var stopped = true
        var calls = 0
        val dispatcher = WebQuestionDispatch { _, _ -> calls++; stopped = true; "discard this" }
        assertEquals("Web question cancelled.", dispatcher.handle("ask web planets", true) { stopped }!!.answer)
        assertEquals(0, calls)
        stopped = false
        assertEquals("Web question cancelled.", dispatcher.handle("ask web planets", true) { stopped }!!.answer)
        assertEquals(1, calls)
        val failure = WebQuestionDispatch { _, _ -> throw IllegalStateException("private transport details") }
        val reply = failure.handle("ask web planets", true) { false }!!.answer
        assertTrue(reply.contains("No cloud AI was used"))
        assertFalse(reply.contains("private transport details"))
    }
}
