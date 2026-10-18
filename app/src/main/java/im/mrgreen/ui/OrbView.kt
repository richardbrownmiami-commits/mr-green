package im.mrgreen.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.provider.Settings
import android.view.View
import android.view.accessibility.AccessibilityManager
import android.view.animation.LinearInterpolator
import androidx.appcompat.content.res.AppCompatResources
import im.mrgreen.R
import kotlin.math.*

/** Original faceted sentinel identity; animation respects visibility and reduced motion. */
class OrbView(context: Context) : View(context) {
    enum class Mode { IDLE, THINKING, ERROR }
    var mode: Mode = Mode.IDLE
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val emblem = AppCompatResources.getDrawable(context, R.drawable.ic_mrgreen_orb)!!
    private var phase = 0f
    private var glow: RadialGradient? = null
    private val orbit = RectF()
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
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val cx = w / 2f; val cy = h / 2f
        val radius = min(w * .64f, h * .98f) * .48f
        glow = if (radius > 0f) RadialGradient(cx, cy, radius * 1.4f,
            intArrayOf(0x443F226D, 0x223F226D, Color.TRANSPARENT), floatArrayOf(0f,.6f,1f), Shader.TileMode.CLAMP) else null
        orbit.set(cx-radius, cy-radius, cx+radius, cy+radius)
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f; val cy = height / 2f
        val size = min(width * .64f, height * .98f)
        val radius = size * .48f
        if (radius <= 0f) return
        paint.style = Paint.Style.FILL
        paint.shader = glow
        canvas.drawCircle(cx, cy, radius * 1.4f, paint)
        paint.shader = null
        val floatOffset = if (animator.isRunning) sin(phase * 2f * PI.toFloat()) * size * .012f else 0f
        canvas.save(); canvas.translate(0f, floatOffset)
        emblem.setBounds((cx-size/2).toInt(), (cy-size/2).toInt(), (cx+size/2).toInt(), (cy+size/2).toInt())
        emblem.draw(canvas); canvas.restore()
        if (mode == Mode.THINKING) {
            paint.style = Paint.Style.STROKE; paint.strokeWidth = resources.displayMetrics.density * 2f
            paint.strokeCap = Paint.Cap.ROUND; paint.color = im.mrgreen.extensions.ThemeManager.palette(context).accent
            canvas.drawArc(orbit, phase*360f, 74f, false, paint)
        } else if (mode == Mode.ERROR) {
            paint.style = Paint.Style.STROKE; paint.strokeWidth = resources.displayMetrics.density * 2f
            paint.color = 0xFFFFB4C8.toInt()
            canvas.drawArc(orbit, 45f, 90f, false, paint)
        }
        paint.style = Paint.Style.FILL
    }
}
