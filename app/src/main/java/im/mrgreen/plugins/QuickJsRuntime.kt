package im.mrgreen.plugins

fun interface ScriptHost {
    /** Return one JSON value. The runtime limits calls and payload sizes. */
    fun invoke(method: String, argsJson: String): String
}

class QuickJsRuntime {
    companion object { init { System.loadLibrary("mrgreen_quickjs") } }
    private external fun nativeRun(source: String, host: ScriptHost, timeoutMs: Long): String

    /** Executes a plugin script and returns its JSON-serialized result. Failures never include script data. */
    fun run(source: String, host: ScriptHost, timeoutMs: Long = 4000): String =
        try { nativeRun(source, host, timeoutMs.coerceIn(1, 4000)) }
        catch (_: Throwable) { throw IllegalStateException("Script execution failed") }
}
