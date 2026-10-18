package im.mrgreen.recipes

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

/** Request JSON: {"recipe":"whatsapp.send","recipient":"...","message":"..."};
 * {"recipe":"meta.prompt"|"gemini.prompt","prompt":"..."}; {"recipe":"wifi.settings"}. */
object RecipeRuntime {
    private const val WHATSAPP = "com.whatsapp"
    private const val GEMINI = "com.google.android.apps.bard"
    private const val META_AI = "com.facebook.katana"
    private const val TIMEOUT_MS = 12_000L
    @Volatile internal var service: RecipeAccessibilityService? = null
    @Volatile var confirmation: (suspend (String, String) -> Boolean)? = null

    /** Null means unrecognized/invalid request. All reported outcomes are explicitly qualified. */
    suspend fun execute(request: String): String? {
        val j = try { JSONObject(request) } catch (_: Exception) { return null }
        val recipe = j.optString("recipe")
        val context = service ?: return "Accessibility bridge is disconnected. Enable Mr Green Recipes accessibility service; nothing was done."
        return try {
            when (recipe) {
                "whatsapp.send" -> {
                    val recipient = j.optString("recipient").trim(); val message = j.optString("message").trim()
                    if (!RecipePolicy.validWhatsApp(recipient, message)) return "Recipient and message are required (maximums 120 and 2,000 characters). Nothing was sent."
                    val approve = confirmation ?: return "No foreground confirmation UI is available. Nothing was sent."
                    if (!approve(recipient, message)) return "Cancelled. Nothing was sent."
                    context.openPackage(WHATSAPP) ?: return "WhatsApp is not installed. Nothing was sent."
                    withTimeout(TIMEOUT_MS) {
                        var root = context.awaitRoot(WHATSAPP)
                        if (!root.labelContains(recipient)) return@withTimeout "WhatsApp recipient could not be validated. Nothing was sent."
                        val visibleRecipient = root.findClickableExact(recipient) ?: return@withTimeout "Exact clickable recipient not found in WhatsApp. Nothing was sent."
                        if (!visibleRecipient.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return@withTimeout "Could not open the selected WhatsApp recipient. Nothing was sent."
                        root = context.awaitRoot(WHATSAPP)
                        if (!root.labelContains(recipient)) return@withTimeout "Opened WhatsApp screen does not show the confirmed recipient. Nothing was sent."
                        val edit = root.findEditable() ?: return@withTimeout "WhatsApp message field not found. Nothing was sent."
                        if (!edit.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, android.os.Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, message) })) return@withTimeout "Could not fill message field. Nothing was sent."
                        val exact = root.findButton(setOf("send", "send message")) ?: return@withTimeout "Send control not found. Message is prepared but was NOT sent; review WhatsApp."
                        if (!exact.performAction(AccessibilityNodeInfo.ACTION_CLICK)) "Send action failed; message may remain unsent. Check WhatsApp." else "Send was requested in WhatsApp. Verify delivery in the app."
                    }
                }
                "meta.prompt", "gemini.prompt" -> {
                    val prompt = j.optString("prompt").trim(); if (!RecipePolicy.validPrompt(prompt)) return "Prompt required (maximum 2,000 characters)."
                    val pkg = if (recipe == "gemini.prompt") GEMINI else META_AI
                    context.openPackage(pkg) ?: return "Target AI app is not installed. Nothing was submitted."
                    withTimeout(TIMEOUT_MS) {
                        val root = context.awaitRoot(pkg)
                        val expectedLabels = if (recipe == "gemini.prompt") listOf("Gemini") else listOf("Meta AI")
                        if (!root.hasAnyLabel(expectedLabels)) return@withTimeout "Expected AI composer label was not found in the validated app. Nothing was submitted."
                        val edit = root.findEditable() ?: return@withTimeout "Expected AI app is open, but no prompt field was found. Nothing was submitted."
                        if (!edit.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, android.os.Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, prompt) })) return@withTimeout "Could not fill prompt field. Nothing was submitted."
                        "Prompt entered in the validated app. Review it and tap Send yourself; this recipe never submits AI prompts."
                    }
                }
                "meta.result", "gemini.result" -> {
                    val pkg = if (recipe == "gemini.result") GEMINI else META_AI
                    context.openPackage(pkg) ?: return "Target AI app is not installed. Nothing was read."
                    withTimeout(TIMEOUT_MS) {
                        val root = context.awaitRoot(pkg)
                        val title = if (recipe == "gemini.result") "Gemini" else "Meta AI"
                        if (!root.hasAnyLabel(listOf(title))) return@withTimeout "Expected AI app label was not found. Nothing was read."
                        val text = root.visibleText().filter { it.length >= 3 && !it.equals(title, true) }.takeLast(30)
                        if (text.isEmpty()) "No readable AI result was found on the validated screen." else "Visible text from $title (review for completeness):\n" + text.joinToString("\n").take(4000)
                    }
                }
                "wifi.settings" -> {
                    val approve = confirmation ?: return "No foreground confirmation UI is available. Wi-Fi settings were not opened."
                    if (!approve("Wi-Fi settings", "Open Android Wi-Fi settings. Mr Green cannot directly toggle Wi-Fi on Android 11.")) return "Cancelled. Wi-Fi settings were not opened."
                    context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    "Opened Android Wi-Fi settings only. No Wi-Fi toggle was performed or verified."
                }
                else -> null
            }
        } catch (_: kotlinx.coroutines.TimeoutCancellationException) { "Timed out waiting for the expected app/screen. No success is claimed; inspect the target app." }
        catch (_: Exception) { "Recipe failed safely. Inspect the target app; no success is claimed." }
    }

    private fun RecipeAccessibilityService.openPackage(pkg: String): Intent? {
        val launch = packageManager.getLaunchIntentForPackage(pkg) ?: return null
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); startActivity(launch); return launch
    }
    private suspend fun RecipeAccessibilityService.awaitRoot(pkg: String): AccessibilityNodeInfo {
        while (true) { val root = rootInActiveWindow; if (root?.packageName?.toString() == pkg) return root; delay(250) }
    }
    private fun AccessibilityNodeInfo.labelContains(value: String): Boolean {
        val labels = mutableListOf<String>(); walk { n -> n.text?.toString()?.let(labels::add); n.contentDescription?.toString()?.let(labels::add) }
        return labels.any { it.contains(value, ignoreCase = true) }
    }
    private fun AccessibilityNodeInfo.hasAnyLabel(values: List<String>): Boolean = values.any { labelContains(it) }
    private fun AccessibilityNodeInfo.findEditable(): AccessibilityNodeInfo? { var found: AccessibilityNodeInfo? = null; walk { if (found == null && it.isEditable && !it.isPassword) found = it }; return found }
    private fun AccessibilityNodeInfo.findButton(allowed: Set<String>): AccessibilityNodeInfo? { var found: AccessibilityNodeInfo? = null; walk { n -> val label=(n.text?.toString() ?: n.contentDescription?.toString()).orEmpty().trim().lowercase(); if(found==null && n.isClickable && label in allowed) found=n }; return found }
    private fun AccessibilityNodeInfo.findClickableExact(value: String): AccessibilityNodeInfo? { var found: AccessibilityNodeInfo? = null; walk { n -> val label=(n.text?.toString() ?: n.contentDescription?.toString()).orEmpty().trim(); if(found==null && n.isClickable && label.equals(value,ignoreCase=true)) found=n }; return found }
    private fun AccessibilityNodeInfo.visibleText(): List<String> { val out=linkedSetOf<String>(); walk { n -> n.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let(out::add) }; return out.toList() }
    private inline fun AccessibilityNodeInfo.walk(crossinline visit: (AccessibilityNodeInfo)->Unit) { val stack=ArrayDeque<AccessibilityNodeInfo>(); stack.add(this); var count=0; while(stack.isNotEmpty() && count++ < 300) { val n=stack.removeLast(); visit(n); for(i in 0 until n.childCount) try { n.getChild(i)?.let(stack::add) } catch (_:Exception) {} } }
}

/** Separate bridge required because existing screen reader intentionally never inspects action screens. */
class RecipeAccessibilityService : AccessibilityService() {
    override fun onServiceConnected() { super.onServiceConnected(); RecipeRuntime.service=this }
    override fun onAccessibilityEvent(event: android.view.accessibility.AccessibilityEvent?) { /* read tree only during an explicit recipe */ }
    override fun onInterrupt() {}
    override fun onDestroy() { if (RecipeRuntime.service === this) RecipeRuntime.service=null; super.onDestroy() }
}
