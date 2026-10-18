package im.mrgreen.plugins

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** No Context, credentials, chat history, database or network client crosses this boundary. */
class PluginSystem(context: Context) {
    private val app = context.applicationContext
    private var approval: (String) -> Boolean = { false }
    var lastOutput: String = ""
        private set
    val trust = PackTrust(File(app.filesDir, "pack-owner-key"))
    val builder = PackBuilder(trust)
    val manager: PackManager = PackManager(
        root = File(app.filesDir, "packs"),
        dryRun = { manifest, files -> dryRun(manifest, files) },
        execute = { manifest, files, entry -> execute(manifest, files, entry) },
        trust = trust,
        memoryLimitBytes = 8 * 1024 * 1024
    )

    fun recover() { manager.recoverInterruptedRun() }

    private fun audit(message: String) {
        (app as? im.mrgreen.GreenApp)?.logger?.write("INFO", "PLUGIN", message)
    }

    @Synchronized fun run(id: String, entry: String, ask: (String) -> Boolean) {
        lastOutput = ""
        approval = ask
        var success = false
        try { audit("Run started"); manager.run(id, entry); success = true }
        finally { approval = { false }; audit("Run finished; success=$success; safe_mode=${manager.isSafeMode()}") }
    }

    private fun tools(manifest: JSONObject): Set<String> = strings(manifest.optJSONArray("tools"))
    private fun permissions(manifest: JSONObject): Set<String> = strings(manifest.optJSONArray("permissions"))
    private fun strings(array: JSONArray?): Set<String> = (0 until (array?.length() ?: 0)).map { array!!.getString(it) }.toSet()
    private fun requirePermission(manifest: JSONObject, permission: String) {
        if (permission !in permissions(manifest)) throw SecurityException("E_PLUGIN_PERMISSION")
    }

    private fun flowHost(manifest: JSONObject, dry: Boolean): PluginHost = object : PluginHost {
        override fun call(name: String, args: JSONObject): Any? {
            if (name !in tools(manifest)) throw SecurityException("E_PLUGIN_TOOL")
            if (!dry && name in setOf("plugin.echo", "time.now")) audit("Tool call; tool=$name")
            return when (name) {
                "plugin.echo" -> { requirePermission(manifest, "plugin.output"); if (!dry) lastOutput = args.optString("text").take(4096); args.optString("text").take(4096) }
                "time.now" -> { requirePermission(manifest, "time.read"); if (dry) 0L else System.currentTimeMillis() }
                else -> error("E_CAPABILITY_UNAVAILABLE")
            }
        }
        override fun requestApproval(prompt: String): Boolean = if (dry) true else approval(prompt.take(500))
        override fun aiWrite(prompt: String): String = error("E_CAPABILITY_UNAVAILABLE")
    }

    private fun scriptHost(manifest: JSONObject, dry: Boolean): ScriptHost {
        val mockMemory = mutableMapOf<String, Any?>()
        return ScriptHost { method, argsJson ->
            val args = JSONArray(argsJson)
            if (!dry && method in setOf("tool.call", "time.now", "mem.get", "mem.set", "notify", "log")) audit("Script host call; api=$method")
            val result: Any? = when (method) {
                "tool.call" -> flowHost(manifest, dry).call(args.getString(0), args.optJSONObject(1) ?: JSONObject())
                "time.now" -> { requirePermission(manifest, "time.read"); if (dry) 0L else System.currentTimeMillis() }
                "mem.get" -> {
                    requirePermission(manifest, "plugin.memory")
                    if (dry) mockMemory[args.getString(0)] else manager.getMemory(manifest.getString("id"), args.getString(0))
                }
                "mem.set" -> {
                    requirePermission(manifest, "plugin.memory")
                    val key = args.getString(0); val value = args.opt(1)
                    if (dry) mockMemory[key] = value else manager.setMemory(manifest.getString("id"), key, value)
                    true
                }
                "notify" -> { requirePermission(manifest, "plugin.output"); if (!dry) lastOutput = args.optString(0).take(4096); true }
                // Deliberately discard arbitrary plugin log text: metadata-only logging.
                "log" -> { requirePermission(manifest, "plugin.log"); true }
                "http.get", "ai.ask" -> error("E_CAPABILITY_UNAVAILABLE")
                else -> error("E_PLUGIN_HOST")
            }
            JSONArray().put(result ?: JSONObject.NULL).toString().let { it.substring(1, it.length - 1) }
        }
    }

