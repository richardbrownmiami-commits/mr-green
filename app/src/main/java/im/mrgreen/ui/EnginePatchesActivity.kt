package im.mrgreen.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import im.mrgreen.extensions.EnginePatchManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class EnginePatchesActivity : AppCompatActivity() {
    private lateinit var manager:EnginePatchManager
    private lateinit var output:TextView
    private val picker=registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri:Uri? -> if(uri!=null)review(uri) }
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState); manager=EnginePatchManager(this)
        val root=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setPadding(24,24,24,24) }
        root.addView(TextView(this).apply { text="Vetted native engine patches"; textSize=23f })
        root.addView(TextView(this).apply { text="Advanced developer feature. A signed native library runs with the app's privileges: a signature proves its owner, not that its code is safe. Only use an independently reviewed ARMv7 CPU-only build for this exact host/JNI ABI. Never install AI-generated or unknown code. No code is downloaded automatically." })
        fun button(s:String,action:()->Unit) { root.addView(Button(this).apply { text=s;setOnClickListener{action()} }) }
        button("Inspect signed patch ZIP") { picker.launch(arrayOf("application/zip","application/octet-stream")) }
        button("Roll back on next process start") { manager.rollback();output.text=manager.status() }
        button("Use built-in engine on next start") { manager.disable();output.text=manager.status() }
        output=TextView(this).apply { text=manager.status();setTextIsSelectable(true) };root.addView(output)
        root.addView(TextView(this).apply { text="Changes never swap a loaded library. Close the app fully and reopen after applying a patch. Three consecutive interrupted native operations put patches into safe mode and preserve the previous candidate. Android killing the process during native work can also count as an interrupted operation." })
        setContentView(ScrollView(this).apply { addView(root) })
    }
    private fun review(uri:Uri) { lifecycleScope.launch {
        try {
            val candidate=withContext(Dispatchers.IO) { contentResolver.openInputStream(uri)?.use { manager.inspect(it) } ?: error("Cannot open patch") }
            val checkbox=CheckBox(this@EnginePatchesActivity).apply { text="I independently verified this owner's key and reviewed this patch for this exact app ABI" }
            android.app.AlertDialog.Builder(this@EnginePatchesActivity).setTitle("Review trusted native replacement")
                .setMessage("Version: ${candidate.manifest.version}\nABI: ${candidate.manifest.abi}\nLibrary: ${candidate.manifest.soname}\nSHA-256: ${candidate.manifest.sha256}\nOwner public-key SHA-256:\n${candidate.ownerFingerprint}\n\nCryptographic checks passed. Verify the owner fingerprint independently; do not trust a fingerprint supplied only with an unknown patch.")
                .setView(checkbox).setNegativeButton("Cancel",null).setPositiveButton("Approve for next start") { _,_->
                    if(!checkbox.isChecked) { output.text="Not approved: independent review is required.";return@setPositiveButton }
                    lifecycleScope.launch { try { withContext(Dispatchers.IO) { manager.installAfterApproval(candidate,true) };output.text=manager.status() } catch(e:Exception) { output.text="Not activated: ${e.message}" } }
                }.show()
        } catch(e:Exception) { output.text="Patch rejected: ${e.message}" }
    } }
}
