package im.mrgreen.gateway

import android.app.Application
import im.mrgreen.engine.ChatTurn
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class)
class GatewayClientTest {
    private class FakeTransport : GatewayTransport {
        var sends = 0
        override fun post(url:String,key:String,body:String,cancelled:AtomicBoolean,onToken:(String)->Unit):HttpReply { sends++; error("No cloud request is permitted") }
        override fun get(url:String,key:String):HttpReply { sends++; error("No cloud request is permitted") }
    }
    private fun fixture(): Pair<GatewayClient,FakeTransport> {
        val app=org.robolectric.RuntimeEnvironment.getApplication(); val tx=FakeTransport()
        val client=GatewayClient(app,{_,_,_,_->},null,null,tx,{"configured-key"},{true})
        client.settings.setEnabled(true); client.settings.setFastModel("fast"); client.settings.setSmartModel("smart")
        return client to tx
    }
    @Test fun chatNeverTransmitsEvenWithEnabledProviderKeyAndValidatedNetwork() {
        val (client,tx)=fixture(); var tokens=""
        val result=client.tryChat(GatewayRequest(listOf(ChatTurn("user","private user text")),"chat",PrivacyClass.P1,true)) { tokens+=it }
        assertNull(result); assertEquals("LOCAL_ONLY",client.lastReason); assertEquals("",tokens); assertEquals(0,tx.sends)
    }
    @Test fun contextPlannerScreenAndPluginCategoriesAllFailClosedAtProductionGateway() {
        val (client,tx)=fixture()
        listOf("context","planner","screen","packs","plugins","rag").forEach { category ->
            assertNull(client.tryChat(GatewayRequest(listOf(ChatTurn("user","private context")),category,PrivacyClass.P1,true)) {})
        }
        assertEquals(0,tx.sends)
    }
    @Test fun modelDiscoveryCannotContactProvider() {
        val (client,tx)=fixture()
        assertTrue(client.discoverModels().isEmpty()); assertEquals("LOCAL_ONLY",client.lastReason); assertEquals(0,tx.sends)
    }
    @Test fun offlinePolicyPrecedesCacheAuditKeyLookupAndDeferredRetry() {
        val app = org.robolectric.RuntimeEnvironment.getApplication()
        val tx = FakeTransport()
        var audits = 0; var reads = 0; var writes = 0; var keys = 0
        val client = GatewayClient(app, { _, _, _, _ -> audits++ }, { reads++; "old cloud answer" },
            { _, _ -> writes++ }, tx, { keys++; "configured-key" }, { true })
        client.settings.setEnabled(true)
        PrivacyClass.entries.forEach { privacy ->
            assertNull(client.tryChat(GatewayRequest(listOf(ChatTurn("user", "private memory")), "chat", privacy, true)) {})
        }
        assertFalse(client.groqProvider.isAvailable())
        assertTrue(client.retryDeferred().isEmpty())
        assertEquals(0, reads); assertEquals(0, writes); assertEquals(0, keys)
        assertEquals(0, audits); assertEquals(0, tx.sends)
    }
}
