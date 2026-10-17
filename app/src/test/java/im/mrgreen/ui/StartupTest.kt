package im.mrgreen.ui

import android.app.Application
import android.os.Environment
import android.util.TypedValue
import android.view.View
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowEnvironment

/** Host Android simulation only: does not exercise ARM JNI or the owner's phone.
 * A plain Application avoids loading the ARM-only SQLite library on the host.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 30], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class StartupTest {
    @Test fun launcherCreatesChatForReturningUser() {
        val controller = Robolectric.buildActivity(MainActivity::class.java)
        controller.get().getPreferences(0).edit().putBoolean("onboarding_seen", true).commit()
        controller.create()
        assertNotNull(controller.get().findViewById<View>(im.mrgreen.R.id.bottom_navigation))
        controller.destroy()
    }

    @Test fun launcherCreatesChatForFirstLaunch() {
        val controller = Robolectric.buildActivity(MainActivity::class.java)
        controller.get().getPreferences(0).edit().clear().commit()
        controller.create()
        assertNotNull(controller.get().findViewById<View>(im.mrgreen.R.id.bottom_navigation))
        assertEquals(OnboardingActivity::class.java.name,
            org.robolectric.Shadows.shadowOf(controller.get()).nextStartedActivity.component?.className)
        controller.destroy()
    }

    @Test fun navigationThemeProvidesAColorResourceNotALiteral() {
        val context = android.view.ContextThemeWrapper(RuntimeEnvironment.getApplication(), im.mrgreen.R.style.AppTheme)
        val value = TypedValue()
        assertTrue(context.theme.resolveAttribute(android.R.attr.textColorSecondary, value, true))
        assertNotEquals("Material NavigationBarMenuView loads this resource ID in its constructor", 0, value.resourceId)
        assertNotNull(androidx.appcompat.content.res.AppCompatResources.getColorStateList(context, value.resourceId))
        assertNotNull(com.google.android.material.bottomnavigation.BottomNavigationView(context))
    }

    // Robolectric 4.14 has no isExternalStorageManager shadow; real Android's
    // implementation expects a storage volume absent from this host test.
    @Test @Config(shadows = [PermissionEnvironment::class])
    fun onboardingCanCreateAndResume() {
        Robolectric.buildActivity(OnboardingActivity::class.java).setup().close()
    }

    @Test fun orbCanConstructAndChangeVisibility() {
        val context = android.view.ContextThemeWrapper(RuntimeEnvironment.getApplication(), im.mrgreen.R.style.AppTheme)
        val orb = OrbView(context)
        orb.visibility = View.GONE
        orb.visibility = View.VISIBLE
        assertFalse(orb.isAttachedToWindow)
    }

    @Implements(Environment::class)
    class PermissionEnvironment : ShadowEnvironment() {
        companion object {
            @JvmStatic @Implementation(minSdk = 30)
            fun isExternalStorageManager(): Boolean = false
        }
    }
}
