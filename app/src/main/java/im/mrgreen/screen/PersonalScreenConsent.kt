package im.mrgreen.screen

import android.content.Context
import android.os.Build
import im.mrgreen.BuildConfig

/** Stores a boolean only. No screen text or screenshots are saved, backed up or exported. */
object PersonalScreenConsent {
    private const val PREFS = "screen_assistant"
    private const val KEY = "personal_legacy_consent_v1"
    const val WARNING = "Android 11–13 cannot reliably verify whether the active accessibility window is protected. This personal-use mode may read text from a protected screen. Password, filled-editable and blocked-app checks remain, but cannot guarantee detection of every secret. Use only harmless screens. Reads happen only when you tap Read or explicitly use a manual Controls session. Manual changes require separate confirmation and Stop cancels future actions, not completed ones. Screen questions remain local and screen text is not saved."

    fun available(): Boolean = BuildConfig.PERSONAL_SCREEN_READING && Build.VERSION.SDK_INT in 30..33
    fun enabled(context: Context): Boolean = ScreenReadPolicy.permitsPersonalLegacyRead(
        Build.VERSION.SDK_INT, BuildConfig.PERSONAL_SCREEN_READING,
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, false)
    )
    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY, enabled && available()).apply()
    }
}
