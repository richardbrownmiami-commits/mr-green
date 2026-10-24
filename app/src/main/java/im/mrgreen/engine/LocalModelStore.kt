package im.mrgreen.engine

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import im.mrgreen.core.AgentController
import im.mrgreen.core.RuntimePolicy
import java.io.File

/** Local GGUF file storage for host-provided document pickers; never overwrites an existing file. */
class LocalModelStore(context: Context) {
    private val app = context.applicationContext
    val directory: File get() = File(app.filesDir, "models").apply { mkdirs() }

    fun listModels(): List<File> = directory.listFiles().orEmpty()
        .filter { it.isFile && RuntimePolicy.validModel(it.name, it.length()) }
        .sortedBy { it.name.lowercase() }

    /** Copies one host-selected content URI using bounded reads and same-directory atomic rename. */
    @Throws(IllegalArgumentException::class, IllegalStateException::class)
    fun importModel(uri: Uri): File {
        val rawName = app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null }
            ?: uri.lastPathSegment
            ?: throw IllegalArgumentException("The selected file has no name.")
        val name = rawName.substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
        require(name.endsWith(".gguf", ignoreCase = true)) { "Choose a .gguf model file." }

        val declaredBytes = app.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
            ?.use { if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else -1L } ?: -1L
        require(declaredBytes <= RuntimePolicy.MAX_MODEL_BYTES) { "Model exceeds the supported import limit." }

        val dir = directory
        val target = File(dir, name)
        val loaded = AgentController.state.value.stats.loadedModel?.let { runCatching { File(it).canonicalFile }.getOrNull() }
        require(loaded == null || runCatching { target.canonicalFile }.getOrNull() != loaded) {
            "Cannot replace the currently loaded model."
        }
        require(!target.exists()) { "A model with that name already exists; it was not overwritten." }

        val temp = File(dir, ".$name.${System.nanoTime()}.partial")
        try {
            var copied = 0L
            val input = app.contentResolver.openInputStream(uri)
                ?: throw IllegalArgumentException("Could not open the selected model file.")
            input.use { source ->
                temp.outputStream().buffered().use { sink ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = source.read(buffer)
                        if (count < 0) break
                        copied += count
                        require(copied <= RuntimePolicy.MAX_MODEL_BYTES) { "Model exceeds the supported import limit." }
                        sink.write(buffer, 0, count)
                    }
                }
            }
            require(RuntimePolicy.validModel(name, copied)) { "The selected file is empty or not a valid-size GGUF model." }
            check(!target.exists()) { "A model with that name already exists; it was not overwritten." }
            check(temp.renameTo(target)) { "Could not atomically complete the model import." }
            return target
        } finally {
            if (temp.exists()) temp.delete()
        }
    }
}
