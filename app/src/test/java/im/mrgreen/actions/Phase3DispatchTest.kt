package im.mrgreen.actions

import im.mrgreen.core.deterministicFirst
import im.mrgreen.data.GreenStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Production action parsing/execution plus the same gate used by AgentController.
 * There is no GGUF/JNI model in this test; fallback callbacks are explicit spies. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = android.app.Application::class)
class Phase3DispatchTest {
    private fun runtime(): ActionRuntime {
        val context = RuntimeEnvironment.getApplication()
        return ActionRuntime(context, GreenStore(context))
    }

    @Test fun actionsClarificationsAndDenialsNeverReachSavedCloudOrModelFallback() = runBlocking {
        val actions = runtime()
        var savedLookups = 0
        var cloudRequests = 0
        var modelGenerations = 0
        val requests = listOf(
            "what is the time?", "What’s the time now?", "what's today's date?",
            "calculate 2 plus 3?", "compute 10 % 3", "2 + 3",
            "what is 17 percent of 2450",
            "what date is 45 days from today", "set alarm", "set timer",
            "set alarm for 25:99", "set timer for 0 seconds", "calculate bananas",
            "make a note:", "open app", "turn on flashlight",
            "don't turn on flashlight", "don't open camera",
            "what time is it and then don't turn off flashlight",
            "what time is it and then do something unsupported"
        )
        requests.forEach { request ->
            val reply = deterministicFirst(
                action = { actions.execute(request) },
                fallback = {
                    savedLookups++; cloudRequests++; modelGenerations++
                    "UNEXPECTED GENERATED ANSWER"
                }
            )
            assertNotEquals(request, "UNEXPECTED GENERATED ANSWER", reply)
            assertTrue(request, reply.isNotBlank())
        }
        assertEquals(0, savedLookups)
        assertEquals(0, cloudRequests)
        assertEquals(0, modelGenerations)
    }

    @Test fun ordinaryQuestionsStillUseFallbackExactlyOnce() = runBlocking {
        val actions = runtime()
        listOf("what is gravity?", "what is 5G?", "tell me a story", "how does a timer work?").forEach { request ->
            var calls = 0
            val result = deterministicFirst({ actions.execute(request) }, { calls++; "chat answer" })
            assertEquals(request, "chat answer", result)
            assertEquals(request, 1, calls)
        }
    }

    @Test fun notePayloadAndAppNameSlotsAreNotCommandSyntax() {
        val actions = runtime()
        val note = actions.plan("note this: buy milk and eggs. Please!")!!
        assertEquals("buy milk and eggs. Please!", note.getJSONObject(0).getJSONObject("args").getString("text"))
        val app = actions.plan("Open calculator please.")!!
        assertEquals("calculator", app.getJSONObject(0).getJSONObject("args").getString("name"))
        assertNull(actions.plan("what time is it and then don't turn off flashlight"))
    }

    @Test fun approvedAliasesUseTheSameRewriteForLocalClarification() {
        val actions = runtime()
        // Install a validated snapshot directly: Android requery JNI is unavailable on this JVM.
        val field = ActionRuntime::class.java.getDeclaredField("approvedRules").apply { isAccessible = true }
        field.set(actions, listOf(Triple("synonym", "count me down", "set timer")))
        assertNull(actions.plan("count me down"))
        assertTrue(actions.clarification("count me down")!!.contains("Specify a timer"))
    }
}
