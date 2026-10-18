package im.mrgreen.plugins

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = android.app.Application::class)
class FlowRuntimeTest {
    private class Host : PluginHost {
        var calls = 0
        var approvals = 0
        var ai = 0
        override fun call(name: String, args: JSONObject): Any? { calls++; return JSONObject().put("name", name).put("x", args.optInt("x")) }
        override fun requestApproval(prompt: String): Boolean { approvals++; return true }
        override fun aiWrite(prompt: String): String { ai++; return "written:$prompt" }
    }
    private fun flow(vararg steps: JSONObject) = JSONObject().put("steps", JSONArray(steps))
    private fun step(op: String, vararg fields: Pair<String, Any?>) = JSONObject().put("op", op).also { o -> fields.forEach { (k, v) -> o.put(k, v) } }

    @Test fun setConditionalLoopAndVariableResolution() {
        val host = Host()
        val f = flow(
            step("set", "name" to "items", "value" to JSONArray(listOf("a", "b"))),
            step("foreach", "in" to "${'$'}items", "item" to "item", "steps" to JSONArray().put(step("set", "name" to "last", "value" to "${'$'}item"))),
            step("if", "condition" to "${'$'}last", "then" to JSONArray().put(step("set", "name" to "ok", "value" to true)))
        )
        val result = FlowRuntime(host).run(f, emptySet())
        assertTrue(result.toString(), result.getBoolean("ok"))
        assertEquals("b", result.getJSONObject("variables").getString("last"))
        assertTrue(result.getJSONObject("variables").getBoolean("ok"))
        assertEquals(0, host.calls)
    }

    @Test fun declaredToolsRunAndDryRunNeverCallsHost() {
        val f = flow(step("call", "tool" to "notes.create", "args" to JSONObject().put("x", 3), "name" to "answer"), step("ask_user", "prompt" to "Sure?"), step("ai_write", "prompt" to "hi", "name" to "text"))
        val host = Host()
        val simulated = FlowRuntime(host).run(f, setOf("notes.create"), true)
        assertTrue(simulated.toString(), simulated.getBoolean("ok"))
        assertEquals(0, host.calls); assertEquals(0, host.approvals); assertEquals(0, host.ai)
        assertEquals("[dry-run AI output]", simulated.getJSONObject("variables").getString("text"))
        val actual = FlowRuntime(host).run(f, setOf("notes.create"))
        assertTrue(actual.getBoolean("ok"))
        assertEquals(1, host.calls); assertEquals(1, host.approvals); assertEquals(1, host.ai)
    }

    @Test fun rejectsUnknownOrUndeclaredOpsAndOverlongStructure() {
        val runtime = FlowRuntime(Host())
        assertFalse(runtime.run(flow(step("eval", "code" to "1+1")), emptySet()).getBoolean("ok"))
        assertFalse(runtime.run(flow(step("call", "tool" to "sms.send")), emptySet()).getBoolean("ok"))
        val many = JSONArray(); repeat(51) { many.put(step("set", "name" to "x$it", "value" to it)) }
        assertFalse(runtime.run(JSONObject().put("steps", many), emptySet()).getBoolean("ok"))
    }

    @Test fun waitInjectionAndVerificationFailureAreContained() {
        var waited = 0L
        val host = Host()
        val runtime = FlowRuntime(host, clock = { 100L }, sleep = { waited += it })
        assertTrue(runtime.run(flow(step("wait", "ms" to 25)), emptySet()).getBoolean("ok"))
        assertEquals(25L, waited)
        val failed = runtime.run(flow(step("verify", "value" to 1, "equals" to 2)), emptySet())
        assertFalse(failed.getBoolean("ok"))
        assertEquals(0, host.calls)
    }

    @Test fun strictKeysTypesAndVariableNamesAreRejectedWithStableErrors() {
        val runtime = FlowRuntime(Host())
        val malformed = listOf(
            JSONObject().put("steps", JSONArray()).put("extra", true),
            flow(step("set", "name" to "bad name", "value" to 1)),
            flow(step("set", "name" to "x", "value" to 1, "surprise" to true)),
            flow(step("if", "condition" to true, "then" to "not-array")),
            flow(step("call", "tool" to "x", "args" to "not-object"))
        )
        malformed.forEach { result -> assertFalse(result.toString(), runtime.run(result, setOf("x")).getBoolean("ok")) }
        val secret = flow(step("verify", "value" to "secret-payload", "equals" to "no"))
        assertEquals("FLOW_VERIFY_FAILED", runtime.run(secret, emptySet()).getString("error"))
    }

    @Test fun verificationUsesJsonNumericAndNestedEquality() {
        val f = flow(step("verify", "value" to JSONObject().put("n", 1), "equals" to JSONObject().put("n", 1.0)))
        assertTrue(FlowRuntime(Host()).run(f, emptySet()).getBoolean("ok"))
    }

    @Test fun dryRunWaitsAccumulateVirtualTimeAndOnErrorCannotHideBudgetOrPermission() {
        var now = 0L
        val runtime = FlowRuntime(object : PluginHost {
            override fun call(name: String, args: JSONObject): Any? = throw SecurityException("private detail")
            override fun requestApproval(prompt: String) = false
            override fun aiWrite(prompt: String) = ""
        }, clock = { now }, sleep = { now += it })
        val waits = flow(step("wait", "ms" to 60_000), step("wait", "ms" to 60_000), step("wait", "ms" to 60_000), step("wait", "ms" to 60_000), step("wait", "ms" to 60_000), step("wait", "ms" to 60_000))
        assertEquals("FLOW_DEADLINE", runtime.run(waits, emptySet(), dryRun = true).getString("error"))
        val permission = flow(step("on_error", "steps" to JSONArray().put(step("call", "tool" to "x"))))
        assertEquals("FLOW_PERMISSION", runtime.run(permission, setOf("x")).getString("error"))
        assertTrue(runtime.run(flow(step("set", "name" to "x", "value" to 1)), emptySet()).getBoolean("ok"))
    }
}
