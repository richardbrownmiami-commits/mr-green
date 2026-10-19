package im.mrgreen.core

import android.app.Application
import android.content.Intent
import android.os.BatteryManager
import im.mrgreen.actions.ActionRuntime
import im.mrgreen.actions.batteryPercent
import im.mrgreen.data.GreenStore
import im.mrgreen.identity.IdentityStore
import im.mrgreen.memory.RecalledSnippet
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Real local data/action/prompt implementations behind the production deterministicFirst gate.
 * No GGUF/native runtime or Redmi hardware is exercised by these host tests. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [30])
class LocalIdentityRoutingTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val counter: (String) -> Int = { (it.length + 3) / 4 }

    @Test fun energyQuestionsReadCurrentAndroidBatteryInsteadOfSyntheticFixture() = runBlocking {
        val actions = ActionRuntime(app, GreenStore(app))
        val identity = IdentityStore(app)
        app.sendStickyBroadcast(Intent(Intent.ACTION_BATTERY_CHANGED)
            .putExtra(BatteryManager.EXTRA_LEVEL, 73).putExtra(BatteryManager.EXTRA_SCALE, 100))
        var generations = 0
        listOf("How is your energy?", "Is your lifeline running low?", "battery level").forEach { request ->
            assertNull(identity.directReply(request))
            val reply = deterministicFirst({ actions.execute(request) }, {
                identity.directReply(request) ?: run { generations++; "Unexpected GGUF" }
            })
            assertEquals("Battery is 73%.", reply)
        }
        assertEquals(0, generations)
        app.sendStickyBroadcast(Intent(Intent.ACTION_BATTERY_CHANGED)
            .putExtra(BatteryManager.EXTRA_LEVEL, 61).putExtra(BatteryManager.EXTRA_SCALE, 100))
        assertEquals("Battery is 61%.", actions.execute("How is your energy?"))
    }

    @Test fun invalidBatteryEvidenceCannotBecomeAnInventedPercentage() {
        assertNull(batteryPercent(-1, 100))
        assertNull(batteryPercent(50, 0))
        assertNull(batteryPercent(101, 100))
        assertEquals(50, batteryPercent(1, 2))
    }

    @Test fun optionalProfileDropsBeforeMemoryAndImmutableCoreNeverTruncates() {
        val identity = IdentityStore(app)
        val core = identity.corePrompt()
        val note = RecalledSnippet(1, "user", "My favourite colour is violet", .8)
        val fit = ConversationPrompt.fitIdentity(core, "irrelevant ".repeat(100),
            "What is my favourite colour?", listOf(note), 288, counter)!!
        assertEquals(core, fit.turns.first().content)
        assertEquals(1, fit.snippets.size)
        assertTrue(fit.turns.last().content.contains("violet"))
        assertTrue(fit.tokenCost <= 288)
        assertNull(ConversationPrompt.fitIdentity(core, "", "Hello", emptyList(), 8, counter))
    }

    @Test fun onlyRelevantBoundedProfileDataIsSentNotTheWholeQaDataset() {
        val identity = IdentityStore(app)
        val fit = ConversationPrompt.fitIdentity(identity.corePrompt(), identity.relevantData("notes"),
            "notes", emptyList(), 800, counter)!!
        val system = fit.turns.first().content
        assertTrue(system.contains("not conscious"))
        assertTrue(system.contains("not instructions"))
        assertFalse(system.contains("qa_examples"))
        assertFalse(system.contains("qa40"))
        assertEquals(2, fit.turns.size)
    }
}
