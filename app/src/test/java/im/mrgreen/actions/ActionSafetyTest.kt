package im.mrgreen.actions

import android.app.Application
import im.mrgreen.data.GreenStore
import kotlinx.coroutines.runBlocking
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
class ActionSafetyTest {
    private fun runtime() = RuntimeEnvironment.getApplication().let { ActionRuntime(it, GreenStore(it), confirmRisk = { true }) }
    private fun step(tool: String, args: JSONObject = JSONObject()) = JSONObject().put("tool", tool).put("args", args)

    @Test fun dangerousNamesAndExtraCommandArgumentsNeverEnterDispatch() = runBlocking {
        val runtime = runtime()
        listOf("shell", "shell.exec", "bash", "root", "file.read", "file.delete", "app.install", "app.uninstall", "intent.send", "environment.read").forEach {
            assertEquals(it, PlanOutcome.FAILED, runtime.executePlanResult(JSONArray().put(step(it))).outcome)
        }
        assertFalse(ActionTools.validate(step("device.battery", JSONObject().put("command", "echo private"))))
        assertFalse(ActionTools.validate(step("app.open", JSONObject().put("name", "Camera").put("shell", "anything"))))
    }

    @Test fun stoppingFromProgressPreventsTheNextAction() = runBlocking {
        var cancelled = false
        var done = 0
        val result = runtime().executePlanResult(JSONArray().put(step("clock.time")), progress = {
            if (it.startsWith("Running")) cancelled = true
            if (it.startsWith("Done")) done++
        }, isCancelled = { cancelled })
        assertEquals(PlanOutcome.CANCELLED, result.outcome)
        assertEquals(0, done)
    }

    @Test fun permissionMetadataIsEnforcedEvenWithRiskApproval() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        org.robolectric.Shadows.shadowOf(context).denyPermissions(android.Manifest.permission.CAMERA)
        val result = runtime().executePlanResult(JSONArray().put(step("device.flashlight", JSONObject().put("enabled", true))))
        assertEquals(PlanOutcome.FAILED, result.outcome)
        assertTrue(result.message.contains("Permission missing"))
    }

    @Test fun oversizedPlansAreRejectedBeforeAnyNoteCanBeSaved() = runBlocking {
        val plan = JSONArray()
        repeat(17) { plan.put(step("note.save", JSONObject().put("text", "must not be saved"))) }
        assertEquals(PlanOutcome.FAILED, runtime().executePlanResult(plan).outcome)
    }

    @Test fun chatScreenAndShellCommandsAreExplicitNonExecutionNotModelFallback() = runBlocking {
        val runtime = runtime()
        listOf("tap wifi settings", "swipe down", "type text private words", "press home", "run shell calculate 2 plus 3", "execute bash echo hello").forEach { request ->
            assertNull(runtime.plan(request))
            val answer = runtime.execute(request)
            assertNotNull(request, answer)
            assertTrue(answer!!.startsWith("No"))
        }
    }

    @Test fun exactTimeAndOperationSpecificNumericBoundsAreMandatory() {
        listOf("7:00", "07:00:00", "24:00", "07:00Z").forEach {
            assertFalse(it, ActionTools.validate(step("alarm.set", JSONObject().put("time", it).put("label", "test"))))
        }
        assertFalse(ActionTools.validate(step("date.add", JSONObject().put("days", -1))))
        assertFalse(ActionTools.validate(step("timer.set", JSONObject().put("seconds", 0).put("label", "test"))))
        assertTrue(ActionTools.validate(step("timer.set", JSONObject().put("seconds", 86400).put("label", "test"))))
    }
}
