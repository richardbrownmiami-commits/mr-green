package im.mrgreen.gateway

import android.app.Application
import im.mrgreen.engine.ChatTurn
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class)
class GatewayClientTest {
    private class FakeTransport(vararg replies: HttpReply) : GatewayTransport {
        private val replies = ArrayDeque(replies.toList())
        val bodies = mutableListOf<String>()
        var sends = 0
        var disconnects = 0
        var onPost: (() -> Unit)? = null
        override fun post(url: String, key: String, body: String, cancelled: AtomicBoolean, onToken: (String) -> Unit): HttpReply {
            sends++
            bodies += body
            val response = replies.removeFirst()
            if (response.code in 200..299) onToken(response.body)
            onPost?.invoke()
            return response
        }
        override fun get(url: String, key: String): HttpReply { sends++; return replies.removeFirst() }
        override fun disconnect() { disconnects++ }
    }
    private data class Fixture(val client: GatewayClient, val transport: FakeTransport, val audits: MutableList<Int>)
    private fun fixture(vararg replies: HttpReply, auditThrows: Boolean = false): Fixture {
        val app = org.robolectric.RuntimeEnvironment.getApplication()
        val transport = FakeTransport(*replies)
        val audits = mutableListOf<Int>()
        val client = GatewayClient(app, { _, _, _, bytes -> if (auditThrows) error("audit"); audits += bytes }, null, null,
            transport, { "fake-key" }, { true })
        client.settings.setEnabled(true)
        client.settings.setFastModel("fast-model")
        client.settings.setSmartModel("smart-model")
        return Fixture(client, transport, audits)
    }
    private fun success(text: String = "answer", headers: Map<String, String> = emptyMap()) = HttpReply(200, text, headers)
    private fun request(
        privacy: PrivacyClass = PrivacyClass.P0, category: String = "chat", initiated: Boolean = true,
        json: Boolean = false, smart: Boolean = false, turns: List<ChatTurn> = listOf(ChatTurn("user", "Hello")),
        source: SensitiveSource? = null, contacts: List<String> = emptyList(), notifications: List<String> = emptyList()
    ) = GatewayRequest(turns, category, privacy, initiated, json, smart, contacts, notifications, source)

    @Test fun successUsesSelectedModelAndAuditsSerializedBytes() {
        val f = fixture(success())
        val result = f.client.tryChat(request(smart = true)) {}
        assertEquals("answer", result?.text)
        assertEquals("smart-model", result?.model)
        assertEquals(1, f.transport.sends)
        assertEquals(f.transport.bodies.single().toByteArray(Charsets.UTF_8).size, f.audits.single())
    }

    @Test fun blocksSensitiveAndUntrustedRequestsBeforeAnySend() {
        val f = fixture(success())
        listOf(
            request(privacy = PrivacyClass.P2), request(source = SensitiveSource.NOTIFICATION),
            request(privacy = PrivacyClass.P1, initiated = false), request(category = "unknown"),
            request(turns = listOf(ChatTurn("user", "My password is: hunter2"))),
            request(turns = listOf(ChatTurn("tool", "untrusted")))
        ).forEach { assertNull(f.client.tryChat(it) {}) }
        f.client.settings.setCategoryLocalOnly("chat", true)
        assertNull(f.client.tryChat(request()) {})
        f.client.settings.setCategoryLocalOnly("chat", false)
        f.client.settings.setEnabled(false)
        assertNull(f.client.tryChat(request()) {})
        assertEquals(0, f.transport.sends)
        assertTrue(f.audits.isEmpty())
    }

    @Test fun redactsContactsNotificationsUrlsAndPathsInEveryMessage() {
        val f = fixture(success())
        val turns = listOf(ChatTurn("user", "Alice called at alice@example.com; see https://x.test/?token=synthetic"),
            ChatTurn("assistant", "Path /storage/emulated/0/Users/alice/secret.txt"))
        f.client.tryChat(request(privacy = PrivacyClass.P1, turns = turns, contacts = listOf("Alice"), notifications = listOf("alert sample"))) {}
        val payload = f.transport.bodies.single()
        assertFalse(payload.contains("alice@example.com"))
        assertFalse(payload.contains("token=synthetic"))
        assertFalse(payload.contains("/storage/emulated/0/Users/alice"))
        assertFalse(payload.contains("Alice"))
    }

