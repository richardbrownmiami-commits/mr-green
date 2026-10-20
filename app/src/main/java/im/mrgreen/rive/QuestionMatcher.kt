package im.mrgreen.rive

import java.text.Normalizer
import java.util.Locale

/** Surface-form matching only; never a semantic similarity or spelling-probability model. */
class QuestionMatcher(triggers: Collection<String>) {
    data class Match(val trigger: String, val typoCorrected: Boolean)

    // Keep original keys: loading an upgrade must not migrate, merge or overwrite the owner's rules.
    private val aliases = triggers.distinct().mapNotNull { original ->
        normalize(original)?.let { (correctedAlias(it) ?: it) to original }
    }.groupBy({ it.first }, { it.second })
    private val literals = triggers.distinct().mapNotNull { original ->
        normalize(original)?.let { it to original }
    }.groupBy({ it.first }, { it.second })

    fun hasEquivalent(question: String): Boolean = normalize(question)?.let { (correctedAlias(it) ?: it) in aliases } == true

    /** New preprocessor candidates must resolve without a second spelling guess. */
    fun matchLiteral(question: String): Match? {
        val normalized = normalize(question) ?: return null
        if ((aliases[normalized]?.size ?: 0) > 1) return null
        val original = literals[normalized]?.singleOrNull() ?: return null
        return Match(original, false)
    }

    fun match(question: String): Match? {
        val normalized = normalize(question) ?: return null
        val alias = correctedAlias(normalized) ?: normalized
        // Even an exact-looking legacy key must not mask a normalized alias collision.
        val original = aliases[alias]?.singleOrNull() ?: return null
        return Match(original, normalized != alias || normalize(original) != alias)
    }

    private fun correctedAlias(normalized: String): String? {
        val tokens = normalized.split(' ')
        val changed = tokens.indices.filter { tokens[it] in commonTypos }
        if (changed.size != 1) return null
        val corrected = tokens.toMutableList()
        corrected[changed.single()] = commonTypos.getValue(tokens[changed.single()])
        val candidate = corrected.joinToString(" ")
        // One adjacent transposition counts as one typo. This is an edit-similarity threshold,
        // not a claim of 94% semantic confidence. Short queries intentionally fall through.
        val distance = singleEditDistance(normalized, candidate) ?: return null
        val length = maxOf(normalized.length, candidate.length)
        if (length == 0 || (length - distance).toDouble() / length < MIN_SIMILARITY) return null
        return candidate
    }

    companion object {
        const val MIN_SIMILARITY = 0.94
        private val words = Regex("[\\p{L}\\p{M}\\p{N}]+(?: [\\p{L}\\p{M}\\p{N}]+)*")
        private val spaces = Regex("\\s+")
        private val contractions = mapOf(
            "can't" to "can not", "cannot" to "can not", "won't" to "will not",
            "don't" to "do not", "doesn't" to "does not", "didn't" to "did not",
            "isn't" to "is not", "aren't" to "are not", "wasn't" to "was not", "weren't" to "were not",
            "haven't" to "have not", "hasn't" to "has not", "hadn't" to "had not",
            "couldn't" to "could not", "wouldn't" to "would not", "shouldn't" to "should not",
            "mustn't" to "must not", "needn't" to "need not",
            "i'm" to "i am", "you're" to "you are", "we're" to "we are", "they're" to "they are",
            "i've" to "i have", "you've" to "you have", "we've" to "we have", "they've" to "they have",
            "i'll" to "i will", "you'll" to "you will", "we'll" to "we will", "they'll" to "they will",
            "he'll" to "he will", "she'll" to "she will", "it'll" to "it will"
        )
        // Deliberately small, directional and reviewed. Never autocorrect real-word pairs
        // (form/from, casual/causal, capital/capitol), names, numbers, or negation words.
        // hwo, wich and capitl are omitted because their intended correction is ambiguous.
        private val commonTypos = mapOf(
            "waht" to "what", "whta" to "what", "whatt" to "what",
            "whcih" to "which", "whihc" to "which", "wihch" to "which",
            "wherre" to "where", "wheere" to "where", "whree" to "where",
            "teh" to "the", "hte" to "the", "thhe" to "the", "adn" to "and",
            "pleaes" to "please", "pleaase" to "please",
            "captial" to "capital", "capitla" to "capital", "capiatl" to "capital", "capittal" to "capital",
            "passwrod" to "password", "pasword" to "password", "paassword" to "password",
            "settigns" to "settings", "setttings" to "settings",
            "defintion" to "definition", "definiton" to "definition",
            "diffrence" to "difference", "differnce" to "difference",
            "installtion" to "installation", "instalation" to "installation",
            "applciation" to "application", "applicaiton" to "application",
            "understnad" to "understand", "understannd" to "understand",
            "answre" to "answer", "udpate" to "update", "upadte" to "update", "updatte" to "update",
            "edti" to "edit", "importt" to "import", "reolad" to "reload", "relaod" to "reload",
            "learnign" to "learning"
        )

        fun normalize(question: String): String? {
            if (question.length > 1024) return null // Bound work before Unicode expansion.
            // Compatibility folding must not reinterpret superscripts/fractions as plain numbers.
            if (question.any { it.category == CharCategory.OTHER_NUMBER || it.category == CharCategory.LETTER_NUMBER }) return null
            var value = Normalizer.normalize(question, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
                .replace('\u2019', '\'').replace('\u2018', '\'')
                .replace('\u00a0', ' ').trim()
            // Wrapping quotes are decoration; apostrophes inside words are never deleted.
            if (value.any { it in "\"\u201c\u201d" }) {
                val paired = value.length >= 2 &&
                    ((value.first() == '"' && value.last() == '"') ||
                        (value.first() == '\u201c' && value.last() == '\u201d'))
                if (!paired) return null
                value = value.substring(1, value.lastIndex).trim()
                if (value.any { it in "\"\u201c\u201d" }) return null
            }
            value = value.trimEnd('.', '!', '?').trim()
            // A greeting/courtesy comma is decoration. Other internal punctuation can carry
            // meaning (numbers, ratios, lists, clauses) and intentionally goes to generation.
            value = value.replace(Regex("^(hello|hi|hey|please),\\s+"), "$1 ")
            value = value.replace(spaces, " ").trim()
            val expanded = value.split(' ').joinToString(" ") { contractions[it] ?: it }
            // Unknown apostrophes ('s, 'd, possessives) stay unsupported rather than guessed.
            return expanded.takeIf { it.length in 1..256 && words.matches(it) }
        }

        /** Return 1 only for one insertion/deletion/substitution or adjacent transposition. */
        private fun singleEditDistance(a: String, b: String): Int? {
            if (a == b) return 0
            if (kotlin.math.abs(a.length - b.length) > 1) return null
            val first = a.indices.firstOrNull { it >= b.length || a[it] != b[it] } ?: a.length
            if (a.length == b.length) {
                if (a.substring(first + 1) == b.substring(first + 1)) return 1
                if (first + 1 < a.length && a[first] == b[first + 1] && a[first + 1] == b[first] &&
                    a.substring(first + 2) == b.substring(first + 2)) return 1
            } else if (a.length > b.length) {
                if (a.substring(first + 1) == b.substring(first)) return 1
            } else if (a.substring(first) == b.substring(first + 1)) return 1
            return null
        }
    }
}
