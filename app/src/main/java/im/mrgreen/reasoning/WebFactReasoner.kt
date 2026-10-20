package im.mrgreen.reasoning

import java.util.Locale

/** Strict, deliberately small grammar. Unsupported language remains quoted evidence, not a fact. */
internal object WebFactBridge {
    private const val WORD = "[A-Za-z][A-Za-z0-9_]{0,23}"
    private val askPattern = Regex("(?i)^Is ($WORD) (?:a |an )?($WORD)$")
    private val factPattern = Regex("(?i)^($WORD) is (?:a |an )?($WORD)$")
    private val rulePattern = Regex("(?i)^All ($WORD) are ($WORD)$")
    private val uncertain = Regex("(?i)\\b(not|no|never|might|may|could|possibly|allegedly|reportedly|probably|perhaps|seems|appears)\\b")
    data class Premise(val url: String, val subject: String, val predicate: String, val universal: Boolean, val claim: String)
    data class Plan(val entity: String, val type: String, val used: List<Premise>) {
        val target get() = "<${atom(entity)} --> ${atom(type)}>"
        val inputs get() = used.map { p ->
            if (p.universal) "<<\$x --> ${atom(p.subject)}> ==> <\$x --> ${atom(p.predicate)}>>."
            else "<${atom(p.subject)} --> ${atom(p.predicate)}>."
        }
    }
    fun plan(question: String, sources: List<Pair<String, String>>): Plan? {
        val ask = askPattern.matchEntire(question.trim().removeSuffix("?").trim()) ?: return null
        val entity = ask.groupValues[1]
        val type = ask.groupValues[2]
        val facts = mutableListOf<Premise>()
        val rules = mutableListOf<Premise>()
        val uncertainClaims = mutableListOf<String>()
        for ((url, text) in sources.take(2)) {
            if (url.isBlank() || url.length > 2048) continue
            for (raw in text.take(12000).split(Regex("(?<=[.!?])\\s+|[\\n\\r]+")).take(100)) {
                // Questions are not declarative premises. Never discard their punctuation first.
                val sentence = raw.trim()
                if (sentence.endsWith('?') || sentence.length > 200) continue
                val claim = sentence.removeSuffix(".").trim()
                if (uncertain.containsMatchIn(claim)) { uncertainClaims.add(claim); continue }
                val rule = rulePattern.matchEntire(claim)
                if (rule != null && facts.size + rules.size < 32) {
                    rules.add(Premise(url, singular(rule.groupValues[1]), singular(rule.groupValues[2]), true, claim))
                    continue
                }
                val fact = factPattern.matchEntire(claim) ?: continue
                if (facts.size + rules.size < 32)
                    facts.add(Premise(url, fact.groupValues[1], fact.groupValues[2], false, claim))
            }
        }
        val direct = facts.firstOrNull { same(it.subject, entity) && same(it.predicate, type) }
        val derived = rules.asSequence().filter { same(it.predicate, type) }.mapNotNull { rule ->
            facts.firstOrNull { same(it.subject, entity) && same(it.predicate, rule.subject) }?.let { listOf(it, rule) }
        }.firstOrNull()
        val used = direct?.let { listOf(it) } ?: derived ?: return null
        // Relevant contrary or hedged text prevents an affirmative logical answer. Extraction can
        // still show it verbatim. This is deliberately overcautious, not contradiction resolution.
        val relevantWords = used.flatMap { listOf(it.subject, it.predicate) }.map { atom(it) }.toSet()
        if (uncertainClaims.any { claim ->
                claim.lowercase(Locale.ROOT).split(Regex("[^a-z0-9_]+"))
                    .count { singular(it) in relevantWords || it in relevantWords } >= 2
            }) return null
        return Plan(entity, type, used)
    }
    private fun same(a: String, b: String) = a.equals(b, true)
    // Only the common regular plural of universal category labels is supported; no fuzzy matching,
    // irregular English morphology, synonyms, multiword entities or general-purpose NLP is claimed.
    private fun singular(word: String): String {
        val low = word.lowercase(Locale.ROOT)
        if (low in setOf("species", "series", "news") || low.endsWith("ss") || low.endsWith("us") || low.endsWith("is")) return low
        return if (low.length > 3 && low.endsWith('s')) low.dropLast(1) else low
    }
    private fun atom(word: String) = word.lowercase(Locale.ROOT)
}

/** Source claims are temporary. An answer is emitted only if the real local ONA core agrees. */
object WebFactReasoner {
    @Synchronized
    fun reason(question: String, sources: List<Pair<String, String>>): String? {
        val plan = WebFactBridge.plan(question, sources) ?: return null
        if (!OnaNative.available()) return null
        try {
            if (!OnaNative.reset()) return null
            for (input in plan.inputs) if (!OnaNative.add(input)) return null
            val actual = OnaNative.query("${plan.target}?", 32)?.trim() ?: return null
            if (!actual.startsWith(plan.target)) return null
            val explanation = if (plan.used.size == 1) "Direct affirmative source claim, checked with local OpenNARS"
                else "Local OpenNARS one-step deduction from the source premises"
            val provenance = plan.used.joinToString("; ") { "\"${it.claim}\" (${it.url})" }
            return "${plan.entity} is ${plan.type}. $explanation; these are source claims, not independently verified facts: $provenance."
        } catch (_: UnsatisfiedLinkError) { return null }
        catch (_: RuntimeException) { return null }
        finally { try { OnaNative.reset() } catch (_: UnsatisfiedLinkError) { } }
    }
}

private object OnaNative {
    private var loaded = false
    private var attempted = false
    @Synchronized fun available(): Boolean {
        if (!attempted) {
            attempted = true
            try { System.loadLibrary("mrgreen_ona"); loaded = true }
            catch (_: UnsatisfiedLinkError) { loaded = false }
        }
        return loaded
    }
    external fun reset(): Boolean
    external fun add(sentence: String): Boolean
    external fun query(sentence: String, cycles: Int): String?
}
