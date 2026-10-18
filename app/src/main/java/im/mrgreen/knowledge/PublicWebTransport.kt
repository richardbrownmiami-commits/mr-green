package im.mrgreen.knowledge

import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.Proxy
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/** Validates the exact address list returned to the socket connector, not a separate preflight lookup. */
internal class PublicDns(private val resolver: (String) -> List<InetAddress> = { Dns.SYSTEM.lookup(it) }) : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        val addresses = resolver(hostname)
        if (addresses.isEmpty() || addresses.any { !isPublicAddress(it) })
            throw UnknownHostException("Private/local network destinations are blocked")
        return addresses
    }

    companion object {
        fun isPublicAddress(address: InetAddress): Boolean {
            if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isSiteLocalAddress ||
                address.isLinkLocalAddress || address.isMulticastAddress) return false
            val b = address.address.map { it.toInt() and 255 }
            if (b.size == 4) {
                if (b[0] == 0 || b[0] >= 224 || (b[0] == 100 && b[1] in 64..127)) return false
                if (b[0] == 198 && b[1] in 18..19) return false
                if (b[0] == 192 && b[1] == 0 && b[2] in listOf(0, 2)) return false
                if (b[0] == 192 && b[1] == 88 && b[2] == 99) return false
                if (b[0] == 198 && b[1] == 51 && b[2] == 100) return false
                if (b[0] == 203 && b[1] == 0 && b[2] == 113) return false
                return true
            }
            // Global IPv6 unicast only; disallow documentation and address-tunnelling ranges.
            return b.size == 16 && b[0] and 0xe0 == 0x20 &&
                !(b[0] == 0x20 && b[1] == 0x02) &&
                !(b[0] == 0x20 && b[1] == 0x01 && b[2] == 0 && b[3] == 0) &&
                !(b[0] == 0x20 && b[1] == 0x01 && b[2] == 0x0d && b[3] == 0xb8)
        }
    }
}

internal data class PublicWebReply(val status: Int, val mime: String, val bytes: ByteArray)

/** No proxies, cookies, redirects or automatic retries. Normal TLS hostname verification stays enabled. */
internal object PublicWebTransport {
    private val client = OkHttpClient.Builder()
        .dns(PublicDns()).proxy(Proxy.NO_PROXY)
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
        .connectTimeout(7, TimeUnit.SECONDS).readTimeout(9, TimeUnit.SECONDS)
        .callTimeout(12, TimeUnit.SECONDS).build()

    fun request(url: String, maxBytes: Int, json: String? = null, requireText: Boolean = false): PublicWebReply {
        require(maxBytes in 1..1024 * 1024)
        val parsed = java.net.URL(url)
        require(parsed.protocol.equals("https", true) && parsed.userInfo == null)
        // OkHttp bypasses DNS for IP literals, so validate these explicitly too.
        val host = parsed.host.removePrefix("[").removeSuffix("]")
        if (host.contains(':') || host.matches(Regex("[0-9.]+")))
            require(PublicDns.isPublicAddress(InetAddress.getByName(host))) { "Private/local network destinations are blocked" }
        val builder = Request.Builder().url(url).header("User-Agent", "MrGreen/1.0 (user-requested)")
            .header("Accept", if (json == null) "text/html,text/plain,application/xhtml+xml" else "application/json")
        if (json != null) builder.post(json.toRequestBody("application/json; charset=utf-8".toMediaType()))
        return client.newCall(builder.build()).execute().use { response ->
            require(response.code !in 300..399) { "Redirect blocked; review the destination explicitly" }
            require(response.isSuccessful) { "HTTP ${response.code}" }
            val body = response.body ?: error("Empty response body")
            val mime = response.header("Content-Type").orEmpty().lowercase(java.util.Locale.ROOT)
            if (requireText) require(mime.startsWith("text/") || "json" in mime || "xml" in mime) { "Only text web pages are supported" }
            require(body.contentLength() < 0 || body.contentLength() <= maxBytes) { "Response exceeds safe size limit" }
            val out = ByteArrayOutputStream()
            body.byteStream().use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    require(out.size() + n <= maxBytes) { "Response exceeds safe size limit" }
                    out.write(buffer, 0, n)
                }
            }
            PublicWebReply(response.code, mime, out.toByteArray())
        }
    }
}
