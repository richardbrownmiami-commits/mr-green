package im.mrgreen.ui

import android.app.Application
import android.view.View
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import im.mrgreen.core.AgentController
import im.mrgreen.core.AgentState
import im.mrgreen.core.TemporaryWebExchange
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [30])
class TemporaryWebUiTest {
    @Test fun temporaryAnswerDisplaysAndClearsWithoutDatabaseOrModel() {
        val field = AgentController::class.java.getDeclaredField("mutable").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val state = field.get(AgentController) as MutableStateFlow<AgentState>
        val original = state.value
        state.value = AgentState(temporaryWeb = TemporaryWebExchange("ask web plants", "Temporary source evidence"))
        val controller = Robolectric.buildActivity(MainActivity::class.java)
        controller.get().getPreferences(0).edit().putBoolean("onboarding_seen", true).commit()
        try {
            controller.setup()
            for (attempt in 0 until 100) {
                shadowOf(android.os.Looper.getMainLooper()).idle()
                if (descendants(controller.get().window.decorView).filterIsInstance<TextView>()
                    .any { it.text.toString().contains("Temporary source evidence") }) break
                Thread.sleep(10)
            }
            val views = descendants(controller.get().window.decorView)
            assertTrue(views.filterIsInstance<TextView>().any { it.text.toString().contains("Temporary source evidence") })
            views.filterIsInstance<MaterialButton>().single { it.text.toString() == "Clear temporary web answer" }.performClick()
            shadowOf(android.os.Looper.getMainLooper()).idle()
            assertNull(state.value.temporaryWeb)
        } finally {
            controller.pause().stop().destroy()
            state.value = original
        }
    }
    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is android.view.ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
}
