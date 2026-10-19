package im.mrgreen.identity

import android.os.Bundle
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import im.mrgreen.GreenApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Plain JSON editor with SAF import/export; store validation is authoritative. */
class IdentityActivity : AppCompatActivity() {
    private lateinit var store: IdentityStore
    private lateinit var editor: EditText
    private val export = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) lifecycleScope.launch {
            runCatching { withContext(Dispatchers.IO) { val json = store.exportJson(); contentResolver.openOutputStream(uri)!!.use { it.write(json.toByteArray(Charsets.UTF_8)) } } }
                .onFailure { toast("Export failed: ${it.message}") }
        }
    }
    private val import = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { contentResolver.openInputStream(uri)!!.use { readBounded(it).toString(Charsets.UTF_8) }.let(store::importJson) } }
            result.onSuccess { show(it); toast("Identity imported") }.onFailure { toast("Import rejected: ${it.message}") }
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); store = (application as? GreenApp)?.identity ?: IdentityStore(this); buildUi() }
    private fun buildUi() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(20, 18, 20, 18) }
        root.addView(TextView(this).apply { text = "Identity · local JSON data only"; textSize = 22f })
        root.addView(TextView(this).apply { text = "Persona, exact-match stable answers, help labels and supplemental notes. Stable Q&A keys: Who are you?; Are you alive?; What are your eyes and ears?; What can you help with?; How do I save a note?; How do I recall memory?; Do you use neural memory vectors?; Can you train your model?; Do you have cloud Doctor?; How do alarms work?; What can you do with time and math?; What permissions do you have? Notes cannot grant capabilities or override safety." })
        editor = EditText(this).apply { gravity = android.view.Gravity.TOP; minLines = 12; inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS }
        root.addView(editor, LinearLayout.LayoutParams(-1, (360 * resources.displayMetrics.density).toInt()))
        lifecycleScope.launch { runCatching { withContext(Dispatchers.IO) { store.exportJson() } }.onSuccess { editor.setText(it) }.onFailure { toast("Could not load identity: ${it.message}") } }
        fun button(label: String, action: () -> Unit) { root.addView(Button(this).apply { text = label; setOnClickListener { action() } }) }
        button("Validate and save") { val json = editor.text.toString(); lifecycleScope.launch { val result = withContext(Dispatchers.IO) { runCatching { store.importJson(json) } }; result.onSuccess { show(it); toast("Saved") }.onFailure { toast("Not saved: ${it.message}") } } }
        button("Import JSON") { import.launch(arrayOf("application/json", "text/*")) }
        button("Export JSON") { export.launch("mr-green-identity.json") }
        button("Reset defaults") { lifecycleScope.launch { val result = withContext(Dispatchers.IO) { runCatching { store.reset() } }; result.onSuccess { show(it); toast("Defaults restored") }.onFailure { toast("Reset failed: ${it.message}") } } }
        setContentView(ScrollView(this).apply { isFillViewport = true; addView(root) })
    }
    private fun show(p: IdentityProfile) { editor.setText(p.toJson()) }
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
    private fun readBounded(input: java.io.InputStream): ByteArray {
        val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(1024)
        while (true) { val n = input.read(buffer, 0, minOf(buffer.size, IdentityStore.MAX_BYTES + 1 - out.size())); if (n < 0) break; out.write(buffer, 0, n); require(out.size() <= IdentityStore.MAX_BYTES) { "Profile too large" } }
        return out.toByteArray()
    }
}
