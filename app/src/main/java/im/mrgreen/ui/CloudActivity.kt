package im.mrgreen.ui

import android.content.Context
import android.os.Bundle
import android.view.WindowManager
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import im.mrgreen.R
import im.mrgreen.core.AgentController
import im.mrgreen.GreenApp
import im.mrgreen.gateway.GatewayClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URI

/** Settings surface for explicitly opted-in cloud inference. Secret input is never persisted by this screen. */
class CloudActivity : AppCompatActivity() {
    private val bg = 0xFF0D0A14.toInt(); private val surface = 0xFF1C1529.toInt(); private val violet = 0xFFB98AFF.toInt()
    private lateinit var content: LinearLayout
    private val client: GatewayClient get() = AgentController.gateway
    private lateinit var fast: Spinner
    private lateinit var smart: Spinner
    private lateinit var status: TextView
    private lateinit var quotaView: TextView
    private lateinit var logView: TextView
    private var secretInput: EditText? = null
    private var discovering = false
    private val categories = listOf("chat", "context", "facts", "notes", "screen", "rag", "models", "technical", "planner", "doctor", "plugins", "packs", "summary")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        window.statusBarColor = bg; window.navigationBarColor = bg
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(18), dp(20), dp(24)); setBackgroundColor(bg) }
        val scroll = ScrollView(this).apply { addView(content) }; setContentView(scroll)
        label(getString(R.string.cloud_title), 25f, true); label(getString(R.string.cloud_intro), 14f)
        status = label(getString(R.string.cloud_idle), 14f)
        val enabled = androidx.appcompat.widget.SwitchCompat(this).apply { text = getString(R.string.cloud_enable); setTextColor(violet); minHeight = dp(52); isChecked = client.settings.enabled }
        content.addView(enabled)
        enabled.setOnCheckedChangeListener { _, checked -> client.settings.setEnabled(checked); if (!checked) AgentController.cloudDisabled(); status.text = getString(if (checked) R.string.cloud_enabled_status else R.string.cloud_disabled_status) }
        val base = field(getString(R.string.cloud_base_url), client.settings.baseUrl).apply { inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI; setAutofillHints() }
        button(getString(R.string.cloud_save_url)) {
            val raw = base.text?.toString()?.trim().orEmpty()
            try { val uri = URI(raw); require(uri.scheme.equals("https", true) && uri.host != null && uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null); client.settings.setBaseUrl(raw); status.text = getString(R.string.cloud_url_saved) }
            catch (_: Exception) { status.text = getString(R.string.cloud_url_invalid) }
        }
        val secret = field(getString(R.string.cloud_key_hint), "").apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            setAutofillHints(); importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            isSaveEnabled = false; setSelectAllOnFocus(false)
        }
        secretInput = secret
        button(getString(R.string.cloud_save_key)) {
            val value = secret.text?.toString().orEmpty()
            if (value.isBlank()) status.text = getString(R.string.cloud_key_blank) else {
                try { client.settings.setApiKey(value); secret.text?.clear(); secret.clearFocus(); status.text = getString(R.string.cloud_key_saved) }
                catch (_: Exception) { secret.text?.clear(); status.text = getString(R.string.cloud_operation_error) }
            }
        }
        button(getString(R.string.cloud_remove_key)) { client.cancel(); client.settings.clearApiKey(); client.settings.setEnabled(false); enabled.isChecked = false; AgentController.cloudDisabled(); secret.text?.clear(); status.text = getString(R.string.cloud_key_removed) }
        button(getString(R.string.cloud_discover)) { discover() }
        label(getString(R.string.cloud_fast), 15f, true); fast = spinner(listOf(client.settings.fastModel).filter { it.isNotBlank() }); label(getString(R.string.cloud_smart), 15f, true); smart = spinner(listOf(client.settings.smartModel).filter { it.isNotBlank() })
        button(getString(R.string.cloud_save_models)) {
            val f = fast.selectedItem as? String
            val m = smart.selectedItem as? String
            if (f == null || m == null) status.text = getString(R.string.cloud_no_models)
            else try {
                client.settings.setFastModel(f); client.settings.setSmartModel(m)
                status.text = getString(R.string.cloud_models_saved)
            } catch (_: Exception) { status.text = getString(R.string.cloud_operation_error) }
        }
        label(getString(R.string.cloud_categories), 17f, true)
        categories.forEach { category ->
            val row = androidx.appcompat.widget.SwitchCompat(this).apply {
                text = categoryLabel(category); setTextColor(violet); minHeight = dp(48)
                isChecked = category in client.settings.localOnlyCategories
            }
            row.setOnCheckedChangeListener { _, checked -> client.settings.setCategoryLocalOnly(category, checked) }; content.addView(row)
        }
        label(getString(R.string.cloud_quota), 17f, true)
        quotaView = label(getString(R.string.cloud_quota_unreported), 14f)
        label(getString(R.string.cloud_metadata_log), 17f, true)
        logView = label("", 12f)
        button(getString(R.string.cloud_refresh_log)) { refreshMetadata() }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) { refreshMetadata(); kotlinx.coroutines.delay(2000) }
            }
        }
    }
    override fun onStop() { secretInput?.text?.clear(); super.onStop() }
    private fun discover() {
        if (discovering) return
        if (!client.settings.enabled || !AgentController.hasValidatedNetwork()) {
            status.text = getString(R.string.cloud_discovery_unavailable); return
        }
        status.text = getString(R.string.cloud_loading_models)
        discovering = true
        lifecycleScope.launch {
            try {
                val found = withContext(Dispatchers.IO) { client.discoverModels() }
                if (found.isEmpty()) { status.text = getString(R.string.cloud_no_models); return@launch }
                val currentFast = client.settings.fastModel; val currentSmart = client.settings.smartModel
                listOf(fast, smart).forEach { s -> s.adapter = ArrayAdapter(this@CloudActivity, android.R.layout.simple_spinner_dropdown_item, found) }
                fast.setSelection(found.indexOf(currentFast).coerceAtLeast(0)); smart.setSelection(found.indexOf(currentSmart).coerceAtLeast(0))
                status.text = getString(R.string.cloud_models_found, found.size)
            } catch (_: Exception) { status.text = getString(R.string.cloud_models_error) }
            finally { discovering = false }
        }
    }
    private fun categoryLabel(key: String) = when (key) {
        "chat" -> getString(R.string.cloud_cat_chat); "context" -> getString(R.string.cloud_cat_context)
        "facts" -> getString(R.string.cloud_cat_facts); "notes" -> getString(R.string.cloud_cat_notes)
        "screen" -> getString(R.string.cloud_cat_screen); "rag" -> getString(R.string.cloud_cat_rag)
        "models" -> getString(R.string.cloud_cat_models); "technical" -> getString(R.string.cloud_cat_technical)
        "planner" -> getString(R.string.cloud_cat_planner); "doctor" -> getString(R.string.cloud_cat_doctor)
        "plugins" -> getString(R.string.cloud_cat_plugins); "packs" -> getString(R.string.cloud_cat_packs)
        else -> getString(R.string.cloud_cat_summary)
    }
    private fun refreshMetadata() {
        val models = listOf(client.settings.fastModel, client.settings.smartModel).filter { it.isNotBlank() }.distinct()
        quotaView.text = models.mapNotNull { client.quotaSnapshot(it) }.joinToString("\n") { q ->
            getString(R.string.cloud_quota_row, q.model,
                q.requestsRemaining?.toString() ?: "—", q.tokensRemaining?.toString() ?: "—",
                if (q.circuitOpen) getString(R.string.cloud_paused) else getString(R.string.cloud_ready),
                if (q.resetAtMillis > 0) java.text.DateFormat.getDateTimeInstance().format(java.util.Date(q.resetAtMillis)) else "—")
        }.ifBlank { getString(R.string.cloud_quota_unreported) }
        lifecycleScope.launch {
            val rows = withContext(Dispatchers.IO) { (application as? GreenApp)?.store?.cloudSends().orEmpty() }
            logView.text = if (rows.isEmpty()) getString(R.string.cloud_log_empty) else rows.joinToString("\n\n") {
                getString(R.string.cloud_log_row, it.provider, it.dataClass, categoryLabel(it.category), it.bytes) +
                    "\n" + java.text.DateFormat.getDateTimeInstance().format(java.util.Date(it.createdAt))
            }
        }
    }
    private fun field(hintText: String, value: String) = com.google.android.material.textfield.TextInputEditText(this).apply {
        hint = hintText; setText(value); setTextColor(0xFFF6F0FF.toInt()); setHintTextColor(0xFFB8ABC9.toInt()); setPadding(dp(14), dp(12), dp(14), dp(12)); background = rounded(surface, 14); minHeight = dp(52)
        isSaveEnabled = false
        content.addView(this, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
    }
    private fun spinner(items: List<String>) = Spinner(this).apply { adapter = ArrayAdapter(this@CloudActivity, android.R.layout.simple_spinner_dropdown_item, items); content.addView(this) }
    private fun button(textValue: String, action: () -> Unit) { content.addView(com.google.android.material.button.MaterialButton(this).apply { text = textValue; setOnClickListener { action() } }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(7) }) }
    private fun label(value: String, size: Float, bold: Boolean = false): TextView = TextView(this).apply { text = value; textSize = size; setTextColor(if (size >= 16) 0xFFF6F0FF.toInt() else 0xFFB8ABC9.toInt()); if (bold) typeface = android.graphics.Typeface.DEFAULT_BOLD; setPadding(0, dp(8), 0, dp(8)); content.addView(this) }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun rounded(color: Int, radius: Int) = android.graphics.drawable.GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat() }
}
