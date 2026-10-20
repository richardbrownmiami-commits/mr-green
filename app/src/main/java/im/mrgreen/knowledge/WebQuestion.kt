package im.mrgreen.knowledge

import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Locale

/** Parsed explicit web question. A null pageUrl means search the public web. */
data class WebQuestion(val question: String, val pageUrl: String? = null)

/** Null only for unrelated input; malformed `ask web` commands throw IllegalArgumentException. */
data class WebQuestionRequest(val question: String, val url: String? = null) {
    companion object {
        fun parse(text: String): WebQuestionRequest? = when (val cmd = WebQuestionParser.parse(text)) {
            WebQuestionCommand.NotWebQuestion -> null
            is WebQuestionCommand.Help -> throw IllegalArgumentException(cmd.message)
            is WebQuestionCommand.Parsed -> WebQuestionRequest(cmd.request.question, cmd.request.pageUrl)
        }
    }
}

sealed class WebQuestionCommand {
    data class Parsed(val request: WebQuestion) : WebQuestionCommand()
    data class Help(val message: String = "Use: ask web QUESTION (up to 300 characters), or ask web https://url | QUESTION") : WebQuestionCommand()
    object NotWebQuestion : WebQuestionCommand()
}

/** Pure command parser; malformed ask-web commands are distinguishable from unrelated input. */
object WebQuestionParser {
    fun parse(input: String): WebQuestionCommand {
        val raw = input.trim()
        if (!raw.startsWith("ask web", ignoreCase = true) || (raw.length > 7 && !raw[7].isWhitespace())) return WebQuestionCommand.NotWebQuestion
        val remainder = raw.drop(7).trim()
        if (remainder.isEmpty()) return WebQuestionCommand.Help()
        val split = remainder.indexOf('|')
        val first = if (split >= 0) remainder.substring(0, split).trim() else ""
        val question = if (split >= 0) remainder.substring(split + 1).trim() else remainder
        if (question.isBlank() || question.length > WebQuestionRetriever.MAX_QUESTION_CHARS) return WebQuestionCommand.Help()
        if (split < 0) return WebQuestionCommand.Parsed(WebQuestion(question))
        if (first.length > WebQuestionRetriever.MAX_URL_CHARS) return WebQuestionCommand.Help()
        val url = try { validateHttps(first, checkDns = false) } catch (_: Exception) { return WebQuestionCommand.Help() }
        return WebQuestionCommand.Parsed(WebQuestion(question, url))
    }
}

