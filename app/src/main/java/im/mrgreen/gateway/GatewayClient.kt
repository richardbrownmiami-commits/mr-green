package im.mrgreen.gateway

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import im.mrgreen.engine.ChatTurn
import im.mrgreen.engine.GenerationOptions
import im.mrgreen.engine.LlamaEngine
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.random.Random

enum class PrivacyClass { P0, P1, P2 }
enum class SensitiveSource { CONTACTS, NOTIFICATION, CALL_LOG, BANKING, OTP, PASSWORD, PAYMENT, OTHER }
data class GatewayRequest(
    val turns: List<ChatTurn>, val category: String, val privacyClass: PrivacyClass,
    val userInitiated: Boolean, val jsonMode: Boolean = false, val smart: Boolean = false,
    val contactNames: List<String> = emptyList(), val notificationBodies: List<String> = emptyList(),
    val sensitiveSource: SensitiveSource? = null
)
data class QuotaSnapshot(val model: String, val requestsRemaining: Int?, val tokensRemaining: Int?, val resetAtMillis: Long, val circuitOpen: Boolean)
data class GatewayResult(val text: String, val provider: String = "groq", val model: String, val cancelled: Boolean = false, val fromCache: Boolean = false)

interface AiProvider {
    val id: String
    val supportsVision: Boolean
    val supportsJsonMode: Boolean
    fun isAvailable(): Boolean
    fun chat(request: GatewayRequest, onToken: (String) -> Unit): GatewayResult?
}

class GatewaySettings internal constructor(private val context: Context) {
    internal fun networkContext(): Context = context
    private val prefs = context.getSharedPreferences("gateway_settings", Context.MODE_PRIVATE)
    val enabled: Boolean get() = prefs.getBoolean("enabled", false)
    val fastModel: String get() = prefs.getString("fast_model", "") ?: ""
    val smartModel: String get() = prefs.getString("smart_model", "") ?: ""
    val baseUrl: String get() = prefs.getString("base_url", DEFAULT_BASE) ?: DEFAULT_BASE
    val visionEnabled: Boolean get() = prefs.getBoolean("vision", false)
    val localOnlyCategories: Set<String> get() = prefs.getStringSet("local_categories", emptySet())?.toSet() ?: emptySet()
    fun setEnabled(value: Boolean) { prefs.edit().putBoolean("enabled", value).apply() }
    fun setFastModel(value: String) { require(value.matches(Regex("[A-Za-z0-9._:/-]{1,200}"))); prefs.edit().putString("fast_model", value).apply() }
    fun setSmartModel(value: String) { require(value.matches(Regex("[A-Za-z0-9._:/-]{1,200}"))); prefs.edit().putString("smart_model", value).apply() }
    @Synchronized fun setBaseUrl(value: String) {
        require(validBase(value) != null) { "Base URL must be HTTPS without credentials, query, or fragment" }
        val normalized = value.trimEnd('/')
        if (normalized != baseUrl.trimEnd('/')) prefs.edit().remove("fast_model").remove("smart_model").apply()
        prefs.edit().putString("base_url", normalized).apply()
    }
    fun setVisionEnabled(value: Boolean) { prefs.edit().putBoolean("vision", value).apply() }
    fun setCategoryLocalOnly(category: String, value: Boolean) {
        require(category.isNotBlank() && category.length <= 100)
        val set = localOnlyCategories.toMutableSet()
        if (value) set.add(category) else set.remove(category)
        prefs.edit().putStringSet("local_categories", set).apply()
    }
    val hasApiKey: Boolean get() = prefs.contains("api_nonce") && decryptKey() != null
    @Synchronized fun setApiKey(key: String) {
        require(key.isNotBlank() && key.length <= 2048)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, keystoreKey())
        val encrypted = cipher.doFinal(key.toByteArray(Charsets.UTF_8))
        prefs.edit().putString("api_nonce", android.util.Base64.encodeToString(cipher.iv, android.util.Base64.NO_WRAP))
            .putString("api_secret", android.util.Base64.encodeToString(encrypted, android.util.Base64.NO_WRAP))
            .putString("api_origin", credentialOrigin()).apply()
    }
    @Synchronized fun clearApiKey() { prefs.edit().remove("api_nonce").remove("api_secret").remove("api_origin").apply() }
    @Synchronized internal fun key(): String? = decryptKey()
    private fun decryptKey(): String? { return try {
        // Changing providers must not silently forward the old provider credential.
        if (prefs.getString("api_origin", null) != credentialOrigin()) return null
        val nonce = prefs.getString("api_nonce", null) ?: return null
        val enc = prefs.getString("api_secret", null) ?: return null
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, keystoreKey(), GCMParameterSpec(128, android.util.Base64.decode(nonce, android.util.Base64.NO_WRAP)))
        String(cipher.doFinal(android.util.Base64.decode(enc, android.util.Base64.NO_WRAP)), Charsets.UTF_8)
    } catch (_: Exception) { null } }
    private fun credentialOrigin(): String {
        val url = URL(baseUrl)
        val port = if (url.port < 0) url.defaultPort else url.port
        return "${url.protocol.lowercase(Locale.ROOT)}://${url.host.lowercase(Locale.ROOT)}:$port"
    }
    private fun keystoreKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("mrgreen.gateway.aes", null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder("mrgreen.gateway.aes", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        return generator.generateKey()
    }
    companion object { internal const val DEFAULT_BASE = "https://api.groq.com/openai/v1" }
}

