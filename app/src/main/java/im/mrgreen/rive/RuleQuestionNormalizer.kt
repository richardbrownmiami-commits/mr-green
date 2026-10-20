package im.mrgreen.rive

/**
 * Read-only lookup helper, not a language model, translator or action router.
 * Only whole reviewed question forms are expanded. Unknown/ambiguous text is left alone.
 * All tables below are original project code; no HinglishNorm dataset/model is bundled.
 */
object RuleQuestionNormalizer {
    data class Candidate(val question: String, val form: Form)
    enum class Form { ENGLISH, HINGLISH }

    private val words = Regex("[a-z]+(?: [a-z]+)*")
    // A closed subject list avoids treating a name, command, number or negated clause as a noun.
    private val subjects = setOf(
        "engine", "model", "language model", "battery", "memory", "ram", "storage",
        "rivescript", "normalizer", "normalization", "hinglish", "english",
        "photosynthesis", "artificial intelligence", "machine learning"
    )
    private val subjectTypos = mapOf(
        "engien" to "engine", "enigne" to "engine", "modle" to "model",
        "batery" to "battery", "memroy" to "memory", "storag" to "storage",
        "englsih" to "english", "hinglsih" to "hinglish"
    )
    // Full strings, not global substitutions: ur/u/r/m/k/h can be names, units or Hindi words.
    private val phrases = mapOf(
        "what is ur name" to "what is your name",
        "wht is ur name" to "what is your name",
        "whats ur name" to "what is your name",
        "what is your nam" to "what is your name",
        "who r u" to "who are you",
        "who are u" to "who are you",
        "how r u" to "how are you",
        "hw r u" to "how are you",
        "how are u" to "how are you"
    )
    private val hinglishPhrases = mapOf(
        "tumhara naam kya hai" to "what is your name",
        "tumhara nam kya hai" to "what is your name",
        "aapka naam kya hai" to "what is your name",
        "apka naam kya hai" to "what is your name",
        "tum kaun ho" to "who are you",
        "aap kaun ho" to "who are you",
        "tum kaise ho" to "how are you",
        "aap kaise ho" to "how are you"
    )
    private val englishDefinition = Regex("(?:what is|wht is|waht is|whats) (.+)")
    private val hinglishDefinition = Regex("(.+) (?:kya|kyaa|kyaaa) (?:hai|h|he|haii)")

    /** Null means no approved correction, not a failed request or a guessed interpretation. */
    fun candidate(original: String): Candidate? {
        val question = QuestionMatcher.normalize(original) ?: return null
        if (!words.matches(question)) return null
        phrases[question]?.let { return Candidate(it, Form.ENGLISH) }
        hinglishPhrases[question]?.let { return Candidate(it, Form.HINGLISH) }
        englishDefinition.matchEntire(question)?.let { match ->
            val subject = subject(match.groupValues[1]) ?: return null
            val result = "what is $subject"
            return if (result != question) Candidate(result, Form.ENGLISH) else null
        }
        hinglishDefinition.matchEntire(question)?.let { match ->
            val subject = subject(match.groupValues[1]) ?: return null
            return Candidate("what is $subject", Form.HINGLISH)
        }
        return null
    }

    private fun subject(value: String): String? = (subjectTypos[value] ?: value).takeIf { it in subjects }
}

/** Prefer existing matches, refuse collisions, and require a literal target for a new candidate. */
class RuleQuestionLookup(triggers: Collection<String>) {
    data class Match(val trigger: String, val typoCorrected: Boolean, val questionNormalized: Boolean)
    private val matcher = QuestionMatcher(triggers)

    fun match(original: String): Match? {
        matcher.match(original)?.let { return Match(it.trigger, it.typoCorrected, false) }
        // An ambiguous existing alias is not permission to pick a different answer.
        if (matcher.hasEquivalent(original)) return null
        val candidate = RuleQuestionNormalizer.candidate(original) ?: return null
        val match = matcher.matchLiteral(candidate.question) ?: return null
        return Match(match.trigger, match.typoCorrected, true)
    }

    // Learning retains its original policy; the preprocessor never creates/rewrites rules.
    fun hasEquivalent(question: String): Boolean = matcher.hasEquivalent(question)
}