/** Bounded retrieval plus extractive fallback. No database, files, cloud model, or persistent state. */
class WebQuestionRetriever(
    private val transport: (url: String, maxBytes: Int) -> String = { url, max ->
        String(PublicWebTransport.request(url, max, requireText = true).bytes, Charsets.UTF_8)
    },
    private val isCancelled: () -> Boolean = { false },
    private val reasoner: ((String, List<Pair<String, String>>) -> String?)? = null
) {
    fun answer(request: WebQuestion, cancelled: () -> Boolean = isCancelled): String {
        require(request.question.isNotBlank() && request.question.length <= MAX_QUESTION_CHARS) { "Question must be 1–300 characters." }
        check(!cancelled()) { "Web question cancelled." }
        val sources = ArrayList<Pair<String, String>>()
        var encyclopediaFallback = false
        if (request.pageUrl != null) {
            val url = validateHttps(request.pageUrl)
            sources.add(url to cleanText(transport(url, MAX_PAGE_BYTES)))
        } else {
            val searchUrl = "https://html.duckduckgo.com/html/?q=" + URLEncoder.encode(request.question, "UTF-8")
            val links = try { searchLinks(transport(searchUrl, MAX_SEARCH_BYTES)) } catch (_: Exception) { emptyList() }
            for (url in links.take(MAX_SOURCES)) {
                check(!cancelled()) { "Web question cancelled." }
                try { sources.add(url to cleanText(transport(validateHttps(url), MAX_PAGE_BYTES))) } catch (_: Exception) { /* Skip failed/unsafe candidates. */ }
            }
            if (sources.isEmpty()) {
                check(!cancelled()) { "Web question cancelled." }
                encyclopediaFallback = true
                sources.addAll(wikipediaSources(request.question, cancelled))
            }
        }
        check(!cancelled()) { "Web question cancelled." }
        try { reasoner?.invoke(request.question, sources)?.takeIf { it.isNotBlank() }?.let { return it.take(MAX_ANSWER_CHARS) } } catch (_: Exception) { }
        val ranked = sources.flatMapIndexed { sourceIndex, (url, text) ->
            sentences(text).mapNotNull { sentence ->
                val score = relevance(request.question, sentence)
                if (score > 0) Evidence(sentence, url, score, sourceIndex) else null
            }
        }.sortedWith(compareByDescending<Evidence> { it.score }.thenBy { it.sourceIndex })
            .distinctBy { it.text.lowercase(Locale.ROOT) }.take(MAX_ANSWER_SENTENCES)
        if (ranked.isEmpty()) return "I couldn't find a relevant answer in the retrieved page text. No answer is available from these sources."
        val refs = ranked.map { it.url }.distinct()
        return buildString {
            append(if (encyclopediaFallback) "Wikipedia fallback source (modest confidence; not independently verified):\n" else "Based on the retrieved page text (modest confidence; not independently verified):\n")
            ranked.forEach { append("\"${it.text}\" [${refs.indexOf(it.url) + 1}]\n") }
            append(refs.mapIndexed { i, url -> "[${i + 1}] $url" }.joinToString("\n"))
        }.trim()
    }

    /** Main-controller API matching WebQuestionRequest.parse. */
    fun answer(request: WebQuestionRequest, cancelled: () -> Boolean = isCancelled): String =
        answer(WebQuestion(request.question, request.url), cancelled)

    private data class Evidence(val text: String, val url: String, val score: Int, val sourceIndex: Int)

    /** Search titles/page IDs only, then retrieve article summaries; search snippets are discarded. */
    private fun wikipediaSources(question: String, cancelled: () -> Boolean): List<Pair<String, String>> {
        val q = URLEncoder.encode(question, "UTF-8")
        val api = "https://en.wikipedia.org/w/api.php?action=query&list=search&srsearch=$q&format=json&srlimit=$MAX_SOURCES"
        val json = org.json.JSONObject(transport(api, MAX_SEARCH_BYTES))
        val results = json.optJSONObject("query")?.optJSONArray("search") ?: return emptyList()
        val out = ArrayList<Pair<String, String>>()
        for (i in 0 until minOf(results.length(), MAX_SOURCES)) {
            check(!cancelled()) { "Web question cancelled." }
            val title = results.optJSONObject(i)?.optString("title").orEmpty()
            if (title.isBlank() || title.length > 300) continue
            val encodedTitle = URLEncoder.encode(title, "UTF-8").replace("+", "%20")
            val summaryUrl = "https://en.wikipedia.org/api/rest_v1/page/summary/$encodedTitle"
            try {
                val summary = org.json.JSONObject(transport(summaryUrl, MAX_WIKI_BYTES))
                val extract = summary.optString("extract").take(MAX_PAGE_CHARS)
                val source = summary.optJSONObject("content_urls")?.optJSONObject("desktop")?.optString("page")
                    ?.takeIf { it.startsWith("https://en.wikipedia.org/") } ?: "https://en.wikipedia.org/wiki/$encodedTitle"
                if (extract.isNotBlank()) out.add(source to cleanText(extract))
            } catch (_: Exception) { /* Skip unavailable article; never cite the search result snippet. */ }
        }
        return out
    }

    private fun searchLinks(html: String): List<String> {
        val out = LinkedHashSet<String>()
        val pattern = Regex("(?is)<a\\b[^>]*class=[\"'][^\"']*result__a[^\"']*[\"'][^>]*href=[\"']([^\"']+)[\"']")
        for (m in pattern.findAll(html).take(20)) {
            val href = decodeEntities(m.groupValues[1])
            val destination = Regex("(?:\\?|&)uddg=([^&]+)").find(href)?.groupValues?.get(1)?.let {
                try { URLDecoder.decode(it, "UTF-8") } catch (_: Exception) { "" }
            } ?: href
            try { out.add(validateHttps(destination)) } catch (_: Exception) { }
            if (out.size >= MAX_SOURCES) break
        }
        return out.toList()
    }

    private fun cleanText(raw: String): String = decodeEntities(
        raw.take(MAX_PAGE_CHARS * 4)
            .replace(Regex("(?is)<(script|style|noscript|svg|template)\\b[^>]*>.*?</\\1\\s*>"), " ")
            .replace(Regex("(?is)<!--.*?-->"), " ")
            .replace(Regex("(?i)</?(?:p|div|section|article|li|h[1-6]|br|tr|blockquote)\\b[^>]*>"), "\n")
            .replace(Regex("(?s)<[^>]{0,2048}>"), " ")
    ).replace(Regex("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F]"), " ")
        .replace(Regex("[ \\t\\x0B\\f]+"), " ").replace(Regex(" *\\n+ *"), "\n").trim().take(MAX_PAGE_CHARS)

    private fun sentences(text: String): List<String> = text.split(Regex("\\n+|(?<=[.!?])\\s+(?=[\\p{Lu}\\d\"'])|(?<=[.!?])$"))
        .map { it.trim().take(700) }.filter { it.length in 25..700 }.take(MAX_SENTENCES)

    private fun relevance(question: String, sentence: String): Int {
        val questionWords = tokens(question)
        val terms = questionWords.filter { it.length > 2 && it !in STOP_WORDS }.distinct().take(30)
        if (terms.isEmpty()) return 0
        val sentenceWords = tokens(sentence).toSet()
        // Small inflection tolerance affects passage ranking only, never logical fact translation.
        val hits = terms.count { term -> sentenceWords.any { word ->
            word == term || word == term + "s" || word == term + "es" ||
                term == word + "s" || term == word + "es"
        } }
        val causalAsk = questionWords.any { it in CAUSAL_QUESTION_WORDS }
        val causalEvidence = sentenceWords.any { it in CAUSAL_EVIDENCE_WORDS }
        return if (hits == 0) 0 else hits * 10 + if (causalAsk && causalEvidence) 3 else 1
    }

    private fun tokens(s: String): List<String> = s.lowercase(Locale.ROOT).split(Regex("[^\\p{L}\\p{N}]+"))

    private fun decodeEntities(s: String): String = s
        .replace(Regex("&#(x[0-9a-fA-F]+|[0-9]+);?")) { m ->
            try { String(Character.toChars(if (m.groupValues[1].startsWith("x", true)) m.groupValues[1].substring(1).toInt(16) else m.groupValues[1].toInt())) } catch (_: Exception) { " " }
        }.replace("&nbsp;", " ", true).replace("&#160;", " ", true)
        .replace("&amp;", "&", true).replace("&lt;", "<", true).replace("&gt;", ">", true)
        .replace("&quot;", "\"", true).replace("&#39;", "'", true).replace("&apos;", "'", true)

    companion object {
        const val MAX_QUESTION_CHARS = 300
        const val MAX_URL_CHARS = 2048
        const val MAX_PAGE_BYTES = 512 * 1024
        const val MAX_SEARCH_BYTES = 256 * 1024
        const val MAX_WIKI_BYTES = 128 * 1024
        const val MAX_PAGE_CHARS = 120_000
        const val MAX_SOURCES = 2
        const val MAX_SENTENCES = 1_000
        const val MAX_ANSWER_SENTENCES = 3
        const val MAX_ANSWER_CHARS = 4_000
        private val STOP_WORDS = setOf("what", "why", "how", "does", "do", "did", "are", "is", "was", "were", "the", "which", "this", "that", "these", "those", "who", "when", "where", "can", "could", "would", "should", "will", "about", "from", "with", "into", "have", "has", "had", "tell", "me", "please", "and", "for", "you")
        private val CAUSAL_QUESTION_WORDS = setOf("why", "cause", "causes", "caused", "reason", "reasons")
        private val CAUSAL_EVIDENCE_WORDS = setOf("because", "causes", "caused", "cause", "causing", "reason", "reasons", "due", "therefore", "leads", "led", "result", "results", "resulted", "explains", "explained", "explanation")
    }
}

private fun validateHttps(raw: String, checkDns: Boolean = true): String {
    require(raw.length <= WebQuestionRetriever.MAX_URL_CHARS)
    val u = URL(raw.trim())
    require(u.protocol.equals("https", true) && u.userInfo == null && u.host.isNotBlank()) { "Only public HTTPS URLs are allowed." }
    val host = u.host.removePrefix("[").removeSuffix("]")
    require(host.lowercase(Locale.ROOT) !in setOf("localhost", "localhost.localdomain") && !host.endsWith(".local", true))
    if (checkDns) PublicDns().lookup(host)
    return u.toString()
}
