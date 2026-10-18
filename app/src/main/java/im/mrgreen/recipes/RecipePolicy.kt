package im.mrgreen.recipes

/** Pure input bounds shared by runtime and host tests. */
internal object RecipePolicy {
    fun validWhatsApp(recipient: String, message: String): Boolean = recipient.isNotBlank() && recipient.length <= 120 && message.isNotBlank() && message.length <= 2000
    fun validPrompt(prompt: String): Boolean = prompt.isNotBlank() && prompt.length <= 2000
}
