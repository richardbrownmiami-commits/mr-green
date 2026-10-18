package im.mrgreen.ui

import android.content.Intent
import android.os.Bundle
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import im.mrgreen.GreenApp
import im.mrgreen.core.AgentController
import im.mrgreen.plugins.PendingPack
import im.mrgreen.plugins.PluginSystem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Phase 7 only: no action tools, companion packages or engine patch loading. */
class PluginsActivity : AppCompatActivity() {
    private lateinit var body: LinearLayout
    private lateinit var status: TextView
    private lateinit var system: PluginSystem
    private var installedStart = 0
    private var working = false
    private var pendingReview: PendingPack? = null
    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) work {
            val candidate = contentResolver.openInputStream(uri)?.use { system.manager.prepare(it) } ?: error("E_IMPORT")
            withContext(Dispatchers.Main) { review(candidate) }
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        system = (application as? GreenApp)?.plugins ?: PluginSystem(this)
        render()
    }
    private fun render() {
        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(28, 24, 28, 32); setBackgroundColor(0xFF0D0A14.toInt()) }
        setContentView(ScrollView(this).apply { addView(body) })
        text("Plugins · Phase 7", 25f)
        text("Local flows and QuickJS only. Android actions, Web/MCP, learning, Doctor and layers 3/4 are not installed. Pack approval never grants Android permissions.")
        status = text("Reading pack state…")
        button("Back") { finish() }
        button("Import .redpack from phone / SD") { if (!working) picker.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) }
        button("Build a local flow") { builder(false) }
        button("Build a local script") { builder(true) }
        button("Review owner signing key") { ownerKey() }
        button("Enable safe mode / stop plugins") { work { system.manager.enterSafeMode(); refreshState() } }
        button("Leave safe mode") {
            AlertDialog.Builder(this).setTitle("Leave safe mode?").setMessage("Disabled packs remain disabled. Review or roll back a failing pack before trying a new version.")
                .setNegativeButton("Cancel", null).setPositiveButton("Leave safe mode") { _, _ -> work { system.manager.exitSafeMode(); refreshState() } }.show()
        }
        installedStart = body.childCount
        work { refreshState() }
    }
    private suspend fun refreshState() {
        val packs = system.manager.listInstalled()
        val bytes = system.manager.usageBytes()
        val safe = system.manager.isSafeMode()
        withContext(Dispatchers.Main) {
            status.text = "${if (safe) "SAFE MODE · plugins stopped" else "Plugins available"}\nStorage: ${bytes / 1024} KiB / 200 MiB (includes rollback, staging and plugin memory)." +
                if (bytes >= 160L * 1024 * 1024) "\nStorage warning: at least 80% full." else ""
            while (body.childCount > installedStart) body.removeViewAt(installedStart)
            if (packs.isEmpty()) text("No installed packs. Build a demo or import a signed pack.")
            packs.forEach { pack ->
                text("${pack.name} · ${pack.version}\nID: ${pack.id}\nPermissions: ${pack.permissions.joinToString().ifEmpty { "none" }}\nTools: ${pack.tools.joinToString().ifEmpty { "none" }}")
                button("Run ${pack.id}") { chooseEntry(pack.id) }
                button("Rollback ${pack.id}") {
                    AlertDialog.Builder(this@PluginsActivity).setTitle("Restore previous approved version?").setMessage(pack.id)
                        .setNegativeButton("Cancel", null).setPositiveButton("Rollback") { _, _ -> work { system.manager.rollback(pack.id); refreshState() } }.show()
                }
                button("Delete ${pack.id}") {
                    AlertDialog.Builder(this@PluginsActivity).setTitle("Delete pack and its retained versions?").setMessage("This deletes only this plugin's files and memory, not your chats or models.")
                        .setNegativeButton("Cancel", null).setPositiveButton("Delete") { _, _ -> work { system.manager.remove(pack.id); refreshState() } }.show()
                }
            }
        }
    }
    private fun chooseEntry(id: String) = work {
        val entries = system.manager.installedEntries(id)
        withContext(Dispatchers.Main) {
            AlertDialog.Builder(this@PluginsActivity).setTitle("Run approved entry locally")
                .setItems(entries.toTypedArray()) { _, index -> work {
                    system.run(id, entries[index]) { prompt -> askDuringFlow(prompt) }
                    val output = system.lastOutput
                    withContext(Dispatchers.Main) { AlertDialog.Builder(this@PluginsActivity).setTitle("Plugin result · local only").setMessage(output.ifBlank { "Completed" }).setPositiveButton("Close", null).show() }
                    refreshState()
                } }.setNegativeButton("Cancel", null).show()
        }
    }
    private fun askDuringFlow(prompt: String): Boolean {
        val latch = CountDownLatch(1); val answer = AtomicBoolean(false)
        runOnUiThread {
            if (isFinishing || isDestroyed) { latch.countDown(); return@runOnUiThread }
            AlertDialog.Builder(this).setTitle("Plugin asks for approval").setMessage(prompt)
                .setNegativeButton("Reject") { _, _ -> latch.countDown() }
                .setPositiveButton("Approve this step") { _, _ -> answer.set(true); latch.countDown() }
                .setOnCancelListener { latch.countDown() }.show()
        }
        return latch.await(60, TimeUnit.SECONDS) && answer.get()
    }
    private fun review(candidate: PendingPack) {
        pendingReview?.let { system.manager.discard(it) }; pendingReview = candidate
        val m = candidate.manifest
        val message = "${m.getString("name")} · ${m.getString("version")}\nID: ${m.getString("id")}\nLayers: ${m.getJSONArray("layers")}\nPermissions: ${m.getJSONArray("permissions")}\nTools: ${m.getJSONArray("tools")}\nExpanded size: ${candidate.payload.values.sumOf { it.size.toLong() }} bytes\n\nSignature, hashes, schema and dry-run passed on this phone. This is not A25/A26 acceptance. Review the source in the builder or with the pack author. Nothing is installed until you approve."
        AlertDialog.Builder(this).setTitle("Review pack before install").setMessage(message)
            .setNegativeButton("Reject") { _, _ -> system.manager.discard(candidate); pendingReview = null }
            .setPositiveButton("Approve and install") { _, _ -> pendingReview = null; work { system.manager.approveInstall(candidate); refreshState() } }
            .setOnCancelListener { system.manager.discard(candidate); pendingReview = null }.show()
    }
    private fun builder(script: Boolean) {
        if (working) return
        val form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 8, 24, 8) }
        fun field(hint: String, value: String = "") = EditText(this).apply { this.hint = hint; setText(value); form.addView(this) }
        val id = field("Pack ID", if (script) "my.script" else "my.flow")
        val version = field("Version", "1.0.0")
        val request = field("Optional: describe a draft for the existing AI Gateway")
        val source = field(if (script) "JavaScript source" else "Flow JSON", if (script) "notify('Hello from a local script');\n'Completed';" else """{"steps":[{"op":"set","name":"message","value":"Hello from a local flow"},{"op":"verify","value":"${'$'}message","equals":"Hello from a local flow"}]}""")
        source.minLines = 6; source.maxLines = 12; source.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
        val expected = field("Expected dry-run result (JSON; flow objects match these fields)", if (script) "\"Completed\"" else """{"ok":true}""")
        val ai = MaterialButton(this).apply { text = "Ask AI for a draft (never installs)"; form.addView(this) }
        ai.setOnClickListener {
            ai.isEnabled = false
            val accepted = AgentController.draftPlugin(this, request.text.toString(), script) { draft ->
                ai.isEnabled = true
                if (draft == null) toast("Draft unavailable. You can edit the local template without AI.")
                else source.setText(draft.trim().removePrefix("```javascript").removePrefix("```js").removePrefix("```json").removePrefix("```").removeSuffix("```").trim())
            }
            if (!accepted) { ai.isEnabled = true; toast("AI is busy or the request is empty. Local editing remains available.") }
        }
        textInto(form, "All draft code is untrusted. Build signs locally, then validates and dry-runs it before a separate install approval. HTTP and runtime AI calls are unavailable; use only plugin.echo / time.now and declared local APIs.")
        AlertDialog.Builder(this).setTitle(if (script) "Script builder" else "Flow builder").setView(ScrollView(this).apply { addView(form) })
            .setNegativeButton("Cancel", null).setPositiveButton("Validate draft") { _, _ ->
                val packId = id.text.toString(); val packVersion = version.text.toString(); val code = source.text.toString()
                val expectedJson = expected.text.toString()
                work {
                    check(code.toByteArray().size <= 65536)
                    val entry = if (script) "scripts/main.js" else "flows/main.json"
                    val test = org.json.JSONObject().put("cases", org.json.JSONArray().put(org.json.JSONObject().put("entry", entry).put("expected", org.json.JSONArray("[$expectedJson]").get(0))))
                    val archive = system.builder.build(packId, packId, packVersion, mapOf(entry to code.toByteArray(), "tests/main.json" to test.toString().toByteArray()),
                        tools = listOf("plugin.echo", "time.now"), permissions = listOf("plugin.output", "time.read", "plugin.memory", "plugin.log"), layer = if (script) 2 else 1)
                    val candidate = system.manager.prepare(ByteArrayInputStream(archive))
                    withContext(Dispatchers.Main) { review(candidate) }
                }
            }.show()
    }
    private fun ownerKey() {
        if (working) return
        val input = EditText(this).apply { hint = "Base64 X.509 RSA or EC public key only (never a private key)"; minLines = 3 }
        AlertDialog.Builder(this).setTitle("Import owner's public signing key").setView(input).setNegativeButton("Cancel", null)
            .setPositiveButton("Review fingerprint") { _, _ ->
                val encoded = input.text.toString().filterNot { it.isWhitespace() }
                val key = runCatching { system.trust.decodePublicKey("RSA", encoded) }.recoverCatching { system.trust.decodePublicKey("EC", encoded) }.getOrNull()
                if (key == null) toast("Invalid public key") else AlertDialog.Builder(this).setTitle("Trust this owner key?")
                    .setMessage("Compare this SHA-256 fingerprint with the owner through a trusted channel. Replacing the owner pin may make older owner packs unavailable.\n\n${system.trust.fingerprint(key)}")
                    .setNegativeButton("Cancel", null).setPositiveButton("Trust public key") { _, _ -> work { system.trust.pinOwnerKey(key) } }.show()
            }.show()
    }
    private fun work(action: suspend () -> Unit) {
        if (working) { toast("A plugin operation is already running"); return }
        working = true
        lifecycleScope.launch {
            try { withContext(Dispatchers.IO) { action() } }
            catch (_: Exception) { toast("Plugin operation rejected or stopped. No private content was logged. Check approval, signing key, limits and safe mode.") }
            finally { working = false }
        }
    }
    private fun text(value: String, size: Float = 14f): TextView = textInto(body, value, size)
    private fun textInto(parent: LinearLayout, value: String, size: Float = 14f): TextView = TextView(this).apply { text = value; textSize = size; setTextColor(0xFFF6F0FF.toInt()); setPadding(0, 10, 0, 12); parent.addView(this) }
    private fun button(label: String, action: () -> Unit) { body.addView(MaterialButton(this).apply { text = label; minHeight = 52; setOnClickListener { action() } }) }
    private fun toast(value: String) = Toast.makeText(this, value, Toast.LENGTH_LONG).show()
    override fun onDestroy() { pendingReview?.let { system.manager.discard(it) }; super.onDestroy() }
}
