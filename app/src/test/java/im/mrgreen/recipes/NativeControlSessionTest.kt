package im.mrgreen.recipes

import android.app.Application
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class)
class NativeControlSessionTest {
    private var controller: org.robolectric.android.controller.ServiceController<RecipeAccessibilityService>? = null

    private fun service(): RecipeAccessibilityService {
        controller = Robolectric.buildService(RecipeAccessibilityService::class.java).create()
        return controller!!.get()
    }

    @After fun tearDown() {
        controller?.destroy()
        RecipeRuntime.cancelPendingConfirmation = null
        RecipeRuntime.service = null
    }

    @Test fun stopOnlyOverlayCreatesCurrentGenerationAndStopInvalidatesIt() {
        val service = service()
        val session = service.controls

        assertTrue("the Stop overlay should be reachable", session.showStopOnly())
        val token = session.generation
        assertTrue(session.active)
        assertTrue(session.isCurrent(token))

        val field = NativeControlSession::class.java.getDeclaredField("bar").apply { isAccessible = true }
        val bar = field.get(session) as android.widget.LinearLayout
        assertTrue(bar.getChildAt(1).performClick())

        assertFalse(session.active)
        assertNotEquals(token, session.generation)
        assertFalse(session.isCurrent(token))
    }

    @Test fun stopCancelsPendingRecipeConfirmation() {
        val service = service()
        var cancelled = false
        RecipeRuntime.cancelPendingConfirmation = { cancelled = true }
        assertTrue(service.controls.showStopOnly())

        service.controls.stop()

        assertTrue("Stop must cancel a pending recipe confirmation", cancelled)
    }

    @Test fun sessionsExpireAndQueuedActionsCannotReuseTheirToken() {
        val session = service().controls
        assertTrue(session.showStopOnly())
        val token = session.generation
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMinutes(5))
        assertFalse(session.active)
        assertFalse(session.isCurrent(token))
    }

    @Test fun manualControlsFailClosedWithoutStoredConsent() {
        val service = service()
        service.getSharedPreferences("screen_assistant", 0).edit()
            .putBoolean("personal_legacy_consent_v1", false).commit()
        assertFalse(service.controls.startControls())
        assertFalse(service.controls.active)
        service.getSharedPreferences("screen_assistant", 0).edit()
            .putBoolean("personal_legacy_consent_v1", true).commit()
        org.junit.Assert.assertEquals(im.mrgreen.BuildConfig.PERSONAL_SCREEN_READING, service.controls.startControls())
    }
}
