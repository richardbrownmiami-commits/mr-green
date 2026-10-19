package im.mrgreen.learning

import android.content.ContentValues
import android.content.Context
import im.mrgreen.data.GreenStore
import im.mrgreen.core.FactMaintenance
import io.requery.android.database.sqlite.SQLiteDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.core.os.CancellationSignal
import android.os.SystemClock
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID

data class LearningItem(val id: String, val name: String, val triggers: JSONArray, val steps: JSONArray, val state: String)
data class DoctorProposal(val id: Long, val operations: JSONArray, val state: String)

class LearningRuntime(context: Context, private val store: GreenStore) {
    private val app = context.applicationContext
    private val db get() = store.writableDatabase
    @Volatile var onUserInput: (() -> Unit)? = null
    private val toolValidator: (String, JSONObject) -> Boolean = { tool, args ->
        // Conservative registry bridge: only tools used by the core declarative runtime are accepted;
        // parent may supply the app's schema-aware validator before planner use.
        registeredPlanValidator?.invoke(tool, args) ?: (tool in setOf("time.now", "math.eval", "device.info", "memory.recall", "tasks.list"))
    }
    @Volatile var registeredPlanValidator: ((String, JSONObject) -> Boolean)? = null
    /** Pure parent Router/rule-planner callback, no AI/tools; required for Doctor validation. */
    @Volatile var goldenPlanner: ((String) -> JSONArray?)? = null
    @Volatile var goldenIncidentRequest: String? = null
    @Volatile var goldenExpectedPlan: JSONArray? = null
    @Volatile var incidentSink: ((String, String, String, String) -> Unit)? = null

    init { db.execSQL("CREATE TABLE IF NOT EXISTS doctor_validation(proposal_id INTEGER PRIMARY KEY,request TEXT NOT NULL,expected_plan TEXT NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS learning_episode(id INTEGER PRIMARY KEY AUTOINCREMENT, request TEXT NOT NULL, steps TEXT NOT NULL, result TEXT NOT NULL, success INTEGER NOT NULL, created INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS learning_inbox(id TEXT PRIMARY KEY, name TEXT NOT NULL, triggers TEXT NOT NULL, steps TEXT NOT NULL, state TEXT NOT NULL, note TEXT NOT NULL DEFAULT '', created INTEGER NOT NULL)") }

    fun onUserInput() { onUserInput?.invoke() }

    /** Reuse only already-approved local routines. New proposals are drafted explicitly
     * through AgentController's local-only queue and reviewed in RoadmapActivity.
     * No injectable remote planner receives an ordinary chat request. */
    suspend fun execute(request: String): String? = withContext(Dispatchers.IO) {
        require(request.isNotBlank() && request.length <= 4000)
        onUserInput()
        reusable(request)?.toString()
    }

