package im.mrgreen.screen

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Build
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/** Host may inject its shared AgentController-backed provider; null keeps answers disabled, never fakes AI. */
object ScreenAssistantBridge {
    @Volatile var answerProvider: ScreenAnswerProvider? = null
    @Volatile var actionConfirmer: ScreenActionConfirmer? = null
}

class ScreenActivity : AppCompatActivity() {
    companion object { const val EXTRA_SNAPSHOT="im.mrgreen.screen.SNAPSHOT" }
    private lateinit var snapshot: ScreenSnapshot
    private lateinit var status: TextView
    private lateinit var container: LinearLayout
    private var localOnly = true
    private var highlightOverlay: View? = null
    private var highlightWindow: WindowManager? = null
    private var expiresAt = 0L
    private val themeReceiver = object : BroadcastReceiver() { override fun onReceive(context: Context?, intent: Intent?) { applyTheme() } }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        snapshot = runCatching { ScreenSnapshotCodec.decode(intent.getStringExtra(EXTRA_SNAPSHOT).orEmpty()) }.getOrElse { ScreenSnapshot("",System.currentTimeMillis(), emptyList(),"Screen snapshot is unavailable. Nothing was read.") }
        localOnly = true
        val root=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(18),dp(12),dp(18),dp(12)) }
        val header=LinearLayout(this).apply { gravity=Gravity.CENTER_VERTICAL }
        header.addView(TextView(this).apply { text="Mr Green · Screen assistant"; textSize=18f; setTextColor(0xff17432d.toInt()) },LinearLayout.LayoutParams(0,-2,1f))
        header.addView(Button(this).apply { text="Close"; setOnClickListener { finish() } })
        root.addView(header)
        root.addView(TextView(this).apply { text="${snapshot.packageName.ifBlank { "Unknown app" }} · read ${snapshot.items.size} items · this request only"; textSize=12f })
        status=TextView(this).apply { text=snapshot.blockedReason ?: if(snapshot.readable) "Screen snapshot ready. Nothing is saved." else "No readable screen text was exposed."; setPadding(0,dp(8),0,dp(8)) }
        root.addView(status)
        if (PersonalScreenConsent.available()) root.addView(TextView(this).apply {
            text="Personal-use mode · protected-window status is unverified. Screen text stays local. Disable this mode in Capability setup."
            setPadding(0,dp(8),0,dp(8))
        })
        val opts=CheckBox(this).apply { text="Screen questions are always local on this device"; isChecked=true; isEnabled=false }
        root.addView(opts)
        lateinit var question: EditText
        val chips=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL }
        listOf("Explain","Summarize","Translate","What can I tap?","Extract").forEach { label -> chips.addView(Button(this).apply { text=label; setOnClickListener { question.setText(when(label){"Summarize"->"Summarize this screen.";"Translate"->"Translate the visible text into English.";"What can I tap?"->"What buttons or controls can I tap?";"Extract"->"Identify visible phone numbers and times, without taking action.";else->"Explain what this screen says."}) } }) }
        root.addView(ScrollView(this).apply { addView(chips) })
        val answer=TextView(this).apply { textSize=15f; setPadding(0,dp(8),0,dp(8)); text="" }
        val scroll=ScrollView(this).apply { addView(answer) }; root.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
        container=root
        question=EditText(this).apply { hint="Ask about this screen…"; maxLines=3; setSingleLine(false); contentDescription="Question about this screen" }
        root.addView(question)
        root.addView(Button(this).apply { text="Ask"; setOnClickListener {
            val invalid=ScreenReadPolicy.validateQuestion(question.text.toString())
            if(invalid!=null){status.text=invalid;return@setOnClickListener}
            if(!snapshot.readable){status.text=snapshot.blockedReason ?: "Nothing readable.";return@setOnClickListener}
            val provider=ScreenAssistantBridge.answerProvider
            if(provider==null){status.text="Screen answer service is not connected. Snapshot remains on this device.";return@setOnClickListener}
            status.text=if(localOnly) "Answering locally…" else "Answering with the connected provider…"
            lifecycleScope.launch { try { answer.text=provider.answer(ScreenReadPolicy.buildPrompt(question.text.toString(),snapshot),localOnly); status.text=if(localOnly) "Local answer · not saved" else "Provider answer · review privacy settings" } catch (_:Exception) { status.text="Could not answer. Screen text was not saved." } }
        } })
        val actions=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL }
        actions.addView(Button(this).apply { text="Highlight controls"; setOnClickListener { drawHighlights() } })
        actions.addView(Button(this).apply { text="Extract actions"; setOnClickListener { showExtractedActions(answer) } })
        root.addView(actions)
        setContentView(root)
        val filter=IntentFilter(im.mrgreen.extensions.ThemeManager.ACTION_THEME_CHANGED)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(themeReceiver,filter,Context.RECEIVER_NOT_EXPORTED) else @Suppress("DEPRECATION") registerReceiver(themeReceiver,filter)
        applyTheme()
    }
    override fun onStop() { clearHighlights(); if (::snapshot.isInitialized && snapshot.packageName.isNotBlank()) { snapshot=ScreenSnapshot("",System.currentTimeMillis(),emptyList(),"Screen snapshot expired when leaving the panel. Nothing was retained.") }; super.onStop() }
    override fun onDestroy() { runCatching { unregisterReceiver(themeReceiver) }; clearHighlights(); super.onDestroy() }
    private fun applyTheme() {
        if (!::container.isInitialized) return
        val p=im.mrgreen.extensions.ThemeManager.palette(this)
        container.setBackgroundColor(p.background)
        fun visit(v: View) { if (v is TextView) v.setTextColor(p.text); if (v is android.view.ViewGroup) for(i in 0 until v.childCount) visit(v.getChildAt(i)) }
        visit(container)
    }
    private fun clearHighlights() { highlightOverlay?.let { v -> runCatching { highlightWindow?.removeView(v) } }; highlightOverlay=null; highlightWindow=null }
    private fun drawHighlights() {
        clearHighlights()
        val p=snapshot.items.mapNotNull { it.bounds }.filter { it.right>it.left && it.bottom>it.top }
        if(p.isEmpty()) { status.text="No labeled control bounds were exposed."; return }
        val overlay=getSystemService(WINDOW_SERVICE) as WindowManager
        val v=object: View(this) { val paint=Paint(3).apply { style=Paint.Style.STROKE; strokeWidth=dp(3).toFloat() }; override fun onDraw(c:Canvas) { super.onDraw(c); paint.color=im.mrgreen.extensions.ThemeManager.palette(this@ScreenActivity).accent; p.forEach { c.drawRoundRect(RectF(it.left.toFloat(),it.top.toFloat(),it.right.toFloat(),it.bottom.toFloat()),dp(5).toFloat(),dp(5).toFloat(),paint) } } }
        val type=if(Build.VERSION.SDK_INT>=26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE
        val lp=WindowManager.LayoutParams(-1,-1,type,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,android.graphics.PixelFormat.TRANSLUCENT)
        try { overlay.addView(v,lp); highlightOverlay=v; highlightWindow=overlay; expiresAt=android.os.SystemClock.elapsedRealtime()+3500; v.postDelayed({ if(android.os.SystemClock.elapsedRealtime()>=expiresAt) clearHighlights() },3500); status.text="Highlights shown briefly; they disappear automatically." } catch (_:Exception) { status.text="Could not safely display highlights." }
    }

    private fun showExtractedActions(output:TextView) {
        if(!snapshot.readable){ output.text=snapshot.blockedReason ?: "Nothing readable."; return }
        val actions=mutableListOf<ScreenAction>()
        val phone=Regex("(?<!\\w)(?:\\+?[0-9][0-9 ()-]{7,}[0-9])(?!\\w)")
        val time=Regex("\\b(?:[01]?\\d|2[0-3])(?::[0-5]\\d)\\s?(?:AM|PM)?\\b",RegexOption.IGNORE_CASE)
        val text=snapshot.items.joinToString(" ") { it.text }
        phone.findAll(text).take(5).forEach { actions+=ScreenAction("Call ${it.value}",it.value,ScreenAction.Type.CALL) }
        time.findAll(text).take(5).forEach { actions+=ScreenAction("Set alarm for ${it.value}",it.value,ScreenAction.Type.ALARM) }
        if(actions.isEmpty()){output.text="No phone numbers or times were found in readable screen text.";return}
        val host=this
        actions.forEach { action ->
            val b=Button(this).apply { this.text=action.label; setOnClickListener {
                AlertDialogBuilder.confirm(host,"Confirm action", "Use extracted screen content to ${action.label}? This will not happen without your confirmation.") {
                    ScreenAssistantBridge.actionConfirmer?.confirm(action) { output.text="Confirmed request handed to host. Verify the result in the target app." } ?: run { output.text="Action host is not connected; nothing was launched." }
                }
            } }
            container.addView(b)
        }
        output.text="Review each extracted item and confirm individually. Nothing has happened yet."
    }
    private fun dp(n:Int)=(n*resources.displayMetrics.density).toInt()
}

