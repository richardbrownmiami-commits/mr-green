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
    @Test fun punctuationContractionsAndOneApprovedTypoUseTheSameLiveRules() {
        val brain = brain()
        install(brain, "+ why can not i import the model\n- Check the model format.\n+ what is the capital of france\n- Paris.\n+ hello how are you\n- Ready.\n")
        assertEquals("Check the model format.", brain.reply("Why can’t I import the model?!")!!.text)
        assertEquals("Ready.", brain.reply("“Hello, how are you?”")!!.text)
        val corrected = brain.reply("What is the captial of France?")!!
        assertEquals("Paris.", corrected.text)
        assertTrue(corrected.typoCorrected)
        assertFalse(corrected.learned)
        assertFalse(brain.reply("what is the capital of france")!!.typoCorrected)
        assertNull(brain.reply("what is the capitol of france"))
        assertNull(brain.reply("why can i import the model"))
    }
    @Test fun contractionLearningIsCanonicalAndDoesNotDuplicateOldEquivalentKeys() {
        val brain = brain()
        assertTrue(learn(brain, "Why don’t you understand the question?", "Please explain it another way."))
        assertEquals("Please explain it another way.", brain.reply("why do not you understand the question")!!.text)
        assertTrue(brain.reply("Why don't you understand the question?")!!.learned)
        assertEquals("why do not you understand the question", brain.learnedRules().single().trigger)
        install(brain, "+ why cannot i import the model\n- Existing owner rule.\n")
        assertFalse(learn(brain, "why can't i import the model", "Do not overwrite."))
        assertEquals("Existing owner rule.", brain.reply("why can not i import the model")!!.text)
    }
    @Test fun legacyDataLoadsAndMatchesWithoutRewritingOrMigratingStoredKeys() {
        val dir = temp.newFolder()
        val file = File(dir, "rules.rive")
        val original = "! version = 2.0\n\n+ why cannot i import the model\n- Owner answer.\n\n\n// MR_GREEN_LEARNED_REPLIES\n+ what is the capital of france\n- Paris.\n\n".toByteArray()
        file.writeBytes(original)
        val brain = RiveBrain(dir)
        assertNull(brain.status().warning)
        assertEquals(1, brain.status().imported)
        assertEquals(1, brain.status().learned)
        assertEquals("Owner answer.", brain.reply("Why can't I import the model?")!!.text)
        assertTrue(brain.reply("What is the captial of France?")!!.learned)
        brain.reload()
        assertArrayEquals(original, file.readBytes())
        assertTrue(brain.importedText().contains("+ why cannot i import the model"))
        assertEquals("what is the capital of france", brain.learnedRules().single().trigger)
    }
    @Test fun legacyMisspelledKeysAreFoundWithoutChangingTheirStoredText() {
        val dir = temp.newFolder()
        val brain = RiveBrain(dir)
        install(brain, "+ what is the captial of france\n- Existing answer.\n")
        val before = File(dir, "rules.rive").readBytes()
        val restarted = RiveBrain(dir)
        assertEquals("Existing answer.", restarted.reply("What is the capital of France?")!!.text)
        assertTrue(restarted.reply("What is the capital of France?")!!.typoCorrected)
        assertTrue(restarted.importedText().contains("+ what is the captial of france"))
        assertArrayEquals(before, File(dir, "rules.rive").readBytes())
    }

    @Test fun normalizedAliasCollisionsFallThroughWithoutLosingEitherStoredAnswer() {
        val brain = brain()
        install(brain, "+ why cannot i import the model\n- One answer.\n+ why can not i import the model\n- Another answer.\n")
        val exported = brain.exportText()
        for (question in listOf("why cannot i import the model", "why can not i import the model", "Why can't I import the model?")) {
            assertNull(brain.reply(question))
            assertFalse(learn(brain, question, "Do not resolve ambiguity by guessing."))
        }
        assertEquals(2, brain.status().imported)
        assertEquals(exported, brain.exportText())
    }
    @Test fun matcherIndexIsRebuiltAfterLearnForgetClearReplaceAndReload() {
        val dir = temp.newFolder()
        val brain = RiveBrain(dir)
        val question = "what is the capital of france"
        val typo = "what is the captial of france"
        assertTrue(learn(brain, question, "Paris."))
        assertEquals("Paris.", brain.reply(typo)!!.text)
        brain.forget(question)
        assertNull(brain.reply(typo))
        assertTrue(learn(brain, question, "Paris again."))
        brain.reload()
        assertEquals("Paris again.", brain.reply(typo)!!.text)
        brain.clearLearned()
        assertNull(brain.reply(typo))
        install(brain, "+ $question\n- Owner version.\n")
        assertEquals("Owner version.", brain.reply(typo)!!.text)
        install(brain, "+ other literal question\n- New rule set.\n")
        assertNull(brain.reply(typo))
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
