package im.mrgreen.gateway

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayRedactorTest {
    @Test fun masksPersonalIdentifiersAndSecrets() {
        val input="Call +1 (415) 555-0123, mail alice@example.com, OTP is 481205, card 4111 1111 1111 1111; https://site.test/?access_token=secret"
        val output=GatewayRedactor.redact(input)
        assertFalse(output.contains("415")); assertFalse(output.contains("alice@example.com")); assertFalse(output.contains("481205")); assertFalse(output.contains("4111 1111")); assertFalse(output.contains("access_token=secret"))
    }
    @Test fun masksContactNamesNotificationBodiesAndPaths() {
        val output=GatewayRedactor.redact("Ask Alice about /storage/emulated/0/Users/alice/file.txt", listOf("Alice"), listOf("private incoming message"))
        assertFalse(output.contains("Alice")); assertTrue(output.contains("[REDACTED_CONTACT]")); assertTrue(output.contains("[REDACTED_PATH]"))
        assertFalse(GatewayRedactor.redact("private incoming message", notifications=listOf("private incoming message")).contains("private incoming message"))
    }
    @Test fun leavesBenignTextReadable() {
        assertTrue(GatewayRedactor.redact("Explain how a timer works") == "Explain how a timer works")
    }
}
