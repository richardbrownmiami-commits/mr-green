package im.mrgreen.core

import im.mrgreen.knowledge.WebQuestion
import im.mrgreen.knowledge.WebQuestionCommand
import im.mrgreen.knowledge.WebQuestionParser

/** UI-only result. Never pass this exchange to GreenStore, Rive, or conversation recall. */
data class TemporaryWebExchange(val question: String, val answer: String)

/** The only input to retrieval is the explicitly typed public question, never conversation context. */
class WebQuestionDispatch(private val retrieve: (WebQuestion, () -> Boolean) -> String) {
    fun handle(text: String, webAllowed: Boolean, isCancelled: () -> Boolean): TemporaryWebExchange? {
        val command = WebQuestionParser.parse(text)
        if (command is WebQuestionCommand.NotWebQuestion) return null
        val reply = when {
            command is WebQuestionCommand.Help -> command.message
            !webAllowed -> "Web access is off. Open Knowledge and allow explicitly requested web access, then use ask web QUESTION. Only that question is sent to the search website, not your chat history."
            isCancelled() -> "Web question cancelled."
            command is WebQuestionCommand.Parsed -> try {
                val answer = retrieve(command.request, isCancelled)
                if (isCancelled()) "Web question cancelled." else answer
            } catch (_: Exception) {
                if (isCancelled()) "Web question cancelled." else
                    "I couldn't retrieve a safe source answer. The connection, site, or page format may be unavailable. Try ask web https://page | QUESTION. No cloud AI was used."
            }
            else -> error("Unrecognized web command")
        }
        return TemporaryWebExchange(text.take(2400), reply.take(12000))
    }
}
