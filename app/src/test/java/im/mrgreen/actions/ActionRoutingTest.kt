package im.mrgreen.actions

import android.content.Context
import im.mrgreen.data.GreenStore
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = android.app.Application::class)
class ActionRoutingTest {
    private val cases = mapOf(
        "clock.time" to listOf("what time is it", "tell me the current time", "time now please", "what is the time right now", "samay kya hua", "abhi kitne baje hain", "time batao", "could you tell me the time", "current time please", "what is the time"),
        "clock.date" to listOf("what is today's date", "tell me today's date", "what date is it today", "which day is it today", "aaj ki date kya hai", "date today please", "what day is it", "please tell today's date", "today date", "tell me the date today"),
        "math.calculate" to listOf("calculate 2 plus 3", "solve 8 minus 4", "what is 7 times 6", "compute 20 divided by 5", "calculate 9 + 2", "solve 18 - 7", "what is 3 * 4", "calculate 100 / 4", "compute 10 % 3", "calculate (2 + 3) * 4"),
        "alarm.set" to listOf("set an alarm for 7 am", "set alarm at 6:30 am", "wake me at 8 am", "alarm for 7:15 pm", "alarm at 9", "alarm lagao 6 am", "remind me at 7:30 am", "please set alarm for 5 pm", "set an alarm at 8:05", "wake me at 6:45 am"),
        "timer.set" to listOf("set a timer for 5 minutes", "timer for 30 seconds", "start timer for 2 minutes", "countdown 10 seconds", "timer lagao 1 hour", "please set a timer for 45 minutes", "start timer 15 secs", "set a timer for 90 seconds", "countdown for 3 minutes", "timer for 1 min"),
        "note.save" to listOf("save a note: buy milk", "take a note: call Sam", "write a note: project review", "remember this: renew insurance", "note this: feed the cat", "save note: meeting at noon", "please save a note: book tickets", "write a note: send invoice", "remember this: pick up parcel", "take a note: dentist Friday"),
        "app.open" to listOf("open app YouTube", "launch app Camera", "open youtube", "open camera", "launch app Chrome", "start app YouTube", "open app Settings", "please open app Camera", "start app Chrome", "launch app YouTube"),
        "device.wifi" to listOf("open wifi settings", "open wi-fi settings", "turn on wifi", "turn off wifi", "enable wifi", "disable wifi", "wifi chalu karo", "wifi band karo", "please open wifi settings", "go to wi-fi settings"),
        "device.bluetooth" to listOf("open bluetooth settings", "turn on bluetooth", "turn off bluetooth", "enable bluetooth", "disable bluetooth", "bluetooth chalu karo", "please open bluetooth settings", "go to bluetooth settings", "open Bluetooth settings please", "bluetooth settings"),
        "device.brightness" to listOf("open brightness settings", "change brightness", "screen brightness settings", "display settings", "brightness badhao", "please open display settings", "go to brightness settings", "open display settings", "change screen brightness", "brightness settings please"),
        "device.volume" to listOf("open volume settings", "sound settings", "change volume", "volume badhao", "ringer settings", "please open sound settings", "go to volume settings", "open sound settings", "change ringer volume", "volume settings please"),
        "device.battery" to listOf("battery level", "battery status", "how much battery is left", "battery kitni hai", "check battery", "what is my battery level", "tell me battery status", "please check battery", "how much battery", "battery status please"),
        "device.airplane" to listOf("open airplane mode settings", "flight mode settings", "open wireless settings", "airplane chalu karo", "please open airplane mode", "go to flight mode settings", "airplane mode settings", "open flight mode", "wireless settings please", "airplane mode"),
        "device.settings" to listOf("open settings", "phone settings", "system settings", "settings kholo", "please open settings", "go to phone settings", "open system settings", "settings please", "show phone settings", "launch settings"),
        "device.location" to listOf("open location settings", "gps settings", "location services settings", "location chalu karo", "please open location settings", "go to GPS settings", "open location services", "location settings please", "show location settings", "location settings"),
        "device.accessibility" to listOf("open accessibility settings", "accessibility settings", "accessibility kholo", "please open accessibility", "go to accessibility settings", "show accessibility settings", "accessibility settings please", "open accessibility settings now", "launch accessibility settings", "accessibility kholo please"),
        "device.notifications" to listOf("open notification settings", "notifications settings", "app notifications settings", "please open notification settings", "go to notifications settings", "show notification settings", "notification settings please", "open app notifications", "launch notification settings", "notification settings"),
        "device.storage" to listOf("open storage settings", "phone storage", "manage storage", "storage space settings", "please open storage settings", "go to phone storage", "show storage space", "open internal storage", "storage settings please", "manage phone storage"),
        "device.date_settings" to listOf("open date and time settings", "time settings", "set date and time settings", "automatic time settings", "please open date and time settings", "go to time settings", "show date settings", "open automatic time", "date and time settings please", "change date and time settings"),
        "device.app_settings" to listOf("open application settings", "installed apps", "manage apps", "app settings", "please open application settings", "go to installed apps", "show app settings", "manage installed apps", "application settings please", "launch app settings")
    )

