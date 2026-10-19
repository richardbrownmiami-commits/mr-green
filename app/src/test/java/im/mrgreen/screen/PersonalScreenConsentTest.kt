package im.mrgreen.screen

import android.app.Application
import android.content.Context
import im.mrgreen.BuildConfig
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class)
class PersonalScreenConsentTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    @Before fun clearConsent() {
        context.getSharedPreferences("screen_assistant",Context.MODE_PRIVATE).edit().clear().commit()
    }
    @Test fun consentStartsOffEvenInPersonalBuild() {
        assertFalse(PersonalScreenConsent.enabled(context))
        assertEquals(BuildConfig.PERSONAL_SCREEN_READING,PersonalScreenConsent.available())
    }
    @Test fun explicitConsentIsBuildGatedAndRevocable() {
        PersonalScreenConsent.setEnabled(context,true)
        assertEquals(BuildConfig.PERSONAL_SCREEN_READING,PersonalScreenConsent.enabled(context))
        PersonalScreenConsent.setEnabled(context,false)
        assertFalse(PersonalScreenConsent.enabled(context))
    }
    @Test fun staleConsentCannotBypassBuildGate() {
        context.getSharedPreferences("screen_assistant",Context.MODE_PRIVATE).edit()
            .putBoolean("personal_legacy_consent_v1",true).commit()
        assertEquals(BuildConfig.PERSONAL_SCREEN_READING,PersonalScreenConsent.enabled(context))
    }
    @Test @Config(sdk=[26]) fun unsupportedAndroidCannotOptIn() {
        PersonalScreenConsent.setEnabled(context,true)
        assertFalse(PersonalScreenConsent.available())
        assertFalse(PersonalScreenConsent.enabled(context))
    }
    @Test fun bubbleWithoutConsentReturnsDeniedSnapshot() {
        val controller=Robolectric.buildService(ScreenAssistantService::class.java).create()
        val service=controller.get()
        ScreenAssistantService::class.java.getDeclaredMethod("captureBeforePanel").apply { isAccessible=true }.invoke(service)
        val intent=shadowOf(service).nextStartedActivity
        assertNotNull(intent)
        val snapshot=ScreenSnapshotCodec.decode(intent.getStringExtra(ScreenActivity.EXTRA_SNAPSHOT)!!)
        assertFalse(snapshot.readable)
        assertTrue(snapshot.items.isEmpty())
        assertTrue(snapshot.blockedReason!!.contains("disabled"))
        controller.destroy()
    }
}
