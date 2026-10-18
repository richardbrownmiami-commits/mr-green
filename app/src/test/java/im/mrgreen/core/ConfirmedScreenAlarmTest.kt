package im.mrgreen.core

import java.time.LocalTime
import org.junit.Assert.*
import org.junit.Test

class ConfirmedScreenAlarmTest {
    @Test fun retainsMeridiemAndTwentyFourHourTimes() {
        assertEquals(LocalTime.of(19, 30), ConfirmedScreenAlarm.parse("7:30 PM"))
        assertEquals(LocalTime.of(19, 30), ConfirmedScreenAlarm.parse("7:30pm"))
        assertEquals(LocalTime.of(0, 0), ConfirmedScreenAlarm.parse("12:00 AM"))
        assertEquals(LocalTime.of(12, 0), ConfirmedScreenAlarm.parse("12:00 pm"))
        assertEquals(LocalTime.of(23, 59), ConfirmedScreenAlarm.parse(" 23:59 "))
        assertEquals(LocalTime.of(7, 5), ConfirmedScreenAlarm.parse("7:05"))
    }
    @Test fun doesNotGuessMidnightOrCoerceMalformedTimes() {
        listOf("garbage", "", "24:00", "13:00 pm", "0:00 am", "7:60", "7:5", "7:30 tomorrow").forEach {
            assertNull(it, ConfirmedScreenAlarm.parse(it))
        }
    }
}
