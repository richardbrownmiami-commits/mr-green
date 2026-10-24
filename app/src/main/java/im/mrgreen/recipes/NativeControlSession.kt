package im.mrgreen.recipes

import android.os.SystemClock
import im.mrgreen.core.AgentController

/** Headless safety gate; the consuming host owns all user interaction and confirmation surfaces. */
internal class NativeControlSession {
    @Volatile private var started = false
    @Volatile private var startedAt = 0L
    @Volatile var generation = 0L
        private set

    val active: Boolean
        get() = started && SystemClock.uptimeMillis() - startedAt < SESSION_TIMEOUT_MS

    /** Requires the host's explicit start action and its per-action confirmation callback. */
    fun startHostApprovedSession(): Boolean {
        if (RecipeRuntime.isRunning || RecipeRuntime.confirmation == null) return false
        if (active) return true
        generation++
        startedAt = SystemClock.uptimeMillis()
        started = true
        return true
    }

    fun isCurrent(token: Long): Boolean = active && token == generation

    fun stop() {
        generation++
        started = false
        RecipeRuntime.cancelPendingConfirmation?.invoke()
        AgentController.cancel()
    }

    private companion object {
        const val SESSION_TIMEOUT_MS = 5 * 60 * 1000L
    }
}
