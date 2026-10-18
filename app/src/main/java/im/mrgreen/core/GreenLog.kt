package im.mrgreen.core

import android.content.Context
import android.os.Environment
import android.util.Log
import im.mrgreen.data.GreenStore
import java.io.File
import java.time.Instant

/** Prompts, responses, file contents and credentials never enter this logger. */
class GreenLog(private val context: Context, private val store: GreenStore) {
    @Volatile var foreground: Boolean = false
    @Volatile var onIncident: ((String,String,String)->Unit)? = null
    @Synchronized fun write(level: String, component: String, message: String, code: ErrorCode? = null, planId: String? = null, stepId: String? = null) {
        try { store.log(level, component, code?.name, message, if (foreground) "foreground" else "background", planId, stepId) }
        catch (_: Exception) { Log.e("MrGreen", "Log database unavailable") }
        if(code!=null && level in setOf("WARN","ERROR")) try { onIncident?.invoke(component.take(80),code.name,"A registered component reported a bounded failure; review permissions and private diagnostics.") } catch(_:Exception) {}
        try {
            val shared = android.os.Build.VERSION.SDK_INT >= 30 && Environment.isExternalStorageManager()
            val dir = if (shared) File(Environment.getExternalStorageDirectory(), "Red/logs") else File(context.filesDir, "logs")
            if (!dir.exists() && !dir.mkdirs()) return
            val file = File(dir, "runtime.log")
            if (file.length() >= 1024 * 1024) {
                File(dir, "runtime.3.log").delete()
                for (i in 2 downTo 1) File(dir,"runtime.$i.log").renameTo(File(dir,"runtime.${i+1}.log"))
                file.renameTo(File(dir, "runtime.1.log"))
            }
            file.appendText("${Instant.now()} $level $component ${code?.name ?: "-"} ${message.replace('\n',' ').take(4000)}\n")
        } catch (_: Exception) { Log.e("MrGreen", "Rolling log unavailable") }
    }
    fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                val dir = File(context.filesDir, "crashes").apply { mkdirs() }
                // No exception message: it may include prompt/model/file content.
                val trace = error.stackTrace.take(40).joinToString("\n") { "  at $it" }
                File(dir, "last-crash.txt").writeText("${Instant.now()} E_SYS_CRASH\nThread: ${thread.name}\nType: ${error.javaClass.name}\n$trace\n")
            } catch (_: Throwable) { /* last resort handler must delegate */ }
            previous?.uncaughtException(thread, error) ?: run { android.os.Process.killProcess(android.os.Process.myPid()) }
        }
    }
}
