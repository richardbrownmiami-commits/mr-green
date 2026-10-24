package im.mrgreen.core

import im.mrgreen.GreenApp
import im.mrgreen.actions.ActionRuntime
import im.mrgreen.actions.ActionTools
import im.mrgreen.events.EventRuntime
import im.mrgreen.knowledge.KnowledgeRuntime
import im.mrgreen.learning.LearningRuntime
import org.json.JSONObject

/** Backend runtime facade: one model, serialized queue and local data store. */
class RoadmapRuntime(private val app: GreenApp) {
    private var connected = false
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
    } }
    @Synchronized fun connect() {
        if (connected) return
        actions.initializeLibrary()
        // GreenStore.log already persists/counts this incident. Export metadata only here.
        app.logger.onIncident = { component,code,summary -> learning.incidentSink?.invoke(component,"",code,summary) }
        if (events.isRunning()) events.start()
        val interrupted=java.io.File(app.filesDir,"bug-reports/native-interrupted.json")
        if(interrupted.isFile && !app.getSharedPreferences("native_journal",0).getBoolean("reported",false)) {
            learning.recordIncident("LLM","NATIVE_OPERATION_INTERRUPTED","The preceding process ended during a native operation. Cause is unknown; no DB fix was made. Inspect Android crash/logcat evidence.")
            app.getSharedPreferences("native_journal",0).edit().putBoolean("reported",true).apply()
        }
        health.start()
        connected = true
    }
    suspend fun execute(request: String, isCancelled: () -> Boolean = { false }, progress: (String) -> Unit): String? {
        learning.onUserInput()
        // Recompute deterministic facts before any saved or model-generated reply.
        actions.jobStatus(request)?.let { return it }
        actions.refreshRules()
        val plan = actions.plan(request)
        if (plan != null) {
            val result = actions.executePlanResult(plan, progress, isCancelled)
            if (result.verified || result.outcome == im.mrgreen.actions.PlanOutcome.FAILED)
                learning.recordEpisode(request.take(4000), plan, result.message.take(4000), result.verified)
            return result.message
        }
        actions.clarification(request)?.let { return it }
        events.execute(request)?.let { return it }
        knowledge.execute(request)?.let { return it }
        if (request.trim().startsWith("{")) im.mrgreen.recipes.RecipeRuntime.execute(request)?.let { return it }
        // No model is invoked automatically here. Only approved routines are eligible.
        val reused = learning.execute(request.take(4000))
        if (reused != null) {
            val document = JSONObject(reused)
            if (document.optString("status") == "approved_skill") {
                val steps = document.getJSONArray("steps")
                val result = actions.executePlanResult(steps, progress, isCancelled)
                if (result.verified || result.outcome == im.mrgreen.actions.PlanOutcome.FAILED)
                    learning.recordEpisode(request.take(4000), steps, result.message.take(4000), result.verified)
                return result.message
            }
        }
        return null
    }
}
