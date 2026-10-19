package im.mrgreen.ui

import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import im.mrgreen.GreenApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ConversationMemoryActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        val app=application as GreenApp; val memory=app.conversationMemory
        val root=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setPadding(24,24,24,24) }
        root.addView(TextView(this).apply { text="Local conversation vector memory";textSize=23f })
        root.addView(TextView(this).apply { text="The model receives your current question plus at most two short historical snippets, never the full transcript. Follow-ups can use the latest exchange even without shared keywords. Longer notes are shortened to fit. Recall-dependent questions use the local GGUF model, even with cloud chat enabled; load a local model first. No recalled history is sent to cloud.\n\nChat is saved in the private database and indexed across restarts. This lightweight word/character index is not a neural embedding model: older paraphrases can still be missed. Native KV caching reuses identical prompt prefixes, not permanent conversation knowledge. Faster output or accurate model answers are not guaranteed." })
        root.addView(Switch(this).apply { text="Use relevant local conversation memory";isChecked=memory.enabled;setOnCheckedChangeListener { _,v->memory.enabled=v } })
        val output=TextView(this); root.addView(output)
        fun refresh() { lifecycleScope.launch { try { val s=withContext(Dispatchers.IO){memory.status()};output.text="Memory enabled: ${s.enabled}. Indexed messages: ${s.indexedMessages}. Current question + at most two snippets; no full-history replay. Chat status and MEMORY diagnostics show whether context actually fit." } catch(e:Exception) { output.text="Index unavailable: ${e.javaClass.simpleName}" } } }
        root.addView(Button(this).apply { text="Index up to 128 missing messages";setOnClickListener { lifecycleScope.launch { try { withContext(Dispatchers.IO){memory.sync(128)};refresh() } catch (_:Exception) { output.text="Indexing unavailable; chat retained. Try again later." } } } })
        root.addView(Button(this).apply { text="Clear vectors and query cache (keep chat)";setOnClickListener {
            android.app.AlertDialog.Builder(this@ConversationMemoryActivity).setTitle("Clear local index?").setMessage("Chat messages remain. Turn memory off if you do not want the index rebuilt on later questions.")
                .setNegativeButton("Cancel",null).setPositiveButton("Clear") { _,_->lifecycleScope.launch { withContext(Dispatchers.IO){memory.clear()};refresh() } }.show()
        } })
        val doctor=app.getSharedPreferences("doctor_settings",0)
        root.addView(Switch(this).apply { text="Allow idle, battery-gated maintenance";isChecked=doctor.getBoolean("automatic",true);setOnCheckedChangeListener{_,v->doctor.edit().putBoolean("automatic",v).apply()} })
        setContentView(ScrollView(this).apply {addView(root)});refresh()
    }
}
