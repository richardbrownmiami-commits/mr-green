package im.mrgreen.actions

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import android.os.StatFs
import im.mrgreen.data.GreenStore
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowStatFs
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [30])
class LocalStatusTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private fun runtime() = ActionRuntime(app, GreenStore(app))
    private fun step(tool: String) = JSONArray().put(JSONObject().put("tool", tool).put("args", JSONObject()))

    @Test fun shortStatusAliasesRouteWithoutChangingSettingsAndNoteActions() {
        val runtime = runtime()
        mapOf("battery" to "device.battery", "battery percentage" to "device.battery", "time" to "clock.time",
            "tell me time" to "clock.time", "storage" to "device.storage_status", "how much space is left" to "device.storage_status",
            "status" to "device.system_status", "system status" to "device.system_status", "phone status" to "device.system_status").forEach { (query, tool) ->
            assertEquals(query, tool, runtime.plan(query)!!.getJSONObject(0).getString("tool"))
        }
        assertEquals("device.storage", runtime.plan("storage settings")!!.getJSONObject(0).getString("tool"))
        assertEquals("device.settings", runtime.plan("system settings")!!.getJSONObject(0).getString("tool"))
        assertEquals("hello battery time system status complete job", runtime.plan("make a note: hello battery time system status complete job")!!
            .getJSONObject(0).getJSONObject("args").getString("text"))
        assertNull(runtime.plan("do not check system status"))
        assertNull(runtime.jobStatus("save a note: complete job"))
        assertNull(runtime.jobStatus("job status then open camera"))
    }

    @Test fun batteryIsRereadAndMissingReadingsAreNotInvented() = runBlocking {
        val runtime = runtime()
        fun battery(level: Int, scale: Int) { app.sendStickyBroadcast(Intent(Intent.ACTION_BATTERY_CHANGED)
            .putExtra(BatteryManager.EXTRA_LEVEL, level).putExtra(BatteryManager.EXTRA_SCALE, scale)) }
        battery(72, 100)
        assertEquals("Battery is 72%.", runtime.execute("battery"))
        battery(19, 100)
        assertEquals("Battery is 19%.", runtime.execute("battery percentage"))
        battery(-1, 0)
        assertEquals("Battery status is unavailable.", runtime.execute("battery"))
    }

    @Test fun timeUsesTheCurrentDeviceClockNotAStoredAnswer() = runBlocking {
        val formatter = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
        val before = LocalTime.now().format(formatter)
        val answer = runtime().execute("time")
        val after = LocalTime.now().format(formatter)
        assertTrue(answer == "It is $before." || answer == "It is $after.")
    }

    @Test fun storageAndRamFollowAndroidApiValuesAndChanges() = runBlocking {
        val path = app.filesDir.absolutePath
        ShadowStatFs.registerStats(path, 1_000_000, 500_000, 250_000)
        val manager = app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        shadowOf(manager).setMemoryInfo(ActivityManager.MemoryInfo().apply {
            totalMem = 2_048L * 1_048_576; availMem = 512L * 1_048_576; lowMemory = false
        })
        val runtime = runtime()
        val first = runtime.execute("system status")!!
        assertTrue(first.contains("API 30"))
        assertTrue(first.contains("RAM available 512 MiB of 2048 MiB"))
        val stat = StatFs(path)
        val free = "%.1f".format(Locale.ROOT, stat.availableBytes / 1_073_741_824.0)
        assertTrue(first.contains("$free GiB available"))
        ShadowStatFs.registerStats(path, 1_000_000, 200_000, 100_000)
        shadowOf(manager).setMemoryInfo(ActivityManager.MemoryInfo().apply {
            totalMem = 2_048L * 1_048_576; availMem = 128L * 1_048_576; lowMemory = true
        })
        val second = runtime.execute("status")!!
        assertTrue(second.contains("RAM available 128 MiB"))
        assertTrue(second.contains("low-memory flag: true"))
        assertNotEquals(first, second)
        assertTrue(runtime.execute("storage")!!.startsWith("App data volume:"))
    }

    @Test fun missingStorageCapacityIsUnavailableNotFabricated() = runBlocking {
        ShadowStatFs.registerStats(app.filesDir.absolutePath, 0, 0, 0)
        val runtime = runtime()
        assertEquals("Storage status is unavailable.", runtime.execute("storage"))
        assertTrue(runtime.execute("system status")!!.contains("Storage status is unavailable."))
    }

    @Test fun unavailableRamIsNotDisplayedAsZeroOrHealthy() = runBlocking {
        val manager = app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        shadowOf(manager).setMemoryInfo(ActivityManager.MemoryInfo().apply { totalMem = 0; availMem = 0 })
        assertTrue(runtime().execute("system status")!!.contains("RAM status is unavailable"))
    }

    @Test fun jobQueriesNeedTypedEvidenceAndDoNotOverwriteIt() = runBlocking {
        val runtime = runtime()
        listOf("complete job", "comple job", "is it done", "job status").forEach {
            assertTrue(runtime.execute(it)!!.contains("No registered-action result"))
        }
        val result = runtime.executePlanResult(step("clock.time"))
        val status = runtime.execute("job status")!!
        assertTrue(status.contains("verified result"))
        assertTrue(status.contains(result.message))
        assertEquals(status, runtime.execute("complete job"))
        runtime.executePlanResult(step("shell.exec"))
        val failed = runtime.execute("job status")!!
        assertTrue(failed.contains("failed; completion is not confirmed"))
        assertFalse(failed.contains("verified result"))
        assertTrue(runtime().execute("job status")!!.contains("No registered-action result"))
    }

    @Test fun actualClockHandoffRemainsUnverifiedInJobStatus() = runBlocking {
        val resolved = android.content.pm.ResolveInfo().apply {
            activityInfo = android.content.pm.ActivityInfo().apply {
                packageName = "test.clock"; name = "test.clock.AlarmActivity"
                applicationInfo = android.content.pm.ApplicationInfo().apply { packageName = "test.clock" }
            }
        }
        shadowOf(app.packageManager).addResolveInfoForIntent(Intent(android.provider.AlarmClock.ACTION_SET_ALARM), resolved)
        val runtime = runtime()
        runtime.execute("set alarm for 7 am")
        assertTrue(runtime.execute("job status")!!.contains("handed off; external completion is unverified"))
        assertFalse(runtime.execute("complete job")!!.contains("verified result:"))
    }

    @Test fun everyNonSuccessOutcomeStaysNonSuccessIncludingHandoffs() {
        val evidence = LastActionStatus()
        mapOf(PlanOutcome.HANDED_OFF to "external completion is unverified", PlanOutcome.FAILED to "completion is not confirmed",
            PlanOutcome.PENDING_CONFIRMATION to "not completed", PlanOutcome.CANCELLED to "remaining work was not completed").forEach { (outcome, text) ->
            evidence.record(PlanExecutionResult(outcome, "Actual recorded detail"))
            assertTrue(evidence.reply().contains(text))
            assertTrue(evidence.reply().contains("Actual recorded detail"))
            assertFalse(evidence.reply().contains("verified result:"))
        }
    }

    @Test fun cancelledAndUnconfirmedExecutionsReplaceEarlierSuccessEvidence() = runBlocking {
        val runtime = runtime()
        runtime.executePlanResult(step("clock.time"))
        runtime.executePlanResult(step("clock.time"), {}, { true })
        assertTrue(runtime.execute("job status")!!.contains("cancelled"))
        val torch = JSONArray().put(JSONObject().put("tool", "device.flashlight").put("args", JSONObject().put("enabled", true)))
        runtime.executePlanResult(torch)
        assertTrue(runtime.execute("job status")!!.contains("pending confirmation"))
    }
}
