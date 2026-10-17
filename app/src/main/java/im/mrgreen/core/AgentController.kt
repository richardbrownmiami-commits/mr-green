package im.mrgreen.core

import android.app.ActivityManager
import android.content.Context
import android.os.Debug
import android.os.PowerManager
import im.mrgreen.GreenApp
import im.mrgreen.engine.ChatTurn
import im.mrgreen.engine.EngineStats
import im.mrgreen.engine.GenerationOptions
import im.mrgreen.engine.LlamaEngine
import im.mrgreen.engine.LoadParams
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

// A single queue owns all native context access. cancel() is the sole out-of-band signal.
data class AgentState(
    val busy: Boolean = false,
    val generating: Boolean = false,
    val status: String = "No model loaded",
    val streaming: String = "",
    val error: String? = null,
    val stats: EngineStats = EngineStats(null, 0, 0.0, 0.0, 0, 0),
    val refresh: Long = 0
)

object AgentController {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private data class QueuedCommand(val userRequest: Boolean, val action: suspend () -> Unit)
    private val commands = Channel<QueuedCommand>(Channel.UNLIMITED)
    private val mutable = MutableStateFlow(AgentState())
    val state: StateFlow<AgentState> = mutable.asStateFlow()
    private val initialized = AtomicBoolean(false)
    private val requested = AtomicBoolean(false)
    private lateinit var app: GreenApp
    private val engine by lazy { LlamaEngine() }
    @Volatile private var lastUse = System.currentTimeMillis()

    fun initialize(application: GreenApp) {
        if (!initialized.compareAndSet(false, true)) return
        app = application
        scope.launch {
            try {
                app.store.writableDatabase
                app.logger.write("INFO", "MEM", "Private database initialized; FTS5=${app.store.fts5Available()}")
                File(app.filesDir,"models").mkdirs()
            } catch (_: Exception) {
                mutable.update { it.copy(error = "E_DB_INIT: Private database could not be initialized", status = "Storage error") }
            }
            for (command in commands) {
                try { command.action() }
                catch (_: Exception) { fail("Agent operation failed", ErrorCode.E_LLM_GENERATE) }
                catch (_: OutOfMemoryError) { engine.cancel(); fail("Low memory. Unload the model and retry with a smaller context.", ErrorCode.E_OOM_TRIM) }
                finally {
                    if (command.userRequest) requested.set(false)
                    mutable.update { it.copy(busy = requested.get(), generating = false, refresh = System.currentTimeMillis()) }
                }
            }
        }
        scope.launch {
            while (isActive) {
                delay(30_000)
                if (!requested.get() && mutable.value.stats.loadedModel != null && System.currentTimeMillis() - lastUse > RuntimePolicy.IDLE_UNLOAD_MS) {
                    enqueue(app) { engine.unload(); publishStats("Model unloaded after 10 idle minutes") }
                }
            }
        }
    }

    private fun enqueue(context: Context, action: suspend () -> Unit) {
        if (!requested.compareAndSet(false, true)) return
        try { AgentService.ensure(context) }
        catch (_: Exception) {
            requested.set(false)
            fail("Foreground service could not start. Open the app and try again.", ErrorCode.E_SYS_SERVICE)
            return
        }
        mutable.update { it.copy(busy = true, error = null) }
        if (!commands.trySend(QueuedCommand(true, action)).isSuccess) { requested.set(false); mutable.update { it.copy(busy = false) } }
    }

    private fun maintenance(action: suspend () -> Unit) { commands.trySend(QueuedCommand(false, action)) }

