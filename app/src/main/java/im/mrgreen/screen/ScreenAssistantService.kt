package im.mrgreen.screen

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.Display
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import java.util.concurrent.atomic.AtomicBoolean

/** User-opt-in service. It observes no event text and captures only after an explicit bubble tap. */
class ScreenAssistantService : AccessibilityService() {
    companion object { internal var instance: ScreenAssistantService? = null }
    private var window: WindowManager? = null
    private var bubble: View? = null
    private var params: WindowManager.LayoutParams? = null
    private val busy = AtomicBoolean(false)
    private var windowEpoch = 0L
    @Volatile private var stopped = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this; stopped = false
        window = getSystemService(WINDOW_SERVICE) as WindowManager
        addBubble()
    }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Track transitions only; never inspect event text, source nodes or package contents.
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            event?.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED) windowEpoch++
    }
    override fun onInterrupt() { stopReading() }
    override fun onDestroy() { stopped = true; if (instance === this) instance = null; removeBubble(); super.onDestroy() }
    internal fun stopReading() {
        stopped = true; windowEpoch++; busy.set(false); removeBubble()
        im.mrgreen.core.AgentController.cancel()
        disableSelf()
    }

    private fun addBubble() {
        if (!Settings.canDrawOverlays(this) && Build.VERSION.SDK_INT < 22) return
        val text = TextView(this).apply {
            this.text = "Read"
            textSize = 19f; gravity = Gravity.CENTER; contentDescription = "Ask Mr Green about this screen"
            setTextColor(0xffffffff.toInt()); setBackgroundColor(0xff176b45.toInt()); elevation = 10f
            setOnTouchListener(DragTouch())
            setOnClickListener { captureBeforePanel() }
        }
        val strip = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            addView(text, android.widget.LinearLayout.LayoutParams(dp(54), dp(54)))
            addView(android.widget.Button(this@ScreenAssistantService).apply {
                this.text = "STOP"; contentDescription = "Stop Mr Green screen reading and actions"
                setOnClickListener { im.mrgreen.recipes.RecipeRuntime.service?.controls?.stop(); stopReading() }
            }, android.widget.LinearLayout.LayoutParams(dp(76), dp(54)))
        }
        val type = if (Build.VERSION.SDK_INT >= 22) WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE
        val lp = WindowManager.LayoutParams(dp(130), dp(54), type, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.START; x = dp(12); y = dp(260) }
        try { window?.addView(strip, lp); bubble = strip; params = lp } catch (_: Exception) { /* Fail closed; no snapshot is taken. */ }
    }

    private inner class DragTouch : View.OnTouchListener {
        private var downX = 0f; private var downY = 0f; private var originX = 0; private var originY = 0; private var dragged = false
        override fun onTouch(v: View, e: MotionEvent): Boolean {
            val lp = params ?: return false
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { downX=e.rawX; downY=e.rawY; originX=lp.x; originY=lp.y; dragged=false; return true }
                MotionEvent.ACTION_MOVE -> { val dx=(e.rawX-downX).toInt(); val dy=(e.rawY-downY).toInt(); if (kotlin.math.abs(dx)+kotlin.math.abs(dy)>dp(5)) dragged=true; lp.x=originX+dx; lp.y=originY+dy; try { window?.updateViewLayout(bubble ?: v,lp) } catch (_: Exception) {}; return true }
                MotionEvent.ACTION_UP -> { val dm=resources.displayMetrics; val width=bubble?.width ?: v.width; lp.x=if(lp.x+width/2 < dm.widthPixels/2) dp(4) else (dm.widthPixels-width-dp(4)).coerceAtLeast(0); lp.y=lp.y.coerceIn(0, (dm.heightPixels-v.height-dp(48)).coerceAtLeast(0)); try { window?.updateViewLayout(bubble ?: v,lp) } catch (_: Exception) {}; if (!dragged) v.performClick(); return true }
            }
            return false
        }
    }

    private fun captureBeforePanel() {
        if (stopped) return
        if (!busy.compareAndSet(false,true)) return
        val personalLegacy = PersonalScreenConsent.enabled(this)
        if (!ScreenReadPolicy.supportsSecureWindowCapture(Build.VERSION.SDK_INT) && !personalLegacy) {
            openDenied("Screen reading is disabled on this Android version. A personal build and explicit personal-use consent in Capability setup are required. Nothing was read."); return
        }
        val initial = try { rootInActiveWindow } catch (_: Exception) { null }
        val expectedWindow = initial?.windowId ?: -1
        val expectedPackage = initial?.packageName?.toString()
        runCatching { initial?.recycle() }
        if (expectedWindow < 0 || expectedPackage.isNullOrBlank()) { openDenied("No safe active window is available. Nothing was read."); return }
        val expectedEpoch = windowEpoch
        if (personalLegacy) {
            // Explicit risk acceptance, NOT evidence that this window is non-secure.
            captureNodes(expectedWindow, expectedPackage, expectedEpoch, secureCaptureVerified = false)
            return
        }
        // Keep the platform API boundary explicit for lint and future policy changes.
        if (Build.VERSION.SDK_INT < 34) {
            openDenied("Secure window capture is unavailable. Nothing was read."); return
        }
        try {
            takeScreenshotOfWindow(expectedWindow, mainExecutor, object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                    // API 34 binds capture and secure-window rejection to this window ID.
                    try { screenshot.hardwareBuffer.close() } catch (_: Exception) { }
                    if (stopped) { busy.set(false); return }
                    if (windowEpoch != expectedEpoch) openDenied("The active window changed. Nothing was read.")
                    else captureNodes(expectedWindow, expectedPackage, expectedEpoch)
                }
                override fun onFailure(errorCode: Int) { openDenied("This screen may be protected or unavailable. Nothing was read.") }
            })
        } catch (_: Exception) { openDenied("Can't verify whether this window is secure. Nothing was read.") }
    }

    private fun captureNodes(expectedWindow: Int, expectedPackage: String, expectedEpoch: Long, secureCaptureVerified: Boolean = true) {
        if (stopped) { busy.set(false); return }
        val before = try { rootInActiveWindow } catch (_: Exception) { null }
        val pkg = before?.packageName?.toString()
        val sensitive = before?.let(::hasPassword) ?: false
        val sameActiveWindow = before != null && before.windowId == expectedWindow && pkg == expectedPackage && windowEpoch == expectedEpoch
        val personalLegacy = !secureCaptureVerified && PersonalScreenConsent.enabled(this)
        val denied = ScreenReadPolicy.denial(pkg, blockedPackages(), sameActiveWindow,
            secureWindowKnown = sameActiveWindow && secureCaptureVerified,
            containsPasswordField = sensitive, personalLegacyReadAllowed = personalLegacy)
        val snapshot = if (denied != null) ScreenSnapshot(pkg.orEmpty(), System.currentTimeMillis(), emptyList(), denied) else try {
            if (containsPasswordOrSecureEditable(before!!)) throw SensitiveScreenException()
            val items=collect(before)
            val after=try { rootInActiveWindow } catch (_:Exception) { null }
            val afterPkg=after?.packageName?.toString()
            val sameWindow=after!=null && after.windowId==before.windowId
            val unsafe=after==null || afterPkg!=pkg || !sameWindow || windowEpoch!=expectedEpoch ||
                (!secureCaptureVerified && !PersonalScreenConsent.enabled(this)) ||
                (after!=null && (hasPassword(after) || containsPasswordOrSecureEditable(after)))
            runCatching { after?.recycle() }
            if(unsafe) throw SensitiveScreenException()
            ScreenSnapshot(pkg!!, System.currentTimeMillis(), items.take(120))
        } catch (_: Exception) { ScreenSnapshot(pkg.orEmpty(), System.currentTimeMillis(), emptyList(), "The active window changed or sensitive content was detected. Nothing was read.") }
        finally { runCatching { before?.recycle() } }
        // Snapshot is created before launching the panel; text is passed only in-process and not persisted.
        try { if (!stopped) startActivity(Intent(this, ScreenActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP).putExtra(ScreenActivity.EXTRA_SNAPSHOT, ScreenSnapshotCodec.encode(snapshot))) }
        catch (_: Exception) { }
        finally { busy.set(false) }
    }
    private fun openDenied(reason: String) {
        if (stopped) { busy.set(false); return }
        try { startActivity(Intent(this,ScreenActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP).putExtra(ScreenActivity.EXTRA_SNAPSHOT,ScreenSnapshotCodec.encode(ScreenSnapshot("",System.currentTimeMillis(),emptyList(),reason)))) } catch (_:Exception) { }
        finally { busy.set(false) }
    }
    private fun blockedPackages(): Set<String> = getSharedPreferences("screen_assistant", MODE_PRIVATE).getStringSet("blocked_packages", emptySet())?.toSet() ?: emptySet()
    private fun hasPassword(node: AccessibilityNodeInfo): Boolean = scanSensitive(node,0,false)
    private fun containsPasswordOrSecureEditable(node: AccessibilityNodeInfo): Boolean = scanSensitive(node,0,true)
    private fun scanSensitive(node: AccessibilityNodeInfo, depth: Int, rejectEditable: Boolean): Boolean {
        val started = android.os.SystemClock.uptimeMillis()
        var visited = 0
        fun scan(n: AccessibilityNodeInfo, d: Int): Boolean {
            if (d > 24 || ++visited > 300 || android.os.SystemClock.uptimeMillis() - started > 400) return true
            if (n.isPassword || n.className?.toString()?.contains("Password", true) == true ||
                (rejectEditable && n.isEditable && n.text?.isNotEmpty() == true)) return true
            for (i in 0 until n.childCount) {
                val child = try { n.getChild(i) } catch (_: Exception) { null } ?: return true
                val found = try { scan(child, d + 1) } finally { runCatching { child.recycle() } }
                if (found) return true
            }
            return false
        }
        return scan(node, depth)
    }
    private class SensitiveScreenException: RuntimeException()
    private fun collect(root: AccessibilityNodeInfo): List<ScreenItem> {
        val out = mutableListOf<ScreenItem>()
        var visited = 0
        val started = android.os.SystemClock.uptimeMillis()
        fun walk(n: AccessibilityNodeInfo, depth: Int) {
            if (depth > 24 || ++visited > 300 || android.os.SystemClock.uptimeMillis()-started > 400) throw SensitiveScreenException()
            val cls=n.className?.toString().orEmpty(); val editable=n.isEditable; val button=n.isClickable || cls.contains("Button",true); val label=(n.text?.toString() ?: n.contentDescription?.toString()).orEmpty().trim()
            if (editable && n.text?.isNotEmpty()==true) throw SensitiveScreenException()
            if (label.isNotEmpty() && !ScreenReadPolicy.shouldDiscardText(label)) {
                val r=Rect(); val bounds=runCatching { n.getBoundsInScreen(r); ScreenRect(r.left,r.top,r.right,r.bottom) }.getOrNull()
                val kind=when { n.isCheckable -> ScreenItem.Kind.CHECKABLE; button -> ScreenItem.Kind.BUTTON; cls.contains("WebView",true) -> ScreenItem.Kind.LINK; else -> ScreenItem.Kind.TEXT }
                out += ScreenItem(label.take(300),kind,bounds)
            }
            for (i in 0 until n.childCount) { val child=runCatching { n.getChild(i) }.getOrNull() ?: continue; try { walk(child,depth+1) } finally { runCatching { child.recycle() } } }
        }
        walk(root,0); return out.distinctBy { it.text to it.bounds }
    }
    private fun removeBubble() { try { bubble?.let { window?.removeView(it) } } catch (_: Exception) {}; bubble=null }
    private fun dp(v:Int)= (v*resources.displayMetrics.density).toInt()
}