    private fun dryRun(manifest: JSONObject, files: Map<String, ByteArray>) {
        val declared = tools(manifest)
        val results = mutableMapOf<String, Any?>()
        val golden = FlowRuntime(flowHost(manifest, true))
        check(golden.run(JSONObject("""{"steps":[{"op":"set","name":"x","value":1},{"op":"verify","value":"${'$'}x","equals":1}]}"""), emptySet(), true).getBoolean("ok"))
        check(!golden.run(JSONObject("""{"steps":[{"op":"call","tool":"not.available"}]}"""), emptySet(), true).getBoolean("ok"))
        files.filterKeys { it.startsWith("flows/") && it.endsWith(".json") }.forEach { (path, bytes) ->
            val flow = JSONObject(bytes.toString(Charsets.UTF_8))
            FlowRuntime(flowHost(manifest, true)).apply {
                validate(flow, declared)
                val result = run(flow, declared, true)
                check(result.getBoolean("ok")) { "E_PLUGIN_DRY_RUN" }
                results[path] = result
            }
        }
        files.filterKeys { it.startsWith("scripts/") && it.endsWith(".js") }.forEach { (path, bytes) ->
            val result = QuickJsRuntime().run(bytes.toString(Charsets.UTF_8), scriptHost(manifest, true))
            results[path] = JSONArray("[$result]").get(0)
        }
        val covered = mutableSetOf<String>()
        files.filterKeys { it.startsWith("tests/") && it.endsWith(".json") }.values.forEach { bytes ->
            val suite = JSONObject(bytes.toString(Charsets.UTF_8))
            check(suite.keys().asSequence().toSet() == setOf("cases"))
            val cases = suite.getJSONArray("cases")
            for (i in 0 until cases.length()) {
                val case = cases.getJSONObject(i)
                check(case.keys().asSequence().toSet() == setOf("entry", "expected"))
                val path = case.getString("entry")
                check(results.containsKey(path) && expectedMatches(case.get("expected"), results[path])) { "E_PLUGIN_TEST" }
                covered.add(path)
            }
        }
        check(results.isNotEmpty() && covered == results.keys) { "E_PLUGIN_TEST_COVERAGE" }
    }

    private fun expectedMatches(expected: Any?, actual: Any?): Boolean = when {
        expected is JSONObject && actual is JSONObject -> expected.keys().asSequence().all { actual.has(it) && expectedMatches(expected.get(it), actual.get(it)) }
        expected is JSONArray && actual is JSONArray -> expected.length() == actual.length() && (0 until expected.length()).all { expectedMatches(expected.get(it), actual.get(it)) }
        expected is Number && actual is Number -> expected.toDouble() == actual.toDouble()
        else -> expected == actual
    }

    private fun execute(manifest: JSONObject, files: Map<String, ByteArray>, entry: String) {
        val bytes = files[entry] ?: error("E_PLUGIN_ENTRY")
        when {
            entry.startsWith("flows/") && entry.endsWith(".json") -> {
                val result = FlowRuntime(flowHost(manifest, false)).run(JSONObject(bytes.toString(Charsets.UTF_8)), tools(manifest))
                check(result.getBoolean("ok")) { "E_PLUGIN_FLOW" }
                if (lastOutput.isEmpty()) lastOutput = result.toString().take(4096)
            }
            entry.startsWith("scripts/") && entry.endsWith(".js") -> {
                val result = QuickJsRuntime().run(bytes.toString(Charsets.UTF_8), scriptHost(manifest, false))
                if (lastOutput.isEmpty()) lastOutput = result.take(4096)
            }
            else -> error("E_PLUGIN_ENTRY")
        }
    }
}
