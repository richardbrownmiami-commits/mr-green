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
        root.addView(TextView(this).apply { text="The model receives your current question, not the full transcript. Up to two relevant earlier snippets may be added within the model's token budget. Native KV caching reuses identical prompt prefixes. All vector lookup and snippets stay on this phone; they are never added to cloud requests.\n\nThis lightweight index uses hashed word/character features with cosine similarity—not a second neural embedding model. It can miss paraphrases and pronouns, and does not guarantee faster output on every query." })
        root.addView(Switch(this).apply { text="Use relevant local conversation memory";isChecked=memory.enabled;setOnCheckedChangeListener { _,v->memory.enabled=v } })
        val output=TextView(this); root.addView(output)
        fun refresh() { lifecycleScope.launch { try { val s=withContext(Dispatchers.IO){memory.status()};output.text="Indexed messages: ${s.indexedMessages}. Query prompt: current question + at most two relevant snippets; no full-history replay." } catch(e:Exception) { output.text="Index unavailable: ${e.javaClass.simpleName}" } } }
        root.addView(Button(this).apply { text="Index next 128 older messages";setOnClickListener { lifecycleScope.launch { withContext(Dispatchers.IO){memory.sync(128)};refresh() } } })
        root.addView(Button(this).apply { text="Clear vectors and query cache (keep chat)";setOnClickListener {
            android.app.AlertDialog.Builder(this@ConversationMemoryActivity).setTitle("Clear local index?").setMessage("Chat messages remain. Turn memory off if you do not want the index rebuilt on later questions.")
                .setNegativeButton("Cancel",null).setPositiveButton("Clear") { _,_->lifecycleScope.launch { withContext(Dispatchers.IO){memory.clear()};refresh() } }.show()
        } })
        val doctor=app.getSharedPreferences("doctor_settings",0)
        root.addView(Switch(this).apply { text="Allow idle, battery-gated maintenance";isChecked=doctor.getBoolean("automatic",true);setOnCheckedChangeListener{_,v->doctor.edit().putBoolean("automatic",v).apply()} })
        setContentView(ScrollView(this).apply {addView(root)});refresh()
    }
}
