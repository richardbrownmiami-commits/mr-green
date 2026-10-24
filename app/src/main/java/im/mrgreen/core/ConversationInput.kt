package im.mrgreen.core

import im.mrgreen.engine.ChatTurn
import im.mrgreen.memory.RecalledSnippet
import org.json.JSONObject
import java.util.Locale

/** History is local-only. Standalone saved replies must not swallow a recall-dependent turn. */
internal object MemoryChatPolicy {
    fun needsRecent(question: String): Boolean {
        val q = question.lowercase(Locale.ROOT).replace('’', '\'').trim()
        return Regex("\\b(remember|recall|earlier|previous|previously|before|just (said|say|told|tell)|last (message|answer|question)|conversation|my (name|favo[u]?rite|birthday|age|home|job|pet|wife|husband|son|daughter))\\b").containsMatchIn(q) ||
            Regex("\\b(what|when|where|which|who|how)\\b.*\\b(did|have) (i|we|you) (say|said|tell|told|ask|asked|mention|mentioned|discuss|discussed)\\b").containsMatchIn(q) ||
            Regex("\\b(what|which|where|who|how|why|can|could|tell|explain|say|do|does|is|was|and|but)\\b.*\\b(it|that|those|them|again|more|instead)\\b").containsMatchIn(q) ||
            Regex("^(and|but|how so|go on|continue|tell me more|say more|what next|repeat|rephrase|translate|shorten|expand)\\b").containsMatchIn(q) ||
            Regex("^(why|yes|no)[?!. ]*$").matches(q) ||
            Regex("(याद|पहले|पिछल|मेरा नाम|मेरी पसंद|उसके|इसके|दोबारा|वही|और बताओ)").containsMatchIn(q)
    }

    fun useLocal(enabled: Boolean, question: String, recalled: List<RecalledSnippet>): Boolean =
        enabled && (needsRecent(question) || recalled.isNotEmpty())
}

internal data class FittedConversationInput(val turns: List<ChatTurn>, val snippets: List<RecalledSnippet>, val tokenCost: Int)

/** Fits the current user input and at most two bounded local history fragments; adds no system instruction. */
internal object ConversationInput {
    fun question(text: String, notes: List<RecalledSnippet>): String = if (notes.isEmpty()) text else
        "<history>\n" + notes.sortedBy { it.messageId }.joinToString("\n") {
            JSONObject().put("role", it.role).put("text", it.text).toString()
        } + "\n</history>\nCurrent question: " + text

    fun fit(text: String, recalled: List<RecalledSnippet>, budget: Int, count: (String) -> Int): FittedConversationInput? {
        require(budget >= 0)
        val notes = recalled.distinctBy { it.messageId }.take(2).toMutableList()
        fun content() = question(text, notes)
        fun cost() = TokenBudget.total(listOf(count(content())), 12)
        while (notes.isNotEmpty() && cost() > budget) {
            val largest = notes.indices.maxBy { notes[it].text.length }
            val note = notes[largest]
            if (note.text.length > 48) notes[largest] = note.copy(text = note.text.take((note.text.length * 3 / 4).coerceAtLeast(48)))
            else notes.removeAt(notes.lastIndex)
        }
        val tokenCost = cost()
        if (tokenCost > budget) return null
        return FittedConversationInput(listOf(ChatTurn("user", content())), notes.toList(), tokenCost)
    }
}
