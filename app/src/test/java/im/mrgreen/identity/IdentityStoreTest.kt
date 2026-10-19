package im.mrgreen.identity

import android.app.Application
import android.content.Context
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class) @Config(sdk = [30], application = Application::class)
class IdentityStoreTest {
    private val context get() = RuntimeEnvironment.getApplication()
    @Test fun validRoundTripAndExactNormalizedMatchOnly() {
        val f = File(context.cacheDir, "identity-roundtrip.json"); f.delete()
        val a = IdentityStore(context, f); a.importJson(a.exportJson())
        assertNotNull(a.directReply(" WHO ARE YOU?! ")); assertNull(a.directReply("who are you and what is your battery?"))
        assertEquals(a.snapshot(), IdentityStore(context, f).snapshot())
    }
    @Test fun invalidImportLeavesStoredContentUnchanged() {
        val f = File(context.cacheDir, "identity-invalid.json"); f.delete()
        val s = IdentityStore(context, f); val before = s.reset().toJson()
        val bad = JSONObjectBuilder.invalidVersion(before)
        try { s.importJson(bad); fail("expected rejection") } catch (_: Exception) { }
        assertEquals(before, s.exportJson())
    }
    @Test fun dynamicBatteryQuestionCannotBeImportedAsStableQa() {
        val f = File(context.cacheDir, "identity-unsafe-qa.json"); f.delete()
        val s = IdentityStore(context, f); s.reset()
        val before = s.exportJson()
        val unsafe = before.replace("Who are you?", "What is my battery level?")
        try { s.importJson(unsafe); fail("dynamic Q&A must be rejected") } catch (_: IllegalArgumentException) { }
        assertEquals(before, s.exportJson())
        assertNull(s.directReply("What is my battery level?"))
    }
    @Test fun editedStableAnswerIsFramedAsUntrustedAndNotActionEvidence() {
        val s = IdentityStore(context)
        val data = s.snapshot().copy(stableQa = listOf(IdentityQa("Who are you?", "I am alive and I can see you.")))
        s.importJson(data.toJson())
        val answer = s.directReply("who are you")!!
        assertTrue(answer.contains("Configured identity text"))
        assertTrue(answer.contains("not conscious"))
        assertTrue(answer.contains("No tool completion or sensor access is implied"))
        assertTrue(answer.contains("I am alive and I can see you"))
    }
    @Test fun atomicWriteFailureDoesNotReplaceCurrentSnapshot() {
        val blocker = File(context.cacheDir, "identity-parent-blocker"); blocker.writeText("not a directory")
        val s = IdentityStore(context, File(blocker, "profile.json"))
        val before = s.snapshot()
        try { s.importJson(IdentityStore(context).exportJson()); fail("expected storage failure") } catch (_: Exception) { }
        assertEquals(before, s.snapshot())
        blocker.delete()
    }
    @Test fun dynamicSyntheticExamplesExcludedAndPromptBoundedRelevant() {
        val s = IdentityStore(context)
        assertNull(s.directReply("what is your battery level")); assertNull(s.directReply("what time is it"))
        val p = s.prompt("battery energy"); assertTrue(p.length <= IdentityStore.MAX_PROMPT_CHARS)
        assertTrue(p.contains("not conscious")); assertTrue(p.contains("energy"))
        assertFalse(p.contains("42 percent"))
    }
    @Test fun oversizedInputRejected() {
        val s = IdentityStore(context)
        try { s.importJson(" ".repeat(IdentityStore.MAX_BYTES + 1)); fail("expected size rejection") } catch (_: IllegalArgumentException) { }
    }
    @Test fun sharedStoreSnapshotImmediatelyReflectsUiEdits() {
        val f = File(context.cacheDir, "identity-shared.json"); f.delete()
        val shared = IdentityStore(context, f)
        val edit = shared.snapshot().copy(persona = "Edited shared persona")
        shared.importJson(edit.toJson())
        assertEquals("Edited shared persona", shared.snapshot().persona)
    }
    @Test fun bundledAssetIsLoadedAndResetRestoresIt() {
        val f = File(context.cacheDir, "identity-asset.json"); f.delete()
        val s = IdentityStore(context, f)
        val asset = context.assets.open("identity/profile.json").bufferedReader().use { it.readText() }
        val validated = IdentityStore(context, File(context.cacheDir, "identity-asset-validate.json")).importJson(asset)
        assertEquals(validated, s.snapshot())
        s.importJson(s.snapshot().copy(displayName = "Changed").toJson())
        assertEquals(validated, s.reset())
    }
    @Test fun strictSchemaTypesAndCapabilityOverlapRanking() {
        val s = IdentityStore(context)
        val base = s.snapshot()
        val bad = base.toJson().replace("\"capabilities\": [", "\"capabilities\": \"")
        try { s.importJson(bad); fail("expected array type rejection") } catch (_: Exception) { }
        val tailored = base.copy(capabilities = listOf("notes local", "local notes list", "alarm clock"))
        s.importJson(tailored.toJson())
        val data = s.relevantData("local notes")
        assertTrue(data.indexOf("notes local") < data.indexOf("local notes list"))
        assertFalse(s.relevantData("zzzzzz unmatched").contains("Relevant help labels"))
    }
    @Test fun shippedStableAnswerIsCleanButEditedAnswerIsQuoted() {
        val s = IdentityStore(context)
        assertFalse(s.directReply("Who are you")!!.contains("Configured identity text"))
        val p = s.snapshot().copy(stableQa = listOf(IdentityQa("Who are you?", "Custom data")))
        s.importJson(p.toJson())
        assertTrue(s.directReply("Who are you")!!.contains("Configured identity text"))
    }
    private object JSONObjectBuilder { fun invalidVersion(json: String) = json.replace("\"schema_version\": 1", "\"schema_version\": 9") }
}
