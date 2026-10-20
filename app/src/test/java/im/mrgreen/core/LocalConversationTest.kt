package im.mrgreen.core

import android.app.Application
import im.mrgreen.actions.ActionRuntime
import im.mrgreen.data.GreenStore
import im.mrgreen.identity.IdentityStore
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [30])
class LocalConversationTest {
    private val app get() = RuntimeEnvironment.getApplication()

    @Test fun commonConversationAndIdentityNeverNeedGeneration() = runBlocking {
        val identity = IdentityStore(app)
        val actions = ActionRuntime(app, GreenStore(app))
        var generations = 0
        listOf("Hi!", "HELLO", "hey Mr Green", "How are you?", "how r u", "good morning", "thanks", "bye",
            "identity", "Who is Mr Green?", "What is your name?", "What can you do?", "are you offline",
            "do I need a model", "status help", "Who are you?", "Are you alive?").forEach { question ->
            val answer = deterministicFirst({ actions.execute(question) }, {
                identity.directReply(question) ?: LocalConversation.reply(question, identity) ?: run { generations++; "GGUF" }
            })
            assertNotEquals(question, "GGUF", answer)
            assertFalse(question, answer.startsWith("No action was run"))
        }
        assertEquals(0, generations)
        assertTrue(LocalConversation.reply("how are you", identity)!!.contains("don’t have feelings"))
    }

    @Test fun wholeMessageMatchingDoesNotSwallowCommandsDynamicFactsOrMemory() {
        val identity = IdentityStore(app)
        listOf("hi then set alarm", "hello save a note: hi", "do not hello", "how are you after our last chat",
            "battery level", "time", "system status", "job status", "complete job", "repeat what I said", "hello ".repeat(100)).forEach {
            assertNull(it, LocalConversation.reply(it, identity))
        }
    }

    @Test fun aliasesRespectEditableProfileAndDoNotRewriteUserFiles() {
        val file = File(app.filesDir, "conversation-profile-test.json")
        val identity = IdentityStore(app, file)
        val data = JSONObject(identity.exportJson())
        data.getJSONArray("stable_qa").getJSONObject(0).put("answer", "My chosen identity text")
        identity.importJson(data.toString())
        val before = file.readBytes()
        val alias = LocalConversation.reply("what is your name", identity)!!
        assertTrue(alias.contains("My chosen identity text"))
        assertTrue(alias.startsWith("Configured identity text"))
        assertArrayEquals(before, file.readBytes())
    }

    @Test fun productionStaticGatePrecedesRiveAndModelButActionsRemainFirst() {
        val source = File("src/main/java/im/mrgreen/core/AgentController.kt").readText()
        assertTrue(source.indexOf("app.roadmap.execute(text") < source.indexOf("fallback = { replyWithRulesOrModel(text) }"))
        assertTrue(source.indexOf("LocalConversation.reply(text") < source.indexOf("app.rive.reply(text)"))
        val roadmap = File("src/main/java/im/mrgreen/core/RoadmapRuntime.kt").readText()
        assertTrue(roadmap.indexOf("actions.jobStatus(request)") < roadmap.indexOf("actions.plan(request)"))
    }
}
