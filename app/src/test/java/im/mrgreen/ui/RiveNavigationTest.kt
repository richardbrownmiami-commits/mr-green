package im.mrgreen.ui

import android.app.Application
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class)
class RiveNavigationTest {
    @Test fun panelLaunchesRiveRulesActivity() {
        val controller = Robolectric.buildActivity(MainActivity::class.java)
        val activity = controller.get()
        activity.getPreferences(0).edit().putBoolean("onboarding_seen", true).commit()
        controller.create()
        activity.findViewById<com.google.android.material.bottomnavigation.BottomNavigationView>(im.mrgreen.R.id.bottom_navigation)
            .selectedItemId = im.mrgreen.R.id.tab_panel
        fun find(view: View, label: String): View? {
            if (view is TextView && view.text.toString() == label) return view
            if (view is ViewGroup) for (i in 0 until view.childCount) find(view.getChildAt(i), label)?.let { return it }
            return null
        }
        val entry = find(activity.window.decorView, "Rive rules & learning")
        assertNotNull("Panel should expose the Rive controls entry", entry)
        entry!!.performClick()
        assertEquals(RiveActivity::class.java.name, Shadows.shadowOf(activity).nextStartedActivity.component?.className)
        assertFalse("Rive management must not be exported", activity.packageManager.getActivityInfo(
            android.content.ComponentName(activity, RiveActivity::class.java), 0).exported)
        controller.destroy()
    }

    @Test fun riveScreenContainsReloadAndAutoLearningControls() {
        val controller = Robolectric.buildActivity(RiveActivity::class.java).create()
        val activity = controller.get()
        fun find(view: View, label: String): View? {
            if (view is TextView && view.text.toString() == label) return view
            if (view is ViewGroup) for (i in 0 until view.childCount) find(view.getChildAt(i), label)?.let { return it }
            return null
        }
        assertNotNull("Saved rules can be reloaded", find(activity.window.decorView, "Reload saved rules"))
        assertFalse("Auto-learning remains disabled by default", activity.getSharedPreferences("rive", 0).getBoolean("auto_learn", false))
        controller.destroy()
    }

    private fun views(view: View): List<View> = buildList {
        add(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) addAll(views(view.getChildAt(i)))
    }
    private fun waitForIo(activity: RiveActivity) {
        val field = RiveActivity::class.java.getDeclaredField("working").apply { isAccessible = true }
        repeat(300) {
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            if (!field.getBoolean(activity)) return
            Thread.sleep(10)
        }
        fail("Rule UI IO did not complete")
    }
    @Test fun autoLearningNeedsApprovalAndCanBeDisabledImmediately() {
        val controller = Robolectric.buildActivity(RiveActivity::class.java).create()
        val activity = controller.get()
        waitForIo(activity)
        val toggle = views(activity.window.decorView).filterIsInstance<androidx.appcompat.widget.SwitchCompat>().single()
        val prefs = activity.getSharedPreferences("rive", 0)
        toggle.performClick()
        assertFalse(toggle.isChecked)
        assertFalse(prefs.getBoolean("auto_learn", false))
        val first = org.robolectric.shadows.ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
        first.getButton(android.content.DialogInterface.BUTTON_NEGATIVE).performClick()
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertFalse(prefs.getBoolean("auto_learn", false))
        toggle.performClick()
        val approval = org.robolectric.shadows.ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
        approval.getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick()
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertTrue(toggle.isChecked)
        assertTrue(prefs.getBoolean("auto_learn", false))
        toggle.performClick()
        assertFalse(toggle.isChecked)
        assertFalse(prefs.getBoolean("auto_learn", false))
        controller.destroy()
    }
    @Test fun existingLearningAndOtherSettingsArePreservedOnScreenOpen() {
        val controller = Robolectric.buildActivity(RiveActivity::class.java)
        val activity = controller.get()
        val preferences = activity.getSharedPreferences("rive", 0)
        preferences.edit().putBoolean("auto_learn", true).putString("owner_sentinel", "keep").commit()
        val cloud = activity.getSharedPreferences("cloud", 0)
        cloud.edit().putBoolean("enabled", true).commit()
        controller.create()
        waitForIo(activity)
        assertTrue(views(activity.window.decorView).filterIsInstance<androidx.appcompat.widget.SwitchCompat>().single().isChecked)
        assertTrue(preferences.getBoolean("auto_learn", false))
        assertEquals("keep", preferences.getString("owner_sentinel", null))
        assertTrue(cloud.getBoolean("enabled", false))
        controller.destroy()
    }

    @Test fun editingRequiresValidationAndReplacementApproval() {
        val controller = Robolectric.buildActivity(RiveActivity::class.java).create()
        val activity = controller.get()
        waitForIo(activity)
        views(activity.window.decorView).filterIsInstance<TextView>().first { it.text.toString() == "Edit imported rule text" }.performClick()
        waitForIo(activity)
        val editorDialog = org.robolectric.shadows.ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
        views(editorDialog.window!!.decorView).filterIsInstance<android.widget.EditText>().single().setText("+ hello\n- Owner answer\n")
        editorDialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick()
        waitForIo(activity)
        val directory = java.io.File(activity.filesDir, "rive")
        assertNull(im.mrgreen.rive.RiveBrain(directory).reply("hello"))
        val confirmation = org.robolectric.shadows.ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
        confirmation.getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick()
        waitForIo(activity)
        assertEquals("Owner answer", im.mrgreen.rive.RiveBrain(directory).reply("hello")!!.text)
        controller.destroy()
    }
}
