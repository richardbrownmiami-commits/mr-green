package im.mrgreen.plugins

import org.json.JSONArray
import org.json.JSONObject

/** The only side-effect/approval/AI boundary available to declarative flows. Risk policy belongs to the host. */
interface PluginHost {
    fun call(name: String, args: JSONObject): Any?
    fun requestApproval(prompt: String): Boolean
    fun aiWrite(prompt: String): String
}

/** Executes the bounded JSON flow format. No expression language or dynamic code is supported. */
class FlowRuntime(
    private val host: PluginHost,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    private val sleep: (Long) -> Unit = { Thread.sleep(it) }
) {
    companion object {
        const val MAX_STEPS = 50
        const val MAX_ITERATIONS = 20
        const val MAX_DURATION_MS = 5 * 60 * 1000L
        const val MAX_WAIT_MS = 60_000L
        private const val MAX_DEPTH = 12
        private const val MAX_VALUE_NODES = 4096
        private const val MAX_STRING_LENGTH = 16_384
        private val variablePattern = Regex("[A-Za-z_][A-Za-z0-9_]{0,63}")
        private val opKeys = mapOf(
            "set" to setOf("op", "name", "value"),
            "call" to setOf("op", "tool", "args", "name"),
            "ask_user" to setOf("op", "prompt", "name"),
            "if" to setOf("op", "condition", "then", "else"),
            "foreach" to setOf("op", "in", "item", "index", "steps"),
            "wait" to setOf("op", "ms"),
            "verify" to setOf("op", "value", "equals", "message"),
            "ai_write" to setOf("op", "prompt", "name"),
            "on_error" to setOf("op", "steps", "name")
        )
        private val required = mapOf(
            "set" to setOf("name", "value"), "call" to setOf("tool"), "ask_user" to setOf("prompt"),
            "if" to setOf("condition"), "foreach" to setOf("in", "item", "steps"), "wait" to setOf("ms"),
            "verify" to setOf("value", "equals"), "ai_write" to setOf("prompt", "name"), "on_error" to setOf("steps")
        )
    }

    private class FlowFailure(val code: String) : RuntimeException()
    private fun fail(code: String): Nothing = throw FlowFailure(code)
    private fun variable(value: Any?, optional: Boolean = false) {
        if (optional && value == null) return
        require(value is String && variablePattern.matches(value)) { "invalid variable name" }
    }
    private fun bounded(value: Any?, depth: Int = 0, nodes: IntArray = intArrayOf(0)) {
        require(depth <= MAX_DEPTH) { "value nesting too deep" }
        nodes[0]++
        require(nodes[0] <= MAX_VALUE_NODES) { "value too large" }
        when (value) {
            is String -> require(value.length <= MAX_STRING_LENGTH) { "string too long" }
            is JSONObject -> { val it = value.keys(); while (it.hasNext()) { val key = it.next(); require(key.length <= MAX_STRING_LENGTH); bounded(value.get(key), depth + 1, nodes) } }
            is JSONArray -> for (i in 0 until value.length()) bounded(value.get(i), depth + 1, nodes)
        }
    }

    fun validate(flow: JSONObject, declaredTools: Set<String>) {
        require(flow.keys().asSequence().toSet() == setOf("steps")) { "flow allows only steps" }
        require(flow.opt("steps") is JSONArray) { "flow.steps must be an array" }
        var count = 0
        fun check(steps: JSONArray, depth: Int) {
            require(depth <= MAX_DEPTH) { "flow nesting too deep" }
            for (i in 0 until steps.length()) {
                count++; require(count <= MAX_STEPS) { "flow exceeds step limit" }
                val step = steps.optJSONObject(i) ?: error("step must be an object")
                val op = step.opt("op") as? String ?: error("op must be a string")
                val allowed = opKeys[op] ?: error("unknown operation")
                val keys = step.keys().asSequence().toSet()
                require(keys.all { it in allowed } && required.getValue(op).all { it in keys }) { "invalid operation fields" }
                when (op) {
                    "set" -> { variable(step.opt("name")); bounded(step.get("value")) }
                    "call" -> {
                        val tool = step.opt("tool") as? String ?: error("tool must be a string")
                        require(tool.isNotBlank() && tool in declaredTools) { "undeclared tool" }
                        if (step.has("args")) require(step.opt("args") is JSONObject) { "call.args must be an object" }
                        if (step.has("name")) variable(step.opt("name"))
                        bounded(step.opt("args") ?: JSONObject())
                    }
                    "ask_user" -> { require(step.opt("prompt") is String); bounded(step.get("prompt")); if (step.has("name")) variable(step.opt("name")) }
                    "if" -> {
                        bounded(step.get("condition"))
                        for (key in listOf("then", "else")) if (step.has(key)) { require(step.opt(key) is JSONArray) { "if.$key must be an array" }; check(step.getJSONArray(key), depth + 1) }
                    }
                    "foreach" -> {
                        variable(step.opt("item")); variable(step.opt("index"), optional = true); bounded(step.get("in"))
                        require(step.opt("steps") is JSONArray) { "foreach.steps must be an array" }; check(step.getJSONArray("steps"), depth + 1)
                    }
                    "wait" -> { val ms = step.opt("ms"); require(ms is Number && ms.toDouble() % 1.0 == 0.0 && ms.toLong() in 0..MAX_WAIT_MS) { "invalid wait duration" } }
                    "verify" -> { bounded(step.get("value")); bounded(step.get("equals")); if (step.has("message")) { require(step.opt("message") is String); bounded(step.get("message")) } }
                    "ai_write" -> { variable(step.opt("name")); require(step.opt("prompt") is String); bounded(step.get("prompt")) }
                    "on_error" -> { require(step.opt("steps") is JSONArray) { "on_error.steps must be an array" }; if (step.has("name")) variable(step.opt("name")); check(step.getJSONArray("steps"), depth + 1) }
                }
            }
        }
        check(flow.getJSONArray("steps"), 0)
    }

    fun run(flow: JSONObject, declaredTools: Set<String>, dryRun: Boolean = false): JSONObject {
        val vars = JSONObject(); val outputs = JSONArray()
        return try {
            validate(flow, declaredTools)
            val started = clock()
            var executed = 0; var virtualWait = 0L
            fun checkDeadline() { if (clock() - started > MAX_DURATION_MS || virtualWait > MAX_DURATION_MS) fail("FLOW_DEADLINE") }
            fun guard() {
                checkDeadline()
                if (executed >= MAX_STEPS) fail("FLOW_BUDGET")
                executed++
            }
            fun resolve(v: Any?): Any? = when (v) {
                is String -> if (v.startsWith("$") && v.length > 1 && vars.has(v.substring(1))) vars.get(v.substring(1)) else v
                is JSONObject -> JSONObject().also { out -> val it = v.keys(); while (it.hasNext()) { val k = it.next(); out.put(k, resolve(v.get(k))) } }
                is JSONArray -> JSONArray().also { out -> for (i in 0 until v.length()) out.put(resolve(v.get(i))) }
                JSONObject.NULL -> null
                else -> v
            }
            fun truth(v: Any?): Boolean = v != null && v !== JSONObject.NULL && v != false && v != "" && !(v is Number && v.toDouble() == 0.0)
            lateinit var execute: (JSONArray) -> Unit
            execute = { steps ->
                for (i in 0 until steps.length()) {
                    guard(); val s = steps.getJSONObject(i)
                    when (s.getString("op")) {
                        "set" -> vars.put(s.getString("name"), resolve(s.get("value")))
                        "call" -> {
                            val tool = s.getString("tool"); val args = resolve(s.optJSONObject("args") ?: JSONObject()) as JSONObject
                            val result = if (dryRun) JSONObject().put("dryRun", true).put("tool", tool).put("args", args) else try { host.call(tool, args) } catch (e: SecurityException) { fail("FLOW_PERMISSION") } catch (_: Exception) { fail("FLOW_HOST_ERROR") }
                            checkDeadline()
                            s.optString("name").takeIf { it.isNotEmpty() }?.let { vars.put(it, result ?: JSONObject.NULL) }; outputs.put(result ?: JSONObject.NULL)
                        }
                        "ask_user" -> {
                            val prompt = resolve(s.get("prompt")).toString(); val answer = if (dryRun) false else try { host.requestApproval(prompt) } catch (e: SecurityException) { fail("FLOW_PERMISSION") } catch (_: Exception) { fail("FLOW_HOST_ERROR") }
                            checkDeadline()
                            s.optString("name").takeIf { it.isNotEmpty() }?.let { vars.put(it, answer) }; outputs.put(answer)
                        }
                        "if" -> execute(s.optJSONArray(if (truth(resolve(s.get("condition")))) "then" else "else") ?: JSONArray())
                        "foreach" -> {
                            val source = resolve(s.get("in")); val items = if (source is JSONArray) (0 until source.length()).map { source.get(it) } else if (source is Iterable<*>) source.toList() else fail("FLOW_INVALID")
                            if (items.size > MAX_ITERATIONS) fail("FLOW_BUDGET")
                            items.forEachIndexed { index, item -> vars.put(s.getString("item"), item); s.optString("index").takeIf { it.isNotEmpty() }?.let { vars.put(it, index) }; execute(s.getJSONArray("steps")) }
                        }
                        "wait" -> {
                            val ms = s.getLong("ms"); virtualWait += ms
                            if (virtualWait > MAX_DURATION_MS || clock() - started + ms > MAX_DURATION_MS) fail("FLOW_DEADLINE")
                            if (!dryRun && ms > 0) try { sleep(ms) } catch (_: Exception) { fail("FLOW_HOST_ERROR") }
                            if (clock() - started > MAX_DURATION_MS) fail("FLOW_DEADLINE")
                        }
                        "verify" -> if (!jsonEquivalent(resolve(s.get("value")), resolve(s.get("equals")))) fail("FLOW_VERIFY_FAILED")
                        "ai_write" -> {
                            val prompt = resolve(s.get("prompt")).toString()
                            val text = if (dryRun) "[dry-run AI output]" else try { host.aiWrite(prompt) } catch (e: SecurityException) { fail("FLOW_PERMISSION") } catch (_: Exception) { fail("FLOW_HOST_ERROR") }
                            checkDeadline()
                            vars.put(s.getString("name"), text)
                        }
                        "on_error" -> try { execute(s.getJSONArray("steps")) } catch (e: FlowFailure) { if (e.code in setOf("FLOW_BUDGET", "FLOW_DEADLINE", "FLOW_PERMISSION")) throw e else { s.optString("name").takeIf { it.isNotEmpty() }?.let { vars.put(it, e.code) }; outputs.put(JSONObject().put("error", e.code)) } }
                    }
                    // Resolved references can expand far beyond the submitted JSON.
                    // Walk with node/depth bounds before serializing or calling again.
                    bounded(vars)
                    bounded(outputs)
                    checkDeadline()
                }
            }
            execute(flow.getJSONArray("steps"))
            JSONObject().put("ok", true).put("variables", vars).put("results", outputs).put("stepsExecuted", executed)
        } catch (e: Exception) {
            val code = if (e is FlowFailure) e.code else "FLOW_INVALID"
            JSONObject().put("ok", false).put("variables", vars).put("results", outputs).put("error", code)
        }
    }

    private fun jsonEquivalent(a: Any?, b: Any?): Boolean {
        if (a == null || a === JSONObject.NULL) return b == null || b === JSONObject.NULL
        if (b == null || b === JSONObject.NULL) return false
        if (a is Number && b is Number) return a.toDouble().isFinite() && b.toDouble().isFinite() && a.toDouble() == b.toDouble()
        if (a is JSONObject && b is JSONObject) {
            val ak = a.keys().asSequence().toSet(); val bk = b.keys().asSequence().toSet()
            return ak == bk && ak.all { jsonEquivalent(a.get(it), b.get(it)) }
        }
        if (a is JSONArray && b is JSONArray) return a.length() == b.length() && (0 until a.length()).all { jsonEquivalent(a.get(it), b.get(it)) }
        return a == b
    }
}
