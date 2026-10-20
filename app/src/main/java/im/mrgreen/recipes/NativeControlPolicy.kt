package im.mrgreen.recipes

/** Strict allowlist/bounds for user-driven native accessibility controls. */
internal object NativeControlPolicy {
    const val MAX_NODES = 120
    const val MAX_DEPTH = 20
    const val MAX_LABEL_LENGTH = 160
    const val MAX_TEXT_LENGTH = 500
    const val MAX_GESTURE_MS = 900L
    const val MAX_ACTIONS_PER_SESSION = 40
    private val blockedPackageFragments = setOf("settings", "installer", "packageinstaller", "permissioncontroller", "wallet", "payment", "bank", "authenticator", "credential", "password", "lockscreen", "com.google.android.apps.bard", "gemini", "chatgpt", "openai", "copilot", "claude", "anthropic", "meta.ai", "facebook.katana", "browser", "chrome", "firefox", "webview", "termux", "terminal", "shell", "shizuku", "ssh", "tasker", "automate", "remotecontrol")
    fun safeTarget(packageName: String?, ownPackage: String): Boolean {
        val p = packageName?.lowercase()?.takeIf { it.isNotBlank() } ?: return false
        if (p == ownPackage.lowercase() || p.startsWith("android") || p.startsWith("com.android.systemui")) return false
        return blockedPackageFragments.none { it in p }
    }
    private val sensitive = Regex("(?i)\\b(password|passcode|otp|cvv|credit card|debit card|bank|payment|checkout|purchase|transfer|uninstall|install|delete|remove|erase|clear all|clear data|format|reset|permission|grant|send|submit|publish|subscribe|sign in|log in)\\b")
    fun sensitiveLabel(label: String): Boolean = sensitive.containsMatchIn(label)
    fun validLabel(label: String): Boolean = label.isNotBlank() && label.length <= MAX_LABEL_LENGTH && !sensitiveLabel(label) && label.none { it.isISOControl() }
    fun exactLabelMatch(requested: String, visible: String): Boolean = validLabel(requested) && visible.trim().equals(requested.trim(), ignoreCase = true)
    fun validText(text: String): Boolean = text.isNotBlank() && text.length <= MAX_TEXT_LENGTH && text.none { it.isISOControl() }
    fun validSwipe(durationMs: Long, distancePx: Int): Boolean = durationMs in 100L..MAX_GESTURE_MS && distancePx in 1..2400
    fun safeTreeNode(depth: Int, count: Int): Boolean = depth in 0..MAX_DEPTH && count < MAX_NODES
}
