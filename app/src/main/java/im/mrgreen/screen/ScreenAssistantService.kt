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
    private var window: WindowManager? = null
    private var bubble: View? = null
    private var params: WindowManager.LayoutParams? = null
    private val busy = AtomicBoolean(false)

    override fun onServiceConnected() {
        super.onServiceConnected()
        window = getSystemService(WINDOW_SERVICE) as WindowManager
        addBubble()
    }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) { /* Never inspect or retain background events. */ }
    override fun onInterrupt() { }
    override fun onDestroy() { removeBubble(); super.onDestroy() }

    private fun addBubble() {
        if (!Settings.canDrawOverlays(this) && Build.VERSION.SDK_INT < 22) return
        val text = TextView(this).apply {
            this.text = "●"
            textSize = 19f; gravity = Gravity.CENTER; contentDescription = "Ask Mr Green about this screen"
            setTextColor(0xffffffff.toInt()); setBackgroundColor(0xff176b45.toInt()); elevation = 10f
            setOnTouchListener(DragTouch())
            setOnClickListener { captureBeforePanel() }
        }
        val type = if (Build.VERSION.SDK_INT >= 22) WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE
        val lp = WindowManager.LayoutParams(dp(54), dp(54), type, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.START; x = dp(12); y = dp(260) }
        try { window?.addView(text, lp); bubble = text; params = lp } catch (_: Exception) { /* Fail closed; no snapshot is taken. */ }
    }

    private inner class DragTouch : View.OnTouchListener {
        private var downX = 0f; private var downY = 0f; private var originX = 0; private var originY = 0; private var dragged = false
        override fun onTouch(v: View, e: MotionEvent): Boolean {
            val lp = params ?: return false
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { downX=e.rawX; downY=e.rawY; originX=lp.x; originY=lp.y; dragged=false; return true }
                MotionEvent.ACTION_MOVE -> { val dx=(e.rawX-downX).toInt(); val dy=(e.rawY-downY).toInt(); if (kotlin.math.abs(dx)+kotlin.math.abs(dy)>dp(5)) dragged=true; lp.x=originX+dx; lp.y=originY+dy; try { window?.updateViewLayout(v,lp) } catch (_: Exception) {}; return true }
                MotionEvent.ACTION_UP -> { val dm=resources.displayMetrics; lp.x=if(lp.x+v.width/2 < dm.widthPixels/2) dp(4) else dm.widthPixels-v.width-dp(4); lp.y=lp.y.coerceIn(dp(24), dm.heightPixels-v.height-dp(48)); try { window?.updateViewLayout(v,lp) } catch (_: Exception) {}; if (!dragged) v.performClick(); return true }
            }
            return false
        }
    }

    private fun captureBeforePanel() {
        if (!busy.compareAndSet(false,true)) return
        if (Build.VERSION.SDK_INT < 30) { openDenied("Can't verify whether this window is secure on this Android version. Nothing was read."); return }
        try {
            takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                    // A successful system capture establishes this window was not FLAG_SECURE at that instant.
                    // Pixels are immediately discarded; only the accessibility tree is used.
                    try { screenshot.hardwareBuffer.close() } catch (_: Exception) { }
                    captureNodes(secureWindowKnown = true)
                }
                override fun onFailure(errorCode: Int) { openDenied("This screen may be protected or unavailable. Nothing was read.") }
            })
        } catch (_: Exception) { openDenied("Can't verify whether this window is secure. Nothing was read.") }
    }

    private fun captureNodes(secureWindowKnown: Boolean) {
        val before = try { rootInActiveWindow } catch (_: Exception) { null }
        val pkg = before?.packageName?.toString()
        val sensitive = before?.let(::hasPassword) ?: false
        val denied = ScreenReadPolicy.denial(pkg, blockedPackages(), before != null, secureWindowKnown = secureWindowKnown, containsPasswordField = sensitive)
        val snapshot = if (denied != null) ScreenSnapshot(pkg.orEmpty(), System.currentTimeMillis(), emptyList(), denied) else try {
            if (containsPasswordOrSecureEditable(before!!)) throw SensitiveScreenException()
            val items=collect(before)
            val after=try { rootInActiveWindow } catch (_:Exception) { null }
            val afterPkg=after?.packageName?.toString()
            val sameWindow=after!=null && after.windowId==before.windowId
            val unsafe=after==null || afterPkg!=pkg || !sameWindow || (after!=null && (hasPassword(after) || containsPasswordOrSecureEditable(after)))
            runCatching { after?.recycle() }
            if(unsafe) throw SensitiveScreenException()
            ScreenSnapshot(pkg!!, System.currentTimeMillis(), items.take(120))
        } catch (_: Exception) { ScreenSnapshot(pkg.orEmpty(), System.currentTimeMillis(), emptyList(), "The active window changed or sensitive content was detected. Nothing was read.") }
        finally { runCatching { before?.recycle() } }
        // Snapshot is created before launching the panel; text is passed only in-process and not persisted.
        try { startActivity(Intent(this, ScreenActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP).putExtra(ScreenActivity.EXTRA_SNAPSHOT, ScreenSnapshotCodec.encode(snapshot))) }
        catch (_: Exception) { }
        finally { busy.set(false) }
    }
    private fun openDenied(reason: String) {
        try { startActivity(Intent(this,ScreenActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP).putExtra(ScreenActivity.EXTRA_SNAPSHOT,ScreenSnapshotCodec.encode(ScreenSnapshot("",System.currentTimeMillis(),emptyList(),reason)))) } catch (_:Exception) { }
        finally { busy.set(false) }
    }
    private fun blockedPackages(): Set<String> = getSharedPreferences("screen_assistant", MODE_PRIVATE).getStringSet("blocked_packages", emptySet())?.toSet() ?: emptySet()
    private fun hasPassword(node: AccessibilityNodeInfo): Boolean = scanSensitive(node,0,false)
    private fun containsPasswordOrSecureEditable(node: AccessibilityNodeInfo): Boolean = scanSensitive(node,0,true)
    private fun scanSensitive(node: AccessibilityNodeInfo, depth:Int, rejectEditable:Boolean): Boolean {
        if(depth>24) return true
        if(node.isPassword || node.className?.toString()?.contains("Password",true)==true || (rejectEditable && node.isEditable && node.text?.isNotEmpty()==true)) return true
        for(i in 0 until node.childCount) {
            val child=try { node.getChild(i) } catch (_:Exception) { null } ?: return true
            val found=try { scanSensitive(child,depth+1,rejectEditable) } finally { runCatching { child.recycle() } }
            if(found) return true
        }
        return false
    }
    private class SensitiveScreenException: RuntimeException()
    private fun collect(root: AccessibilityNodeInfo): List<ScreenItem> {
        val out = mutableListOf<ScreenItem>()
        fun walk(n: AccessibilityNodeInfo, depth: Int) {
            if (depth > 24 || out.size >= 120) return
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
