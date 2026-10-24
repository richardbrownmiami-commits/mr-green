package im.mrgreen.recipes

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.provider.Settings
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

/** Backend-owned recipe allowlist. No generic intents, model tools, shell or external AI chat. */
object RecipeRuntime {
    private const val WHATSAPP = "com.whatsapp"
    @Volatile internal var service: RecipeAccessibilityService? = null
    /** Host-provided per-action confirmation UI; absent callbacks always fail closed. */
    @Volatile var confirmation: (suspend (String, String) -> Boolean)? = null
    @Volatile internal var cancelPendingConfirmation: (() -> Unit)? = null
    @Volatile internal var isRunning = false
    private val mutex = Mutex()

    /** Call only from a host-owned user action after its confirmation surface is ready. */
    fun beginManualSession(): Boolean = service?.controls?.startHostApprovedSession() == true
    fun stopManualSession() { service?.controls?.stop() }

    suspend fun execute(request: String): String? {
        if (request.length > 4096) return "Recipe input is too long. Nothing was done."
        val j = try { JSONObject(request) } catch (_: Exception) { return null }
        val recipe = j.optString("recipe")
        if (recipe in setOf("meta.prompt", "gemini.prompt", "meta.result", "gemini.result"))
            return "External AI conversation recipes are disabled. Use a local GGUF model; no prompt was entered and no result was read."
        if (recipe !in setOf("whatsapp.send", "wifi.settings")) return null
        val context = service ?: return "Host accessibility service is disconnected; nothing was done."
        val allowed = if (recipe == "whatsapp.send") setOf("recipe", "recipient", "message") else setOf("recipe")
        if (j.keys().asSequence().any { it !in allowed } || j.opt("recipe") !is String) return "Invalid recipe arguments. Nothing was done."
        return withContext(Dispatchers.Main.immediate) {
            mutex.withLock {
                if (!context.controls.active) return@withLock "Start a host-approved manual session first. Nothing was done."
                val token = context.controls.generation
                fun checkActive() { check(service === context && context.controls.isCurrent(token)) { "stopped" } }
                isRunning = true
                try {
                    when (recipe) {
                        "whatsapp.send" -> {
                            if (j.opt("recipient") !is String || j.opt("message") !is String) return@withLock "Recipient and message must be text. Nothing was sent."
                            val recipient = j.getString("recipient").trim(); val message = j.getString("message").trim()
                            if (!RecipePolicy.validWhatsApp(recipient, message)) return@withLock "Recipient and message are required (maximums 120 and 2,000 characters). Nothing was sent."
                            val approve = confirmation ?: return@withLock "Host confirmation is unavailable. Nothing was sent."
                            if (!withTimeout(30_000) { approve(recipient, message) }) return@withLock "Cancelled. Nothing was sent."
                            checkActive()
                            val launch = context.packageManager.getLaunchIntentForPackage(WHATSAPP) ?: return@withLock "WhatsApp is not installed. Nothing was sent."
                            context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            withTimeout(12_000) {
                                var root = context.awaitRoot(WHATSAPP, token)
                                try {
                                    val recipientNode = root.unique { it.isClickable && label(it).equals(recipient, true) }
                                        ?: return@withTimeout "Exact unique clickable recipient not found in WhatsApp. Nothing was sent."
                                    try { checkActive(); context.verifyWindow(root); if (!recipientNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return@withTimeout "Could not open the selected recipient. Nothing was sent." }
                                    finally { recipientNode.recycle() }
                                } finally { root.recycle() }
                                delay(300); checkActive()
                                root = context.awaitRoot(WHATSAPP, token)
                                try {
                                    var recipientVisible = false
                                    root.walk { if (it.isVisibleToUser && label(it).equals(recipient, true)) recipientVisible = true }
                                    if (!recipientVisible) return@withTimeout "Recipient could not be revalidated. Nothing was sent."
                                    val edit = root.unique { it.isEditable && !it.isPassword && it.text.isNullOrEmpty() }
                                        ?: return@withTimeout "Unique empty message field not found. Existing text was not overwritten."
                                    try {
                                        checkActive()
                                        context.verifyWindow(root)
                                        if (!edit.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, android.os.Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, message) })) return@withTimeout "Could not fill message field. Nothing was sent."
                                    } finally { edit.recycle() }
                                } finally { root.recycle() }
                                // Reacquire after text entry. Never send through a stale retained root.
                                checkActive(); root = context.awaitRoot(WHATSAPP, token)
                                try {
                                    var recipientVisible = false
                                    root.walk { if (it.isVisibleToUser && label(it).equals(recipient, true)) recipientVisible = true }
                                    if (!recipientVisible) return@withTimeout "Recipient changed. Prepared text was NOT sent; review WhatsApp."
                                    val send = root.unique { it.isClickable && label(it).lowercase() in setOf("send", "send message") }
                                        ?: return@withTimeout "Send control not unique or unavailable. Prepared text was NOT sent; review WhatsApp."
                                    try { checkActive(); context.verifyWindow(root); if (send.performAction(AccessibilityNodeInfo.ACTION_CLICK)) "Send was requested in WhatsApp. Verify delivery in the app." else "Send action failed. Check WhatsApp; no delivery is claimed." }
                                    finally { send.recycle() }
                                } finally { root.recycle() }
                            }
                        }
                        "wifi.settings" -> {
                            val approve = confirmation ?: return@withLock "Host confirmation is unavailable. Wi-Fi settings were not opened."
                            if (!withTimeout(30_000) { approve("Wi-Fi settings", "Open Android Wi-Fi settings only; no setting is changed.") }) return@withLock "Cancelled. Wi-Fi settings were not opened."
                            checkActive()
                            context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            "Opened Android Wi-Fi settings only. No Wi-Fi toggle was performed or verified."
                        }
                        else -> null
                    }
                } catch (_: kotlinx.coroutines.TimeoutCancellationException) { "Timed out. No further action was run; inspect the target app. No success is claimed." }
                catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (_: Exception) { "Stopped or recipe failed safely. Earlier actions were not undone; inspect the target app. No success is claimed." }
                finally { isRunning = false }
            }
        }
    }
    private fun RecipeAccessibilityService.verifyWindow(expected: AccessibilityNodeInfo) {
        val current = rootInActiveWindow ?: error("window unavailable")
        try { check(current.windowId == expected.windowId && current.packageName?.toString() == WHATSAPP) }
        finally { current.recycle() }
    }
    private suspend fun RecipeAccessibilityService.awaitRoot(pkg: String, token: Long): AccessibilityNodeInfo {
        while (true) {
            check(service === this && controls.isCurrent(token))
            val root = rootInActiveWindow
            if (root?.packageName?.toString() == pkg) {
                try { root.walk { check(!it.isPassword) } } catch (e: Exception) { root.recycle(); throw e }
                return root
            }
            root?.recycle(); delay(150)
        }
    }
    private fun label(node: AccessibilityNodeInfo) = (node.text ?: node.contentDescription)?.take(500)?.toString()?.trim().orEmpty()
    private fun AccessibilityNodeInfo.unique(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        val nodes = mutableListOf<AccessibilityNodeInfo>()
        try {
            walk { if (it.isVisibleToUser && it.isEnabled && predicate(it)) nodes += AccessibilityNodeInfo.obtain(it) }
            return if (nodes.size == 1) nodes.removeAt(0) else null
        } finally { nodes.forEach { it.recycle() } }
    }
    private fun AccessibilityNodeInfo.walk(visit: (AccessibilityNodeInfo) -> Unit) {
        var count = 0; val began = android.os.SystemClock.uptimeMillis()
        fun scan(node: AccessibilityNodeInfo, depth: Int) {
            check(++count <= 300 && depth <= 24 && android.os.SystemClock.uptimeMillis() - began <= 400)
            visit(node)
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: error("unavailable")
                try { scan(child, depth + 1) } finally { child.recycle() }
            }
        }
        scan(this, 0)
    }
}

class RecipeAccessibilityService : AccessibilityService() {
    internal val controls by lazy { NativeControlSession() }
    override fun onServiceConnected() { super.onServiceConnected(); RecipeRuntime.service = this }
    // The backend reads the active tree only for an explicitly requested, confirmed recipe.
    override fun onAccessibilityEvent(event: android.view.accessibility.AccessibilityEvent?) = Unit
    override fun onInterrupt() { controls.stop() }
    override fun onDestroy() { controls.stop(); if (RecipeRuntime.service === this) RecipeRuntime.service = null; super.onDestroy() }
}
