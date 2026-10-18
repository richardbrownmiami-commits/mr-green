package im.mrgreen.plugins

import android.app.Application
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import im.mrgreen.ui.MainActivity
import im.mrgreen.ui.PluginsActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class PluginsUiTest {
    private fun texts(view: View): List<TextView> = buildList {
        if (view is TextView) add(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) addAll(texts(view.getChildAt(i)))
    }
    @Test fun overviewOpensSameAppPluginScreen() {
        val controller = Robolectric.buildActivity(MainActivity::class.java)
        controller.get().getPreferences(0).edit().putBoolean("onboarding_seen", true).commit()
        controller.create()
        val navigation = controller.get().findViewById<com.google.android.material.bottomnavigation.BottomNavigationView>(im.mrgreen.R.id.bottom_navigation)
        navigation.selectedItemId = im.mrgreen.R.id.tab_panel
        val plugin = texts(controller.get().window.decorView).first { it.text.toString() == "Plugins · Phase 7" }
        assertTrue(plugin.performClick())
        assertEquals(PluginsActivity::class.java.name, Shadows.shadowOf(controller.get()).nextStartedActivity.component?.className)
        controller.destroy()
    }
    @Test fun pluginScreenOffersImportBuilderAndExplicitSafeModeRecovery() {
        val controller = Robolectric.buildActivity(PluginsActivity::class.java).create()
        val labels = texts(controller.get().window.decorView).map { it.text.toString() }
        assertTrue(labels.any { it == "Import .redpack from phone / SD" })
        assertTrue(labels.any { it == "Build a local flow" })
        assertTrue(labels.any { it == "Build a local script" })
        assertTrue(labels.any { it == "Leave safe mode" })
        assertTrue(labels.any { it.contains("layers 3/4 are not installed") })
        controller.destroy()
    }
    @Test fun builderStartsWithEditableLocalDraftAndDoesNotInstall() {
        val controller = Robolectric.buildActivity(PluginsActivity::class.java).create()
        val working=PluginsActivity::class.java.getDeclaredField("working").apply { isAccessible=true }
        for (attempt in 0 until 200) {
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            if (!working.getBoolean(controller.get())) break
            Thread.sleep(10)
        }
        assertFalse("Initial state loading must finish",working.getBoolean(controller.get()))
        texts(controller.get().window.decorView).first { it.text.toString() == "Build a local flow" }.performClick()
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        val dialog = org.robolectric.shadows.ShadowDialog.getLatestDialog()
        assertNotNull(dialog)
        val labels = texts(dialog.window!!.decorView).map { it.text.toString() }
        assertTrue(labels.any { it == "Validate draft" })
        assertTrue(labels.any { it.contains("separate install approval") })
        dialog.dismiss()
        controller.destroy()
    }
}