private val allowedCategories = setOf("chat", "context", "facts", "notes", "screen", "rag", "models", "technical", "planner", "doctor", "plugins", "packs", "summary")
private fun validBase(value: String): String? = try {
    val url = URL(value)
    if (url.protocol.equals("https", true) && url.host.isNotBlank() && url.userInfo == null && url.query == null && url.ref == null) value.trimEnd('/') else null
} catch (_: Exception) { null }

object GatewayRedactor {
    private val email = Regex("(?i)\\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}\\b")
    private val phone = Regex("(?<![\\p{L}\\p{N}])\\+?\\d[\\d .()\\-]{6,}\\d(?![\\p{L}\\p{N}])")
    private val card = Regex("(?<!\\d)(?:\\d[ -]?){13,19}(?!\\d)")
    private val otp = Regex("(?i)\\b(?:otp|one[- ]time(?: password| code)?|verification code)\\s*(?:is|:|=)?\\s*\\d{4,8}\\b")
    private val secrets = Regex("(?i)\\b(?:bearer\\s+|api[_-]?key\\s*[:=]?\\s*|access[_-]?token\\s*[:=]?\\s*|secret\\s*[:=]?\\s*|password\\s*[:=]?\\s*)[A-Za-z0-9._~+/=-]{6,}")
    private val urls = Regex("(?i)https?://[^\\s<>\\\"]+")
    private val winPath = Regex("(?i)\\b[A-Z]:\\\\(?:[^\\s<>|?*\\\"]+\\\\?)*[^\\s<>|?*\\\"]*")
    private val path = Regex("(?i)(?:/data/user/\\d+/|/storage/emulated/\\d+/|/sdcard/|/home/|/Users/)[^\\s]+")
    fun redact(input: String, contacts: List<String> = emptyList(), notifications: List<String> = emptyList()): String {
        var text = input
        for (body in notifications.filter { it.isNotBlank() }.sortedByDescending { it.length }) text = text.replace(body, "[REDACTED_NOTIFICATION]", true)
        for (name in contacts.filter { it.isNotBlank() }.distinct().sortedByDescending { it.length }) {
            text = text.replace(Regex("(?i)(?<![\\p{L}\\p{N}])${Regex.escape(name.trim())}(?![\\p{L}\\p{N}])"), "[REDACTED_CONTACT]")
        }
        text = secrets.replace(text, "[REDACTED_SECRET]")
        text = otp.replace(text, "[REDACTED_OTP]")
        text = urls.replace(text) { match -> if (match.value.any { it in "?#@" } || Regex("(?i)(token|auth|key|code|session|signature)=").containsMatchIn(match.value)) "[REDACTED_URL]" else match.value }
        text = email.replace(text, "[REDACTED_EMAIL]").let { card.replace(it, "[REDACTED_NUMBER]") }
        text = phone.replace(text, "[REDACTED_PHONE]")
        text = winPath.replace(text, "[REDACTED_PATH]")
        text = path.replace(text, "[REDACTED_PATH]")
        return text
    }
    /** Any potentially typed credential is blocked rather than trusted to redact perfectly. */
    fun containsTypedSecret(text: String): Boolean =
        Regex("(?i)\\b(?:password|passcode|pin|otp|one[- ]time code|verification code|cvv|cvc|security code|card number|credit card|debit card)\\b").containsMatchIn(text) ||
        Regex("(?i)\\b(?:bearer\\s+[A-Za-z0-9._~+/=-]{8,}|(?:api[_-]?key|access[_-]?token)\\s*[:=]|gsk_[A-Za-z0-9]{12,}|sk-[A-Za-z0-9]{20,})").containsMatchIn(text) ||
        Regex("(?<!\\d)(?:\\d[ -]?){13,19}(?!\\d)").containsMatchIn(text)

}

