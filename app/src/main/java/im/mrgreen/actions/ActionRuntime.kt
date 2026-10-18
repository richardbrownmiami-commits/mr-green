package im.mrgreen.actions

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.AlarmClock
import android.provider.Settings
import im.mrgreen.data.GreenStore
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.math.roundToInt

/** Schema published for orchestration and plan validation. No outbound/destructive operation is auto-approved. */
data class ToolSchema(val id: String, val description: String, val arguments: Map<String, String>, val risk: String = "low", val permission: String? = null)
object ActionTools {
    val registry: List<ToolSchema> = listOf(
        ToolSchema("notifications.read", "Read locally collected notification events; special notification access required", emptyMap()),
        ToolSchema("calls.missed", "Read locally collected missed-call events", emptyMap(), permission="android.permission.READ_CALL_LOG"),
        ToolSchema("media.play", "Find local music/video or open a YouTube search; never assume playback", mapOf("query" to "string", "video" to "boolean")),
        ToolSchema("clock.time", "Read local time", emptyMap()), ToolSchema("clock.date", "Read local date", emptyMap()),
        ToolSchema("date.add", "Calculate a date offset", mapOf("days" to "integer")),
        ToolSchema("math.calculate", "Evaluate arithmetic expression", mapOf("expression" to "string")),
        ToolSchema("alarm.set", "Open Android alarm creation UI", mapOf("time" to "HH:mm", "label" to "string")),
        ToolSchema("timer.set", "Open Android timer creation UI", mapOf("seconds" to "integer", "label" to "string")),
        ToolSchema("note.save", "Save a private note", mapOf("text" to "string")), ToolSchema("note.list", "List saved private notes", emptyMap()),
        ToolSchema("app.open", "Launch an installed app by label", mapOf("name" to "string")),
        ToolSchema("device.wifi", "Open Wi-Fi settings; user makes changes", emptyMap()),
        ToolSchema("device.bluetooth", "Open Bluetooth settings; user makes changes", emptyMap()),
        ToolSchema("device.brightness", "Open display settings", emptyMap()),
        ToolSchema("device.volume", "Open sound settings", emptyMap()),
        ToolSchema("device.battery", "Read battery level", emptyMap()),
        ToolSchema("device.airplane", "Open wireless settings; user makes changes", emptyMap()),
        ToolSchema("device.settings", "Open Android settings", emptyMap()),
        ToolSchema("device.location", "Open location settings", emptyMap()),
        ToolSchema("device.accessibility", "Open accessibility settings", emptyMap()),
        ToolSchema("device.notifications", "Open notification settings", emptyMap()),
        ToolSchema("device.storage", "Open storage settings", emptyMap()),
        ToolSchema("device.date_settings", "Open date and time settings", emptyMap()),
        ToolSchema("device.app_settings", "Open app settings", emptyMap()),
        ToolSchema("device.flashlight", "Turn flashlight on or off using CameraManager; CAMERA permission required", mapOf("enabled" to "boolean"), risk = "medium", permission = "android.permission.CAMERA")
    )
    private val byId = registry.associateBy { it.id }
    internal fun validate(step: JSONObject): Boolean {
        if(step.keys().asSequence().any { it !in setOf("tool","args","verify") } || (step.has("args") && step.opt("args") !is JSONObject)) return false
        val schema = byId[step.optString("tool")] ?: return false
        val args = step.optJSONObject("args") ?: JSONObject()
        if (args.keys().asSequence().any { it !in schema.arguments } || schema.arguments.keys.any { !args.has(it) }) return false
        if (args.toString().length > 4096 || schema.arguments.size > 16) return false
        return schema.arguments.all { (key, type) ->
            val value = args.opt(key)
            when (type) {
                "string", "HH:mm" -> value is String && value.isNotBlank() && value.length <= (if (key == "text") 2000 else 120) && (type != "HH:mm" || runCatching { java.time.LocalTime.parse(value) }.isSuccess)
                "integer" -> value is Number && value.toDouble() % 1.0 == 0.0 && value.toDouble() in -36500.0..86400.0
                "boolean" -> value is Boolean
                else -> false
            }
        }
    }
}

