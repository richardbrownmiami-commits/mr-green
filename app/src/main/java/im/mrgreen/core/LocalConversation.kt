package im.mrgreen.core

import im.mrgreen.identity.IdentityStore

/** Exact whole-message defaults only. Never interprets note payloads or supplies live facts. */
object LocalConversation {
    fun reply(question: String, identity: IdentityStore): String? {
        if (question.length > 200) return null
        val key = IdentityStore.normalize(question)
        val stableQuestion = when (key) {
            "identity", "your identity", "who is mr green", "what is mr green", "tell me about mr green",
            "what is your name", "what s your name", "your name" -> "Who are you?"
            "what can you do", "help", "mr green help" -> "What can you help with?"
            "are you a human", "are you conscious" -> "Are you alive?"
            else -> null
        }
        // Keep the user's validated editable identity profile as the identity source.
        if (stableQuestion != null) return identity.directReply(stableQuestion)
        return when (key) {
            "hi", "hello", "hey", "hi mr green", "hello mr green", "hey mr green", "hi green", "hello green" ->
                "Hello! I’m Mr Green. How can I help?"
            "good morning", "good afternoon", "good evening" -> "Hello! What can I help you with?"
            "how are you", "how are you doing", "how r u", "how are you mr green" ->
                "I’m here to help locally. I’m software, so I don’t have feelings. Ask for battery level or system status for a live phone reading."
            "thanks", "thank you", "thanks mr green" -> "You’re welcome!"
            "bye", "goodbye", "good night" -> "Goodbye! Come back whenever you need help."
            "are you offline", "do you need internet", "do you use cloud", "does mr green work offline" ->
                "Chat stays on this phone. Greetings, identity help and supported local actions need no GGUF. Other generated answers need a loaded local GGUF; there is no cloud model fallback."
            "do i need a model", "do you need a model", "what is gguf" ->
                "GGUF is the local language-model file used for generated answers. Greetings, identity help, battery, time and supported device actions work without it."
            "how do i check status", "status help" ->
                "Ask ‘battery level’, ‘what time is it’, ‘storage status’, ‘system status’ or ‘model status’. Ask ‘job status’ for the last recorded registered-action result in this app session."
            else -> null
        }
    }
}