    @Test fun auditFailurePreventsNetworkSend() {
        val f = fixture(success(), auditThrows = true)
        assertNull(f.client.tryChat(request()) {})
        assertEquals("AUDIT_FAILED", f.client.lastReason)
        assertEquals(0, f.transport.sends)
    }

    @Test fun jsonRetriesOnceStagesTokensAndNeverLeaksInvalidFragment() {
        val f = fixture(success("not-json"), success("{\"ok\":true}"))
        val emitted = StringBuilder()
        val result = f.client.tryChat(request(json = true), emitted::append)
        assertEquals("{\"ok\":true}", result?.text)
        assertEquals(2, f.transport.sends)
        assertEquals(2, f.audits.size)
        assertFalse(emitted.toString().contains("not-json"))
        assertEquals("{\"ok\":true}", emitted.toString())
    }

    @Test fun failedJsonRetriesExactlyTwiceWithoutStreamingEitherAttempt() {
        val f = fixture(success("no"), success("also no"))
        val emitted = StringBuilder()
        assertNull(f.client.tryChat(request(json = true), emitted::append))
        assertEquals(2, f.transport.sends)
        assertEquals(2, f.audits.size)
        assertEquals("", emitted.toString())
    }

    @Test fun quota429IsPerModelAndRetryAfterPausesOnlyThatModel() {
        val f = fixture(HttpReply(429, "", mapOf("retry-after" to "120")), success())
        assertNull(f.client.tryChat(request(), {}))
        assertTrue(f.client.quotaSnapshot("fast-model")!!.circuitOpen)
        assertNull(f.client.quotaSnapshot("smart-model"))
        assertNotNull(f.client.tryChat(request(smart = true), {}))
        assertEquals(2, f.transport.sends)
        assertNull(f.client.tryChat(request(), {}))
        assertEquals("CIRCUIT_OPEN", f.client.lastReason)
        assertEquals(2, f.transport.sends)
    }

    @Test fun resetDurationParsingIncludesFractionalSeconds() {
        val f = fixture(success())
        val before = System.currentTimeMillis()
        val parsed = f.client.parseReset("2m59.56s")!!
        assertTrue(parsed - before in 179_000..181_000)
    }

    @Test fun cacheOnlyP0AndCacheHitStillAppliesPolicy() {
        val app = org.robolectric.RuntimeEnvironment.getApplication()
        var reads = 0
        val transport = FakeTransport(success())
        val client = GatewayClient(app, { _, _, _, _ -> }, { reads++; "cached" }, null,
            transport, { "key" }, { true })
        client.settings.setEnabled(true); client.settings.setFastModel("fast"); client.settings.setSmartModel("smart")
        assertEquals("cached", client.tryChat(request(), {})?.text)
        assertEquals(0, transport.sends)
        assertNull(client.tryChat(request(privacy = PrivacyClass.P2), {}))
        assertEquals(1, reads)
    }

    @Test fun offlineAndUnavailableKeyNeverSend() {
        val app = org.robolectric.RuntimeEnvironment.getApplication()
        val transport = FakeTransport(success())
        val client = GatewayClient(app, { _, _, _, _ -> }, null, null, transport, { null }, { false })
        client.settings.setEnabled(true); client.settings.setFastModel("fast")
        assertNull(client.tryChat(request(), {}))
        assertEquals("OFFLINE", client.lastReason)
        assertEquals(0, transport.sends)
    }

    @Test fun modelDiscoveryRequiresValidatedNetworkAndKillSwitch() {
        val f = fixture(HttpReply(200, "{\"data\":[{\"id\":\"model-a\"}] }"))
        assertEquals(listOf("model-a"), f.client.discoverModels())
        assertEquals(1, f.transport.sends)
        f.client.settings.setEnabled(false)
        assertTrue(f.client.discoverModels().isEmpty())
        assertEquals(1, f.transport.sends)
    }

