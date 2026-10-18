package im.mrgreen.core

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import im.mrgreen.GreenApp
import kotlinx.coroutines.*
import java.io.File

/** No AI in event processing; background maintenance yields between bounded database tasks. */
class HealthMaintenance(private val app: GreenApp) {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    @Volatile private var lastInput=android.os.SystemClock.elapsedRealtime()
    @Volatile private var work: Job?=null
    private var started=false
    fun onUserInput() { lastInput=android.os.SystemClock.elapsedRealtime(); work?.cancel() }
    @Synchronized fun start() {
        if(started)return
        started=true
        scope.launch {
            importManual()
            runCatching { app.conversationMemory.sync(128) }
            while(isActive) {
                delay(60_000)
                if(eligible() && work?.isActive!=true) work=scope.launch { try { runNow() } catch (_:CancellationException) { throw CancellationException("User input") } catch (_:Exception) { app.logger.write("WARN","DOCTOR","Deferred maintenance after bounded task failure") } }
            }
        }
    }
    fun eligible():Boolean {
        if(!app.getSharedPreferences("doctor_settings",0).getBoolean("automatic",true) || AgentController.state.value.busy)return false
        val battery=app.registerReceiver(null,IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return false
        val level=battery.getIntExtra(BatteryManager.EXTRA_LEVEL,0)
        val scale=battery.getIntExtra(BatteryManager.EXTRA_SCALE,100).coerceAtLeast(1)
        val charging=battery.getIntExtra(BatteryManager.EXTRA_PLUGGED,0)!=0
        val idle=android.os.SystemClock.elapsedRealtime()-lastInput>=300_000
        val memory=ActivityManager.MemoryInfo(); (app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(memory)
        return (idle||charging) && level*100/scale>30 && battery.getIntExtra(BatteryManager.EXTRA_TEMPERATURE,999)<420 && memory.availMem>300L*1024*1024
    }
    suspend fun runNow() {
        if(!eligible())return
        val prefs=app.getSharedPreferences("doctor_settings",0)
        val today=java.time.LocalDate.now().toString()
        currentCoroutineContext().ensureActive()
        if(prefs.getString("snapshot_day","")!=today) {
            app.roadmap.learning.createSnapshot("daily")
            prefs.edit().putString("snapshot_day",today).apply()
            File(app.filesDir,"learning-snapshots").listFiles()?.sortedByDescending { it.lastModified() }?.drop(7)?.forEach { it.delete() }
        }
        delay(1); currentCoroutineContext().ensureActive()
        app.store.writableDatabase.execSQL("DELETE FROM facts WHERE id IN (SELECT id FROM facts WHERE id NOT IN (SELECT MIN(id) FROM facts GROUP BY key,value,tags,importance,source,last_used) LIMIT 10)")
        delay(1); currentCoroutineContext().ensureActive()
        app.store.writableDatabase.execSQL("DELETE FROM logs WHERE timestamp < ?",arrayOf(System.currentTimeMillis()-14L*86400000))
        delay(1); currentCoroutineContext().ensureActive()
        app.conversationMemory.sync(128)
        // Rebuild only known FTS external-content indexes; no model-supplied SQL.
        for(table in listOf("facts","messages","rag_chunks","library_templates","self_manual")) {
            currentCoroutineContext().ensureActive()
            app.store.writableDatabase.execSQL("INSERT INTO fts_$table(fts_$table) VALUES('rebuild')")
            delay(1)
        }
        app.logger.write("INFO","DOCTOR","Scheduled snapshot, metadata pruning and FTS maintenance completed")
    }
    private fun importManual() {
        try {
            val files=app.assets.list("self_manual").orEmpty()
            files.filter { it.endsWith(".md") }.take(100).forEach { name ->
                val body=app.assets.open("self_manual/$name").bufferedReader().use { it.readText().take(4000) }
                app.store.writableDatabase.execSQL("INSERT OR REPLACE INTO self_manual(id,component,text) VALUES(?,?,?)",arrayOf(name,name.substringBefore('-').uppercase(),body))
            }
        } catch (_:Exception) { app.logger.write("WARN","DOCTOR","Self-manual import unavailable") }
    }
}
