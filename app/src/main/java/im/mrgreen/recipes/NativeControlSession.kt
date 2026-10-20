package im.mrgreen.recipes

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.AlertDialog
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import im.mrgreen.screen.PersonalScreenConsent
import im.mrgreen.screen.ScreenReadPolicy

/** Manual-only bridge. Neither model output nor chat commands can invoke these controls. */
internal class NativeControlSession(private val service: RecipeAccessibilityService) {
    private val handler = Handler(Looper.getMainLooper())
    private val wm get() = service.getSystemService(AccessibilityService.WINDOW_SERVICE) as WindowManager
    private var bar: LinearLayout? = null
    private var dialog: AlertDialog? = null
    private var controls = false
    private var actions = 0
    private var started = 0L
    @Volatile var generation = 0L
        private set
    private var epoch = 0L
    private var observedPackage: String? = null
    private var observedWindow = -1
    val active: Boolean get() = bar != null
    private data class Target(val pkg: String, val window: Int, val epoch: Long, val captured: Long, val fingerprint: String)

    fun windowChanged(pkg: String?, windowId: Int) {
        if (pkg != null && pkg != service.packageName && (pkg != observedPackage || windowId != observedWindow)) {
            epoch++; observedPackage = pkg; observedWindow = windowId
        }
    }
    fun isCurrent(token: Long): Boolean = active && token == generation && SystemClock.uptimeMillis() - started < 300_000L
    fun showStopOnly(): Boolean {
        if (active) return true
        generation++; started = SystemClock.uptimeMillis(); actions = 0
        val layout = LinearLayout(service).apply { orientation = LinearLayout.HORIZONTAL }
        layout.addView(Button(service).apply { text = "Controls"; setOnClickListener { menu() } })
        layout.addView(Button(service).apply { text = "STOP"; contentDescription = "Stop all Mr Green screen actions"; setOnClickListener { stop() } })
        return try {
            wm.addView(layout, WindowManager.LayoutParams(-2, -2, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.END; y = (48 * service.resources.displayMetrics.density).toInt() })
            bar = layout
            val token = generation
            handler.postDelayed({ if (generation == token) stop() }, 300_000L)
            true
        } catch (_: Exception) { stop(); false }
    }
    fun startControls(): Boolean {
        // API 30-33 cannot prove secure-window status. Require the existing personal-build consent.
        if (!PersonalScreenConsent.enabled(service)) { say("Manual controls require personal-use Android 11–13 screen consent. Nothing was read."); return false }
        controls = true
        return showStopOnly()
    }
    fun stop() {
        generation++; controls = false; handler.removeCallbacksAndMessages(null)
        dialog?.dismiss(); dialog = null
        bar?.let { runCatching { wm.removeView(it) } }; bar = null
        RecipeRuntime.cancelPendingConfirmation?.invoke()
        im.mrgreen.screen.ScreenAssistantService.instance?.stopReading()
        im.mrgreen.core.AgentController.cancel()
    }
    private fun ready(): Boolean = controls && active && !RecipeRuntime.isRunning && PersonalScreenConsent.enabled(service) &&
        SystemClock.uptimeMillis() - started < 300_000L && actions < NativeControlPolicy.MAX_ACTIONS_PER_SESSION
    private fun target(): Target? {
        if (!ready()) { say("Start a new manual session in Recipes after granting personal-use consent."); return null }
        val root = service.rootInActiveWindow ?: return null
        return try {
            val pkg = root.packageName?.toString()
            if (!safePackage(pkg)) { say("This app is blocked for manual controls."); null }
            else {
                val fingerprint = inspect(root)
                observedPackage = pkg; observedWindow = root.windowId
                Target(pkg!!, root.windowId, epoch, SystemClock.uptimeMillis(), fingerprint)
            }
        } catch (_: Exception) { say("Sensitive, large or unavailable screen: no action was run."); null }
        finally { root.recycle() }
    }
    private fun safePackage(pkg: String?): Boolean {
        val blocked = service.getSharedPreferences("screen_assistant", 0).getStringSet("blocked_packages", emptySet()).orEmpty()
        return NativeControlPolicy.safeTarget(pkg, service.packageName) && pkg !in blocked
    }
    private fun menu() {
        if (dialog != null) return
        val target = target() ?: return
        val choices = arrayOf("Read visible tree locally", "Tap exact label…", "Scroll down", "Scroll up", "Swipe up", "Swipe down", "Enter text in labeled field…", "Back", "Home")
        show(AlertDialog.Builder(service).setTitle("Manual controls · ${target.pkg}").setItems(choices) { _, index ->
            dialog = null
            when (index) {
                0 -> afterDismiss(target) { root -> sayTree(root) }
                1 -> input(target, false)
                6 -> input(target, true)
                else -> confirm(target, choices[index]) { root ->
                    when (index) {
                        2, 3 -> nodeAction(root, { it.isScrollable }, if (index == 2) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
                        4, 5 -> swipe(root, index == 4)
                        7 -> service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                        8 -> service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
                        else -> false
                    }
                }
            }
        }.setNegativeButton("Cancel", null).create())
    }
    private fun input(target: Target, typing: Boolean) {
        val fields = LinearLayout(service).apply { orientation = LinearLayout.VERTICAL }
        val label = EditText(service).apply { hint = "Exact visible label (unique)"; filters = arrayOf(android.text.InputFilter.LengthFilter(NativeControlPolicy.MAX_LABEL_LENGTH)); isSingleLine = true }
        val text = EditText(service).apply { hint = "Text to enter (never submitted automatically)"; filters = arrayOf(android.text.InputFilter.LengthFilter(NativeControlPolicy.MAX_TEXT_LENGTH)) }
        fields.addView(label); if (typing) fields.addView(text)
        show(AlertDialog.Builder(service).setTitle(if (typing) "Enter text" else "Tap label").setView(fields)
            .setNegativeButton("Cancel", null).setPositiveButton("Review") { _, _ ->
                dialog = null
                val requested = label.text.toString().trim(); val value = text.text.toString()
                if (!NativeControlPolicy.validLabel(requested) || (typing && !NativeControlPolicy.validText(value))) { say("Invalid or sensitive input. Nothing was run."); return@setPositiveButton }
                confirm(target, if (typing) "Enter this exact text in “$requested” in ${target.pkg}:\n$value\nThis changes the field only; the target app may handle or sync it. No Send/Save button is pressed." else "Tap “$requested” in ${target.pkg}") { root ->
                    if (typing) nodeAction(root, { it.isEditable && labelOf(it).equals(requested, true) }, AccessibilityNodeInfo.ACTION_SET_TEXT,
                        Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value) })
                    else nodeAction(root, { it.isClickable && NativeControlPolicy.exactLabelMatch(requested, labelOf(it)) }, AccessibilityNodeInfo.ACTION_CLICK)
                }
            }.create())
    }
    private fun confirm(target: Target, description: String, action: (AccessibilityNodeInfo) -> Boolean) {
        show(AlertDialog.Builder(service).setTitle("Confirm one action").setMessage(description + "\nStop remains available. A changed screen cancels this action.")
            .setNegativeButton("Cancel", null).setPositiveButton("Confirm") { _, _ ->
                dialog = null
                afterDismiss(target) { root ->
                    actions++
                    val ok = action(root)
                    say(if (ok) "Android accepted the action request. Verify its effect in the app; completion is not verified." else "Action was rejected or the target was not unique. No success is claimed.")
                }
            }.create())
    }
    private fun afterDismiss(target: Target, block: (AccessibilityNodeInfo) -> Unit) {
        val token = generation
        handler.postDelayed({
            if (!ready() || !isCurrent(token) || target.epoch != epoch || SystemClock.uptimeMillis() - target.captured > 30_000L) { say("Stopped, expired or screen changed. Nothing further was run."); return@postDelayed }
            val root = service.rootInActiveWindow ?: return@postDelayed
            try {
                if (root.windowId != target.window || root.packageName?.toString() != target.pkg || !safePackage(target.pkg)) error("stale")
                if (inspect(root) != target.fingerprint) error("changed tree")
                block(root)
            } catch (_: Exception) { say("Screen changed, is sensitive or unavailable. No success is claimed.") }
            finally { root.recycle() }
        }, 200L)
    }
    private fun inspect(root: AccessibilityNodeInfo): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        walk(root) { node ->
            val label = labelOf(node)
            if (node.isPassword || (node.isEditable && !node.text.isNullOrEmpty()) || ScreenReadPolicy.shouldDiscardText(label) || NativeControlPolicy.sensitiveLabel(label)) error("sensitive")
            val bounds = Rect(); node.getBoundsInScreen(bounds)
            digest.update("${node.className}|$label|$bounds|${node.isEnabled}|${node.isClickable}|${node.isEditable}".toByteArray(Charsets.UTF_8))
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
    private fun sayTree(root: AccessibilityNodeInfo) {
        val labels = mutableListOf<String>()
        walk(root) { node -> if (node.isVisibleToUser) labelOf(node).takeIf { it.isNotBlank() }?.let { labels += it.take(160) } }
        show(AlertDialog.Builder(service).setTitle("Visible tree · local only").setMessage(labels.distinct().joinToString("\n").take(3000).ifBlank { "No visible labels." }).setPositiveButton("Close", null).create())
    }
    private fun labelOf(node: AccessibilityNodeInfo): String = (if (node.isEditable) node.hintText ?: node.contentDescription else node.text ?: node.contentDescription)?.take(500)?.toString()?.trim().orEmpty()
    private fun nodeAction(root: AccessibilityNodeInfo, predicate: (AccessibilityNodeInfo) -> Boolean, action: Int, args: Bundle? = null): Boolean {
        val matches = mutableListOf<AccessibilityNodeInfo>()
        try {
            walk(root) { if (it.isVisibleToUser && it.isEnabled && predicate(it)) matches += AccessibilityNodeInfo.obtain(it) }
            return matches.singleOrNull()?.performAction(action, args) ?: false
        } finally { matches.forEach { it.recycle() } }
    }
    private fun swipe(root: AccessibilityNodeInfo, up: Boolean): Boolean {
        val r = Rect(); root.getBoundsInScreen(r)
        if (r.width() <= 0 || r.height() <= 0) return false
        val distance = (r.height() / 3).coerceAtMost(600)
        if (!NativeControlPolicy.validSwipe(300, distance)) return false
        val x = r.exactCenterX(); val y = r.exactCenterY()
        val path = Path().apply { moveTo(x, y); lineTo(x, y + if (up) -distance.toFloat() else distance.toFloat()) }
        return service.dispatchGesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 300)).build(), null, null)
    }
    private fun walk(root: AccessibilityNodeInfo, visit: (AccessibilityNodeInfo) -> Unit) {
        var count = 0; val began = SystemClock.uptimeMillis()
        fun scan(node: AccessibilityNodeInfo, depth: Int) {
            if (!NativeControlPolicy.safeTreeNode(depth, count++) || SystemClock.uptimeMillis() - began > 300) error("bounds")
            visit(node)
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: error("unavailable")
                try { scan(child, depth + 1) } finally { child.recycle() }
            }
        }
        scan(root, 0)
    }
    private fun show(value: AlertDialog) {
        if (!active) return
        dialog?.dismiss(); dialog = value
        value.window?.setType(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY)
        value.setOnDismissListener { if (dialog === value) dialog = null }
        runCatching {
            value.show()
            value.window?.setGravity(Gravity.BOTTOM)
            value.window?.setLayout(WindowManager.LayoutParams.MATCH_PARENT, (service.resources.displayMetrics.heightPixels * 0.65).toInt())
            value.window?.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)
            value.window?.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        }.onFailure { dialog = null; stop() }
    }
    private fun say(text: String) { Toast.makeText(service, text, Toast.LENGTH_LONG).show() }
}