private object AlertDialogBuilder {
    fun confirm(activity:Activity,title:String,message:String,yes:()->Unit) = android.app.AlertDialog.Builder(activity).setTitle(title).setMessage(message).setNegativeButton("Cancel",null).setPositiveButton("Confirm") { _,_->yes() }.show()
}

/** Bounded in-process transport; intentionally excludes screenshot pixels and never writes disk. */
object ScreenSnapshotCodec {
    fun encode(s:ScreenSnapshot):String = JSONObject().put("package",s.packageName.take(200)).put("time",s.capturedAtMillis).put("blocked",s.blockedReason).put("items",JSONArray().apply { s.items.take(120).forEach { i -> put(JSONObject().put("text",i.text.take(300)).put("kind",i.kind.name).put("bounds",i.bounds?.let { JSONArray().put(it.left).put(it.top).put(it.right).put(it.bottom) })) } }).toString()
    fun decode(raw:String):ScreenSnapshot {
        require(raw.length<=64_000)
        val j=JSONObject(raw); val arr=j.optJSONArray("items") ?: JSONArray(); val items=(0 until arr.length()).map { n -> val i=arr.getJSONObject(n); val a=i.optJSONArray("bounds"); val b=if(a!=null&&a.length()==4) ScreenRect(a.getInt(0),a.getInt(1),a.getInt(2),a.getInt(3)) else null; ScreenItem(i.getString("text").take(300),ScreenItem.Kind.valueOf(i.getString("kind")),b) }.take(120)
        return ScreenSnapshot(j.optString("package").take(200),j.optLong("time"),items,j.optString("blocked").takeIf { it.isNotBlank()&&it!="null" })
    }
}