internal interface GatewayTransport {
    fun post(url: String, key: String, body: String, cancelled: AtomicBoolean, onToken: (String) -> Unit): HttpReply
    fun get(url: String, key: String): HttpReply
    fun disconnect() {}
}
internal data class HttpReply(val code: Int, val body: String, val headers: Map<String, String> = emptyMap())

internal class UrlConnectionTransport : GatewayTransport {
    @Volatile private var active: HttpURLConnection? = null
    override fun disconnect() { active?.disconnect() }
    override fun post(url: String, key: String, body: String, cancelled: AtomicBoolean, onToken: (String) -> Unit): HttpReply {
        val connection = URL(url).openConnection() as HttpURLConnection
        active = connection
        connection.instanceFollowRedirects = false
        connection.requestMethod = "POST"
        connection.connectTimeout = 15_000
        connection.readTimeout = 15_000
        connection.doOutput = true
        connection.setRequestProperty("Authorization", "Bearer $key")
        connection.setRequestProperty("Content-Type", "application/json")
        connection.setRequestProperty("Accept", "text/event-stream")
        val watchdog = Executors.newSingleThreadScheduledExecutor()
        val future = watchdog.schedule({ connection.disconnect() }, 15, TimeUnit.SECONDS)
        val started = System.currentTimeMillis()
        try {
            val bytes = body.toByteArray(Charsets.UTF_8)
            connection.setFixedLengthStreamingMode(bytes.size)
            connection.outputStream.use { it.write(bytes) }
            if (cancelled.get()) throw IOException("cancelled")
            val code = connection.responseCode
            val headers = connection.headerFields.filterKeys { it != null }.mapKeys { it.key.lowercase(Locale.ROOT) }.mapValues { it.value?.firstOrNull().orEmpty() }
            if (code !in 200..299) return HttpReply(code, "", headers)
            val output = StringBuilder()
            var done = false
            var total = 0
            BufferedReader(InputStreamReader(connection.inputStream, Charsets.UTF_8)).use { reader ->
                while (true) {
                    if (cancelled.get()) throw IOException("cancelled")
                    if (System.currentTimeMillis() - started >= 15_000) throw IOException("deadline")
                    val line = readBoundedLine(reader, cancelled, started) ?: break
                    total += line.toByteArray(Charsets.UTF_8).size + 1
                    if (total > MAX_RESPONSE_BYTES) throw IOException("response limit")
                    if (!line.startsWith("data:")) continue
                    val data = line.substring(5).trim()
                    if (data == "[DONE]") { done = true; break }
                    try {
                        val token = JSONObject(data).optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("delta")?.optString("content").orEmpty()
                        if (token.isNotEmpty()) {
                            if (output.length + token.length > MAX_ANSWER_CHARS) throw IOException("answer limit")
                            output.append(token)
                            onToken(token)
                        }
                    } catch (e: IOException) { throw e } catch (_: Exception) { throw IOException("malformed stream") }
                }
            }
            if (cancelled.get()) throw IOException("cancelled")
            if (!done || output.isEmpty()) throw IOException("incomplete stream")
            return HttpReply(code, output.toString(), headers)
        } finally {
            future.cancel(true)
            watchdog.shutdownNow()
            connection.disconnect()
            if (active === connection) active = null
        }
    }
    override fun get(url: String, key: String): HttpReply {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = false
        connection.connectTimeout = 15_000
        connection.readTimeout = 15_000
        connection.setRequestProperty("Authorization", "Bearer $key")
        active = connection
        val watchdog = Executors.newSingleThreadScheduledExecutor()
        val future = watchdog.schedule({ connection.disconnect() }, 15, TimeUnit.SECONDS)
        val started = System.currentTimeMillis()
        try {
            val code = connection.responseCode
            val headers = connection.headerFields.filterKeys { it != null }.mapKeys { it.key.lowercase(Locale.ROOT) }.mapValues { it.value?.firstOrNull().orEmpty() }
            if (code !in 200..299) return HttpReply(code, "", headers)
            val out = StringBuilder()
            connection.inputStream.bufferedReader().use { reader ->
                val buffer = CharArray(4096)
                while (true) {
                    if (System.currentTimeMillis() - started >= 15_000) throw IOException("deadline")
                    val count = reader.read(buffer)
                    if (count < 0) break
                    if (out.length + count > MAX_RESPONSE_BYTES) throw IOException("response limit")
                    out.append(buffer, 0, count)
                }
            }
            return HttpReply(code, out.toString(), headers)
        } finally {
            future.cancel(true); watchdog.shutdownNow(); connection.disconnect()
            if (active === connection) active = null
        }
    }
    private fun readBoundedLine(reader: BufferedReader, cancelled: AtomicBoolean, started: Long): String? {
        val line = StringBuilder()
        while (true) {
            if (cancelled.get()) throw IOException("cancelled")
            if (System.currentTimeMillis() - started >= 15_000) throw IOException("deadline")
            val next = reader.read()
            if (next < 0) return if (line.isEmpty()) null else line.toString()
            if (next == '\n'.code) return line.toString().removeSuffix("\r")
            if (line.length >= MAX_SSE_LINE_CHARS) throw IOException("line limit")
            line.append(next.toChar())
        }
    }
    companion object {
        private const val MAX_RESPONSE_BYTES = 1_000_000
        private const val MAX_ANSWER_CHARS = 500_000
        private const val MAX_SSE_LINE_CHARS = 64_000
    }
}

