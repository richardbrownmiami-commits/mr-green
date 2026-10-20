package im.mrgreen.rive

import android.app.Application
import im.mrgreen.core.LocalConversation
import im.mrgreen.identity.IdentityStore
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.junit.rules.TemporaryFolder
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class)
class BundledConversationTest {
    @get:Rule val temp = TemporaryFolder()

    private fun builtInText(): String = RuntimeEnvironment.getApplication().assets
        .open("rive/builtin-conversation.rive").bufferedReader(Charsets.UTF_8).use { it.readText() }

    private fun brain(directory: File = temp.newFolder()): RiveBrain = RiveBrain(directory, builtInText())

    @Test fun everyBundledLiteralTriggerRoundTripsAsAReadOnlyReply() {
        val raw = builtInText()
        val triggers = raw.lineSequence().filter { it.startsWith("+ ") }.map { it.substring(2) }.toList()
        val brain = brain()
        assertTrue("The generated asset should contain a substantial rule set", triggers.size >= 100)
        assertEquals(triggers.size, brain.status().bundled)
        assertEquals(raw, brain.builtInText())
        triggers.forEach { trigger ->
            val reply = brain.reply(trigger)
            assertNotNull("No reply for bundled trigger: $trigger", reply)
            assertFalse("A bundled reply must not be reported as learned: $trigger", reply!!.learned)
        }
        assertEquals(0, brain.status().imported)
        assertEquals(0, brain.status().learned)
    }

    @Test fun acknowledgementLiteralsHandlePunctuationAndNeverClaimActionApproval() {
        val brain = brain()
        assertEquals("Good to hear! What would you like help with next?", brain.reply("that's good")!!.text)
        assertEquals("Good to hear! What would you like help with next?", brain.reply("that’s good!")!!.text)
        for (question in listOf("sure", "yes")) {
            val reply = brain.reply(question)!!
            assertEquals("Okay. Tell me what you’d like to do next. This reply does not approve or run an action.", reply.text)
            assertFalse(reply.learned)
        }
    }

    @Test fun liveFactsAndJobStatusAreNotCannedConversationRules() {
        val brain = brain()
        listOf("battery level", "what time is it", "storage status", "system status", "job status").forEach {
            assertNull("Live status must not be answered from the canned bundle: $it", brain.reply(it))
        }
        assertNotNull(brain.reply("how do i check status"))
    }

    @Test fun oldOwnerFileLoadsWithoutRewriteAndItsLearnedAnswerWinsOverBundle() {
        val dir = temp.newFolder()
        val disk = File(dir, "rules.rive")
        val original = "! version = 2.0\n\n// MR_GREEN_LEARNED_REPLIES\n+ thanks\n- My existing private thanks answer.\n\n".toByteArray()
        disk.writeBytes(original)
        val brain = brain(dir)
        assertEquals("My existing private thanks answer.", brain.reply("thanks")!!.text)
        assertTrue(brain.reply("thanks")!!.learned)
        brain.reload()
        assertArrayEquals("Read-only startup and reload must preserve legacy bytes", original, disk.readBytes())
    }

    @Test fun ownerImportOverridesBuiltInAndClearOrReplacementKeepsBundledRules() {
        val brain = brain()
        val candidate = brain.prepare("+ thanks\n- Imported owner reply.\n".byteInputStream())
        brain.replace(candidate)
        assertEquals("Imported owner reply.", brain.reply("thanks")!!.text)
        assertFalse(brain.reply("thanks")!!.learned)

        assertTrue(brain.learn("my private question", "A private learned reply.", brain.revision(), true, false))
        assertTrue(brain.reply("sure")!!.text.contains("does not approve"))
        brain.clearLearned()
        assertEquals("Imported owner reply.", brain.reply("thanks")!!.text)
        assertNotNull(brain.reply("sure"))
        assertEquals(0, brain.status().learned)

        brain.replace(brain.prepare("+ hello\n- Replacement owner reply.\n".byteInputStream()))
        assertEquals("Replacement owner reply.", brain.reply("hello")!!.text)
        assertNotNull("Replacing private imports must not remove built-ins", brain.reply("thanks"))
        assertEquals(1, brain.status().imported)
    }

