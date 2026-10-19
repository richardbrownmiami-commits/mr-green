package im.mrgreen.recipes

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONObject

/** Parent app may launch this activity explicitly; all target-app actions remain user-visible. */
class RecipesActivity : Activity() {
    private val scope = MainScope()
    private lateinit var output: TextView
    private lateinit var recipient: EditText
    private lateinit var message: EditText
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        RecipeRuntime.confirmation = { title, body -> confirm(title, body) }
        val root=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(24,24,24,24) }
        fun field(hint:String, lines:Int=1)=EditText(this).apply { this.hint=hint; minLines=lines; if(lines>1) gravity=android.view.Gravity.TOP }
        root.addView(TextView(this).apply { text="Confirmed accessibility recipes · experimental"; textSize=20f })
        recipient=field("WhatsApp recipient name exactly as shown")
        message=field("WhatsApp message",3)
        root.addView(recipient); root.addView(message)
        root.addView(TextView(this).apply { text="External AI-app conversation recipes are disabled. All model answers use the local GGUF only." })
        fun button(label:String, action:()->Unit) { root.addView(Button(this).apply { text=label; setOnClickListener { action() } }) }
        button("WhatsApp: review and send…") { runRecipe(JSONObject().put("recipe","whatsapp.send").put("recipient",recipient.text.toString()).put("message",message.text.toString()).toString()) }
        button("Open Wi-Fi settings…") { runRecipe("""{"recipe":"wifi.settings"}""") }
        output=TextView(this).apply { text="No recipe has run."; textSize=15f }
        root.addView(output)
        setContentView(android.widget.ScrollView(this).apply { addView(root,ViewGroup.LayoutParams(-1,-2)) })
    }
    private fun runRecipe(request:String) { output.text="Waiting for accessibility bridge…"; scope.launch { output.text=RecipeRuntime.execute(request) ?: "Unsupported recipe request." } }
    private suspend fun confirm(title:String, body:String):Boolean = kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
        AlertDialog.Builder(this).setTitle("Confirm: $title").setMessage(body).setNegativeButton("Cancel") { _,_-> if(continuation.isActive) continuation.resume(false) {} }
            .setPositiveButton(if(title=="Wi-Fi settings") "Open settings" else "Send this exact message") { _,_-> if(continuation.isActive) continuation.resume(true) {} }
            .setOnCancelListener { if(continuation.isActive) continuation.resume(false) {} }.show()
    }
    override fun onDestroy() { if (RecipeRuntime.confirmation != null) RecipeRuntime.confirmation=null; scope.cancel(); super.onDestroy() }
}
