package im.mrgreen.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.os.SystemClock
import android.provider.Settings
import android.view.View
import android.view.accessibility.AccessibilityManager
import android.view.animation.LinearInterpolator
import kotlin.math.*

/** Hand-drawn ambient status orb; animation is lifecycle/visibility aware and respects animator scale. */
class OrbView(context: Context) : View(context) {
    enum class Mode { IDLE, THINKING, ERROR }
    var mode: Mode = Mode.IDLE
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var phase = 0f
    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 7200L; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
        addUpdateListener { phase = it.animatedValue as Float; invalidate() }
    }
    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); syncAnimation() }
    override fun onDetachedFromWindow() { animator.cancel(); super.onDetachedFromWindow() }
    override fun onVisibilityChanged(changedView: View, visibility: Int) { super.onVisibilityChanged(changedView, visibility); syncAnimation() }
    override fun onWindowVisibilityChanged(visibility: Int) { super.onWindowVisibilityChanged(visibility); syncAnimation() }
    private fun syncAnimation() {
        val scale = try { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) } catch (_: Exception) { 1f }
        val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val reduce = context.getSharedPreferences("ui", Context.MODE_PRIVATE).getBoolean("reduce_motion", false)
        if (isAttachedToWindow && isShown && windowVisibility == VISIBLE && scale > 0f && !am.isEnabled && !reduce) { if (!animator.isStarted) animator.start() }
        else animator.cancel()
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f; val cy = height / 2f; val base = min(width * .29f, height * .39f)
        val t = phase * 2f * Math.PI.toFloat()
        val pulse = if (mode == Mode.IDLE) 1f + .035f * sin(t) else 1f
        val radius = base * pulse
        val color = if (mode == Mode.ERROR) Color.rgb(255, 103, 92) else Color.rgb(91, 222, 147)
        paint.shader = RadialGradient(cx, cy, radius * 1.9f, intArrayOf(Color.TRANSPARENT, Color.argb(34, 56, 225, 132), Color.TRANSPARENT), floatArrayOf(.2f,.65f,1f), Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, radius * 1.9f, paint); paint.shader = null
        paint.style = Paint.Style.STROKE; paint.strokeWidth = 1.5f; paint.color = Color.argb(65, 132, 246, 180)
        canvas.drawCircle(cx, cy, radius * 1.23f, paint); canvas.drawCircle(cx, cy, radius * 1.39f, paint)
        paint.style = Paint.Style.FILL
        paint.shader = RadialGradient(cx - radius * .28f, cy - radius * .36f, radius * 1.7f, intArrayOf(Color.WHITE, color, Color.rgb(10, 87, 52)), floatArrayOf(0f,.24f,1f), Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, radius, paint); paint.shader = null
        if (mode == Mode.THINKING) {
            paint.style = Paint.Style.STROKE; paint.strokeWidth = 4f; paint.strokeCap = Paint.Cap.ROUND
            paint.color = Color.rgb(178, 255, 202)
            val rect = RectF(cx-radius*1.52f, cy-radius*1.52f, cx+radius*1.52f, cy+radius*1.52f)
            canvas.drawArc(rect, phase*360f, 100f, false, paint); paint.style = Paint.Style.FILL
        } else if (mode == Mode.ERROR) {
            paint.color = color; canvas.drawCircle(cx + radius*1.1f, cy-radius*1.0f, 5f, paint)
        }
    }
}
