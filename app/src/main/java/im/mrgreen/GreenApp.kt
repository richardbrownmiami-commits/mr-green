package im.mrgreen

import android.app.Application
import im.mrgreen.core.AgentController
import im.mrgreen.core.GreenLog
import im.mrgreen.data.GreenStore

class GreenApp : Application() {
    val store by lazy { GreenStore(this) }
    val logger by lazy { GreenLog(this, store) }
    override fun onCreate() {
        super.onCreate()
        androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES)
        logger.installCrashHandler()
        // Database initialization stays off the startup/UI thread in AgentController.
        AgentController.initialize(this)
    }
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_RUNNING_LOW && level != TRIM_MEMORY_UI_HIDDEN) AgentController.trimMemory()
    }
    override fun onLowMemory() { super.onLowMemory(); AgentController.trimMemory() }
}
