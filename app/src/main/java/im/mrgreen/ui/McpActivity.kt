package im.mrgreen.ui

import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import im.mrgreen.GreenApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Only manually entered public arguments are eligible; screen, notifications and memory never enter this UI. */
class McpActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val runtime=(application as GreenApp).roadmap.knowledge
        val root=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(24,24,24,24) }
        root.addView(TextView(this).apply { text="MCP · public data only"; textSize=23f })
        root.addView(TextView(this).apply { text="Remote tools can act outside your phone. Review the destination, tool and complete arguments on every call. Do not enter notifications, contact details, private documents, credentials or screen text." })
        fun field(h:String)=EditText(this).apply { hint=h; root.addView(this) }
        val server=field("Server nickname")
        val endpoint=field("https://public-server.example/mcp")
        val tool=field("Allowed tool name")
        val args=field("Public arguments JSON, e.g. {}")
        val output=TextView(this).apply { setTextIsSelectable(true) }
        fun button(s:String, f:()->Unit) { root.addView(Button(this).apply { text=s; setOnClickListener { f() } }) }
        button("Review and approve server") {
            val name=server.text.toString().trim(); val url=endpoint.text.toString().trim(); val t=tool.text.toString().trim()
            android.app.AlertDialog.Builder(this).setTitle("Approve remote destination").setMessage("Server: $name\nURL: $url\nAllowed tool: $t\nNo call will run yet.")
                .setNegativeButton("Cancel",null).setPositiveButton("Approve") { _,_-> lifecycleScope.launch { try {
                    withContext(Dispatchers.IO) { runtime.approveMcpServer(name,url,setOf(t),true) }
                    output.text="Server approved. Enable web/MCP access in Knowledge, then initialize before invoking."
                } catch(e:Exception) { output.text=e.message } } }.show()
        }
        button("Review connection initialization / list tools") {
            val name=server.text.toString().trim()
            android.app.AlertDialog.Builder(this).setTitle("Connect to reviewed server?").setMessage("$name\n${endpoint.text}\nSends protocol information only; no personal context.")
                .setNegativeButton("Cancel",null).setPositiveButton("Connect") { _,_-> lifecycleScope.launch { try {
                    output.text=runtime.initializeMcp(name,true)+"\n"+runtime.listMcpTools(name,true)
                } catch(e:Exception) { output.text=e.message } } }.show()
        }
        button("Review remote tool call") {
            val name=server.text.toString().trim(); val t=tool.text.toString().trim(); val raw=args.text.toString()
            val parsed=try { JSONObject(raw) } catch (_:Exception) { output.text="Arguments must be a JSON object."; return@button }
            val destination=runtime.approvedMcpEndpoint(name) ?: run { output.text="Approve this server first."; return@button }
            val reviewedArgs=parsed.toString()
            android.app.AlertDialog.Builder(this).setTitle("Authorize this exact remote call").setMessage("Server: $name\nDestination: $destination\nTool: $t\nArguments:\n${parsed.toString(2)}\n\nConfirm these are manually typed PUBLIC data and authorize this action.")
                .setNegativeButton("Cancel",null).setPositiveButton("Authorize once") { _,_-> lifecycleScope.launch {
                    try { output.text=runtime.callMcp(name,t,reviewedArgs,destination,true,true) } catch(e:Exception) { output.text=e.message }
                } }.show()
        }
        root.addView(output); setContentView(ScrollView(this).apply { addView(root) })
    }
}