    fun recordEpisode(request: String, steps: JSONArray, result: String, success: Boolean) {
        require(request.length <= 4000 && steps.length() <= 20 && result.length <= 4000)
        db.insertOrThrow("learning_episode", null, ContentValues().apply { put("request", request.take(4000)); put("steps", steps.toString()); put("result", result.take(4000)); put("success", if(success) 1 else 0); put("created", System.currentTimeMillis()) })
        if (!success) db.execSQL("UPDATE skills SET failures=failures+1, status=CASE WHEN failures+1>=3 THEN 'needs_repair' ELSE status END WHERE status='approved' AND steps_json=?", arrayOf(steps.toString()))
        else {
            db.execSQL("UPDATE skills SET successes=successes+1, failures=0 WHERE status='approved' AND steps_json=?", arrayOf(steps.toString()))
            val count = db.rawQuery("SELECT COUNT(*) FROM learning_episode WHERE success=1 AND steps=?", arrayOf(steps.toString())).use { it.moveToFirst(); it.getInt(0) }
            if (count >= 3) proposeSkill(request, steps)
        }
    }
    private fun proposeSkill(request: String, steps: JSONArray) {
        val normalized = request.trim().take(120)
        val id = UUID.nameUUIDFromBytes((normalized + steps).toByteArray()).toString()
        val triggers = JSONArray().put(normalized)
        db.insertWithOnConflict("learning_inbox", null, ContentValues().apply { put("id",id); put("name",normalized.take(60)); put("triggers",triggers.toString()); put("steps",steps.toString()); put("state","pending"); put("created",System.currentTimeMillis()) }, SQLiteDatabase.CONFLICT_IGNORE)
    }
    private fun reusable(request: String): JSONObject? {
        val normalized = request.trim().lowercase()
        return db.rawQuery("SELECT steps_json,triggers FROM skills WHERE status='approved'", null).use { c ->
            while (c.moveToNext()) { val triggers=JSONArray(c.getString(1)); if ((0 until triggers.length()).any { triggers.optString(it).trim().lowercase()==normalized }) {
                val steps=JSONArray(c.getString(0)); for (i in 0 until steps.length()) { val s=steps.optJSONObject(i) ?: return@use null; if (!toolValidator(s.optString("tool"),s.optJSONObject("args")?:JSONObject())) return@use null }
                return@use JSONObject().put("status","approved_skill").put("steps",steps).put("executed",false)
            } }; null
        }
    }
    fun inbox(): List<LearningItem> = db.rawQuery("SELECT id,name,triggers,steps,state FROM learning_inbox WHERE state='pending' ORDER BY created",null).use { c -> buildList { while(c.moveToNext()) add(LearningItem(c.getString(0),c.getString(1),JSONArray(c.getString(2)),JSONArray(c.getString(3)),c.getString(4))) } }
    fun approveSkill(id:String, editedTriggers:JSONArray?=null, editedSteps:JSONArray?=null): Boolean {
        val item=db.rawQuery("SELECT name,triggers,steps FROM learning_inbox WHERE id=? AND state='pending'",arrayOf(id)).use { if(it.moveToFirst()) Triple(it.getString(0),it.getString(1),it.getString(2)) else null } ?: return false
        val triggers=editedTriggers?:JSONArray(item.second); val steps=editedSteps?:JSONArray(item.third)
        if (!validatePlan(steps) || triggers.length() !in 1..20 || (0 until triggers.length()).any { triggers.optString(it).isBlank() || triggers.optString(it).length>160 }) return false
        db.beginTransaction(); try { db.insertOrThrow("skills",null,ContentValues().apply { put("id",id); put("triggers",triggers.toString()); put("slots","[]"); put("steps_json",steps.toString()); put("status","approved"); put("origin","learning") }); db.execSQL("UPDATE learning_inbox SET state='approved',triggers=?,steps=? WHERE id=?",arrayOf(triggers.toString(),steps.toString(),id)); db.setTransactionSuccessful() } finally { db.endTransaction() }; return true
    }
    fun rejectSkill(id:String): Boolean = db.update("learning_inbox",ContentValues().apply{put("state","rejected")},"id=? AND state='pending'",arrayOf(id))==1
    private fun validatePlan(steps:JSONArray):Boolean = steps.length() in 1..20 && (0 until steps.length()).all { val s=steps.optJSONObject(it)?:return@all false; s.keys().asSequence().all { k->k in setOf("tool","args","verify") } && toolValidator(s.optString("tool"),s.optJSONObject("args")?:JSONObject()) }

    /** Run candidate operations transactionally, always roll back, then require incident improvement and exact golden preservation. */
    private fun goldenCheck(ops:JSONArray):Boolean {
        val planner=goldenPlanner ?: return false
        val incident=goldenIncidentRequest ?: return false
        val expected=goldenExpectedPlan ?: return false
        val prior=try{planner(incident)}catch(_:Exception){null}
        val golden=db.rawQuery("SELECT request,expected_plan FROM golden_tests LIMIT 200",null).use{c->buildList{while(c.moveToNext())add(c.getString(0) to c.getString(1))}}
        if(golden.isEmpty()) return false
        val baseline=golden.map{try{planner(it.first)?.toString()}catch(_:Exception){null}}
        if(baseline.any{it==null} || baseline.indices.any{baseline[it]!=golden[it].second}) return false
        var valid=false
        db.beginTransaction()
        try {
            for(i in 0 until ops.length()) applyOperation(ops.getJSONObject(i))
            val after=try{planner(incident)}catch(_:Exception){null}
            val improved=after!=null && after.toString()==expected.toString() && after.toString()!=prior?.toString()
            val preserved=golden.indices.all { i -> try { planner(golden[i].first)?.toString()==baseline[i] && baseline[i]==golden[i].second } catch(_:Exception){false} }
            valid=improved && preserved
            // Never mark transaction successful: simulation is always rolled back.
        } catch (_:Exception) { valid=false } finally { db.endTransaction() }
        return valid
    }

