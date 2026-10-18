package im.mrgreen.core

import android.content.Context
import im.mrgreen.GreenApp
import im.mrgreen.actions.ActionRuntime
import im.mrgreen.actions.ActionTools
import im.mrgreen.events.EventRuntime
import im.mrgreen.knowledge.KnowledgeRuntime
import im.mrgreen.learning.LearningRuntime
import im.mrgreen.learning.LearningActivityBridge
import im.mrgreen.screen.*
import org.json.JSONObject

/** One facade for the existing app: no second model, queue, chat store or APK. */
class RoadmapRuntime(private val app: GreenApp) {
    val health by lazy { HealthMaintenance(app) }
    val actions by lazy { ActionRuntime(app, app.store, confirmRisk = { tool -> tool == "device.flashlight" }) }
    val events by lazy { EventRuntime.shared(app, app.store) }
    val knowledge by lazy { KnowledgeRuntime(app, app.store) }
    val learning by lazy { LearningRuntime(app, app.store).also { runtime ->
        runtime.incidentSink = { component,_,code,summary ->
            val dir=java.io.File(app.filesDir,"bug-reports").apply{mkdirs()}
            val report=JSONObject().put("component",component).put("code",code).put("summary",summary).put("timestamp",System.currentTimeMillis()).put("android_sdk",android.os.Build.VERSION.SDK_INT).put("device",android.os.Build.MODEL).put("app_version",app.packageManager.getPackageInfo(app.packageName,0).versionName).put("abi","armeabi-v7a").put("model_loaded",AgentController.state.value.stats.loadedModel!=null).put("note","Metadata only. No user prompts, source text, credentials or automatic DB changes.")
            val name=(component+"-"+code).replace(Regex("[^A-Za-z0-9_-]"),"_").take(120)
            java.io.File(dir,"$name.json").writeText(report.toString(2))
        }
        runtime.onUserInput = { health.onUserInput() }
        runtime.goldenPlanner = { input -> actions.refreshRules(); actions.planDryRun(input) }
        runtime.registeredPlanValidator = { tool, args -> ActionTools.validate(JSONObject().put("tool", tool).put("args", args)) }
        LearningActivityBridge.runtime = runtime
    } }
    fun connect() {
        actions.initializeLibrary()
        app.logger.onIncident = { component,code,summary -> learning.recordIncident(component,code,summary) }
        LearningActivityBridge.runtime = learning
        im.mrgreen.knowledge.KnowledgeActivity.runtime = knowledge
        ScreenAssistantBridge.answerProvider = ScreenAnswerProvider { prompt, _ ->
            // Screen context can contain P2 data. Keep it local even when cloud chat is enabled.
            AgentController.answerContext(app, prompt, localOnly = true, category = "screen")
        }
        ScreenAssistantBridge.actionConfirmer = ScreenActionConfirmer { action, done ->
            val intent = when (action.type) {
                ScreenAction.Type.CALL -> android.content.Intent(android.content.Intent.ACTION_DIAL, android.net.Uri.parse("tel:" + android.net.Uri.encode(action.value)))
                ScreenAction.Type.COPY -> { (app.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(android.content.ClipData.newPlainText("Extracted item", action.value)); done(); return@ScreenActionConfirmer }
                ScreenAction.Type.ALARM -> android.content.Intent(android.provider.AlarmClock.ACTION_SET_ALARM).apply {
                    val parts = action.value.trim().substringBefore(' ').split(':')
                    putExtra(android.provider.AlarmClock.EXTRA_HOUR, parts.first().toIntOrNull() ?: 0)
                    putExtra(android.provider.AlarmClock.EXTRA_MINUTES, parts.getOrNull(1)?.toIntOrNull() ?: 0)
                    putExtra(android.provider.AlarmClock.EXTRA_SKIP_UI, false)
                }
            }
            try { app.startActivity(intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)); done() }
            catch (_: Exception) { app.logger.write("WARN", "SCREEN", "Confirmed extracted action could not open the target app") }
        }
        if (events.isRunning()) events.start()
        val interrupted=java.io.File(app.filesDir,"bug-reports/native-interrupted.json")
        if(interrupted.isFile && !app.getSharedPreferences("native_journal",0).getBoolean("reported",false)) {
            learning.recordIncident("LLM","NATIVE_OPERATION_INTERRUPTED","The preceding process ended during a native operation. Cause is unknown; no DB fix was made. Inspect Android crash/logcat evidence.")
            app.getSharedPreferences("native_journal",0).edit().putBoolean("reported",true).apply()
        }
        health.start()
    }
    suspend fun execute(request: String, progress: (String) -> Unit): String? {
        learning.onUserInput()
        // Recompute deterministic facts before any saved or model-generated reply.
        actions.refreshRules()
        val plan = actions.plan(request)
        if (plan != null) {
            val result = actions.executePlan(plan, progress)
            val success = !result.startsWith("Stopped:") && !result.contains("Could not", true) && !result.contains("failed", true) && !result.contains("unverified",true) && !result.contains("not verified",true) && !result.contains("Pending",true) && !result.contains("Tell me",true) && !result.contains("Please specify",true)
            learning.recordEpisode(request.take(4000), plan, result.take(4000), success)
            return result
        }
        events.execute(request)?.let { return it }
        knowledge.execute(request)?.let { return it }
        if (request.trim().startsWith("{")) im.mrgreen.recipes.RecipeRuntime.execute(request)?.let { return it }
        // No model is invoked automatically here. Only approved routines are eligible.
        val old = learning.plannerGateway
        learning.plannerGateway = null
        val reused = try { learning.execute(request.take(4000)) } finally { learning.plannerGateway = old }
        if (reused != null) {
            val document = JSONObject(reused)
            if (document.optString("status") == "approved_skill") {
                val steps = document.getJSONArray("steps")
                val result = actions.executePlan(steps, progress)
                learning.recordEpisode(request.take(4000), steps, result.take(4000), !result.startsWith("Stopped:"))
                return result
            }
        }
        return null
    }
}
