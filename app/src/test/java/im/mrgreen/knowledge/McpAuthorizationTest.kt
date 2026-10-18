package im.mrgreen.knowledge

import android.app.Application
import im.mrgreen.data.GreenStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class)
class McpAuthorizationTest {
    @Test fun eachCallNeedsItsOwnPublicArgumentReview() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        val runtime = KnowledgeRuntime(app, GreenStore(app)).apply { localOnly = false }
        try {
            runtime.callMcp("server", "notes.read", "{}", "https://example.com/mcp", true, false)
            fail("An unreviewed call was accepted")
        } catch (e: IllegalArgumentException) { assertTrue(e.message!!.contains("reviewed")) }
    }

    @Test fun changingTheApprovedDestinationRequiresANewReview() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences("knowledge_consent", 0).edit().putString("mcp:server",
            "{\"url\":\"https://changed.example/mcp\",\"tools\":[\"notes.read\"]}").commit()
        val runtime = KnowledgeRuntime(app, GreenStore(app)).apply { localOnly = false }
        try {
            runtime.callMcp("server", "notes.read", "{}", "https://reviewed.example/mcp", true, true)
            fail("A different destination was accepted")
        } catch (e: IllegalArgumentException) { assertTrue(e.message!!.contains("destination changed")) }
    }
}