    @Test fun builtinQuestionCannotBeLearnedAndOwnerExportRemainsPrivateOnly() {
        val brain = brain()
        assertFalse(brain.learn("thanks", "A fabricated answer", brain.revision(), true, false))
        assertFalse(brain.learn("that’s good", "A fabricated answer", brain.revision(), true, false))
        assertTrue(brain.learn("my private question", "A private learned reply.", brain.revision(), true, false))
        brain.replace(brain.prepare("+ owner import question\n- Imported private answer.\n".byteInputStream()))
        val exported = brain.exportText()
        assertTrue(exported.contains("+ owner import question"))
        assertTrue(exported.contains("+ my private question"))
        assertFalse(exported.contains("+ thanks\n"))
        assertFalse(exported.contains("+ sure\n"))
        assertFalse(brain.importedText().contains("+ thanks\n"))
        assertFalse(brain.importedText().contains("+ sure\n"))
        assertFalse(brain.importedText().contains("my private question"))
        assertTrue(brain.builtInText().contains("+ thanks\n"))
        assertEquals(builtInText().lineSequence().count { it.startsWith("+ ") }, brain.status().bundled)
    }

    @Test fun startupWithOnlyBuiltInsDoesNotCreatePrivateRulesFile() {
        val dir = temp.newFolder()
        brain(dir)
        assertFalse(File(dir, "rules.rive").exists())
    }

    @Test fun bundledIdentityCopiesAgreeWithDefaultIdentityAndCommonAliases() {
        val app = RuntimeEnvironment.getApplication()
        val identity = IdentityStore(app, File(temp.newFolder(), "identity.json"))
        val brain = brain()
        identity.snapshot().stableQa.forEach { qa ->
            val key = IdentityStore.normalize(qa.question).replace(Regex("[^a-z0-9 ]"), "").replace(Regex("\\s+"), " ").trim()
            val answer = brain.reply(key)
            assertNotNull("Missing bundled identity default for ${qa.question}", answer)
            assertEquals(qa.answer, answer!!.text)
        }
        listOf("identity", "your identity", "who is mr green", "what is mr green", "tell me about mr green", "what is your name", "what s your name", "your name", "what can you do", "help", "mr green help", "are you a human", "are you conscious").forEach { alias ->
            assertNotNull("Missing common static LocalConversation alias: $alias", brain.reply(alias))
        }
        val static = LocalConversation.reply("thanks", identity)
        assertEquals(static, brain.reply("thanks")!!.text)
    }

    @Test fun requestedTestDefaultPreservesExplicitLearningChoices() {
        val app = RuntimeEnvironment.getApplication()
        val prefs = app.getSharedPreferences("rive", 0)
        prefs.edit().clear().commit()
        assertTrue(RiveSettings.autoLearning(app))
        prefs.edit().putBoolean("auto_learn", false).commit()
        assertFalse(RiveSettings.autoLearning(app))
        prefs.edit().putBoolean("auto_learn", true).commit()
        assertTrue(RiveSettings.autoLearning(app))
        prefs.edit().clear().commit()
    }

    @Test fun courtesyContractionsAreClosedPhrasesNotGenericPossessiveExpansion() {
        val brain = brain()
        assertEquals(brain.reply("that is great")!!.text, brain.reply("that’s great!")!!.text)
        assertEquals(brain.reply("what is up")!!.text, brain.reply("what's up?")!!.text)
        assertNull(QuestionMatcher.normalize("that's my phone"))
        assertNull(QuestionMatcher.normalize("john's phone"))
        assertNull(brain.reply("sure then delete my notes"))
        assertNull(brain.reply("that's good delete everything"))
    }

    @Test fun normalizedOwnerAliasesWinWithoutChangingPrivateLimits() {
        val brain = brain()
        brain.replace(brain.prepare("+ you are welcome\n- My owner answer.\n".byteInputStream()))
        assertEquals("My owner answer.", brain.reply("you’re welcome")!!.text)
        val full = (1..RiveBrain.MAX_RULES).joinToString("") { "+ owner rule $it\n- Owner answer $it.\n" }
        brain.replace(brain.prepare(full.byteInputStream()))
        assertEquals(RiveBrain.MAX_RULES, brain.status().imported)
        assertNotNull(brain.reply("sure"))
        val exported = brain.exportText()
        assertEquals(RiveBrain.MAX_RULES, RiveBrain(temp.newFolder()).prepare(exported.byteInputStream()).count)
    }
}