    /** Typed, bounded Doctor proposals. Safe by default: no operation is applied until explicit approval. */
    fun proposeDoctor(ops:JSONArray):Long? {
        if(ops.length() !in 1..10 || (0 until ops.length()).any{!validDoctorOp(ops.optJSONObject(it)?:return null)}) return null
        if (!goldenCheck(ops)) return null
        val id=db.insertOrThrow("doctor_changes",null,ContentValues().apply{put("before_json","[]");put("after_json",ops.toString());put("status","proposed");put("created_at",System.currentTimeMillis())})
        db.execSQL("INSERT INTO doctor_validation(proposal_id,request,expected_plan) VALUES(?,?,?)",arrayOf(id,goldenIncidentRequest!!,goldenExpectedPlan!!.toString()))
        return id
    }
    fun doctorProposals():List<DoctorProposal> = db.rawQuery("SELECT id,after_json,status FROM doctor_changes WHERE status IN ('proposed','applied') ORDER BY id DESC LIMIT 30",null).use{c->buildList{while(c.moveToNext())add(DoctorProposal(c.getLong(0),JSONArray(c.getString(1)),c.getString(2)))} }
    private fun validDoctorOp(o:JSONObject):Boolean {
        if(o.length()>5) return false
        return when(o.optString("op")) {
            "fact.add" -> o.optString("key").length in 1..120 && o.optString("value").length<=1000 && o.keys().asSequence().all{it in setOf("op","key","value","tags","importance")}
            "fact.update","fact.retag","fact.delete","memory.importance.set" -> o.optLong("id")>0 && o.keys().asSequence().all{it in setOf("op","id","value","tags","importance")}
            "match_rule.add" -> o.optString("kind") in setOf("synonym","trigger","slot_alias","negative") && safePattern(o.optString("pattern")) && o.optString("replacement").length<=120 && o.keys().asSequence().all{it in setOf("op","kind","pattern","replacement")}
            "skill.set_status" -> o.optString("id").length in 1..80 && o.optString("status") in setOf("needs_repair","disabled")
            "query_recipe.add" -> o.optString("name").length in 1..80 && validSelect(o.optString("sql"))
            "report.bug" -> o.optString("summary").length in 1..1000 && o.keys().asSequence().all{it in setOf("op","summary","component","error_code")}
            "index.rebuild" -> o.optString("table") in setOf("facts","tasks","messages","rag_docs","rag_chunks") && o.keys().asSequence().all{it in setOf("op","table")}
            "fact.merge" -> false
            "template.add" -> o.optString("intent").length in 1..80 && o.optString("phrase").length in 1..240 && o.keys().asSequence().all{it in setOf("op","intent","phrase","priority","negative_phrases")}
            "skill.add_trigger" -> o.optString("id").length in 1..80 && o.optString("trigger").length in 1..160 && o.keys().asSequence().all{it in setOf("op","id","trigger")}
            else -> false
        }
    }
    private fun safePattern(p:String)=p.length in 1..120 && p.none{it in "\\[]{}()*+?|^$"} && p.none{it.isISOControl()}
    fun validSelect(sql:String):Boolean {
        if(sql.length !in 8..2000 || sql.contains(';') || sql.contains("--") || sql.contains("/*") || sql.contains("*/")) return false
        val s=sql.trim().lowercase().replace(Regex("\\s+")," ")
        if(!s.startsWith("select ") || Regex("\\b(union|intersect|except|with|exists|insert|update|delete|drop|alter|create|replace|attach|detach|pragma|vacuum|analyze|load_extension|into)\\b").containsMatchIn(s)) return false
        val tables=Regex("\\b(from|join)\\s+([a-z_][a-z0-9_]*)").findAll(s).map{it.groupValues[2]}.toList()
        val allowed=setOf("facts","tasks","episodes","skills","messages","library_templates","incidents","logs","rag_docs","rag_chunks","match_rules")
        if(tables.isEmpty() || tables.any{it !in allowed} || Regex("\\b(from|join)\\s*\\(").containsMatchIn(s)) return false
        val fromClause=s.substringAfter(" from ").substringBefore(" where ").substringBefore(" group by ").substringBefore(" order by ").substringBefore(" limit ")
        if(fromClause.contains(',') || s.any { it in "`[]\"" }) return false
        val funcs=Regex("([a-z_][a-z0-9_]*)\\s*\\(").findAll(s).map{it.groupValues[1]}.toSet()
        if(funcs.any{it !in setOf("count","min","max","avg","sum","lower","upper","length","coalesce")}) return false
        return Regex("\\bselect\\b").findAll(s).count()==1
    }
    /** Executes vetted read-only SQL with a hard row limit on a read-only connection. */
    fun runReadOnlyQuery(sql:String):List<String> {
        require(validSelect(sql)); val safe=sql.trim().removeSuffix(";").replace(Regex("\\s+")," ")
        val bounded=if(Regex("\\blimit\\b",RegexOption.IGNORE_CASE).containsMatchIn(safe)) safe.replace(Regex("\\blimit\\s+\\d+",RegexOption.IGNORE_CASE),"LIMIT 50") else "$safe LIMIT 50"
        val ro=SQLiteDatabase.openDatabase(app.getDatabasePath("mr-green.db").absolutePath,null,SQLiteDatabase.OPEN_READONLY)
        return try { ro.execSQL("PRAGMA query_only=ON"); val signal=CancellationSignal(); val deadline=SystemClock.elapsedRealtime()+2000; val timer=java.util.Timer(true); timer.schedule(object:java.util.TimerTask(){override fun run(){signal.cancel()}},2000); try { ro.rawQuery(bounded,null,signal).use{c-> val out=mutableListOf<String>(); while(c.moveToNext()&&out.size<50){if(SystemClock.elapsedRealtime()>deadline) throw java.util.concurrent.TimeoutException("query exceeded 2s");out.add((0 until c.columnCount).joinToString(" | "){c.getString(it)?.take(500)?:""})};out } } finally {timer.cancel()} } finally { ro.close() }
    }
    fun approveDoctor(id:Long):Boolean {
        val ops=db.rawQuery("SELECT after_json,status FROM doctor_changes WHERE id=?",arrayOf(id.toString())).use{if(it.moveToFirst()&&it.getString(1)=="proposed") JSONArray(it.getString(0)) else null}?:return false
        if(ops.length()>10 || (0 until ops.length()).any{!validDoctorOp(ops.optJSONObject(it)?:return false)}) return false
        val evidence=db.rawQuery("SELECT request,expected_plan FROM doctor_validation WHERE proposal_id=?",arrayOf(id.toString())).use { if(it.moveToFirst())it.getString(0) to it.getString(1) else null } ?: return false
        goldenIncidentRequest=evidence.first; goldenExpectedPlan=JSONArray(evidence.second)
        if (!goldenCheck(ops)) return false
        val before=JSONArray(); db.beginTransaction(); try { for(i in 0 until ops.length()) { val o=ops.getJSONObject(i); before.put(applyOperation(o)) }; db.execSQL("UPDATE doctor_changes SET before_json=?,status='applied' WHERE id=?",arrayOf(before.toString(),id)); db.setTransactionSuccessful() } catch(_:Exception){return false} finally {db.endTransaction()}; return true
    }
    private fun applyOperation(o:JSONObject):JSONObject {
        val kind=o.getString("op"); val id=o.optLong("id")
        return when(kind) {
            "fact.add" -> { val row=JSONObject().put("op","fact.delete").put("id",db.insertOrThrow("facts",null,ContentValues().apply{put("key",o.getString("key"));put("value",o.getString("value"));put("tags",o.optString("tags"));put("importance",o.optDouble("importance",0.5));put("source","doctor")})); row }
            "fact.update","fact.retag","fact.delete","memory.importance.set" -> { val row=db.rawQuery("SELECT key,value,tags,importance,source,last_used FROM facts WHERE id=?",arrayOf(id.toString())).use{if(it.moveToFirst()) JSONObject().put("op","fact.restore").put("id",id).put("key",it.getString(0)).put("value",it.getString(1)).put("tags",it.getString(2)).put("importance",it.getDouble(3)).put("source",it.getString(4)).put("last_used",it.getLong(5)) else throw IllegalArgumentException("missing fact")}; when(kind){"fact.delete"->db.delete("facts","id=?",arrayOf(id.toString()));"fact.update"->db.update("facts",ContentValues().apply{put("value",o.getString("value"))},"id=?",arrayOf(id.toString()));"fact.retag"->db.update("facts",ContentValues().apply{put("tags",o.getString("tags"))},"id=?",arrayOf(id.toString()));else->db.update("facts",ContentValues().apply{put("importance",o.getDouble("importance").coerceIn(0.0,1.0))},"id=?",arrayOf(id.toString()))};row }
            "match_rule.add" -> JSONObject().put("op","match_rule.delete").put("id",db.insertOrThrow("match_rules",null,ContentValues().apply{put("kind",o.getString("kind"));put("pattern",o.getString("pattern"));put("replacement",o.getString("replacement"));put("approved",1)}))
            "skill.set_status" -> {val sid=o.getString("id");val prev=db.rawQuery("SELECT status FROM skills WHERE id=?",arrayOf(sid)).use{if(it.moveToFirst())it.getString(0) else throw IllegalArgumentException("missing skill")};db.update("skills",ContentValues().apply{put("status",o.getString("status"))},"id=?",arrayOf(sid));JSONObject().put("op","skill.status.restore").put("id",sid).put("status",prev)}
            "query_recipe.add" -> JSONObject().put("op","query_recipe.delete").put("id",db.insertOrThrow("query_recipes",null,ContentValues().apply{put("name",o.getString("name"));put("select_sql",o.getString("sql"));put("approved",1)}))
            "fact.merge" -> throw IllegalArgumentException("fact.merge requires explicit reversible target snapshot and is not currently supported")
            "template.add" -> { val tid=UUID.randomUUID().toString(); db.insertOrThrow("library_templates",null,ContentValues().apply{put("id",tid);put("intent",o.getString("intent"));put("phrase",o.getString("phrase"));put("priority",o.optInt("priority",0));put("negative_phrases",o.optJSONArray("negative_phrases")?.toString()?:"[]")}); JSONObject().put("op","template.delete").put("id",tid) }
            "skill.add_trigger" -> { val sid=o.getString("id"); val prev=db.rawQuery("SELECT triggers FROM skills WHERE id=?",arrayOf(sid)).use{if(it.moveToFirst())it.getString(0)else throw IllegalArgumentException("missing skill")}; val a=JSONArray(prev); val trigger=o.getString("trigger"); if((0 until a.length()).any{a.optString(it)==trigger}) throw IllegalArgumentException("duplicate trigger"); a.put(trigger); db.update("skills",ContentValues().apply{put("triggers",a.toString())},"id=?",arrayOf(sid)); JSONObject().put("op","skill.triggers.restore").put("id",sid).put("triggers",prev) }
            "index.rebuild" -> { db.execSQL("REINDEX"); JSONObject().put("op","noop") }
            "report.bug" -> { val rid=db.insertOrThrow("doctor_changes",null,ContentValues().apply{put("before_json","[]");put("after_json",JSONObject().put("bug_report",o.toString()).toString());put("status","bug_report");put("created_at",System.currentTimeMillis())}); JSONObject().put("op","doctor_change.delete").put("id",rid) }
            else -> error("unsupported")
        }
    }
    fun rejectDoctor(id:Long)=db.update("doctor_changes",ContentValues().apply{put("status","rejected")},"id=? AND status='proposed'",arrayOf(id.toString()))==1
    fun undoDoctor(id:Long):Boolean { val before=db.rawQuery("SELECT before_json,status FROM doctor_changes WHERE id=?",arrayOf(id.toString())).use{if(it.moveToFirst()&&it.getString(1)=="applied")JSONArray(it.getString(0))else null}?:return false; db.beginTransaction();try{for(i in before.length()-1 downTo 0)undoOperation(before.getJSONObject(i));db.execSQL("UPDATE doctor_changes SET status='undone' WHERE id=?",arrayOf(id));db.setTransactionSuccessful()}catch(_:Exception){return false}finally{db.endTransaction()};return true }
    private fun undoOperation(o:JSONObject){when(o.getString("op")){"fact.delete"->db.delete("facts","id=?",arrayOf(o.getString("id")));"fact.restore"->db.insertWithOnConflict("facts",null,ContentValues().apply{put("id",o.getLong("id"));put("key",o.getString("key"));put("value",o.getString("value"));put("tags",o.getString("tags"));put("importance",o.getDouble("importance"));put("source",o.getString("source"));put("last_used",o.getLong("last_used"))},SQLiteDatabase.CONFLICT_REPLACE);"match_rule.delete"->db.delete("match_rules","id=?",arrayOf(o.getString("id")));"skill.status.restore"->db.update("skills",ContentValues().apply{put("status",o.getString("status"))},"id=?",arrayOf(o.getString("id")));"query_recipe.delete"->db.delete("query_recipes","id=?",arrayOf(o.getString("id")));"template.delete"->db.delete("library_templates","id=?",arrayOf(o.getString("id")));"skill.triggers.restore"->db.update("skills",ContentValues().apply{put("triggers",o.getString("triggers"))},"id=?",arrayOf(o.getString("id")));"fact.merge.restore"->{val src=JSONObject(o.getString("source"));db.update("facts",ContentValues().apply{put("value",o.getString("target_value"))},"id=?",arrayOf(o.getString("target_id")));undoOperation(src)};"doctor_change.delete"->db.delete("doctor_changes","id=?",arrayOf(o.getString("id")));"noop"->Unit;else->error("invalid undo")}}

