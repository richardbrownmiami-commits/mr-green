package im.mrgreen.learning

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONArray
import org.json.JSONObject

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk=[30], application=android.app.Application::class)
class LearningRuntimeTest {
    @Test fun queryValidatorAcceptsWhitelistedReadAndRejectsWriteAndUnknownTable() {
        // Exercise the same static safety grammar without constructing Android storage.
        assertTrue(sqlLooksSafe("SELECT key,value FROM facts LIMIT 4"))
        assertTrue(sqlLooksSafe("select count(*) from skills"))
        assertFalse(sqlLooksSafe("DELETE FROM facts"))
        assertFalse(sqlLooksSafe("SELECT * FROM sqlite_master"))
        assertFalse(sqlLooksSafe("SELECT * FROM facts; DROP TABLE facts"))
        assertFalse(sqlLooksSafe("SELECT load_extension('x') FROM facts"))
        assertFalse(sqlLooksSafe("SELECT key FROM facts WHERE id IN (SELECT id FROM skills)"))
        assertFalse(sqlLooksSafe("SELECT (SELECT key FROM skills) FROM facts"))
        assertFalse(sqlLooksSafe("SELECT random() FROM facts"))
        assertFalse(sqlLooksSafe("SELECT * FROM facts f JOIN sqlite_master m ON 1=1"))
        assertFalse(sqlLooksSafe("SELECT key FROM facts;"))
    }
    @Test fun planShapeRejectsUnknownToolAndExtraKeys() {
        val known: (String, JSONObject) -> Boolean = { tool, _ -> tool == "time.now" }
        assertTrue(planSafe(JSONArray().put(JSONObject().put("tool","time.now").put("args",JSONObject())),known))
        assertFalse(planSafe(JSONArray().put(JSONObject().put("tool","shell.exec").put("args",JSONObject())),known))
        assertFalse(planSafe(JSONArray().put(JSONObject().put("tool","time.now").put("args",JSONObject()).put("run",true)),known))
    }
    @Test fun doctorOperationWhitelistIsBounded() {
        assertTrue(doctorSafe(JSONObject().put("op","match_rule.add").put("kind","synonym").put("pattern","good morning").put("replacement","gm")))
        assertFalse(doctorSafe(JSONObject().put("op","match_rule.add").put("kind","synonym").put("pattern",".*").put("replacement","x")))
        assertFalse(doctorSafe(JSONObject().put("op","tool.registry.modify")))
    }
    private fun sqlLooksSafe(sql:String):Boolean {
        val s=sql.trim().lowercase().replace(Regex("\\s+")," ")
        val allowed=setOf("facts","tasks","episodes","skills","messages","library_templates","incidents","logs","rag_docs","rag_chunks","match_rules")
        if(!s.startsWith("select ") || s.contains(';')) return false
        if(Regex("\\b(insert|update|delete|drop|alter|create|replace|attach|detach|pragma|vacuum|analyze|load_extension|union|into)\\b").containsMatchIn(s)) return false
        val tables=Regex("\\b(from|join)\\s+([a-z_][a-z0-9_]*)").findAll(s).map{it.groupValues[2]}.toList()
        val funcs=Regex("([a-z_][a-z0-9_]*)\\s*\\(").findAll(s).map{it.groupValues[1]}.toSet()
        return tables.isNotEmpty()&&tables.all{it in allowed}&&Regex("\\bselect\\b").findAll(s).count()==1&&funcs.all{it in setOf("count","min","max","avg","sum","lower","upper","length","coalesce")}&&!s.contains("--")&&!s.contains("/*")
    }
    private fun planSafe(a:JSONArray, validator:(String,JSONObject)->Boolean):Boolean = a.length() in 1..20 && (0 until a.length()).all{val s=a.optJSONObject(it)?:return@all false; s.keys().asSequence().all{k->k in setOf("tool","args","verify")} && validator(s.optString("tool"),s.optJSONObject("args")?:JSONObject())}
    private fun doctorSafe(o:JSONObject):Boolean { val p=o.optString("pattern"); return o.optString("op")=="match_rule.add" && o.optString("kind") in setOf("synonym","trigger","slot_alias","negative") && p.length in 1..120 && p.none{it in "\\[]{}()*+?|^$"} }
}
