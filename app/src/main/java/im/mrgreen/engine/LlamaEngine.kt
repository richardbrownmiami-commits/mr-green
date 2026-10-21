package im.mrgreen.engine

import android.util.Log

/** Local-only llama.cpp facade. Native failures are represented as EngineResult, never asserted as success. */
data class ChatTurn(val role: String, val content: String)
data class LoadParams(val nCtx: Int = 512, val nBatch: Int = 128, val nUbatch: Int = 64, val threads: Int = 2)
data class GenerationOptions(
    val maxTokens: Int = 512,
    val temperature: Float = .7f,
    val topK: Int = 20,
    val topP: Float = .8f,
    val minP: Float = 0f,
    val presencePenalty: Float = 0f,
    val grammar: String? = null,
    val stop: List<String> = emptyList()
)
data class EngineResult(val ok: Boolean, val error: String? = null) {
    val cancelled: Boolean get() = !ok && error == "Generation cancelled"
}
data class EngineStats(
    val loadedModel: String?, val nCtx: Int, val promptTokPerSec: Double,
    val genTokPerSec: Double, val kvBytes: Long, val nativeHeapBytes: Long,
    val architecture: String? = null, val modelName: String? = null,
    val templateKind: String? = null, val tokenizerType: String? = null,
    val vocabSize: Int = 0
)

class LlamaEngine {
    companion object {
        private val loadError: String? = try {
            im.mrgreen.extensions.EnginePatchRuntime.beginOperation("link")
            val replacement = im.mrgreen.extensions.EnginePatchRuntime.selectedLibrary
            if (replacement == null) System.loadLibrary("mrgreen") else System.load(replacement)
            im.mrgreen.extensions.EnginePatchRuntime.finishOperation()
            null
        } catch (t: Throwable) {
            im.mrgreen.extensions.EnginePatchRuntime.reject()
            "Native engine unavailable: ${t.message ?: t.javaClass.simpleName}"
        }
    }

    private external fun nativeLoad(path: String, nCtx: Int, nBatch: Int, nUbatch: Int, threads: Int): String?
    private external fun nativeUnload()
    private external fun nativeGenerate(messages: Array<String>, opts: Array<String>, callback: TokenCallback): String?
    private external fun nativeCancel()
    private external fun nativeStats(): String
    private external fun nativeCountTokens(text: String): Int

    private interface TokenCallback {
        fun onToken(token: String)
        fun onProgress(stage: String, completed: Int, total: Int)
    }

    fun load(path: String, params: LoadParams = LoadParams()): EngineResult = guarded {
        if (loadError != null) return EngineResult(false, loadError)
        Log.i("MrGreenLLM", "load requested file=${path.substringAfterLast('/')} ctx=${params.nCtx} batch=${params.nBatch} ubatch=${params.nUbatch} threads=${params.threads}")
        im.mrgreen.extensions.EnginePatchRuntime.beginOperation("load")
        nativeLoad(path, params.nCtx, params.nBatch, params.nUbatch, params.threads).also {
            im.mrgreen.extensions.EnginePatchRuntime.finishOperation()
            if (it != null) Log.e("MrGreenLLM", "load failed: $it")
        }
    }

    fun unload() { try { nativeUnload() } catch (t: Throwable) { Log.w("MrGreenLLM", "unload failed", t) } }

    fun generate(
        messages: List<ChatTurn>,
        opts: GenerationOptions = GenerationOptions(),
        onProgress: (String, Int, Int) -> Unit = { _, _, _ -> },
        onToken: (String) -> Unit
    ): EngineResult = guarded {
        val m = messages.flatMap { listOf(it.role, it.content) }.toTypedArray()
        val o = arrayOf(opts.maxTokens.toString(), opts.temperature.toString(), opts.topK.toString(), opts.topP.toString(), opts.minP.toString(), opts.presencePenalty.toString(), opts.grammar ?: "", opts.stop.joinToString("\u0001"))
        Log.i("MrGreenLLM", "generate requested turns=${messages.size} maxTokens=${opts.maxTokens} temp=${opts.temperature} topK=${opts.topK} topP=${opts.topP}")
        im.mrgreen.extensions.EnginePatchRuntime.beginOperation("generate")
        nativeGenerate(m, o, object : TokenCallback {
            override fun onToken(token: String) = onToken.invoke(token)
            override fun onProgress(stage: String, completed: Int, total: Int) = onProgress.invoke(stage, completed, total)
        }).also {
            im.mrgreen.extensions.EnginePatchRuntime.finishOperation(generated = it == null)
            if (it != null) Log.e("MrGreenLLM", "generate failed: $it")
        }
    }

    fun cancel() { try { nativeCancel() } catch (t: Throwable) { Log.w("MrGreenLLM", "cancel failed", t) } }

    fun stats(): EngineStats = try {
        val p = nativeStats().split('\t')
        EngineStats(
            loadedModel = p.getOrNull(0)?.ifEmpty { null },
            nCtx = p.getOrNull(1)?.toIntOrNull() ?: 0,
            promptTokPerSec = p.getOrNull(2)?.toDoubleOrNull() ?: 0.0,
            genTokPerSec = p.getOrNull(3)?.toDoubleOrNull() ?: 0.0,
            kvBytes = p.getOrNull(4)?.toLongOrNull() ?: 0L,
            nativeHeapBytes = p.getOrNull(5)?.toLongOrNull() ?: 0L,
            architecture = p.getOrNull(6)?.ifEmpty { null },
            modelName = p.getOrNull(7)?.ifEmpty { null },
            templateKind = p.getOrNull(8)?.ifEmpty { null },
            tokenizerType = p.getOrNull(9)?.ifEmpty { null },
            vocabSize = p.getOrNull(10)?.toIntOrNull() ?: 0
        ).also { Log.i("MrGreenLLM", "stats arch=${it.architecture} template=${it.templateKind} tokenizer=${it.tokenizerType} vocab=${it.vocabSize} ctx=${it.nCtx}") }
    } catch (t: Throwable) {
        Log.e("MrGreenLLM", "stats failed", t)
        EngineStats(null, 0, 0.0, 0.0, 0L, 0L)
    }

    fun countTokens(text: String): Int = try { nativeCountTokens(text) } catch (_: Throwable) { -1 }

    private inline fun guarded(call: () -> String?): EngineResult = try {
        val e = call()
        if (e == null) EngineResult(true) else EngineResult(false, e)
    } catch (t: Throwable) {
        Log.e("MrGreenLLM", "JNI call failed", t)
        EngineResult(false, t.message ?: t.javaClass.simpleName)
    }
}
