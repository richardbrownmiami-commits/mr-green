package im.mrgreen.rive

import android.app.Application
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class)
class RiveBrainTest {
    @get:Rule val temp = TemporaryFolder()
    private fun brain() = RiveBrain(temp.newFolder())
    private fun prepare(brain: RiveBrain, text: String) = brain.prepare(text.byteInputStream())
    private fun install(brain: RiveBrain, text: String) = brain.replace(prepare(brain, text))
    private fun learn(brain: RiveBrain, question: String = "what is a cat", answer: String = "A small animal.") =
        brain.learn(question, answer, brain.revision(), completed = true, cancelled = false)
    private fun rejected(block: () -> Unit) {
        try { block(); fail("Expected validation failure") } catch (_: IllegalArgumentException) { }
    }

    @Test fun emptyBrainFallsBackWithoutInterpreterErrors() {
        val brain = brain()
        assertNull(brain.reply("hello"))
        assertEquals(0, brain.status().imported)
    }
    @Test fun prepareDoesNotActivateAndReplaceIsLive() {
        val brain = brain()
        val candidate = prepare(brain, "+ hello\n- Hello owner!\n")
        assertNull(brain.reply("hello"))
        assertEquals(1, candidate.count)
        brain.replace(candidate)
        assertEquals("Hello owner!", brain.reply("HELLO?")!!.text)
        assertFalse(brain.reply("hello")!!.learned)
        install(brain, "+ hello\n- Updated live.\n")
        assertEquals("Updated live.", brain.reply("hello")!!.text)
    }
    @Test fun learnedAnswerIsImmediatelyAvailableAndSurvivesRestart() {
        val dir = temp.newFolder()
        val brain = RiveBrain(dir)
        assertTrue(learn(brain))
        assertEquals("A small animal.", brain.reply("What is a cat?")!!.text)
        assertTrue(brain.reply("what is a cat")!!.learned)
        val restarted = RiveBrain(dir)
        assertEquals(1, restarted.status().learned)
        assertEquals("A small animal.", restarted.reply("what is a cat")!!.text)
        assertTrue(File(dir, "rules.rive").readText().contains("+ what is a cat"))
    }
    @Test fun onlyCompleteNonCancelledAnswersAreSaved() {
        val brain = brain()
        assertFalse(brain.learn("hello", "partial", brain.revision(), completed = false, cancelled = false))
        assertFalse(brain.learn("hello", "cancelled", brain.revision(), completed = true, cancelled = true))
        assertEquals(0, brain.status().learned)
    }
    @Test fun changedRulesDuringGenerationPreventStaleLearning() {
        val brain = brain()
        val revision = brain.revision()
        install(brain, "+ hello\n- owner edited\n")
        assertFalse(brain.learn("new question", "stale answer", revision, true, false))
        assertNull(brain.reply("new question"))
    }
    @Test fun editsAndDeletesInvalidateInFlightLearning() {
        val brain = brain()
        val revision = brain.revision()
        brain.clearLearned()
        assertFalse(brain.learn("question", "stale", revision, true, false))
    }
    @Test fun importedRulesWinAndAreNeverOverwrittenByModel() {
        val brain = brain()
        assertTrue(learn(brain))
        install(brain, "+ what is a cat\n- Owner correction.\n")
        assertFalse(learn(brain, answer = "Wrong replacement"))
        assertEquals("Owner correction.", brain.reply("what is a cat")!!.text)
        assertFalse(brain.reply("what is a cat")!!.learned)
        assertEquals(0, brain.status().learned)
    }
    @Test fun duplicateLearningDoesNotSilentlyReplaceAnswer() {
        val brain = brain()
        assertTrue(learn(brain))
        assertFalse(learn(brain, answer = "Different"))
        assertEquals("A small animal.", brain.reply("what is a cat")!!.text)
    }
    @Test fun malformedImportDoesNotReplaceWorkingRules() {
        val brain = brain()
        install(brain, "+ hello\n- working\n")
        for (text in listOf("+ missing reply", "- orphan", "+ duplicate\n- one\n+ duplicate\n- two", "+ hello\n- one\n- two")) {
            rejected { install(brain, text) }
            assertEquals("working", brain.reply("hello")!!.text)
        }
    }
    @Test fun executableAndPatternSyntaxIsRejected() {
        val brain = brain()
        for (text in listOf(
            "> object unsafe javascript\nalert(1)\n< object", "+ *\n- catches everything", "+ hello *\n- wildcard",
            "+ (a|b)\n- regex", "+ hello\n@ goodbye", "+ hello\n- <call>evil</call>",
            "+ hello\n- {topic=other}", "! global depth = 999999", "+ hello\n- <set secret=x>",
            "+ hello\n- comment */", "+ hello\n- truncated // reply"
        )) rejected { install(brain, text) }
        assertEquals(0, brain.status().imported)
    }
    @Test fun generatedRuleInjectionIsRejectedWithoutChangingBrain() {
        val brain = brain()
        for (answer in listOf("<call>evil</call>", "{random}bad{/random}", "bad\\n+ hijack", "comment */", "text // comment")) {
            assertFalse(learn(brain, answer = answer))
        }
        assertEquals(0, brain.status().learned)
    }
    @Test fun multilineAnswersCannotInjectNewDirectives() {
        val brain = brain()
        val answer = "Line one\n+ extra\n- literal text"
        assertTrue(learn(brain, answer = answer))
        assertEquals(answer, brain.reply("what is a cat")!!.text)
        assertNull(brain.reply("extra"))
    }
    @Test fun operatorsAreNotNormalizedIntoCollidingQuestions() {
        val brain = brain()
        assertFalse(learn(brain, "what is 2+2", "4"))
        assertFalse(learn(brain, "what is 2-2", "0"))
        assertFalse(learn(brain, "is 1.5 larger than 1", "Yes"))
        assertNull(brain.reply("what is 2+2"))
        assertEquals("hello world", RiveBrain.normalizeQuestion(" HELLO   world?! "))
    }
    @Test fun invalidUtf8AndOversizedStreamsAreRejected() {
        val brain = brain()
        rejected { brain.prepare(ByteArrayInputStream(byteArrayOf(0xc3.toByte(), 0x28))) }
        rejected { brain.prepare(ByteArrayInputStream(ByteArray(RiveBrain.MAX_BYTES + 1) { 32 })) }
    }
    @Test fun ruleAndAnswerLimitsAreEnforced() {
        val brain = brain()
        rejected { install(brain, (0..500).joinToString("") { "+ rule $it\n- text\n" }) }
        assertFalse(learn(brain, answer = "x".repeat(4097)))
        assertFalse(learn(brain, question = "a".repeat(257)))
    }
    @Test fun corruptDiskReloadKeepsLastWorkingSnapshot() {
        val dir = temp.newFolder()
        val brain = RiveBrain(dir)
        install(brain, "+ hello\n- old answer\n")
        File(dir, "rules.rive").writeText("invalid data")
        rejected { brain.reload() }
        assertEquals("old answer", brain.reply("hello")!!.text)
        assertNotNull(brain.status().warning)
    }
    @Test fun startupCorruptionIsVisibleAndImportCanRecover() {
        val dir = temp.newFolder()
        File(dir, "rules.rive").writeText("broken")
        val brain = RiveBrain(dir)
        assertNotNull(brain.status().warning)
        install(brain, "+ hello\n- recovered\n")
        assertNull(brain.status().warning)
        assertEquals("recovered", brain.reply("hello")!!.text)
    }
    @Test fun exportsArePortableAndDoNotExposeStorageMarkers() {
        val brain = brain()
        install(brain, "+ hello\n- imported\n")
        assertTrue(learn(brain))
        val exported = brain.exportText()
        assertFalse(exported.contains("MR_GREEN_LEARNED"))
        val other = brain()
        install(other, exported)
        assertEquals(2, other.status().imported)
        assertEquals("A small animal.", other.reply("what is a cat")!!.text)
    }
    @Test fun forgettingAndClearingPreservesImportedRules() {
        val brain = brain()
        install(brain, "+ hello\n- imported\n")
        assertTrue(learn(brain))
        brain.forget("what is a cat")
        assertNull(brain.reply("what is a cat"))
        assertTrue(learn(brain))
        brain.clearLearned()
        assertEquals("imported", brain.reply("hello")!!.text)
        assertEquals(0, brain.status().learned)
    }
    @Test fun concurrentReadsAndReplacesSeeOnlyCompleteSnapshots() {
        val brain = brain()
        install(brain, "+ hello\n- first\n")
        val first = prepare(brain, "+ hello\n- first\n")
        val second = prepare(brain, "+ hello\n- second\n")
        val pool = Executors.newFixedThreadPool(3)
        try {
            val reads = (0..1).map { pool.submit {
                repeat(100) { assertTrue(brain.reply("hello")!!.text in listOf("first", "second")) }
            } }
            val writes = pool.submit { repeat(10) { brain.replace(if (it % 2 == 0) first else second) } }
            reads.forEach { it.get(20, TimeUnit.SECONDS) }
            writes.get(20, TimeUnit.SECONDS)
        } finally { pool.shutdownNow() }
    }
    @Test fun writeFailureDoesNotPublishReplacement() {
        val dir = temp.newFolder()
        val brain = RiveBrain(dir)
        install(brain, "+ hello\n- working\n")
        // A directory at the staging-file path forces AtomicFile.startWrite to fail.
        File(dir, "rules.rive.new").mkdir()
        try {
            install(brain, "+ hello\n- should not publish\n")
            fail("Expected storage failure")
        } catch (_: java.io.IOException) { }
        assertEquals("working", brain.reply("hello")!!.text)
    }
}
