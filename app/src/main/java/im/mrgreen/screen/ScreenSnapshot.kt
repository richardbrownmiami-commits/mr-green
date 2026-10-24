package im.mrgreen.screen

/** Immutable, short-lived content from one user-requested accessibility read. Never persisted. */
data class ScreenItem(val text: String, val kind: Kind, val bounds: ScreenRect? = null) {
    enum class Kind { TEXT, BUTTON, EDITABLE, LINK, CHECKABLE }
}
data class ScreenRect(val left: Int, val top: Int, val right: Int, val bottom: Int)
data class ScreenSnapshot(val packageName: String, val capturedAtMillis: Long, val items: List<ScreenItem>, val blockedReason: String? = null) {
    val readable: Boolean get() = blockedReason == null && items.isNotEmpty()
    fun compactText(maxChars: Int = 6000): String = items.asSequence().map { "[${it.kind.name.lowercase()}] ${it.text}" }.joinToString("\n").take(maxChars)
}

/** Fail-closed policy evaluated immediately before each capture. */
object ScreenReadPolicy {
    // Only API 34+ offers window-targeted capture with explicit secure-window rejection.
    const val MIN_SECURE_CAPTURE_SDK = 34
    fun supportsSecureWindowCapture(sdk: Int): Boolean = sdk >= MIN_SECURE_CAPTURE_SDK
    fun permitsPersonalLegacyRead(sdk: Int, personalBuild: Boolean, consent: Boolean): Boolean =
        sdk in 30..33 && personalBuild && consent
    private val defaultBlocked = setOf("com.android.settings.password", "com.google.android.apps.authenticator2")
    private val sensitiveWords = Regex("(?i)(password|passcode|pin code|one[- ]time code|otp|credit card|cvv)")
    fun denial(packageName: String?, blocklist: Set<String>, hasAccessibilityWindow: Boolean,
               secureWindowKnown: Boolean, containsPasswordField: Boolean, personalLegacyReadAllowed: Boolean = false): String? {
        if (packageName.isNullOrBlank() || !hasAccessibilityWindow || (!secureWindowKnown && !personalLegacyReadAllowed)) return "Can't safely determine which screen is active. Nothing was read."
        if (packageName in (blocklist + defaultBlocked)) return "Screen reading is blocked for this app. Nothing was read."
        if (containsPasswordField) return "This screen contains a password field. Nothing was read."
        return null
    }
    fun shouldDiscardText(text: String): Boolean = sensitiveWords.containsMatchIn(text)
}

/** Shared host callback: parent may route through its singleton AgentController with local-only enforced. */
fun interface ScreenAnswerProvider { suspend fun answer(prompt: String, localOnly: Boolean): String }
fun interface ScreenActionConfirmer { fun confirm(action: ScreenAction, confirmed: () -> Unit) }
data class ScreenAction(val label: String, val value: String, val type: Type) { enum class Type { CALL, ALARM, COPY } }
