package im.mrgreen.knowledge

import java.net.InetAddress
import java.net.UnknownHostException
import org.junit.Assert.*
import org.junit.Test

class PublicWebTransportTest {
    @Test fun onlyGlobalAddressesAreEligibleForConnections() {
        for (ip in listOf("8.8.8.8", "1.1.1.1", "2606:4700:4700::1111"))
            assertTrue(ip, PublicDns.isPublicAddress(InetAddress.getByName(ip)))
        for (ip in listOf("0.0.0.0", "127.0.0.1", "10.0.0.1", "172.16.0.1", "192.168.1.1",
            "169.254.169.254", "100.64.0.1", "198.18.0.1", "192.0.2.1", "198.51.100.1",
            "203.0.113.1", "224.0.0.1", "255.255.255.255", "::1", "fc00::1", "fe80::1",
            "2001:db8::1", "2002:7f00:1::1", "64:ff9b::7f00:1"))
            assertFalse(ip, PublicDns.isPublicAddress(InetAddress.getByName(ip)))
    }

    @Test fun connectionLookupRejectsARebindingAnswerAfterAnAllowedPreflight() {
        var calls = 0
        val dns = PublicDns {
            calls++
            listOf(InetAddress.getByName(if (calls == 1) "8.8.8.8" else "127.0.0.1"))
        }
        assertEquals("8.8.8.8", dns.lookup("public.example").single().hostAddress)
        assertThrows(UnknownHostException::class.java) { dns.lookup("public.example") }
        assertEquals(2, calls)
    }

    @Test fun emptyOrMixedPublicAndPrivateAddressListsFailClosed() {
        assertThrows(UnknownHostException::class.java) { PublicDns { emptyList() }.lookup("empty.example") }
        val mixed = listOf(InetAddress.getByName("8.8.8.8"), InetAddress.getByName("10.1.2.3"))
        assertThrows(UnknownHostException::class.java) { PublicDns { mixed }.lookup("mixed.example") }
    }
}
