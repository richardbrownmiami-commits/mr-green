package im.mrgreen.plugins

import android.app.Application
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class)
class PluginHostTest {
    private fun host(system: PluginSystem, permissions: List<String>, tools: List<String> = emptyList(), dry: Boolean = true): ScriptHost {
        val manifest = JSONObject().put("id", "test.namespace").put("permissions", JSONArray(permissions)).put("tools", JSONArray(tools))
        val method = PluginSystem::class.java.getDeclaredMethod("scriptHost", JSONObject::class.java, Boolean::class.javaPrimitiveType)
        method.isAccessible = true
        return method.invoke(system, manifest, dry) as ScriptHost
    }
    private fun reject(block: () -> Unit) { try { block(); fail("Expected fail-closed host boundary") } catch (_: RuntimeException) { } }
    @Test fun ungrantedHostPermissionsAreRejected() {
        val system = PluginSystem(RuntimeEnvironment.getApplication())
        val host = host(system, emptyList())
        reject { host.invoke("time.now", "[]") }
        reject { host.invoke("mem.get", "[\"key\"]") }
        reject { host.invoke("notify", "[\"private-local-sentinel\"]") }
        reject { host.invoke("tool.call", "[\"plugin.echo\",{}]") }
    }
    @Test fun futureNetworkAiAndArbitraryMethodsRemainUnavailable() {
        val host = host(PluginSystem(RuntimeEnvironment.getApplication()), listOf("plugin.output", "plugin.memory", "time.read", "plugin.log"))
        reject { host.invoke("http.get", "[\"https://example.org/\"]") }
        reject { host.invoke("ai.ask", "[\"private-local-sentinel\"]") }
        reject { host.invoke("gateway.key", "[]") }
        reject { host.invoke("android.startActivity", "[]") }
    }
    @Test fun mockedMemoryIsNamespacedPerDryRunAndNeverPersisted() {
        val system = PluginSystem(RuntimeEnvironment.getApplication())
        val first = host(system, listOf("plugin.memory"))
        assertEquals("true", first.invoke("mem.set", "[\"key\",\"local-value\"]"))
        assertEquals("\"local-value\"", first.invoke("mem.get", "[\"key\"]"))
        assertEquals("null", host(system, listOf("plugin.memory")).invoke("mem.get", "[\"key\"]"))
        assertNull(system.manager.getMemory("test.namespace", "key"))
    }
    @Test fun dryRunClockAndOutputAreDeterministicAndLogTextIsDiscarded() {
        val system = PluginSystem(RuntimeEnvironment.getApplication())
        val host = host(system, listOf("time.read", "plugin.output", "plugin.log"), listOf("plugin.echo"))
        assertEquals("0", host.invoke("time.now", "[]"))
        assertEquals("\"hello\"", host.invoke("tool.call", "[\"plugin.echo\",{\"text\":\"hello\"}]"))
        assertEquals("true", host.invoke("log", "[\"not-written-to-log\"]"))
        assertEquals("", system.lastOutput)
    }
}
