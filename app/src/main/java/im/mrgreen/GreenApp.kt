package im.mrgreen

import android.app.Activity
import android.app.Application
import android.os.Bundle
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
    val rive by lazy { im.mrgreen.rive.RiveBrain(java.io.File(filesDir, "rive"),
        assets.open("rive/builtin-conversation.rive").bufferedReader(Charsets.UTF_8).use { it.readText() }) }

    override fun onCreate() {
        super.onCreate()
        im.mrgreen.learning.DatabaseSnapshots.applyPending(this)
        im.mrgreen.extensions.EnginePatchRuntime.prepare(this)
        androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES)
        logger.installCrashHandler()
        logger.write("INFO", "APP", "Application created; sdk=${android.os.Build.VERSION.SDK_INT} device=${android.os.Build.MANUFACTURER}/${android.os.Build.MODEL}")
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, state: Bundle?) = logger.write("INFO", "ACTIVITY", "created ${activity.javaClass.simpleName}")
            override fun onActivityStarted(activity: Activity) { logger.foreground = true; logger.write("DEBUG", "ACTIVITY", "started ${activity.javaClass.simpleName}") }
            override fun onActivityResumed(activity: Activity) { logger.foreground = true; logger.write("DEBUG", "ACTIVITY", "resumed ${activity.javaClass.simpleName}") }
            override fun onActivityPaused(activity: Activity) { logger.write("DEBUG", "ACTIVITY", "paused ${activity.javaClass.simpleName}") }
            override fun onActivityStopped(activity: Activity) { logger.foreground = false; logger.write("DEBUG", "ACTIVITY", "stopped ${activity.javaClass.simpleName}") }
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) { logger.write("DEBUG", "ACTIVITY", "state saved ${activity.javaClass.simpleName}") }
            override fun onActivityDestroyed(activity: Activity) { logger.write("DEBUG", "ACTIVITY", "destroyed ${activity.javaClass.simpleName}") }
        })
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
