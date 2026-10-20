package im.mrgreen.rive

import android.content.Context

/** This personal test build defaults on at the owner's request; a saved choice always wins. */
object RiveSettings {
    fun autoLearning(context: Context): Boolean =
        context.getSharedPreferences("rive", Context.MODE_PRIVATE).getBoolean("auto_learn", true)
}