private data class Match(val intent: String, val tool: String, val priority: Int, val terms: List<String>, val negatives: List<String> = emptyList())
private val library = listOf(
 Match("notifications_read","notifications.read",105,listOf("read my notifications","read notifications","show notifications")),
 Match("missed_calls","calls.missed",105,listOf("missed call","missed calls")),
 Match("media_play","media.play",105,listOf("play music","play song","play video")),
 Match("date_add","date.add",110,listOf("45 days from today","in 45 days","45 days from now","date after 45 days")),
 Match("flashlight","device.flashlight",100,listOf("turn on flashlight","turn off flashlight","flashlight on","flashlight off","torch on","torch off"), listOf("do not turn on","dont turn on","don't turn on","not turn on")),
 Match("time","clock.time",100,listOf("what time","current time","time now","tell me the time","samay kya","kitne baje","time bata")),
 Match("date","clock.date",95,listOf("what date","today date","today's date","which day","aaj ki date","date today","what day is it")),
 Match("math","math.calculate",100,listOf("calculate","what is","solve","percent of","% of","plus","minus","multiply","divide","square root","add","subtract"),listOf("what is the time")),
 Match("alarm","alarm.set",90,listOf("set an alarm","set alarm","wake me","alarm for","alarm at","alarm lagao","remind me at")),
 Match("timer","timer.set",90,listOf("set a timer","timer for","start timer","countdown","timer lagao")),
 Match("notes","note.list",92,listOf("check notes","list notes","show notes","my notes")),
 Match("note","note.save",90,listOf("make a note","save a note","take a note","remember this","write a note","note this","yaad rakh","save note")),
 Match("app","app.open",80,listOf("open app","launch app","open youtube","open camera","open calculator","open settings"," kholo app","start app")),
 Match("wifi","device.wifi",90,listOf("wifi settings","wi-fi settings","turn on wifi","turn off wifi","enable wifi","disable wifi","wifi chalu","wifi band")),
 Match("bluetooth","device.bluetooth",90,listOf("bluetooth settings","turn on bluetooth","turn off bluetooth","enable bluetooth","disable bluetooth","bluetooth chalu")),
 Match("brightness","device.brightness",90,listOf("brightness settings","change brightness","screen brightness","display settings","brightness badhao")),
 Match("volume","device.volume",90,listOf("volume settings","sound settings","change volume","volume badhao","ringer settings")),
 Match("battery","device.battery",95,listOf("battery level","battery status","how much battery","battery kitni","check battery")),
 Match("airplane","device.airplane",90,listOf("airplane mode","flight mode","wireless settings","airplane chalu")),
 Match("settings","device.settings",80,listOf("open settings","phone settings","system settings","settings kholo")),
 Match("location","device.location",90,listOf("location settings","gps settings","location services","location chalu")),
 Match("accessibility","device.accessibility",90,listOf("accessibility settings","open accessibility","accessibility kholo")),
 Match("notifications","device.notifications",90,listOf("notification settings","notifications settings","app notifications")),
 Match("storage","device.storage",90,listOf("storage settings","phone storage","manage storage","storage space")),
 Match("date_settings","device.date_settings",90,listOf("date and time settings","time settings","set date and time","automatic time")),
 Match("app_settings","device.app_settings",85,listOf("application settings","installed apps","manage apps","manage application settings"))
)

/** Rules-first local runtime. Compound steps execute sequentially; state changes always remain user-mediated Android UI. */
class ActionRuntime(private val context: Context, private val store: GreenStore, private val today: () -> LocalDate = { LocalDate.now() }, private val confirmRisk: (String) -> Boolean = { false }) {
    fun plan(request: String): JSONArray? = planDryRun(request)

    /** Pure deterministic planning API; approved DB rule snapshots only change after refreshRules(). */
    fun planDryRun(request: String): JSONArray? {
        val rewritten = approvedRules.fold(request) { text, rule ->
            if(rule.first in setOf("synonym","slot_alias","trigger")) {
                val before=" "+normalize(text)+" "; val pattern=" "+normalize(rule.second)+" "
                if(before.contains(pattern)) before.replace(pattern," "+rule.third+" ").trim() else text
            } else text
        }
        val parts = splitCompound(rewritten)
        if (parts.isEmpty()) return null
        val result = JSONArray()
        for (part in parts) {
            val match = route(part) ?: return null
            val args = arguments(match.tool, part) ?: return null
            val step = JSONObject().put("tool", match.tool).put("args", args).put("verify", verifier(match.tool))
            if (!ActionTools.validate(step)) return null
            if (match.tool == "alarm.set" && part.contains("with note",true)) {
                val note=part.substringAfter("with note", "").trim().take(120)
                if(note.isNotBlank()) { result.put(JSONObject().put("tool","note.save").put("args",JSONObject().put("text",note)).put("verify","note.readback")); args.put("label",note) }
            }
            result.put(step)
        }
        return result
    }

