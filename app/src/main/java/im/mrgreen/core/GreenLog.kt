package im.mrgreen.core

import android.content.Context
import android.util.Log
import im.mrgreen.data.GreenStore
import java.io.File
import java.time.Instant

/** Prompts, responses, file contents and credentials never enter this logger. */
class GreenLog(private val context: Context, private val store: GreenStore) {
    @Volatile var foreground: Boolean = false
    @Volatile var onIncident: ((String, String, String) -> Unit)? = null

    @Synchronized
    fun write(level: String, component: String, message: String, code: ErrorCode? = null, planId: String? = null, stepId: String? = null) {
        val safe = message.replace('\n', ' ').take(4000)
        try { store.log(level, component, code?.name, safe, if (foreground) "foreground" else "background", planId, stepId) }
        catch (_: Exception) { Log.e("MrGreen", "diagnostic database unavailable") }

        val androidLevel = when (level.uppercase()) {
            "ERROR" -> Log.ERROR
            "WARN", "WARNING" -> Log.WARN
            "DEBUG" -> Log.DEBUG
            else -> Log.INFO
        }
        Log.println(androidLevel, "MrGreen/$component", "${code?.name ?: "-"} $safe")

        if (code != null && level.uppercase() in setOf("WARN", "ERROR")) {
            try { onIncident?.invoke(component.take(80), code.name, "A registered component reported a bounded failure; review private diagnostics.") }
            catch (_: Exception) { /* diagnostics must never crash the app */ }
        }
        try {
            val dir = File(context.filesDir, "logs")
            if (!dir.exists() && !dir.mkdirs()) return
            val file = File(dir, "runtime.log")
            if (file.length() >= 1024 * 1024) {
                File(dir, "runtime.3.log").delete()
                for (i in 2 downTo 1) File(dir, "runtime.$i.log").renameTo(File(dir, "runtime.${i + 1}.log"))
                file.renameTo(File(dir, "runtime.1.log"))
            }
            file.appendText("${Instant.now()} $level $component ${code?.name ?: "-"} $safe\n")
        } catch (_: Exception) { Log.e("MrGreen", "rolling log unavailable") }
    }

    fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                val dir = File(context.filesDir, "crashes").apply { mkdirs() }
                val trace = error.stackTrace.take(40).joinToString("\n") { "  at $it" }
                File(dir, "last-crash.txt").writeText("${Instant.now()} E_SYS_CRASH\nThread: ${thread.name}\nType: ${error.javaClass.name}\n$trace\n")
                Log.e("MrGreen/CRASH", "uncaught exception type=${error.javaClass.name} thread=${thread.name}")
            } catch (_: Throwable) { /* last resort handler must delegate */ }
            previous?.uncaughtException(thread, error) ?: run { android.os.Process.killProcess(android.os.Process.myPid()) }
        }
    }
}
