package im.mrgreen.ui

import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.lifecycle.lifecycleScope
import im.mrgreen.GreenApp
import im.mrgreen.rive.RiveBrain
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Safe, text-only RiveScript rule management. All engine operations run on IO. */
class RiveActivity : AppCompatActivity() {
    private lateinit var content: LinearLayout
    private lateinit var statusView: TextView
    private lateinit var importButton: Button
    private lateinit var editButton: Button
    private lateinit var exportButton: Button
    private lateinit var reviewButton: Button
    private lateinit var clearButton: Button
    private lateinit var reloadButton: Button
    private lateinit var learningSwitch: SwitchCompat
    private var enablingConfirmation = false
    private var working = false
    private val prefs by lazy { getSharedPreferences("rive", MODE_PRIVATE) }
    private val brain by lazy {
        (application as? GreenApp)?.rive ?: RiveBrain(File(filesDir, "rive"))
    }
    private val importPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(::importDocument) }
    private val exportPicker = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri -> uri?.let(::exportDocument) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = 0xFF0D0A14.toInt()
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(0xFF0D0A14.toInt()) }
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(18), dp(20), dp(24)) }
        val scroll = ScrollView(this).apply { addView(content) }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f)); setContentView(root)
        text("Rive rules & learning", 25f, true)
        text("Safe text-only RiveScript subset: one literal lowercase trigger and one literal reply per rule. No wildcards, tags, or scripts. Replies may use \\n for a newline.", 14f)
        text("Rules reload immediately between replies; a reply already streaming is unchanged.", 14f)
        statusView = text("Loading rule status…", 14f)
        importButton = button("Import .rive rules") { chooseImport() }
        editButton = button("Edit imported rule text") { editImported() }
        exportButton = button("Export rules…") { exportPicker.launch("mr-green-rules.rive") }
        text("Auto-learning uses completed local GGUF answers only. Rules are checked first; when enabled, misses are forced local (not cloud). Unverified answers may be wrong, stale, or context-dependent; this does not train the model. Exact normalized questions only. Learned answers never execute tools.", 14f)
        learningSwitch = SwitchCompat(this).apply {
            text = "Auto-learn from local answers"; setTextColor(0xFFB98AFF.toInt()); minHeight = dp(52)
            isChecked = prefs.getBoolean("auto_learn", false)
        }
        installLearningListener()
        content.addView(learningSwitch)
        reviewButton = button("Review learned rules") { reviewLearned() }
        clearButton = button("Clear all learned rules") { confirmClearLearned() }
        reloadButton = button("Reload saved rules") { runIo("Reloading saved rules…") { brain.reload(); "Saved rules reloaded." } }
        text("Imported rules and learned rules are separate. Removing a learned item affects future replies after reload.", 14f)
        refreshStatus()
    }

    private fun chooseImport() { importPicker.launch(arrayOf("*/*")) }
    private fun importDocument(uri: Uri) = runIo("Importing rules…") {
        val name = displayName(uri) ?: throw IllegalArgumentException("Could not read the selected filename")
        if (!name.endsWith(".rive", true)) throw IllegalArgumentException("Choose a file whose name ends in .rive")
        val prepared = contentResolver.openInputStream(uri)?.use { brain.prepare(it) }
            ?: throw IllegalArgumentException("Could not open selected document")
        withContext(Dispatchers.Main) {
            AlertDialog.Builder(this@RiveActivity).setTitle("Replace imported rules?")
                .setMessage("Validated ${prepared.count} rules from $name. Replacing the imported rule set takes effect between replies. Learned rules are kept except when they conflict with imported rules; the imported rule wins.")
                .setNegativeButton("Cancel", null).setPositiveButton("Replace") { _, _ -> runIo("Replacing imported rules…") { brain.replace(prepared); brain.reload(); "Imported ${prepared.count} rules." } }.show()
        }
    }

    private fun editImported() = runIo("Loading imported text…") {
        val original = brain.importedText()
        withContext(Dispatchers.Main) { showEditor(original) }
    }
    private fun showEditor(initial: String) {
        val editor = EditText(this).apply { setText(initial); minLines = 8; maxLines = 18; gravity = android.view.Gravity.TOP or android.view.Gravity.START; setTextIsSelectable(true) }
        AlertDialog.Builder(this).setTitle("Edit imported rules").setMessage("Text only: one literal lowercase trigger and one literal reply per rule; no wildcards, tags, or scripts.\\n is a newline escape in replies.")
            .setView(editor).setNegativeButton("Cancel", null).setPositiveButton("Validate", null).show().also { dialog ->
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    val candidate = editor.text.toString()
                    runIo("Validating edited rules…") {
                        val prepared = brain.prepare(candidate.byteInputStream())
                        withContext(Dispatchers.Main) {
                            AlertDialog.Builder(this@RiveActivity).setTitle("Confirm rule replacement")
                                .setMessage("Validation passed: ${prepared.count} rules. Replace imported rules? Learned rules are kept except when they conflict with imported rules; the imported rule wins.")
                                .setNegativeButton("Cancel", null).setPositiveButton("Replace") { _, _ -> runIo("Saving edited rules…") { brain.replace(prepared); brain.reload(); "Saved ${prepared.count} rules." } }.show()
                        }
                    }
                    dialog.dismiss()
                }
            }
    }

    private fun exportDocument(uri: Uri) = runIo("Exporting rules…") {
        val data = brain.exportText()
        contentResolver.openOutputStream(uri, "wt")?.bufferedWriter(Charsets.UTF_8)?.use { it.write(data) }
            ?: throw IllegalArgumentException("Could not create destination document")
        "Rules exported."
    }

    private fun reviewLearned() = runIo("Loading learned rules…") {
        val items = brain.learnedRules()
        withContext(Dispatchers.Main) {
            if (items.isEmpty()) { toast("No learned rules yet."); return@withContext }
            val list = LinearLayout(this@RiveActivity).apply { orientation = LinearLayout.VERTICAL }
            items.forEach { item ->
                val row = LinearLayout(this@RiveActivity).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8), dp(8), dp(8), dp(8)) }
                row.addView(TextView(this@RiveActivity).apply { text = "Q: ${item.trigger}\nA: ${item.answer}"; setTextColor(0xFFF6F0FF.toInt()) })
                row.addView(Button(this@RiveActivity).apply { text = "Delete this learned rule"; setOnClickListener {
                    AlertDialog.Builder(this@RiveActivity).setTitle("Delete learned rule?").setMessage("Remove this exact learned question and answer?")
                        .setNegativeButton("Cancel", null).setPositiveButton("Delete") { _, _ -> runIo("Deleting learned rule…") { brain.forget(item.trigger); brain.reload(); "Learned rule deleted." } }.show()
                } })
                list.addView(row)
            }
            AlertDialog.Builder(this@RiveActivity).setTitle("Learned rules (${items.size})").setView(ScrollView(this@RiveActivity).apply { addView(list) }).setPositiveButton("Done", null).show()
        }
    }
    private fun confirmClearLearned() {
        AlertDialog.Builder(this).setTitle("Clear all learned rules?").setMessage("This permanently removes every learned question and answer. Imported rules are not affected.")
            .setNegativeButton("Cancel", null).setPositiveButton("Clear") { _, _ -> runIo("Clearing learned rules…") { brain.clearLearned(); brain.reload(); "All learned rules cleared." } }.show()
    }
    private fun setLearning(enabled: Boolean) {
        prefs.edit().putBoolean("auto_learn", enabled).apply()
        learningSwitch.setOnCheckedChangeListener(null)
        learningSwitch.isChecked = enabled
        installLearningListener()
    }
    private fun installLearningListener() {
        learningSwitch.setOnCheckedChangeListener { _, checked ->
            if (working) return@setOnCheckedChangeListener
            if (checked) {
                if (enablingConfirmation) return@setOnCheckedChangeListener
                enablingConfirmation = true
                learningSwitch.setOnCheckedChangeListener(null)
                learningSwitch.isChecked = false
                installLearningListener()
                AlertDialog.Builder(this).setTitle("Enable auto-learning?")
                    .setMessage("Only completed local GGUF answers are learned. Answers are unverified and may be wrong, stale, or context-dependent. Misses stay local rather than going to cloud; learned answers are exact-question matches and never execute tools. This does not train the model.")
                    .setNegativeButton("Cancel", null).setPositiveButton("Enable") { _, _ -> setLearning(true) }
                    .setOnDismissListener { enablingConfirmation = false }.show()
            } else {
                prefs.edit().putBoolean("auto_learn", false).apply()
            }
        }
    }

    private fun refreshStatus() = runIo("Refreshing rule status…") {
        val result = brain.status()
        buildString { append("Imported rules: ${result.imported}\nLearned rules: ${result.learned}"); result.warning?.let { append("\nWarning: $it") } }
    }
    private fun runIo(label: String, operation: suspend () -> Any?) {
        if (working) return
        working = true; updateEnabled(); statusView.text = label
        lifecycleScope.launch {
            val outcome = withContext(Dispatchers.IO) { runCatching { operation() } }
            working = false; updateEnabled()
            val error = outcome.exceptionOrNull()
            statusView.text = if (error != null) "Operation failed: ${error.localizedMessage ?: error.javaClass.simpleName}"
                else (outcome.getOrNull() as? String).orEmpty().ifEmpty { label }
            if (error != null) toast(statusView.text.toString())
            runCatching { withContext(Dispatchers.IO) { brain.status() } }.onSuccess { s -> statusView.text = "${statusView.text}\nImported: ${s.imported} · Learned: ${s.learned}" + (s.warning?.let { "\nWarning: $it" } ?: "") }
        }
    }
    private fun updateEnabled() { listOf(importButton, editButton, exportButton, reviewButton, clearButton, reloadButton).forEach { it.isEnabled = !working }; learningSwitch.isEnabled = !working }
    private fun displayName(uri: Uri): String? = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }
    private fun text(value: String, size: Float, bold: Boolean = false): TextView = TextView(this).apply { text = value; textSize = size; setTextColor(0xFFB8ABC9.toInt()); setPadding(0, dp(8), 0, dp(8)); if (bold) typeface = android.graphics.Typeface.DEFAULT_BOLD; content.addView(this) }
    private fun button(label: String, action: () -> Unit): Button = Button(this).apply { text = label; setOnClickListener { action() }; content.addView(this, LinearLayout.LayoutParams(-1, dp(56))) }
    private fun toast(s: String) { Toast.makeText(this, s, Toast.LENGTH_LONG).show() }
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
}
