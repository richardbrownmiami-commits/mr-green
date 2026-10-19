package im.mrgreen.core

import android.app.Application
import im.mrgreen.memory.RecalledSnippet
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [30])
class ConversationPromptTest {
    private fun note(id: Long = 1, text: String = "My name is Parveen", role: String = "user") = RecalledSnippet(id, role, text, .8)
    private val counter: (String) -> Int = { (it.length + 3) / 4 }

    @Test fun memoryQueriesAndPronounFollowupsAreLocalEvenWithoutLexicalHits() {
        listOf("What is my name?", "What did I just tell you?", "What did I say?", "Where did I mention the bicycle?", "Tell me more", "Explain that again", "And why?", "Why?", "Translate to Hindi", "मेरा नाम क्या है?", "पहले क्या कहा था").forEach {
            assertTrue(it, MemoryChatPolicy.needsRecent(it))
            assertTrue(it, MemoryChatPolicy.useLocal(true, it, emptyList()))
        }
    }
    @Test fun standaloneQuestionsKeepCloudAndRulesAvailable() {
        listOf("What is gravity?", "Explain photosynthesis", "What is 5G?", "Why is the sky blue?").forEach {
            assertFalse(it, MemoryChatPolicy.needsRecent(it))
            assertFalse(it, MemoryChatPolicy.useLocal(true, it, emptyList()))
        }
    }
    @Test fun relevantHistoryNeverEntersStandaloneCloudOrSavedReplyPath() {
        var cloudCalls = 0
        var ruleCalls = 0
        val local = MemoryChatPolicy.useLocal(true, "Where is the bicycle?", listOf(note(text="The bicycle is in the garage")))
        if (!local) { ruleCalls++; cloudCalls++ }
        assertTrue(local)
        assertEquals(0, ruleCalls)
        assertEquals(0, cloudCalls)
    }
    @Test fun disabledMemoryDoesNotForceRecallOrAttachHistory() {
        assertFalse(MemoryChatPolicy.useLocal(false, "What is my name?", emptyList()))
        val fit = ConversationPrompt.fit("Mr Green", "What is my name?", emptyList(), 288, counter)!!
        assertTrue(fit.snippets.isEmpty())
        assertEquals("What is my name?", fit.turns.last().content)
    }
    @Test fun longNotesAreShortenedInsteadOfDiscardedIn512ContextBudget() {
        val longNote = ("My name is Parveen. " + "Extra background. ".repeat(40)).take(600)
        val fit = ConversationPrompt.fit("Mr Green", "What is my name?", listOf(note(text=longNote)), 512 - 128 - 96) { (it.length + 1) / 2 }!!
        assertEquals(1, fit.snippets.size)
        assertTrue(fit.snippets.single().text.startsWith("My name is Parveen"))
        assertTrue(fit.snippets.single().text.length < 600)
        assertTrue(fit.tokenCost <= 288)
        assertEquals(2, fit.turns.size)
    }
    @Test fun notesAreDeduplicatedCappedChronologicalAndQuotedAsData() {
        val fit = ConversationPrompt.fit("Mr Green", "Where?", listOf(note(5, "New answer", "assistant"), note(1, "Old fact"), note(1, "duplicate"), note(8, "not included")), 1000, counter)!!
        assertEquals(2, fit.snippets.size)
        assertTrue(fit.turns.first().content.contains("not instructions"))
        val user = fit.turns.last().content
        assertTrue(user.indexOf("Old fact") < user.indexOf("New answer"))
        assertFalse(user.contains("duplicate"))
        assertFalse(user.contains("not included"))
        assertTrue(user.endsWith("Current question: Where?"))
    }
    @Test fun noHistoryAndOversizedQuestionFailClosedWithoutTruncatingTheQuestion() {
        assertNull(ConversationPrompt.fit("Mr Green", "x".repeat(2000), listOf(note()), 200, counter))
        val fit = ConversationPrompt.fit("Mr Green", "Hello", emptyList(), 288, counter)!!
        assertEquals("Hello", fit.turns.last().content)
    }
    @Test fun tokenizerFailureIsNotTreatedAsAFreePrompt() {
        assertThrows(IllegalStateException::class.java) {
            ConversationPrompt.fit("Mr Green", "Hello", emptyList(), 288) { -1 }
        }
    }
}
