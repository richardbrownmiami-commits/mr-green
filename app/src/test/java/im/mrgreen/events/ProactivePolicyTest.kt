package im.mrgreen.events

import org.junit.Assert.*
import org.junit.Test

class ProactivePolicyTest {
    private val hour = 60L * 60 * 1000
    @Test fun disabledNeverSends() { assertFalse(ProactivePolicy.allowed(false, 12, 22, 8, 2 * hour)) }
    @Test fun overnightQuietHoursIncludeBothSides() {
        assertFalse(ProactivePolicy.allowed(true, 23, 22, 8, 2 * hour))
        assertFalse(ProactivePolicy.allowed(true, 7, 22, 8, 2 * hour))
        assertTrue(ProactivePolicy.allowed(true, 8, 22, 8, 2 * hour))
        assertTrue(ProactivePolicy.allowed(true, 21, 22, 8, 2 * hour))
    }
    @Test fun daytimeQuietHoursAndRateLimit() {
        assertFalse(ProactivePolicy.allowed(true, 13, 12, 14, 2 * hour))
        assertFalse(ProactivePolicy.allowed(true, 15, 12, 14, hour - 1))
        assertTrue(ProactivePolicy.allowed(true, 15, 12, 14, hour))
    }
    @Test fun equalQuietBoundsMeanAllDayQuiet() {
        assertFalse(ProactivePolicy.allowed(true, 15, 15, 15, 5 * hour))
    }
    @Test fun configuredAlertIntervalIsRespected() {
        assertFalse(ProactivePolicy.allowed(true, 15, 22, 8, 29 * 60 * 1000L, 30))
        assertTrue(ProactivePolicy.allowed(true, 15, 22, 8, 30 * 60 * 1000L, 30))
    }
}
