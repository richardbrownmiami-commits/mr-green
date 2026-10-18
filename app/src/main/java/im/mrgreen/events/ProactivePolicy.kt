package im.mrgreen.events

/** Pure policy logic, independently testable without Android or wall-clock dependencies. */
internal object ProactivePolicy {
    fun allowed(enabled: Boolean, hour: Int, quietFrom: Int, quietUntil: Int, elapsedSinceLastMs: Long, minimumIntervalMinutes: Int = 60): Boolean {
        require(hour in 0..23 && quietFrom in 0..23 && quietUntil in 0..23)
        require(minimumIntervalMinutes in 1..1440)
        if (!enabled) return false
        val quiet = when {
            quietFrom == quietUntil -> true
            quietFrom < quietUntil -> hour >= quietFrom && hour < quietUntil
            else -> hour >= quietFrom || hour < quietUntil
        }
        return !quiet && elapsedSinceLastMs >= minimumIntervalMinutes * 60_000L
    }
}