    @Test fun lowQuotaDefersBackgroundButAllowsUserAndRetriesQueuedWork() {
        val f = fixture(
            success(headers = mapOf("x-ratelimit-remaining-requests" to "2", "x-ratelimit-remaining-tokens" to "900")),
            success(headers = mapOf("x-ratelimit-remaining-requests" to "1", "x-ratelimit-remaining-tokens" to "800")),
            success(headers = mapOf("x-ratelimit-remaining-requests" to "5", "x-ratelimit-remaining-tokens" to "500")),
            success()
        )
        assertNotNull(f.client.tryChat(request(), {}))
        assertNull(f.client.tryChat(request(category = "doctor", initiated = false), {}))
        assertEquals("LOW_QUOTA_DEFERRED", f.client.lastReason)
        assertEquals(1, f.transport.sends)
        assertNotNull(f.client.tryChat(request(privacy = PrivacyClass.P1, initiated = true), {}))
        assertEquals(2, f.transport.sends)
        assertTrue(f.client.retryDeferred().isEmpty())
        assertNotNull(f.client.tryChat(request(), {}))
        assertEquals(1, f.client.retryDeferred().size)
        assertEquals(4, f.transport.sends)
    }
    @Test fun zeroQuotaRecoversAfterReportedResetWithoutAppRestart() {
        val f = fixture(success(headers = mapOf("x-ratelimit-remaining-requests" to "0", "x-ratelimit-reset-requests" to "0.001s")), success())
        assertNotNull(f.client.tryChat(request()) {})
        Thread.sleep(10)
        assertNotNull(f.client.tryChat(request()) {})
        assertEquals(2, f.transport.sends)
    }

    @Test fun cancellationReturnsCancelledInsteadOfFallback() {
        val f = fixture(success())
        f.transport.onPost = { f.client.cancel() }
        val result = f.client.tryChat(request()) {}
        assertTrue(result!!.cancelled)
        assertEquals("", result.text)
        assertEquals(1, f.transport.disconnects)
    }

    @Test fun discoveryLocalOnlySwitchPreventsEvenModelListing() {
        val f = fixture(success())
        f.client.settings.setCategoryLocalOnly("models", true)
        assertTrue(f.client.discoverModels().isEmpty())
        assertEquals(0, f.transport.sends)
        assertTrue(f.audits.isEmpty())
    }

    @Test fun secretsAreBlockedWithoutBlockingShoppingOrTyping() {
        assertFalse(GatewayRedactor.containsTypedSecret("Help with shopping and typing"))
        assertTrue(GatewayRedactor.containsTypedSecret("My PIN is 1234"))
        assertTrue(GatewayRedactor.containsTypedSecret("gsk_" + "X".repeat(30)))
        val f = fixture(success())
        SensitiveSource.values().forEach { assertNull(f.client.tryChat(request(source = it)) {}) }
        assertEquals(0, f.transport.sends)
    }

    @Test fun trailingGarbageInJsonIsNotAcceptedOrStreamed() {
        val f = fixture(success("{\"ok\":true}garbage"), success("[]"))
        val tokens = StringBuilder()
        assertNull(f.client.tryChat(request(json = true), tokens::append))
        assertEquals("", tokens.toString())
        assertEquals(2, f.transport.sends)
    }

    @Test fun networkFailureNeverLeaksExceptionOrServerBody() {
        val f = fixture(HttpReply(503, "SYNTHETIC_PRIVATE_SERVER_BODY"))
        assertNull(f.client.tryChat(request()) {})
        assertEquals("HTTP_5XX", f.client.lastReason)
        assertFalse(f.client.lastReason!!.contains("PRIVATE"))
    }

    @Test fun backoffSurvivesClientRecreationWithoutPersistingCredentials() {
        val f = fixture(HttpReply(429, "", mapOf("retry-after" to "120")))
        assertNull(f.client.tryChat(request()) {})
        val next = fixture(success())
        assertNull(next.client.tryChat(request()) {})
        assertEquals("CIRCUIT_OPEN", next.client.lastReason)
        assertEquals(0, next.transport.sends)
        val app = org.robolectric.RuntimeEnvironment.getApplication()
        assertFalse(app.getSharedPreferences("gateway_quota", 0).all.toString().contains("fake-key"))
    }

}