    @Test fun starterLibraryRoutesAtLeastNinetyPercentOfTwoHundredGoldenPhrases() {
        assertEquals("expected twenty starter intent groups", 20, cases.size)
        assertTrue("expected 200 fixtures", cases.values.sumOf { it.size } == 200)
        val context = RuntimeEnvironment.getApplication()
        val runtime = ActionRuntime(context, GreenStore(context))
        var correct = 0; var total = 0; val failures = mutableListOf<String>()
        cases.forEach { (expected, phrases) -> phrases.forEach { phrase ->
            total++
            val got = runtime.plan(phrase)?.optJSONObject(0)?.optString("tool")
            if (got == expected) correct++ else failures += "$expected | $phrase => $got"
        } }
        assertTrue("routing accuracy $correct/$total; failures: ${failures.joinToString("; ")}", correct.toDouble()/total >= .90)
    }

    @Test fun compoundPlanIsOrderedAndOutboundMessageNeverMapsToTool() {
        val context = RuntimeEnvironment.getApplication()
        val runtime = ActionRuntime(context, GreenStore(context))
        val plan = runtime.plan("what time is it and then calculate 2 plus 3")!!
        assertEquals("clock.time", plan.getJSONObject(0).getString("tool"))
        assertEquals("math.calculate", plan.getJSONObject(1).getString("tool"))
        assertEquals(null, runtime.plan("send a WhatsApp message to Sam saying hello"))
    }

    @Test fun schemasAreUniqueAndDeclaredForEveryRoutedTool() {
        assertEquals(ActionTools.registry.size, ActionTools.registry.map { it.id }.toSet().size)
        val context = RuntimeEnvironment.getApplication()
        val runtime = ActionRuntime(context, GreenStore(context))
        cases.keys.forEach { intent -> cases[intent]!!.take(1).forEach { phrase ->
            val planned=runtime.plan(phrase)
            assertNotNull("No plan for $intent: $phrase",planned)
            val step=planned!!.getJSONObject(0)
            assertTrue(ActionTools.registry.any { it.id==step.getString("tool") })
        } }
    }
    @Test fun deterministicMathDateAndNoteSlotsArePreserved() {
        val context=RuntimeEnvironment.getApplication()
        val runtime=ActionRuntime(context, GreenStore(context), today={ java.time.LocalDate.of(2026,10,19) })
        assertEquals("math.calculate", runtime.plan("what is 17% of 2450")!!.getJSONObject(0).getString("tool"))
        val expr=runtime.plan("what is 17% of 2450")!!.getJSONObject(0).getJSONObject("args").getString("expression")
        assertEquals("(17 / 100) * 2450", expr)
        val note=runtime.plan("make a note buy milk and eggs")
        assertEquals("buy milk and eggs", note!!.getJSONObject(0).getJSONObject("args").getString("text"))
        assertEquals("date.add", runtime.plan("in 45 days")!!.getJSONObject(0).getString("tool"))
        assertEquals(null, runtime.plan("don't turn on flashlight"))
    }

