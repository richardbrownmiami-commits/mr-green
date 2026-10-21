package im.mrgreen.core

enum class ErrorCode {
    E_LLM_LOAD, E_LLM_GENERATE, E_LLM_CANCELLED, E_LLM_UNAVAILABLE, E_LLM_CONTEXT,
    E_LLM_TIMEOUT, E_DB_INIT, E_DB_WRITE, E_DB_FTS5, E_STORAGE_PERMISSION,
    E_TOOL_FAILED, E_TOOL_PERMISSION, E_VERIFY_FAIL, E_GROQ_429, E_ACCESS_NODE_NOT_FOUND,
    E_SCREEN_SECURE, E_PLUGIN_SANDBOX_KILL, E_DB_RECALL_EMPTY, E_OOM_TRIM,
    E_GATEWAY_FALLBACK, E_GATEWAY_PRIVACY, E_GATEWAY_NETWORK,
    E_SYS_CRASH, E_SYS_SERVICE
}

object RuntimePolicy {
    const val LOW_RAM_BYTES = 600L * 1024 * 1024
    const val TARGET_PEAK_BYTES = 1200L * 1024 * 1024
    const val HARD_CAP_BYTES = 2L * 1024 * 1024 * 1024
    // The old 1.3 GiB gate rejected valid 1.4–1.7 GiB GGUFs before llama.cpp ever saw them.
    // Keep a 32-bit-safe ceiling instead of pretending a 2+ GiB ARMv7 process is safe.
    const val MAX_MODEL_BYTES = 1792L * 1024 * 1024
    const val MODEL_HEADROOM_BYTES = 320L * 1024 * 1024
    const val IDLE_UNLOAD_MS = 10L * 60 * 1000

    fun contextSize(requested: Int, availableBytes: Long): Int =
        requested.coerceIn(512, if (availableBytes < LOW_RAM_BYTES) 1024 else 2048)

    fun validModel(fileName: String, bytes: Long): Boolean =
        fileName.endsWith(".gguf", ignoreCase = true) && bytes in 1..MAX_MODEL_BYTES

    fun hasModelMemoryBudget(modelBytes: Long, availableBytes: Long): Boolean =
        modelBytes > 0 && modelBytes <= MAX_MODEL_BYTES && availableBytes >= modelBytes + MODEL_HEADROOM_BYTES
}
