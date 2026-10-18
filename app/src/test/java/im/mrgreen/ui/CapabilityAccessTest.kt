package im.mrgreen.ui

import android.app.Application
import android.content.ComponentName
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 30], application = Application::class)
class CapabilityAccessTest {
    private val component = ComponentName("im.mrgreen", "im.mrgreen.screen.ScreenAssistantService")
    @Test fun acceptsFullAndAbbreviatedComponents() {
        assertTrue(CapabilityAccess.containsComponent("im.mrgreen/im.mrgreen.screen.ScreenAssistantService", component))
        assertTrue(CapabilityAccess.containsComponent("other/.Service:im.mrgreen/.screen.ScreenAssistantService", component))
    }
    @Test fun rejectsSubstringPackagesAndServiceNames() {
        assertFalse(CapabilityAccess.containsComponent("im.mrgreen.evil/im.mrgreen.screen.ScreenAssistantService", component))
        assertFalse(CapabilityAccess.containsComponent("im.mrgreen/im.mrgreen.screen.ScreenAssistantServiceExtra", component))
        assertFalse(CapabilityAccess.containsComponent("im.mrgreen/.DifferentService", component))
        assertFalse(CapabilityAccess.containsComponent(null, component))
        assertFalse(CapabilityAccess.containsComponent("im.mrgreen", component))
    }
}