    fun load(context: Context, path: String, params: LoadParams) = enqueue(context) {
        val file = File(path)
        if (!file.isFile || !RuntimePolicy.validModel(file.name, file.length())) {
            fail("Select a readable GGUF model smaller than 1.3 GiB", ErrorCode.E_LLM_LOAD)
            return@enqueue
        }
        val info = ActivityManager.MemoryInfo()
        (app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(info)
        val nCtx = RuntimePolicy.contextSize(params.nCtx, info.availMem)
        if (info.availMem < 250L * 1024 * 1024) {
            fail("Too little available RAM to load safely. Close other apps first.", ErrorCode.E_OOM_TRIM)
            return@enqueue
        }
        mutable.update { it.copy(status = if(nCtx < params.nCtx) "Low RAM: loading with context $nCtx" else "Loading model…", streaming = "") }
        withWakeLock {
            val result = engine.load(file.absolutePath, params.copy(nCtx = nCtx))
            if (result.ok) {
                lastUse = System.currentTimeMillis()
                publishStats(if(nCtx < params.nCtx) "Local ready · reduced context $nCtx" else "Local ready")
                app.logger.write("INFO", "LLM", "Model loaded; context=$nCtx threads=${params.threads}; device performance unverified")
            } else { publishStats("Model load failed"); fail(result.error ?: "Model load failed", ErrorCode.E_LLM_LOAD) }
        }
    }

    fun submit(context: Context, prompt: String) {
        val text = prompt.trim()
        if (text.isEmpty()) return
        enqueue(context) {
            if (mutable.value.stats.loadedModel == null) {
                fail("Import and load a GGUF model on the Llama screen first. Nothing was sent online.", ErrorCode.E_LLM_UNAVAILABLE)
                return@enqueue
            }
            if (text.length > 12_000) {
                fail("Message is too long for the small local context. Please shorten it.", ErrorCode.E_LLM_CONTEXT)
                return@enqueue
            }
            val runId = UUID.randomUUID().toString()
            val turns = app.store.messages().filter { it.role == "user" || it.role == "assistant" }.takeLast(20)
                .map { ChatTurn(it.role,it.content) }.toMutableList()
            turns.add(ChatTurn("user",text))
            val name = app.store.identityName()
            val system = ChatTurn("system", "You are $name, a helpful offline assistant. Be concise and honest. Do not claim to perform device actions: no tools are connected in this Phase 1 build. Do not invent device state, memory records, time or computed facts. Thinking is disabled. /no_think")
            val maxTokens = 256
            val budget = (mutable.value.stats.nCtx - maxTokens - 96).coerceAtLeast(128)
            fun tokenCost() = engine.countTokens(system.content) + turns.sumOf { engine.countTokens(it.content) + 12 }
            while (turns.size > 1 && tokenCost() > budget) {
                turns.removeAt(0)
                if (turns.size > 1 && turns.first().role == "assistant") turns.removeAt(0)
            }
            if (tokenCost() > budget) {
                fail("Message does not fit the loaded model context. Please shorten it.", ErrorCode.E_LLM_CONTEXT)
                return@enqueue
            }
            app.store.addMessage("user",text)
            mutable.update { it.copy(status = "Thinking locally…", generating = true, streaming = "", refresh = System.currentTimeMillis()) }
            val buffer = StringBuilder()
            var lastPublish = 0L
            val started = System.nanoTime()
            val monitor = scope.launch {
                while (isActive) {
                    delay(1000)
                    val mem = Debug.MemoryInfo(); Debug.getMemoryInfo(mem)
                    if (mem.totalPss.toLong() * 1024 >= RuntimePolicy.HARD_CAP_BYTES) {
                        engine.cancel()
                        fail("Memory safety stop; unload the model and use a smaller context", ErrorCode.E_OOM_TRIM)
                        break
                    }
                }
            }
            try {
                withWakeLock {
                    val result = engine.generate(listOf(system) + turns, GenerationOptions(maxTokens = maxTokens)) { token ->
                        buffer.append(token)
                        val now = System.currentTimeMillis()
                        if (now - lastPublish >= 50) {
                            mutable.update { it.copy(streaming = buffer.toString()) }; lastPublish = now
                        }
                    }
                    if (buffer.isNotBlank()) app.store.addMessage("assistant",buffer.toString())
                    mutable.update { it.copy(streaming = "", refresh = System.currentTimeMillis()) }
                    if (result.ok) publishStats("Local ready")
                    else if (result.cancelled) publishStats("Generation cancelled")
                    else { publishStats("Local generation stopped"); fail(result.error ?: "Generation stopped", ErrorCode.E_LLM_GENERATE) }
                    val elapsedMs = (System.nanoTime() - started) / 1_000_000
                    val detail = if (result.ok) "" else if (result.cancelled) "cancelled" else "; native_error=${result.error ?: "unknown"}"
                    app.logger.write(if(result.ok || result.cancelled) "INFO" else "WARN", "LLM", "Local chat completed=${result.ok}; duration_ms=$elapsedMs; output_chars=${buffer.length}; gen_tok_s=${engine.stats().genTokPerSec}$detail", if (!result.ok && !result.cancelled) ErrorCode.E_LLM_GENERATE else null, planId = runId)
                }
            } finally { monitor.cancel(); lastUse = System.currentTimeMillis() }
        }
    }

    fun unload(context: Context) {
        if (requested.get()) {
            engine.cancel()
            maintenance {
                engine.unload()
                publishStats("No model loaded")
                app.logger.write("INFO", "LLM", "Local model unloaded after cancellation")
            }
            return
        }
        enqueue(context) { engine.unload(); publishStats("No model loaded"); app.logger.write("INFO", "LLM", "Local model unloaded") }
    }
    fun cancel() { if (initialized.get()) { engine.cancel(); mutable.update { it.copy(status = "Stopping…") } } }
    fun releaseForServiceStop() {
        if (!initialized.get()) return
        engine.cancel()
        maintenance {
            engine.unload()
            publishStats("Service stopped · model unloaded")
        }
    }
    fun trimMemory() {
        if (!initialized.get()) return
        if (mutable.value.stats.loadedModel == null && !requested.get()) return
        engine.cancel()
        maintenance {
            app.logger.write("WARN", "SYS", "Android requested memory trim", ErrorCode.E_OOM_TRIM)
            engine.unload()
            publishStats("Model unloaded to release memory")
        }
    }
    private fun publishStats(status: String) { mutable.update { it.copy(stats = engine.stats(), status = status) } }
    private fun fail(message: String, code: ErrorCode) {
        mutable.update { it.copy(error = "${code.name}: $message", status = "Needs attention") }
        if (initialized.get()) app.logger.write("WARN", "LLM", message, code)
    }
    private suspend fun withWakeLock(block: suspend () -> Unit) {
        val power = app.getSystemService(Context.POWER_SERVICE) as PowerManager
        val lock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MrGreen:active-task")
        try { lock.acquire(10 * 60 * 1000L); block() }
        finally { if (lock.isHeld) lock.release() }
    }
}
