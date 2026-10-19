package im.mrgreen

import android.app.Application
import im.mrgreen.core.AgentController
import im.mrgreen.core.GreenLog
import im.mrgreen.data.GreenStore

class GreenApp : Application() {
    val identity by lazy { im.mrgreen.identity.IdentityStore(this) }
    val conversationMemory by lazy { im.mrgreen.memory.ConversationMemory(this, store) }
    val roadmap by lazy { im.mrgreen.core.RoadmapRuntime(this) }
    val store by lazy { GreenStore(this) }
    val logger by lazy { GreenLog(this, store) }
    val plugins by lazy { im.mrgreen.plugins.PluginSystem(this) }
    val rive by lazy { im.mrgreen.rive.RiveBrain(java.io.File(filesDir, "rive")) }
    override fun onCreate() {
        super.onCreate()
        im.mrgreen.learning.DatabaseSnapshots.applyPending(this)
        im.mrgreen.extensions.EnginePatchRuntime.prepare(this)
        androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES)
        logger.installCrashHandler()
        // Persisted in-flight marker also detects a native plugin crash on next start.
        try { plugins.recover() } catch (_: Exception) { plugins.manager.enterSafeMode() }
        // Database initialization stays off the startup/UI thread in AgentController.
        AgentController.initialize(this)
    }
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_RUNNING_LOW && level != TRIM_MEMORY_UI_HIDDEN) AgentController.trimMemory()
    }
    override fun onLowMemory() { super.onLowMemory(); AgentController.trimMemory() }
}
