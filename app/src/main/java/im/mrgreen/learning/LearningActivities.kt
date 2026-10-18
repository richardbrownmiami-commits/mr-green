package im.mrgreen.learning

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray

/** Parent supplies a runtime before launching these screens; safe empty-state otherwise. */
object LearningActivityBridge { @Volatile var runtime: LearningRuntime? = null }

class LearningActivity : Activity() {
    private val runtime get() = LearningActivityBridge.runtime
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); render() }
    private fun render() {
        val root=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(24,24,24,24) }
        root.addView(TextView(this).apply { text="Learning inbox"; textSize=24f })
        root.addView(TextView(this).apply { text="Learned routines remain inactive until you approve them." })
        val scroll=ScrollView(this); val list=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}; scroll.addView(list)
        val items=runtime?.inbox().orEmpty()
        if(items.isEmpty()) list.addView(TextView(this).apply{text="No routine proposals yet.";setPadding(0,24,0,24)})
        items.forEach { item ->
            list.addView(TextView(this).apply{text="${item.name}\nTrigger: ${strings(item.triggers)}\n${item.steps}";setPadding(0,20,0,12)})
            val buttons=LinearLayout(this)
            buttons.addView(Button(this).apply{text="Approve";setOnClickListener{if(runtime?.approveSkill(item.id)==true) render()}})
            buttons.addView(Button(this).apply{text="Reject";setOnClickListener{runtime?.rejectSkill(item.id);render()}})
            list.addView(buttons)
        }
        root.addView(scroll,LinearLayout.LayoutParams(-1,0,1f)); setContentView(root)
    }
    private fun strings(a:JSONArray)=(0 until a.length()).joinToString(", "){a.optString(it)}
}

class DoctorActivity : Activity() {
    private val runtime get() = LearningActivityBridge.runtime
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); render() }
    private fun render() {
        val root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(24,24,24,24)}
        root.addView(TextView(this).apply{text="Doctor";textSize=24f})
        root.addView(TextView(this).apply{text="Maintenance is data-only. Proposed fixes require explicit approval; applied changes can be undone."})
        val scroll=ScrollView(this); val list=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL};scroll.addView(list)
        val r=runtime
        r?.incidentCards().orEmpty().forEach { card -> list.addView(TextView(this).apply {text="Incident\n$card";setPadding(0,16,0,16)}) }
        val proposals=r?.doctorProposals().orEmpty()
        if(proposals.isEmpty()) list.addView(TextView(this).apply{text="No pending Doctor proposals.";setPadding(0,24,0,24)})
        proposals.forEach { p ->
            list.addView(TextView(this).apply{text="${p.state} #${p.id}\n${p.operations}";setPadding(0,20,0,12)})
            val row=LinearLayout(this)
            if(p.state=="proposed") row.addView(Button(this).apply{text="Approve";setOnClickListener{r?.approveDoctor(p.id);render()}})
            if(p.state=="proposed") row.addView(Button(this).apply{text="Reject";setOnClickListener{r?.rejectDoctor(p.id);render()}})
            if(p.state=="applied") row.addView(Button(this).apply {
                text="Undo"
                setOnClickListener {
                    android.app.AlertDialog.Builder(this@DoctorActivity).setTitle("Undo Doctor proposal #${p.id}?")
                        .setMessage("Restore the stored data rows changed by this proposal. Later edits to those same rows may be replaced. This does not restore models, preferences or external actions.")
                        .setNegativeButton("Cancel",null).setPositiveButton("Undo changes") { _,_->
                            isEnabled=false
                            Thread {
                                val message=try { if(r?.undoDoctor(p.id)==true) "Changes undone." else "Undo was not applied." }
                                    catch(e:Exception) { "Undo failed: ${e.javaClass.simpleName}" }
                                runOnUiThread { render(); android.widget.Toast.makeText(this@DoctorActivity,message,android.widget.Toast.LENGTH_LONG).show() }
                            }.start()
                        }.show()
                }
            })
            list.addView(row)
        }
        list.addView(Button(this).apply{text="Run non-model maintenance";setOnClickListener{Thread { val message=try{r?.maintain()?:"Runtime unavailable"}catch(e:Exception){"Maintenance failed: ${e.javaClass.simpleName}"};runOnUiThread{list.addView(TextView(this@DoctorActivity).apply{text=message})} }.start()}})
        list.addView(Button(this).apply{text="Create database snapshot";setOnClickListener{Thread {val message=try{r?.createSnapshot()?:"Runtime unavailable"}catch(e:Exception){"Snapshot failed: ${e.javaClass.simpleName}"};runOnUiThread{list.addView(TextView(this@DoctorActivity).apply{text=message})}}.start()}})
        list.addView(Button(this).apply{text="Stage latest database snapshot for next restart";setOnClickListener {
            val latest=DatabaseSnapshots.available(this@DoctorActivity).firstOrNull()
            if(latest==null) list.addView(TextView(this@DoctorActivity).apply{text="No app-owned database snapshot is available."})
            else android.app.AlertDialog.Builder(this@DoctorActivity).setTitle("Restore ${latest.name}?").setMessage("At the next full app restart, all current database rows will be replaced with this snapshot. A recovery copy of the previous DB is retained. Models, separate files and preferences are not restored. No change is made to the running database.").setNegativeButton("Cancel",null).setPositiveButton("Stage restore") {_,_->Thread {
                val message=try{DatabaseSnapshots.stageAfterApproval(this@DoctorActivity,latest,true);"Restore staged. Force-stop Mr Green in Android App settings, then reopen it to activate the snapshot."}catch(e:Exception){"Restore not staged: ${e.javaClass.simpleName}"}
                runOnUiThread{list.addView(TextView(this@DoctorActivity).apply{text=message})}
            }.start()}.show()
        }})
        root.addView(scroll,LinearLayout.LayoutParams(-1,0,1f));setContentView(root)
    }
}