    fun recordIncident(component:String,code:String,summary:String) {
        try { incidentSink?.invoke(component,"",code,summary) } catch (_:Exception) { /* Host telemetry must not block incident persistence. */ }
        val fingerprint=sha("$component|$code"); val now=System.currentTimeMillis(); val card=JSONObject().put("component",component.take(80)).put("code",code.take(80)).put("summary",summary.take(300)).put("tokensApprox",60).toString()
        db.execSQL("INSERT INTO incidents(fingerprint,component,error_code,count,first_seen,last_seen,card_json) VALUES(?,?,?,1,?,?,?) ON CONFLICT(fingerprint) DO UPDATE SET count=count+1,last_seen=excluded.last_seen,card_json=excluded.card_json",arrayOf(fingerprint,component.take(80),code.take(80),now,now,card))
    }
    fun incidentCards():List<String> = db.rawQuery("SELECT card_json,count FROM incidents ORDER BY last_seen DESC LIMIT 20",null).use { c -> buildList { while(c.moveToNext()) add(c.getString(0)+"\nOccurrences: "+c.getInt(1)) } }
    fun createSnapshot(label:String="daily"):String { val dir=File(app.filesDir,"learning-snapshots").apply{mkdirs()}; val target=File(dir,"${System.currentTimeMillis()}-${label.replace(Regex("[^a-zA-Z0-9_-]"),"").take(24)}.db"); db.execSQL("VACUUM INTO ?", arrayOf(target.absolutePath)); target.setReadable(false,false); target.setReadable(true,true); return target.absolutePath }
    fun maintain():String { val d=db; d.execSQL(FactMaintenance.PRUNE_DUPLICATES_SQL); d.execSQL("ANALYZE"); return "Maintenance completed: up to 10 exact duplicate facts removed and database analyzed." }
    private fun sha(s:String)=MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString(""){"%02x".format(it)}
}