class GroqProvider internal constructor(private val client: GatewayClient) : AiProvider {
    override val id = "groq"
    override val supportsVision = false
    override val supportsJsonMode = true
    override fun isAvailable() = client.isCloudAvailable()
    override fun chat(request: GatewayRequest, onToken: (String) -> Unit) = client.tryChat(request, onToken)
}

class LocalLlamaProvider(private val engine: LlamaEngine, private val options: GenerationOptions = GenerationOptions()) : AiProvider {
    override val id = "local"
    override val supportsVision = false
    override val supportsJsonMode = false
    override fun isAvailable() = try { engine.stats().loadedModel != null } catch (_: Exception) { false }
    override fun chat(request: GatewayRequest, onToken: (String) -> Unit): GatewayResult? {
        val output = StringBuilder()
        val result = engine.generate(request.turns, options, onToken = { token -> output.append(token); onToken(token) })
        return if (result.ok) GatewayResult(output.toString(), provider = id, model = "local") else null
    }
}

class GatewayClient @JvmOverloads constructor(
    context: Context,
    private val audit: (provider: String, dataClass: String, category: String, bytes: Int) -> Unit,
    private val cacheRead: ((String) -> String?)? = null,
    private val cacheWrite: ((String, String) -> Unit)? = null
) {
    internal constructor(
        context: Context, audit: (String, String, String, Int) -> Unit,
        cacheRead: ((String) -> String?)?, cacheWrite: ((String, String) -> Unit)?,
        transport: GatewayTransport, keyProvider: () -> String?, networkAvailable: () -> Boolean
    ) : this(context, audit, cacheRead, cacheWrite) {
        this.transport = transport
        this.injectedKeyProvider = keyProvider
        this.injectedNetworkAvailable = networkAvailable
    }

    val settings = GatewaySettings(context.applicationContext)
    private var transport: GatewayTransport = UrlConnectionTransport()
    private var injectedKeyProvider: (() -> String?)? = null
    private var injectedNetworkAvailable: (() -> Boolean)? = null
    private val cancelled = AtomicBoolean(false)
    private val lock = Any()
    private val quota = mutableMapOf<String, Quota>()
    private val quotaPrefs = context.getSharedPreferences("gateway_quota", Context.MODE_PRIVATE)
    private val deferred = LinkedHashMap<String, GatewayRequest>()
    @Volatile var lastReason: String? = null; private set
    private data class Quota(var remaining: Int? = null, var tokens: Int? = null, var resetAt: Long = 0, var pausedUntil: Long = 0, var requestReset: Long = 0, var tokenReset: Long = 0)
    val groqProvider: GroqProvider = GroqProvider(this)

    fun cancel() { cancelled.set(true); transport.disconnect() }
    fun quotaSnapshot(model: String): QuotaSnapshot? = synchronized(lock) {
        refreshedQuota(model)?.let { QuotaSnapshot(model, it.remaining, it.tokens, it.resetAt, it.pausedUntil > System.currentTimeMillis()) }
    }
    fun retryDeferred(onToken: (String) -> Unit = {}): List<GatewayResult?> {
        val ready = synchronized(lock) {
            deferred.values.filter { request ->
                val model = if (request.smart) settings.smartModel else settings.fastModel
                val q = refreshedQuota(model)
                q == null || (q.pausedUntil <= System.currentTimeMillis() && (q.remaining ?: Int.MAX_VALUE) > 2 && (q.tokens ?: Int.MAX_VALUE) > 2)
            }.also { list -> list.forEach { deferred.remove(deferKey(it)) } }
        }
        return ready.map { tryChat(it, onToken) }
    }
    @Synchronized fun discoverModels(): List<String> {
        cancelled.set(false)
        if (!settings.enabled) return failModels("CLOUD_DISABLED")
        if ("models" in settings.localOnlyCategories) return failModels("CATEGORY_LOCAL_ONLY")
        if (!isNetworkAvailable()) return failModels("OFFLINE")
        val key = currentKey() ?: return failModels("KEY_MISSING")
        val url = validBase(settings.baseUrl)?.plus("/models") ?: return failModels("INVALID_BASE_URL")
        return try {
            auditSend("P0", "models", 0)
            if (cancelled.get() || !settings.enabled) return failModels("CANCELLED")
            val response = transport.get(url, key)
            if (cancelled.get() || !settings.enabled) return failModels("CANCELLED")
            observe("*", response)
            if (response.code !in 200..299) return failModels("MODELS_HTTP_${response.code}")
            val data = JSONObject(response.body).optJSONArray("data") ?: JSONArray()
            (0 until data.length()).mapNotNull { index -> data.optJSONObject(index)?.optString("id")?.takeIf { it.matches(Regex("[A-Za-z0-9._:/-]{1,200}")) } }.sorted()
        } catch (_: Exception) { failModels("MODELS_NETWORK") }
    }
    private fun failModels(reason: String): List<String> { lastReason = reason; return emptyList() }
    internal fun isCloudAvailable(): Boolean = settings.enabled && currentKey() != null && isNetworkAvailable()

    @Synchronized fun tryChat(request: GatewayRequest, onToken: (String) -> Unit): GatewayResult? {
        cancelled.set(false)
        lastReason = null
        if (request.privacyClass == PrivacyClass.P2 || request.sensitiveSource != null) return fail("P2_BLOCKED")
        if (request.privacyClass == PrivacyClass.P1 && !request.userInitiated) return fail("P1_REQUIRES_USER")
        if (request.category !in allowedCategories) return fail("CATEGORY_BLOCKED")
        if (request.category in settings.localOnlyCategories) return fail("CATEGORY_LOCAL_ONLY")
        if (!settings.enabled) return fail("CLOUD_DISABLED")
        if (!isNetworkAvailable()) return fail("OFFLINE")
        val base = validBase(settings.baseUrl) ?: return fail("INVALID_BASE_URL")
        val key = currentKey() ?: return fail("KEY_MISSING")
        if (request.turns.isEmpty() || request.turns.none { it.role == "user" } ||
            request.turns.any { it.content.length > 100_000 || it.role !in setOf("system", "user", "assistant") }) return fail("INVALID_REQUEST")
        if (request.turns.any { GatewayRedactor.containsTypedSecret(it.content) }) return fail("SENSITIVE_CONTENT_BLOCKED")
        val model = (if (request.smart) settings.smartModel else settings.fastModel)
            .takeIf { it.isNotBlank() && it.matches(Regex("[A-Za-z0-9._:/-]{1,200}")) } ?: return fail("MODEL_UNSET")
        val redacted = request.turns.map { ChatTurn(it.role, GatewayRedactor.redact(it.content, request.contactNames, request.notificationBodies)) }
        val cacheKey = if (request.privacyClass == PrivacyClass.P0) hash("$base|$model|${request.category}|${request.jsonMode}|" + redacted.joinToString("\n") { "${it.role}:${it.content}" }) else null
        if (cacheKey != null) {
            try { cacheRead?.invoke(cacheKey)?.let { cached -> if (!request.jsonMode || validJson(cached)) return GatewayResult(cached, model = model, fromCache = true) } }
            catch (_: Exception) { /* Cache is optional; continue to cloud. */ }
        }
        synchronized(lock) {
            val q = refreshedQuota(model)
            val blocked = q != null && (q.pausedUntil > System.currentTimeMillis() || q.remaining == 0 || q.tokens == 0)
            val lowForBackground = q != null && !request.userInitiated && ((q.remaining ?: Int.MAX_VALUE) <= 2 || (q.tokens ?: Int.MAX_VALUE) <= 2)
            if (blocked || lowForBackground) {
                lastReason = if (blocked) "CIRCUIT_OPEN" else "LOW_QUOTA_DEFERRED"
                if (!request.userInitiated) {
                    if (deferred.size < 100) deferred[deferKey(request)] = request.copy(turns = redacted, contactNames = emptyList(), notificationBodies = emptyList())
                    lastReason = "LOW_QUOTA_DEFERRED"
                }
                return null
            }
        }
        val attempts = if (request.jsonMode) 2 else 1
        for (attempt in 0 until attempts) {
            if (cancelled.get()) return cancelledResult(model)
            val messages = redacted.toMutableList()
            if (request.jsonMode && messages.none { it.role == "system" && it.content.contains("JSON", true) }) {
                messages.add(0, ChatTurn("system", "Return only a valid JSON object. Do not include markdown fences or explanatory text."))
            }
            val body = JSONObject().put("model", model)
                .put("messages", JSONArray().also { array -> messages.forEach { array.put(JSONObject().put("role", it.role).put("content", it.content)) } })
                .put("stream", true).put("max_completion_tokens", 256).apply { if (request.jsonMode) put("response_format", JSONObject().put("type", "json_object")) }.toString()
            try {
                auditSend(request.privacyClass.name, request.category, body.toByteArray(Charsets.UTF_8).size)
            } catch (_: Exception) { return fail("AUDIT_FAILED") }
            try {
                if (cancelled.get() || !settings.enabled) return cancelledResult(model)
                val staged = StringBuilder()
                val response = transport.post("$base/chat/completions", key, body, cancelled) { token ->
                    if (request.jsonMode) staged.append(token) else onToken(token)
                }
                observe(model, response)
                if (cancelled.get() || !settings.enabled) return cancelledResult(model)
                if (response.code !in 200..299) return fail(when (response.code) { 429 -> "HTTP_429"; in 500..599 -> "HTTP_5XX"; else -> "HTTP_${response.code}" })
                if (request.jsonMode && !validJson(response.body)) {
                    lastReason = "INVALID_JSON"
                    if (attempt == attempts - 1) return null
                    continue
                }
                if (response.body.isBlank()) return fail("EMPTY_RESPONSE")
                if (request.jsonMode) onToken(staged.toString())
                if (cacheKey != null) try { cacheWrite?.invoke(cacheKey, response.body) } catch (_: Exception) { }
                return GatewayResult(response.body, model = model)
            } catch (_: Exception) {
                if (cancelled.get()) return cancelledResult(model)
                return fail("NETWORK_ERROR")
            }
        }
        return null
    }
    private fun cancelledResult(model: String): GatewayResult { lastReason = "CANCELLED"; return GatewayResult("", model = model, cancelled = true) }
    private fun fail(reason: String): GatewayResult? { lastReason = reason; return null }
    private fun currentKey(): String? = try {
        val provider = injectedKeyProvider
        if (provider != null) provider() else settings.key()
    } catch (_: Exception) { null }
    private fun isNetworkAvailable(): Boolean { return try {
        injectedNetworkAvailable?.invoke() ?: run {
            val manager = settingsContext().getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
            val network = manager.activeNetwork ?: return false
            val caps = manager.getNetworkCapabilities(network) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        }
    } catch (_: Exception) { false } }
    private fun settingsContext(): Context = settings.networkContext()
    private fun validJson(value: String): Boolean = try {
        val parser = org.json.JSONTokener(value)
        parser.nextValue() is JSONObject && parser.nextClean() == '\u0000'
    } catch (_: Exception) { false }
    private fun auditSend(cls: String, category: String, bytes: Int) { audit("groq", cls, category, bytes) }
    private fun observe(model: String, reply: HttpReply) {
        val headers = reply.headers.mapKeys { it.key.lowercase(Locale.ROOT) }
        synchronized(lock) {
            val q = refreshedQuota(model) ?: Quota().also { quota[quotaKey(model)] = it }
            headers["x-ratelimit-remaining-requests"]?.toIntOrNull()?.let { q.remaining = it }
            headers["x-ratelimit-remaining-tokens"]?.toIntOrNull()?.let { q.tokens = it }
            val requestReset = headers["x-ratelimit-reset-requests"]?.let(::parseReset)
            val tokenReset = headers["x-ratelimit-reset-tokens"]?.let(::parseReset)
            if (requestReset != null) q.requestReset = requestReset
            if (tokenReset != null) q.tokenReset = tokenReset
            if (q.remaining == 0 && q.requestReset == 0L) q.requestReset = System.currentTimeMillis() + 30_000
            if (q.tokens == 0 && q.tokenReset == 0L) q.tokenReset = System.currentTimeMillis() + 30_000
            val reset = listOfNotNull(requestReset, tokenReset).maxOrNull()
            q.resetAt = maxOf(q.requestReset, q.tokenReset)
            val retry = headers["retry-after"]?.let(::retryAfter)
            if (reply.code == 429) {
                val until = retry ?: reset ?: (System.currentTimeMillis() + 30_000)
                q.pausedUntil = maxOf(q.pausedUntil, until + Random.nextLong(0, 1500))
            } else if (reply.code in 200..299) {
                // A quota count by itself is informational; a successful request does not open a circuit.
                if (q.pausedUntil <= System.currentTimeMillis()) q.pausedUntil = 0
            }
            saveQuota(model, q)
        }
    }
    // Exhausted counts become unknown after their own reset, allowing a new user probe.
    // This prevents an observed zero from permanently disabling cloud for this process.
    private fun refreshedQuota(model: String): Quota? {
        val q = quota[quotaKey(model)] ?: loadQuota(model)?.also { quota[quotaKey(model)] = it } ?: return null
        val now = System.currentTimeMillis()
        if (q.requestReset > 0 && q.requestReset <= now) { q.remaining = null; q.requestReset = 0 }
        if (q.tokenReset > 0 && q.tokenReset <= now) { q.tokens = null; q.tokenReset = 0 }
        if (q.pausedUntil <= now) q.pausedUntil = 0
        return q
    }

    private fun saveQuota(model: String, q: Quota) {
        val value = JSONObject().put("remaining", q.remaining ?: JSONObject.NULL)
            .put("tokens", q.tokens ?: JSONObject.NULL).put("reset", q.resetAt)
            .put("paused", q.pausedUntil).put("requests_reset", q.requestReset).put("tokens_reset", q.tokenReset)
        quotaPrefs.edit().putString(hash(quotaKey(model)), value.toString()).apply()
    }
    private fun loadQuota(model: String): Quota? = try {
        val stored = quotaPrefs.getString(hash(quotaKey(model)), null)
        if (stored == null) null else {
            val value = JSONObject(stored)
            Quota(if (value.isNull("remaining")) null else value.getInt("remaining"),
                if (value.isNull("tokens")) null else value.getInt("tokens"), value.optLong("reset"),
                value.optLong("paused"), value.optLong("requests_reset"), value.optLong("tokens_reset"))
        }
    } catch (_: Exception) { null }

    private fun retryAfter(value: String): Long? {
        value.trim().toLongOrNull()?.let { return System.currentTimeMillis() + it * 1000 }
        return try { ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() } catch (_: Exception) { null }
    }
    internal fun parseReset(value: String): Long? {
        value.trim().toLongOrNull()?.let { return if (it < 100_000_000_000L) System.currentTimeMillis() + it * 1000 else it }
        val matcher = Regex("(?:(\\d+(?:\\.\\d+)?)h)?(?:(\\d+(?:\\.\\d+)?)m)?(?:(\\d+(?:\\.\\d+)?)s)?").matchEntire(value.trim()) ?: return null
        val millis = ((matcher.groupValues[1].toDoubleOrNull() ?: 0.0) * 3600 + (matcher.groupValues[2].toDoubleOrNull() ?: 0.0) * 60 + (matcher.groupValues[3].toDoubleOrNull() ?: 0.0)) * 1000
        return if (millis > 0) System.currentTimeMillis() + millis.toLong() else null
    }
    private fun deferKey(request: GatewayRequest) = "${request.category}:${request.smart}:${request.turns.hashCode()}"
    private fun quotaKey(model: String) = "${validBase(settings.baseUrl).orEmpty()}:$model"
    private fun hash(value: String) = java.security.MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
}
