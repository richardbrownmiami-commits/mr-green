package im.mrgreen.extensions

import android.content.Context
import android.content.Intent
import android.graphics.Color

/** Shared theme preference API for the app and overlay. Consumers subscribe to ACTION_THEME_CHANGED. */
object ThemeManager {
    const val ACTION_THEME_CHANGED = "im.mrgreen.extensions.THEME_CHANGED"
    const val EXTRA_MODE = "mode"
    const val EXTRA_ACCENT = "accent"
    enum class Mode { DARK, LIGHT, AMOLED }
    data class Palette(val background: Int, val surface: Int, val text: Int, val muted: Int, val accent: Int)
    val accents: List<Int> = listOf(0xFF2BE37A.toInt(), 0xFFB6FF3B.toInt(), 0xFF54C7EC.toInt(), 0xFF8B7CFF.toInt(), 0xFFFF6B9A.toInt(), 0xFFFFA94D.toInt(), 0xFFFFD43B.toInt(), 0xFF66D9C2.toInt())
    private const val PREFS = "appearance_v1"
    private const val MODE = "mode"
    private const val ACCENT = "accent"
    fun mode(context: Context): Mode = runCatching { Mode.valueOf(prefs(context).getString(MODE, Mode.DARK.name)!!) }.getOrDefault(Mode.DARK)
    fun accentIndex(context: Context): Int = prefs(context).getInt(ACCENT, 0).coerceIn(accents.indices)
    fun palette(context: Context): Palette = palette(mode(context), accents[accentIndex(context)])
    fun setMode(context: Context, mode: Mode) { prefs(context).edit().putString(MODE, mode.name).apply(); broadcast(context) }
    fun setAccent(context: Context, index: Int) { require(index in accents.indices); prefs(context).edit().putInt(ACCENT, index).apply(); broadcast(context) }
    private fun palette(mode: Mode, accent: Int) = when (mode) {
        Mode.DARK -> Palette(0xFF07130C.toInt(), 0xFF0E1F15.toInt(), 0xFFE8F5EC.toInt(), 0xFF8FB19B.toInt(), accent)
        Mode.LIGHT -> Palette(0xFFF3FAF5.toInt(), Color.WHITE, 0xFF122219.toInt(), 0xFF526B5A.toInt(), accent)
        Mode.AMOLED -> Palette(Color.BLACK, 0xFF080D09.toInt(), 0xFFE8F5EC.toInt(), 0xFF8FB19B.toInt(), accent)
    }
    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun broadcast(context: Context) { val p = palette(context); context.applicationContext.sendBroadcast(Intent(ACTION_THEME_CHANGED).setPackage(context.packageName).putExtra(EXTRA_MODE, mode(context).name).putExtra(EXTRA_ACCENT, p.accent)) }
}
