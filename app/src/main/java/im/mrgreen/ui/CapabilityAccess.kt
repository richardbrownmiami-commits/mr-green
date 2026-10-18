package im.mrgreen.ui

import android.content.ComponentName

/** Android secure settings contain colon-separated flattened components, not package substrings. */
internal object CapabilityAccess {
    fun containsComponent(setting: String?, component: ComponentName): Boolean =
        setting.orEmpty().split(':').any { ComponentName.unflattenFromString(it) == component }
}
