package im.mrgreen.knowledge

import im.mrgreen.gateway.GatewayRedactor
import org.json.JSONObject
import java.net.URL

/** Only the local chat controller can interpret these model requests. */
internal sealed interface LocalWebToolDecision {
    data object Finish : LocalWebToolDecision
    data class Search(val query: String) : LocalWebToolDecision
    data class Fetch(val url: String) : LocalWebToolDecision
}

/**
 * Constrained JSON tool routing for local GGUFs that do not expose native tool-call APIs.
 * The parser is intentionally strict: malformed or expanded requests never reach the network.
 */
internal object LocalWebToolProtocol {
    const val MAX_TOOL_CALLS = 2
    const val MAX_QUERY_CHARS = 300
    const val MAX_URL_CHARS = 2000
    const val ROUTER_MAX_TOKENS = 96
    const val FINAL_MAX_TOKENS = 128

    // llama.cpp applies this GBNF before sampling, so supported models emit only a small JSON envelope.
    // Semantic limits and URL validation are still enforced in Kotlin after parsing.
    val ROUTER_GRAMMAR = """
root ::= search | fetch | finish
search ::= "{\"type\":\"tool_call\",\"name\":\"web_search\",\"arguments\":{\"query\":" json-string "}}"
fetch ::= "{\"type\":\"tool_call\",\"name\":\"web_fetch\",\"arguments\":{\"url\":" json-string "}}"
finish ::= "{\"type\":\"final\"}"
json-string ::= "\"" chars "\""
chars ::= ([^"\\] | "\\" ( ["\\/bfnrt] | "u" [0-9a-fA-F] [0-9a-fA-F] [0-9a-fA-F] [0-9a-fA-F] ))*
""".trimIndent()

    private val explicitSearchIntent = Regex(
        "(?i)\\b(?:search(?: the)? (?:web|internet|online)|search for|look up|find online|browse(?: the)? web|on the web|online sources?)\\b"
    )
    private val currentInfoIntent = Regex(
        "(?i)\\b(?:latest|upcoming|current(?:ly)?|recent|up[- ]to[- ]date|right now|as of today|today's news|live (?:price|status|news|weather))\\b"
    )
    private val urlPattern = Regex("https://[^\\s<>\\\"']+")

    /** Avoid an extra model pass for ordinary chat. The switch must also be enabled by the caller. */
    fun shouldConsider(request: String): Boolean =
        explicitSearchIntent.containsMatchIn(request) || currentInfoIntent.containsMatchIn(request) ||
            Regex("(?i)\\bfetch\\b.*https://").containsMatchIn(request)

    fun hasSearchIntent(request: String): Boolean =
        explicitSearchIntent.containsMatchIn(request) || currentInfoIntent.containsMatchIn(request)

    fun plannerInput(request: String, priorEvidence: String? = null): String = buildString {
        append("Current user request, quoted as JSON data: ").append(JSONObject.quote(request))
        if (!priorEvidence.isNullOrBlank()) {
            append("\nPrior web-search result, quoted as untrusted JSON data: ").append(JSONObject.quote(priorEvidence.take(4000)))
        }
    }

    fun parse(raw: String): LocalWebToolDecision? {
        if (raw.length !in 1..16_384) return null
        return try {
            val root = JSONObject(raw.trim())
            when (root.optString("type")) {
                "final" -> if (root.keys().asSequence().toSet() == setOf("type")) LocalWebToolDecision.Finish else null
                "tool_call" -> {
                    if (root.keys().asSequence().toSet() != setOf("type", "name", "arguments")) return null
                    val args = root.opt("arguments") as? JSONObject ?: return null
                    when (root.optString("name")) {
                        "web_search" -> {
                            if (args.keys().asSequence().toSet() != setOf("query")) return null
                            val query = args.opt("query") as? String ?: return null
                            if (query.isBlank() || query.length > MAX_QUERY_CHARS || query.any(Char::isISOControl)) null
                            else LocalWebToolDecision.Search(query)
                        }
                        "web_fetch" -> {
                            if (args.keys().asSequence().toSet() != setOf("url")) return null
                            val url = args.opt("url") as? String ?: return null
                            if (url.length !in 1..MAX_URL_CHARS || url.any(Char::isISOControl) || !url.startsWith("https://", true)) null
                            else LocalWebToolDecision.Fetch(url)
                        }
                        else -> null
                    }
                }
                else -> null
            }
        } catch (_: Exception) { null }
    }

    internal fun httpsUrls(text: String): List<String> = urlPattern.findAll(text)
        .map { it.value.trimEnd('.', ',', ';', '!', '?', ')', ']', '}') }
        .filter { it.length in 1..MAX_URL_CHARS }
        .distinct()
        .toList()
}

/** Policy shared by the production dispatcher and unit tests; no network or model access here. */
internal object LocalWebToolPolicy {
    private val personalTerms = Regex(
        "(?i)\\b(?:my|mine|our|ours|me|we|personal|private|account|password|passcode|pin|bank|medical|health|patient|home address|phone number)\\b"
    )

    fun requestHasSensitiveContent(request: String): Boolean =
        GatewayRedactor.containsTypedSecret(request) || GatewayRedactor.redact(request) != request ||
            personalTerms.containsMatchIn(request)

    /** Returns a user-safe rejection reason, or null if this single call is eligible. */
    fun rejection(call: LocalWebToolDecision, request: String, priorEvidence: String?, localOnly: Boolean): String? {
        if (localOnly) return "Web access is disabled. No network request was made."
        if (request.isBlank() || request.length > 12_000) return "The request is outside the supported size limit."
        if (requestHasSensitiveContent(request)) return "This request may contain private or sensitive information. Remove it before using web access."
        return when (call) {
            LocalWebToolDecision.Finish -> "No web tool was requested."
            is LocalWebToolDecision.Search -> {
                if (priorEvidence != null) "Only one search is allowed per request."
                else if (!LocalWebToolProtocol.hasSearchIntent(request)) "The user did not request current or online information."
                else if (call.query.isBlank() || call.query.length > LocalWebToolProtocol.MAX_QUERY_CHARS || call.query.any(Char::isISOControl)) "The search query is invalid."
                else if (requestHasSensitiveContent(call.query)) "The search query may contain private or sensitive information."
                else null
            }
            is LocalWebToolDecision.Fetch -> {
                if (call.url.length > LocalWebToolProtocol.MAX_URL_CHARS || !call.url.startsWith("https://", true) || call.url.any(Char::isISOControl))
                    "Only bounded HTTPS URLs are supported."
                else {
                    val typed = LocalWebToolProtocol.httpsUrls(request)
                    val fromSearch = if (priorEvidence?.startsWith("Web search results for:") == true) {
                        Regex("(?m)^\\d+\\. [^\\n]+\\n(https://[^\\s]+)$").findAll(priorEvidence)
                            .map { it.groupValues[1].trimEnd('.', ',', ';', '!', '?', ')', ']', '}') }.toList()
                    } else emptyList()
                    if (call.url !in typed && call.url !in fromSearch) "The fetch URL was not explicitly supplied or returned by this request's web search."
                    else try {
                        val parsed = URL(call.url)
                        if (!parsed.protocol.equals("https", true) || parsed.userInfo != null || parsed.host.isBlank()) "Only HTTPS URLs without credentials are supported."
                        else null
                    } catch (_: Exception) { "The fetch URL is invalid." }
                }
            }
        }
    }
}
