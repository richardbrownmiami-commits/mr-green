package im.mrgreen.core

import java.time.LocalTime

/** Convert reviewed screen text without dropping AM/PM or inventing midnight on parse errors. */
internal object ConfirmedScreenAlarm {
    fun parse(value: String): LocalTime? {
        val match = Regex("(?i)^(\\d{1,2}):(\\d{2})\\s*(am|pm)?$").matchEntire(value.trim()) ?: return null
        val hour = match.groupValues[1].toIntOrNull() ?: return null
        val minute = match.groupValues[2].toIntOrNull() ?: return null
        val meridiem = match.groupValues[3].lowercase(java.util.Locale.ROOT)
        if (minute !in 0..59) return null
        val normalized = if (meridiem.isEmpty()) {
            if (hour !in 0..23) return null
            hour
        } else {
            if (hour !in 1..12) return null
            hour % 12 + if (meridiem == "pm") 12 else 0
        }
        return LocalTime.of(normalized, minute)
    }
}
