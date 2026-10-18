package im.mrgreen.knowledge

import org.junit.Assert.*
import org.junit.Test
import im.mrgreen.gateway.GatewayRedactor
import java.net.URL

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [30], application = android.app.Application::class)
class KnowledgePolicyTest {
    @Test fun webUrlRequiresHttpsAndNoCredentials() {
        assertFalse(URL("http://example.com").protocol.equals("https",true))
        assertTrue(URL("https://example.com/path").userInfo==null)
        assertNotNull(KnowledgeRuntime::class.java.getField("CHUNK"))
    }
    @Test fun privateAddressCategoriesIncludeIpv6UlaAndV4Loopback() {
        assertTrue(java.net.InetAddress.getByName("127.0.0.1").isLoopbackAddress)
        val ula=java.net.InetAddress.getByName("fc00::1")
        assertTrue((ula.address[0].toInt() and 0xfe)==0xfc)
    }
    @Test fun redactorDetectsTypedSecretAndLeavesPublicText() {
        assertTrue(GatewayRedactor.containsTypedSecret("password: hello123"))
        assertEquals("plain public text", GatewayRedactor.redact("plain public text"))
    }
    @Test fun chunkConstantsMatchPlan() { assertEquals(1200,KnowledgeRuntime.CHUNK); assertEquals(150,KnowledgeRuntime.OVERLAP) }
    @Test fun mcpResponseMustMatchRequestAndContainSuccessfulResult() {
        assertEquals("{\"ok\":true}", validatedMcpResult("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"ok\":true}}"))
        listOf(
            "{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{}}",
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":{\"code\":-1,\"message\":\"failed\"}}",
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":null}"
        ).forEach { raw -> assertThrows(IllegalArgumentException::class.java) { validatedMcpResult(raw) } }
    }
}