    @Volatile private var approvedRules: List<Triple<String,String,String>> = emptyList()
    /** Explicit IO refresh; rejects regex semantics and accepts only literal token replacements. */
    fun refreshRules() {
        approvedRules = store.readableDatabase.rawQuery("SELECT kind,pattern,replacement FROM match_rules WHERE approved=1 ORDER BY id", null).use { c ->
            buildList { while (c.moveToNext()) { val k=c.getString(0); val p=c.getString(1); val r=c.getString(2)
                if (k in setOf("synonym","trigger","slot_alias","negative") && p.isNotBlank() && p.length <= 120 && r.length <= 120 && p.all { it.isLetterOrDigit() || it.isWhitespace() || it in "'-" }) add(Triple(k,p,r))
            } }
        }
    }

    /** Seed data only on the owner queue; pure plan tests need no native SQLite. */
    fun initializeLibrary() {
        val db=store.writableDatabase
        db.beginTransaction()
        try {
            library.forEach { match -> match.terms.forEachIndexed { n, phrase ->
                db.execSQL("INSERT OR IGNORE INTO library_templates(id,intent,phrase,priority,negative_phrases) VALUES(?,?,?,?,?)",arrayOf("starter.${match.intent}.$n",match.tool,phrase,match.priority,JSONArray(match.negatives).toString()))
            } }
            val base=listOf("what time is it","what date is today","calculate 2 plus 3","set alarm 7 am","set a timer for 5 minutes","make a note: buy milk and eggs","open calculator","wifi settings","bluetooth settings","brightness settings","volume settings","battery level","airplane mode","phone settings","location settings","accessibility settings","notification settings","storage settings","date and time settings","manage apps")
            var count=0
            val wrappers=listOf("%s","please %s","could you %s","can you %s","%s please","hey %s","%s for me","kindly %s","I would like to %s","now %s")
            base.forEach { phrase -> wrappers.forEach { wrapper ->
                val request=wrapper.format(phrase); val planned=planDryRun(request)
                if(planned!=null) db.execSQL("INSERT OR IGNORE INTO golden_tests(id,request,expected_plan) VALUES(?,?,?)",arrayOf("starter.${count++}",request,planned.toString()))
            } }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        refreshRules()
    }

    suspend fun execute(request: String, progress: (String)->Unit = {}): String? {
        val steps = plan(request) ?: return null
        return executePlan(steps, progress)
    }

    suspend fun executePlan(steps: JSONArray, progress: (String)->Unit = {}): String {
        if (steps.length() == 0) return "No steps to run."
        if (steps.length() > 16) return "Stopped: plans are limited to 16 steps."
        for (i in 0 until steps.length()) { val candidate=steps.optJSONObject(i); if(candidate==null || !ActionTools.validate(candidate)) return "Stopped: unknown tool or invalid arguments in step ${i + 1}." }
        val outcomes = mutableListOf<String>()
        for (i in 0 until steps.length()) {
            val step = steps.optJSONObject(i) ?: return "Stopped: step ${i + 1} is not a valid object."
            if (!ActionTools.validate(step)) return "Stopped: unknown tool or invalid arguments in step ${i + 1}."
            val tool = step.getString("tool"); val args = step.optJSONObject("args") ?: JSONObject()
            progress("Running ${i + 1}/${steps.length()}: $tool")
            if (ActionTools.registry.firstOrNull { it.id == tool }?.risk != "low" && !confirmRisk(tool)) { progress("Pending confirmation ${i + 1}/${steps.length()}: $tool"); return "Pending confirmation: $tool was not run." }
            val result = try { runTool(tool, args) } catch (e: Exception) {
                store.log("WARN", "TOOL", "E_TOOL_FAILED", "$tool failed: ${e.javaClass.simpleName}", "foreground")
                ToolResult(false, "Could not complete $tool: ${e.message ?: "Android rejected the request"}")
            }
            if(!result.ok) {
                val app=context.applicationContext as? im.mrgreen.GreenApp
                if(app!=null) app.logger.write("WARN","TOOL","Registered action failed; tool=$tool",im.mrgreen.core.ErrorCode.E_TOOL_FAILED)
                else store.log("WARN","TOOL","E_TOOL_FAILED","Registered action failed; tool=$tool","foreground")
            }
            if (!result.ok) { progress("Failed ${i + 1}/${steps.length()}: $tool"); return "${outcomes.joinToString("; ").let { if(it.isBlank()) "" else "$it. " }}${result.message}" }
            progress("Done ${i + 1}/${steps.length()}: $tool")
            outcomes += result.message
        }
        return outcomes.joinToString(" ")
    }

    private data class ToolResult(val ok:Boolean, val message:String)
    private suspend fun runTool(id:String,a:JSONObject):ToolResult = when(id) {
        "notifications.read", "calls.missed", "media.play" -> {
            val events=im.mrgreen.events.EventRuntime.shared(context,store)
            val query=when(id){"notifications.read"->"read my notifications";"calls.missed"->"missed calls";else->"play ${if(a.getBoolean("video")) "video" else "music"} ${a.getString("query")}"}
            val message=events.execute(query) ?: "Requested event tool is unavailable."
            ToolResult(!message.startsWith("Tell me") && !message.contains("needs your",true) && !message.contains("access is off",true),message)
        }
        "clock.time" -> ToolResult(true, "It is ${LocalTime.now().format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))}.")
        "clock.date" -> ToolResult(true, "Today is ${today().format(DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL))}.")
        "date.add" -> ToolResult(true, "${a.getInt("days")} days from today is ${today().plusDays(a.getInt("days").toLong()).format(DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL))}.")
        "device.flashlight" -> setFlashlight(a.getBoolean("enabled"))
        "math.calculate" -> try { ToolResult(true, "${a.getString("expression")} = ${Arithmetic.evaluate(a.getString("expression"))}") } catch (_:Exception) { ToolResult(false,"I couldn't evaluate that arithmetic expression.") }
        "note.save" -> saveNote(a.getString("text"))
        "note.list" -> listNotes()
        "alarm.set" -> {
            val t=LocalTime.parse(a.getString("time")); val i=Intent(AlarmClock.ACTION_SET_ALARM).putExtra(AlarmClock.EXTRA_HOUR,t.hour).putExtra(AlarmClock.EXTRA_MINUTES,t.minute).putExtra(AlarmClock.EXTRA_MESSAGE,a.optString("label","Mr Green alarm")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if(i.resolveActivity(context.packageManager)==null) ToolResult(false,"No alarm app is available.") else { context.startActivity(i); ToolResult(true,"Pending external verification: opened alarm setup for ${t.format(DateTimeFormatter.ofPattern("h:mm a",Locale.getDefault()))}; confirm/save it in the clock app and verify there before relying on it.") }
        }
        "timer.set" -> { val seconds=a.getInt("seconds"); if(seconds<1 || seconds>86400) ToolResult(false,"Timer must be between 1 second and 24 hours.") else { val i=Intent(AlarmClock.ACTION_SET_TIMER).putExtra(AlarmClock.EXTRA_LENGTH,seconds).putExtra(AlarmClock.EXTRA_MESSAGE,a.optString("label","Mr Green timer")).putExtra(AlarmClock.EXTRA_SKIP_UI,false).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); if(i.resolveActivity(context.packageManager)==null) ToolResult(false,"No timer app is available.") else { context.startActivity(i); ToolResult(true,"Opened timer setup for ${seconds}s; please confirm it in the clock app.") } } }
        "app.open" -> openApp(a.getString("name"))
        "device.battery" -> { val b=context.registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED)); val level=b?.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL,-1) ?: -1; val scale=b?.getIntExtra(android.os.BatteryManager.EXTRA_SCALE,100) ?: 100; if(level<0) ToolResult(false,"Battery status is unavailable.") else ToolResult(true,"Battery is ${(level*100f/scale).roundToInt()}%.") }
        else -> openSettings(id)
    }
    private fun listNotes(): ToolResult = try {
        val notes=store.readableDatabase.rawQuery("SELECT value FROM facts WHERE tags='note' ORDER BY id DESC LIMIT 100",null).use { c -> buildList { while(c.moveToNext()) add(c.getString(0)) } }
        ToolResult(true, if(notes.isEmpty()) "No saved notes." else "Saved notes: " + notes.joinToString("; "))
    } catch(e:Exception) { ToolResult(false,"Could not list notes: ${e.message}") }
    private fun saveNote(text:String):ToolResult {
        val clean=text.trim(); if(clean.length > 2000) return ToolResult(false,"Note is too long (maximum 2000 characters).")
        if(clean.isBlank()) return ToolResult(false,"What should I save in the note?")
        return try {
            val dir=java.io.File(context.filesDir,"notes").apply { mkdirs() }
            val file=java.io.File(dir,"note_${System.currentTimeMillis()}_${java.util.UUID.randomUUID()}.txt")
            val tmp=java.io.File(dir,file.name+".tmp")
            val db=store.writableDatabase; db.beginTransaction()
            try {
                tmp.writeText(clean, Charsets.UTF_8)
                if(!tmp.renameTo(file)) error("atomic note-file rename failed")
                val now=System.currentTimeMillis(); val key="note_${file.nameWithoutExtension}"
                db.execSQL("INSERT INTO facts(key,value,tags,importance,source,last_used) VALUES(?,?,?,0.5,?,?)", arrayOf(key,clean,"note", "action-runtime", now))
                val verified=db.rawQuery("SELECT value FROM facts WHERE key=?",arrayOf(key)).use { it.moveToFirst() && it.getString(0)==clean }
                val fileVerified=file.readText(Charsets.UTF_8)==clean
                if(!verified || !fileVerified) error("note readback failed")
                db.setTransactionSuccessful()
                ToolResult(true,"Saved note: $clean")
            } catch(e:Exception) { file.delete(); tmp.delete(); throw e } finally { db.endTransaction() }
        } catch(e:Exception) { ToolResult(false,"Could not save the note: ${e.message}") }
    }
    private fun openApp(name:String):ToolResult {
        val pm=context.packageManager
        val candidates=pm.getInstalledApplications(PackageManager.GET_META_DATA).map { it to pm.getApplicationLabel(it).toString() }
        val exact=candidates.filter{it.second.equals(name.trim(),true)}
        val partial=candidates.filter{it.second.contains(name.trim(),true)}
        val found=exact.singleOrNull() ?: knownApp(name)?.let { pkg -> candidates.firstOrNull { it.first.packageName==pkg } } ?: partial.singleOrNull()
        if(found==null && (exact.size>1 || partial.size>1)) return ToolResult(false,"Several apps match that name. Please specify the full app name.")
        val launch=found?.let { pm.getLaunchIntentForPackage(it.first.packageName) } ?: return ToolResult(false,"I couldn't find an installed app matching “$name”.")
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); context.startActivity(launch); return ToolResult(true,"Opened ${found.second}.")
    }
    private fun knownApp(s:String)=when(s.trim().lowercase(Locale.ROOT)){"youtube"->"com.google.android.youtube";"camera"->"com.android.camera";"phone"->"com.android.dialer";"chrome"->"com.android.chrome";else->null}
    private fun setFlashlight(enabled:Boolean):ToolResult {
        if (android.os.Build.VERSION.SDK_INT < 23) return ToolResult(false,"Flashlight API requires Android 6 or later.")
        if (context.checkSelfPermission(android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return ToolResult(false,"Flashlight not changed: CAMERA permission is not granted.")
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as? android.hardware.camera2.CameraManager ?: return ToolResult(false,"Camera service unavailable.")
        return try { val id=manager.cameraIdList.firstOrNull { manager.getCameraCharacteristics(it).get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE)==true } ?: return ToolResult(false,"No camera flash is available."); manager.setTorchMode(id,enabled); ToolResult(true,"Flashlight ${if(enabled) "turned on" else "turned off"} via Android CameraManager.") } catch(e:SecurityException) { ToolResult(false,"Flashlight permission denied by Android.") }
    }
    private fun openSettings(id:String):ToolResult {
        val action=when(id){"device.wifi"->Settings.ACTION_WIFI_SETTINGS;"device.bluetooth"->Settings.ACTION_BLUETOOTH_SETTINGS;"device.brightness"->Settings.ACTION_DISPLAY_SETTINGS;"device.volume"->Settings.ACTION_SOUND_SETTINGS;"device.airplane"->Settings.ACTION_AIRPLANE_MODE_SETTINGS;"device.location"->Settings.ACTION_LOCATION_SOURCE_SETTINGS;"device.accessibility"->Settings.ACTION_ACCESSIBILITY_SETTINGS;"device.notifications"->Settings.ACTION_APP_NOTIFICATION_SETTINGS;"device.storage"->Settings.ACTION_INTERNAL_STORAGE_SETTINGS;"device.date_settings"->Settings.ACTION_DATE_SETTINGS;"device.app_settings"->Settings.ACTION_APPLICATION_SETTINGS;else->Settings.ACTION_SETTINGS}
        val i=Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if(i.resolveActivity(context.packageManager)==null) return ToolResult(false,"This Android settings screen isn't available on this device.")
        context.startActivity(i); return ToolResult(true,"Opened the relevant Android settings. No setting was changed automatically; review and change it there.")
    }

    private fun route(raw:String):Match? {
        if(raw.length > 4096) return null
        val s=normalize(raw)
        if(Regex("(?i)\\b(?:date|today|from now|from today)\\b").containsMatchIn(s) && Regex("\\b\\d+ days?\\b").containsMatchIn(s)) return library.first { it.tool=="date.add" }
        return library.asSequence().filter { m -> m.terms.any { normalize(it) in s } && m.negatives.none { normalize(it) in s } && approvedRules.none { it.first=="negative" && normalize(it.second) in s && (it.third==m.tool || it.third==m.intent) } }
            .filter { arguments(it.tool,raw)!=null }.sortedByDescending { it.priority }.firstOrNull()
    }
    private fun arguments(tool:String,raw:String):JSONObject? {
        val s=raw.trim()
        return when(tool){
            "note.list","clock.time","clock.date","device.wifi","device.bluetooth","device.brightness","device.volume","device.battery","device.airplane","device.settings","device.location","device.accessibility","device.notifications","device.storage","device.date_settings","device.app_settings" -> JSONObject()
            "math.calculate" -> { val expr=extractMath(s) ?: return null; JSONObject().put("expression",expr) }
            "date.add" -> { val n=Regex("(?i)(\\d+)\\s+days?").find(s)?.groupValues?.get(1)?.toIntOrNull() ?: return null; if(n !in 1..36500) return null; JSONObject().put("days",n) }
            "device.flashlight" -> { val on = !Regex("(?i)\\b(off|turn off|torch off)\\b").containsMatchIn(s); JSONObject().put("enabled",on) }
            "media.play" -> JSONObject().put("query",raw.replace(Regex("(?i)\\b(play|music|song|video|please|a|the)\\b")," ").trim().ifBlank { "music" }).put("video",raw.contains("video",true))
            "alarm.set" -> { val t=parseTime(s) ?: return null; JSONObject().put("time",t).put("label", s.substringAfter("alarm", "").substringAfter("for", "").trim().take(60).ifBlank { "Mr Green alarm" }) }
            "timer.set" -> { val sec=parseDuration(s) ?: return null; JSONObject().put("seconds",sec).put("label","Mr Green timer") }
            "note.save" -> { val text=s.replace(Regex("(?i)^(please\\s+)?(make|save|take|write|remember)\\s+(a\\s+)?(note|this)[: ]*"),"").trim(); if(text.isBlank()) return null; JSONObject().put("text",text) }
            "app.open" -> { val n=s.replace(Regex("(?i)^(please\\s+)?(open|launch|start)\\s+(the\\s+)?(app\\s+)?"),"").trim(); if(n.isBlank()) return null; JSONObject().put("name",n) }
            else -> null
        }
    }
    private fun parseTime(s:String):String? {
        val m=Regex("(?i)(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)?").find(s) ?: return null
        var h=m.groupValues[1].toIntOrNull() ?: return null; val min=m.groupValues[2].ifBlank { "0" }.toIntOrNull() ?: return null
        if(min !in 0..59) return null; val ap=m.groupValues[3].lowercase(); if(ap=="pm"&&h<12) h+=12 else if(ap=="am"&&h==12) h=0
        if(h !in 0..23) return null
        if(ap.isEmpty() && h in 1..12 && Regex("(?i)\\b(evening|tonight|pm)\\b").containsMatchIn(s)) h=if(h==12)12 else h+12
        return "%02d:%02d".format(h,min)
    }
    private fun parseDuration(s:String):Int? {
        val m=Regex("(?i)(\\d+)\\s*(seconds?|secs?|minutes?|mins?|hours?|hrs?)").find(s) ?: return null
        val n=m.groupValues[1].toLongOrNull() ?: return null; val unit=m.groupValues[2].lowercase()
        val multiplier=if(unit.startsWith("h"))3600 else if(unit.startsWith("m"))60 else 1
        return (n*multiplier).takeIf { it in 1..86400 }?.toInt()
    }
    private fun extractMath(s:String):String? {
        val percent=Regex("(?i)(\\d+(?:\\.\\d+)?)\\s*%\\s*(?:of)\\s*(\\d+(?:\\.\\d+)?)").find(s)
        if(percent!=null) return "(${percent.groupValues[1]} / 100) * ${percent.groupValues[2]}"
        val candidate=s.replace(Regex("(?i)^(please\\s+)?(calculate|solve|what is|what's|compute)\\s+"),"").replace(Regex("(?i)\\s+(please|for me)$"),"").trim()
        val normalized=candidate.replace(Regex("(?i)\\bplus\\b"),"+").replace(Regex("(?i)\\bminus\\b"),"-").replace(Regex("(?i)\\b(times|multiplied by)\\b"),"*").replace(Regex("(?i)\\b(divided by|over)\\b"),"/").replace("×","*").replace("÷","/")
        return normalized.takeIf { it.matches(Regex("[0-9+*/().%\\- ]{1,100}")) && it.any(Char::isDigit) && Regex("[+*/%\\-]").containsMatchIn(it) }
    }
    private fun verifier(tool:String)=when(tool){"note.save"->"note.readback";"alarm.set"->"alarm.user_confirmation";"timer.set"->"timer.user_confirmation";"app.open"->"app.launch_requested";"math.calculate"->"math.result";else->"tool.result"}
    private fun splitCompound(s:String):List<String> = s.split(Regex("(?i)\\s+(?:and then|then|after that|also)\\s+|\\s+and\\s+(?=(?:calculate|compute|solve|open|launch|start|set|turn on|turn off|enable|disable|check|what time|what date|tell me|save a note|take a note|write a note|remember this))")).map{it.trim()}.filter{it.isNotEmpty()}
    private fun normalize(s:String)=s.lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}:.'+*/% -]")," ").replace(Regex("\\s+")," ").trim()
}

private object Arithmetic {
    fun evaluate(input:String):Double { val p=Parser(input); val x=p.expr(); if(!p.end()) error("trailing"); if(!x.isFinite()) error("not finite"); return x }
    private class Parser(private val s:String){var i=0; fun end()=i>=s.length
        fun ws(){while(i<s.length&&s[i].isWhitespace())i++}
        fun expr():Double {var x=term(); while(true){ws(); if(take('+'))x+=term() else if(take('-'))x-=term() else return x}}
        fun term():Double {var x=factor(); while(true){ws(); if(take('*'))x*=factor() else if(take('/')){val d=factor();if(d==0.0)error("zero");x/=d} else if(take('%')){val d=factor();if(d==0.0)error("zero");x%=d} else return x}}
        fun factor():Double {ws(); if(take('+'))return factor(); if(take('-'))return -factor(); if(take('(')){val n=expr();ws();if(!take(')'))error("paren");return n}; val start=i; while(i<s.length&&(s[i].isDigit()||s[i]=='.'))i++; if(start==i)error("number"); return s.substring(start,i).toDouble()}
        fun take(c:Char):Boolean {ws();if(i<s.length&&s[i]==c){i++;return true};return false}
    }
}
