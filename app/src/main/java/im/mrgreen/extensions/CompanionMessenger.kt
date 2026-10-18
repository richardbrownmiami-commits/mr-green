package im.mrgreen.extensions

import android.content.*
import android.os.*
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Wire contract for separately installed companions. No AIDL, reflection loading, or code execution. */
object CompanionWire {
    const val ACTION = "im.mrgreen.companion.BIND"
    const val VERSION = 1
    const val MSG_HELLO = 10
    const val MSG_TOOLS = 11
    const val MSG_INVOKE = 12
    const val MSG_RESULT = 13
    const val KEY_VERSION = "protocol_version"
    const val KEY_NONCE = "nonce"
    const val KEY_TOOLS = "tools" // Bundle[]; each has name, description only
    const val KEY_NAME = "name"
    const val KEY_DESCRIPTION = "description"
    const val KEY_ARGS = "args" // Bundle containing JSON string; bounded by caller
    const val KEY_RESULT = "result"
}

data class CompanionDescriptor(val packageName: String, val signerSha256: String, val tools: List<CompanionTool>)

/** Caller must pin/review package+signer first. Service is explicitly package-targeted to prevent hijacking. */
class CompanionMessengerClient(private val context: Context, private val trust: CompanionTrust, private val approvals: CompanionApprovalStore) {
    fun inspect(packageName: String, pinnedSigner: String, timeoutMs: Long = 4000): CompanionDescriptor {
        require(trust.isTrustedAndApproved(packageName, pinnedSigner)) { "Signer not pinned or companion not explicitly approved" }
        val nonce = UUID.randomUUID().toString()
        val response = exchange(packageName, CompanionWire.MSG_HELLO, Bundle().apply {
            putInt(CompanionWire.KEY_VERSION, CompanionWire.VERSION)
            putString(CompanionWire.KEY_NONCE, nonce)
        }, timeoutMs)
        require(response.getString(CompanionWire.KEY_NONCE) == nonce) { "Companion handshake nonce mismatch" }
        require(response.getInt(CompanionWire.KEY_VERSION, -1) == CompanionWire.VERSION) { "Unsupported companion protocol" }
        val bundles = response.getParcelableArray(CompanionWire.KEY_TOOLS) ?: emptyArray()
        require(bundles.size in 1..64) { "Invalid tool list" }
        val tools = bundles.map { item ->
            val b = item as? Bundle ?: error("Malformed tool descriptor")
            CompanionTool(b.getString(CompanionWire.KEY_NAME).orEmpty(), b.getString(CompanionWire.KEY_DESCRIPTION).orEmpty(), "untrusted")
        }
        CompanionToolPolicy.validate(packageName, tools, emptySet()) // structural validation only; all names remain blocked until separately reviewed
        return CompanionDescriptor(packageName, pinnedSigner.lowercase(), tools)
    }

    /** Rechecks installed signer and explicit approval for every invocation. Approval is never inferred from discovery. */
    fun invoke(packageName: String, pinnedSigner: String, toolName: String, argsJson: String,
               reviewedToolNames: Set<String>, confirm: (String, String, String) -> Boolean, timeoutMs: Long = 8000): String {
        require(argsJson.toByteArray(Charsets.UTF_8).size <= 16 * 1024) { "Arguments too large" }
        require(trust.isTrustedAndApproved(packageName, pinnedSigner)) { "Companion trust revoked" }
        require(toolName in reviewedToolNames) { "Tool not reviewed" }
        val policy = CompanionToolPolicy.validate(packageName, listOf(CompanionTool(toolName, "reviewed tool", "untrusted")), reviewedToolNames)
        require(policy.risks[toolName] == ToolRisk.CONFIRM) { "Tool blocked" }
        require(confirm(packageName, toolName, argsJson)) { "User confirmation required" }
        val nonce = UUID.randomUUID().toString()
        val reply = exchange(packageName, CompanionWire.MSG_INVOKE, Bundle().apply {
            putString(CompanionWire.KEY_NAME, toolName); putString(CompanionWire.KEY_ARGS, argsJson)
            putString(CompanionWire.KEY_NONCE, nonce)
        }, timeoutMs)
        require(reply.getString(CompanionWire.KEY_NONCE) == nonce) { "Companion invocation nonce mismatch" }
        return reply.getString(CompanionWire.KEY_RESULT)?.take(16 * 1024) ?: error("Malformed invocation response")
    }

    private fun exchange(packageName: String, what: Int, data: Bundle, timeoutMs: Long): Bundle {
        require(packageName.matches(Regex("^[A-Za-z0-9_.]{3,255}$")))
        val latch = CountDownLatch(1); var result: Bundle? = null; var failure: Throwable? = null
        val reply = Messenger(object : Handler(Looper.getMainLooper()) {
            override fun handleMessage(msg: Message) {
                if (msg.what != CompanionWire.MSG_RESULT) { failure = IllegalStateException("Unexpected companion response"); latch.countDown(); return }
                result = Bundle(msg.data); latch.countDown()
            }
        })
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                try { Messenger(service).send(Message.obtain(null, what).apply { this.data = Bundle(data); replyTo = reply }) }
                catch (t: Throwable) { failure = t; latch.countDown() }
            }
            override fun onServiceDisconnected(name: ComponentName) { failure = IllegalStateException("Companion disconnected"); latch.countDown() }
        }
        val intent = Intent(CompanionWire.ACTION).setPackage(packageName)
        check(context.bindService(intent, conn, Context.BIND_AUTO_CREATE)) { "Companion service unavailable" }
        try { check(latch.await(timeoutMs, TimeUnit.MILLISECONDS)) { "Companion timeout" }; failure?.let { throw it }; return result ?: error("Empty companion reply") }
        finally { runCatching { context.unbindService(conn) } }
    }
}
