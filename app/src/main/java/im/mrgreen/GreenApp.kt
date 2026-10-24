package im.mrgreen

import android.app.Application
import im.mrgreen.core.AgentController
import im.mrgreen.core.GreenLog
import im.mrgreen.data.GreenStore

/** Backend bootstrap for hosts that choose this Application class (or subclass it). */
open class GreenApp : Application() {
    val identity by lazy { im.mrgreen.identity.IdentityStore(this) }
    val conversationMemory by lazy { im.mrgreen.memory.ConversationMemory(this, store) }
    val roadmap by lazy { im.mrgreen.core.RoadmapRuntime(this) }
    val store by lazy { GreenStore(this) }
    val models by lazy { im.mrgreen.engine.LocalModelStore(this) }
    val logger by lazy { GreenLog(this, store) }
    val plugins by lazy { im.mrgreen.plugins.PluginSystem(this) }
    val rive by lazy { im.mrgreen.rive.RiveBrain(java.io.File(filesDir, "rive"),
        assets.open("rive/builtin-conversation.rive").bufferedReader(Charsets.UTF_8).use { it.readText() }) }

    override fun onCreate() {
        super.onCreate()
        im.mrgreen.learning.DatabaseSnapshots.applyPending(this)
        im.mrgreen.extensions.EnginePatchRuntime.prepare(this)
        logger.installCrashHandler()
        logger.write("INFO", "APP", "Backend initialized; sdk=${android.os.Build.VERSION.SDK_INT}")
        try { plugins.recover() } catch (_: Exception) { plugins.manager.enterSafeMode() }
        AgentController.initialize(this)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        logger.write("WARN", "MEM", "onTrimMemory level=$level")
        if (level >= TRIM_MEMORY_RUNNING_LOW && level != TRIM_MEMORY_UI_HIDDEN) AgentController.trimMemory()
    }

    override fun onLowMemory() {
        super.onLowMemory()
        logger.write("ERROR", "MEM", "onLowMemory")
        AgentController.trimMemory()
    }
}