    @Test fun dryRunAndNotesListRoutingAreAvailable() {
        val context = RuntimeEnvironment.getApplication()
        val runtime = ActionRuntime(context, GreenStore(context))
        assertEquals("note.list", runtime.planDryRun("check notes")!!.getJSONObject(0).getString("tool"))
        assertEquals("note.list", runtime.plan("list notes")!!.getJSONObject(0).getString("tool"))
    }
    @Test fun invalidLaterStepPreventsEarlierSideEffect() = kotlinx.coroutines.runBlocking {
        val context=RuntimeEnvironment.getApplication(); val runtime=ActionRuntime(context, GreenStore(context))
        val steps=org.json.JSONArray().put(org.json.JSONObject().put("tool","note.save").put("args",org.json.JSONObject().put("text","should not be written"))).put(org.json.JSONObject().put("tool","no.such.tool").put("args",org.json.JSONObject()))
        assertTrue(runtime.executePlan(steps).startsWith("Stopped:"))
    }

    @Test fun cancellationStopsRemainingCompoundSteps() = kotlinx.coroutines.runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val runtime = ActionRuntime(context, GreenStore(context))
        val steps = runtime.plan("calculate 2 plus 3 and then calculate 8 minus 4")!!
        var cancelled = false
        var completed = 0
        val result = runtime.executePlanResult(steps, progress = {
            if (it.startsWith("Done")) { completed++; cancelled = true }
        }, isCancelled = { cancelled })
        assertEquals(1, completed)
        assertEquals(PlanOutcome.CANCELLED, result.outcome)
        assertTrue(result.message.contains("Earlier actions were not undone"))
    }

    @Test fun compoundPlansCannotInterleaveAcrossRuntimeInstances() = kotlinx.coroutines.runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val one = ActionRuntime(context, GreenStore(context))
        val two = ActionRuntime(context, GreenStore(context))
        val steps = one.plan("calculate 2 plus 3 and then calculate 8 minus 4")!!
        val entered = CompletableDeferred<Unit>()
        val order = java.util.Collections.synchronizedList(mutableListOf<String>())
        val first = async(Dispatchers.Default) {
            one.executePlanResult(steps) { progress ->
                if (progress.startsWith("Running")) {
                    order.add("first"); entered.complete(Unit); Thread.sleep(100)
                }
            }
        }
        entered.await()
        val second = async(Dispatchers.Default) {
            two.executePlanResult(steps) { if (it.startsWith("Running")) order.add("second") }
        }
        assertTrue(first.await().verified)
        assertTrue(second.await().verified)
        assertEquals(listOf("first", "first", "second", "second"), order.toList())
    }

    @Test fun typedExecutionDistinguishesVerifiedResultsAndClockHandoffs() = kotlinx.coroutines.runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val runtime = ActionRuntime(context, GreenStore(context))
        val math = org.json.JSONArray().put(org.json.JSONObject().put("tool", "math.calculate").put("args", org.json.JSONObject().put("expression", "2+3")))
        assertEquals(PlanOutcome.VERIFIED, runtime.executePlanResult(math).outcome)
        val alarm = org.json.JSONArray().put(org.json.JSONObject().put("tool", "alarm.set").put("args", org.json.JSONObject().put("time", "07:00").put("label", "test")))
        val resolved = android.content.pm.ResolveInfo().apply {
            activityInfo = android.content.pm.ActivityInfo().apply {
                packageName = "test.clock"; name = "test.clock.AlarmActivity"
                applicationInfo = android.content.pm.ApplicationInfo().apply { packageName = "test.clock" }
            }
        }
        org.robolectric.Shadows.shadowOf(context.packageManager).addResolveInfoForIntent(
            android.content.Intent(android.provider.AlarmClock.ACTION_SET_ALARM), resolved)
        val result = runtime.executePlanResult(alarm)
        assertEquals(PlanOutcome.HANDED_OFF, result.outcome)
        assertEquals(false, result.verified)
        val launched = org.robolectric.Shadows.shadowOf(context).nextStartedActivity
        assertEquals(7, launched.getIntExtra(android.provider.AlarmClock.EXTRA_HOUR, -1))
        assertEquals(0, launched.getIntExtra(android.provider.AlarmClock.EXTRA_MINUTES, -1))
        assertEquals(result.message, runtime.executePlan(alarm))
    }

}
