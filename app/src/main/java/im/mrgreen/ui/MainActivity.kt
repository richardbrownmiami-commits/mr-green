package im.mrgreen.ui

import android.app.ActivityManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import im.mrgreen.R
import im.mrgreen.GreenApp
import im.mrgreen.core.AgentController
import im.mrgreen.core.RuntimePolicy
import im.mrgreen.engine.LoadParams
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

internal enum class ComposerAction { SEND, STOP_GENERATING, WORKING }

/** Pure state mapping: only active generation is cancellable from the composer. */
internal fun composerAction(busy: Boolean, generating: Boolean): ComposerAction = when {
    !busy -> ComposerAction.SEND
    generating -> ComposerAction.STOP_GENERATING
    else -> ComposerAction.WORKING
}

class MainActivity : AppCompatActivity() {
    private lateinit var root: LinearLayout
    private lateinit var host: FrameLayout
    private lateinit var body: LinearLayout
    private lateinit var orb: OrbView
    private var modeBadge: TextView? = null
    private lateinit var transcript: LinearLayout
    private lateinit var entry: TextInputEditText
    private lateinit var sendButton: MaterialButton
    private lateinit var navView: BottomNavigationView
    private lateinit var heroView: View
    private var engineStatus: TextView? = null
    private var liveRam: TextView? = null
    private var pageJob: kotlinx.coroutines.Job? = null
    private var transcriptJob: kotlinx.coroutines.Job? = null
    private var renderedRefresh = -1L
    private var selected = R.id.tab_chat
    private var displayedStream = ""
    private val bg = 0xFF0D0A14.toInt(); private val surface = 0xFF1C1529.toInt(); private val mint = 0xFFB98AFF.toInt()
    private val modelPicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) result.data?.data?.let(::importModel)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bg; window.navigationBarColor = bg
        window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        // Inverting LIGHT_STATUS_BAR enables every other flag, including fullscreen,
        // which prevents adjustResize from keeping the composer above the IME.
        window.decorView.systemUiVisibility = 0
        if (!getPreferences(MODE_PRIVATE).getBoolean("onboarding_seen", false)) {
            startActivity(Intent(this, OnboardingActivity::class.java)); getPreferences(MODE_PRIVATE).edit().putBoolean("onboarding_seen", true).apply()
        }
        buildShell(); showChat()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                AgentController.state.collect { state ->
                    if (::orb.isInitialized) { orb.mode = when { state.error != null -> OrbView.Mode.ERROR; state.busy -> OrbView.Mode.THINKING; else -> OrbView.Mode.IDLE }; orb.invalidate() }
                    findViewById<TextView>(R.id.status_text)?.text = state.error ?: state.status
                    modeBadge?.text = getString(if (state.mode == "Cloud") R.string.cloud_badge_cloud else R.string.cloud_badge_local, state.error ?: state.status)
                    if (selected == R.id.tab_llama) engineStatus?.text = state.error ?: getString(R.string.engine_live, state.status, state.stats.promptTokPerSec, state.stats.genTokPerSec, state.stats.nCtx)
                    sendButtonIfShown(state.busy, state.generating)
                    if (state.streaming.isNotEmpty()) updateStream(state.streaming)
                    else if (state.refresh != renderedRefresh) { renderedRefresh = state.refresh; refreshTranscript() }
                }
            }
        }
    }
    override fun onStart() { super.onStart(); (application as? GreenApp)?.logger?.foreground = true; if (selected == R.id.tab_chat) refreshTranscript() }
    override fun onStop() { (application as? GreenApp)?.logger?.foreground = false; super.onStop() }

    private fun buildShell() {
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(bg) }
        host = FrameLayout(this)
        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(14), dp(20), 0) }
        root.addView(host, LinearLayout.LayoutParams(-1, 0, 1f))
        val nav = BottomNavigationView(this).apply {
            id = R.id.bottom_navigation; setBackgroundColor(surface); itemIconTintList = android.content.res.ColorStateList.valueOf(mint); itemTextColor = android.content.res.ColorStateList.valueOf(0xFFB8ABC9.toInt())
            menu.add(0,R.id.tab_chat,0,getString(R.string.nav_chat)).setIcon(R.drawable.ic_nav_chat)
            menu.add(0,R.id.tab_panel,1,getString(R.string.nav_panel)).setIcon(R.drawable.ic_nav_overview)
            menu.add(0,R.id.tab_llama,2,getString(R.string.nav_llama)).setIcon(R.drawable.ic_nav_models)
            menu.add(0,R.id.tab_logs,3,getString(R.string.nav_logs)).setIcon(R.drawable.ic_nav_diagnostics)
            menu.add(0,R.id.tab_files,4,getString(R.string.nav_files)).setIcon(R.drawable.ic_nav_library)
            labelVisibilityMode = BottomNavigationView.LABEL_VISIBILITY_LABELED
            setOnItemSelectedListener { item -> selected=item.itemId; when(item.itemId){R.id.tab_chat->showChat();R.id.tab_panel->showPanel();R.id.tab_llama->showLlama();R.id.tab_logs->showLogs();R.id.tab_files->showFiles()}; true }
        }
        navView = nav
        root.addView(nav, LinearLayout.LayoutParams(-1,dp(72))); setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val imeVisible = insets.isVisible(WindowInsetsCompat.Type.ime())
            navView.visibility = if (imeVisible) View.GONE else View.VISIBLE
            if (::heroView.isInitialized && selected == R.id.tab_chat) heroView.visibility = if (imeVisible) View.GONE else View.VISIBLE
            insets
        }
        ViewCompat.requestApplyInsets(root)
    }
    private fun start(title: String, subtitle: String? = null) {
        pageJob?.cancel(); transcriptJob?.cancel(); engineStatus = null; liveRam = null
        (body.parent as? android.view.ViewGroup)?.removeView(body)
        host.removeAllViews()
        body.removeAllViews()
        if (selected == R.id.tab_chat) host.addView(body, FrameLayout.LayoutParams(-1,-1))
        else {
            val scroll = ScrollView(this).apply { isFillViewport = true }
            scroll.addView(body, android.view.ViewGroup.LayoutParams(-1,-2))
            host.addView(scroll, FrameLayout.LayoutParams(-1,-1))
        }
        body.addView(TextView(this).apply { text=title; textSize=25f; minHeight=dp(42); setTextColor(0xFFF6F0FF.toInt()); typeface=android.graphics.Typeface.DEFAULT_BOLD },LinearLayout.LayoutParams(-1,-2))
        if(subtitle!=null) body.addView(TextView(this).apply { text=subtitle; textSize=14f; minHeight=dp(38); setTextColor(0xFFB8ABC9.toInt()) },LinearLayout.LayoutParams(-1,-2))
    }
    private fun showChat() {
        start(getString(R.string.app_name),getString(R.string.chat_subtitle))
        orb=OrbView(this).apply { contentDescription=getString(R.string.orb_description) }; heroView=orb; body.addView(orb,LinearLayout.LayoutParams(-1,dp(130)))
        modeBadge = TextView(this).apply { text=getString(if (AgentController.state.value.mode == "Cloud") R.string.cloud_badge_cloud else R.string.cloud_badge_local, AgentController.state.value.error ?: AgentController.state.value.status); gravity=Gravity.CENTER; textSize=13f; setTextColor(mint); isClickable=true; setOnClickListener { startActivity(Intent(this@MainActivity, CloudActivity::class.java)) } }; body.addView(modeBadge,LinearLayout.LayoutParams(-1,dp(48)))
        body.addView(TextView(this).apply { id=R.id.status_text; text=AgentController.state.value.error ?: AgentController.state.value.status; visibility=View.GONE },LinearLayout.LayoutParams(-1,dp(1)))
        val scroll=ScrollView(this).apply { isFillViewport=true }; transcript=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(0,dp(8),0,dp(8)) }
        scroll.addView(transcript); body.addView(scroll,LinearLayout.LayoutParams(-1,0,1f)); refreshTranscript()
        val row=LinearLayout(this).apply { gravity=Gravity.CENTER_VERTICAL; orientation=LinearLayout.HORIZONTAL; setPadding(0,dp(6),0,dp(8)) }
        entry=TextInputEditText(this).apply { hint=getString(R.string.message_hint); setTextColor(0xFFF6F0FF.toInt()); setHintTextColor(0xFFB8ABC9.toInt()); setPadding(dp(14),dp(10),dp(10),dp(10)); minHeight=dp(52); maxLines=4; gravity=Gravity.TOP or Gravity.START; inputType=android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES; background=rounded(surface,18) }
        row.addView(entry,LinearLayout.LayoutParams(0,-2,1f))
        sendButton=MaterialButton(this).apply { text=getString(R.string.send); minHeight=dp(48); cornerRadius=dp(18); setOnClickListener { val state=AgentController.state.value; if (state.busy && state.generating) AgentController.cancel() else if (!state.busy) send() } }; row.addView(sendButton,LinearLayout.LayoutParams(dp(104),dp(54)))
        body.addView(row); sendButtonIfShown(AgentController.state.value.busy, AgentController.state.value.generating)
    }
    private fun sendButtonIfShown(busy: Boolean, generating: Boolean) {
        if (!::sendButton.isInitialized || sendButton.parent == null) return
        when (composerAction(busy, generating)) {
            ComposerAction.SEND -> { sendButton.text = getString(R.string.send); sendButton.isEnabled = true }
            ComposerAction.STOP_GENERATING -> { sendButton.text = getString(R.string.stop); sendButton.isEnabled = true }
            ComposerAction.WORKING -> { sendButton.text = getString(R.string.working); sendButton.isEnabled = false }
        }
        // Keep a next-message draft editable without allowing a second submission.
        entry.isEnabled = true
    }
    private fun refreshTranscript() {
        if (!::transcript.isInitialized || selected!=R.id.tab_chat || !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return
        transcriptJob?.cancel()
        transcriptJob = lifecycleScope.launch {
            val rows=withContext(Dispatchers.IO){(application as? GreenApp)?.store?.messages().orEmpty()}
            if (!::transcript.isInitialized || selected!=R.id.tab_chat) return@launch
            transcript.removeAllViews(); displayedStream=""
            if(rows.isEmpty()) addBubble("assistant",getString(R.string.empty_chat)) else rows.forEach { addBubble(it.role,it.content) }
            val stream = AgentController.state.value.streaming
            if (stream.isNotEmpty()) updateStream(stream)
        }
    }
    private fun send() { val prompt=entry.text?.toString()?.trim().orEmpty(); if(prompt.isBlank() || AgentController.state.value.busy)return
        entry.setText(""); lifecycleScope.launch { addBubble("user",prompt); AgentController.submit(this@MainActivity,prompt) }
    }
    private fun updateStream(text:String) {
        if(selected!=R.id.tab_chat || !::transcript.isInitialized || !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return
        if(displayedStream.isEmpty()) { displayedStream=text; addBubble("assistant",text+" ▍") }
        else { displayedStream=text; (transcript.getChildAt(transcript.childCount-1) as? TextView)?.text=text+" ▍" }
    }
    private fun addBubble(role:String,text:String) {
        if(!::transcript.isInitialized || selected!=R.id.tab_chat)return
        val mine=role.equals("user",true); val bubble=TextView(this).apply { this.text=text; textSize=15f; setTextColor(0xFFF6F0FF.toInt()); setPadding(dp(15),dp(12),dp(15),dp(12)); background=rounded(if(mine)0xFF6B43A0.toInt() else surface,18) }
        transcript.addView(bubble,LinearLayout.LayoutParams(if(mine)dp(300) else -1,-2).apply { gravity=if(mine)Gravity.END else Gravity.START; bottomMargin=dp(10) })
    }
    private fun showPanel() {
        start(getString(R.string.panel_title),getString(R.string.panel_subtitle))
        card(getString(R.string.runtime_title),"${AgentController.state.value.status}\n${getString(if (application is GreenApp && AgentController.gateway.settings.enabled) R.string.cloud_overview_enabled else R.string.cloud_overview_disabled)}")
        action(getString(R.string.cloud_open_gateway),getString(R.string.cloud_open_gateway)) { startActivity(Intent(this,CloudActivity::class.java)) }
        action("Plugins · Phase 7", "Review, build, run or roll back a local pack") { startActivity(Intent(this, PluginsActivity::class.java)) }
        action("Rive rules & learning", "Import, review and manage safe local Rive rules") { startActivity(Intent(this, RiveActivity::class.java)) }
        card(getString(R.string.permissions_title),getString(R.string.permission_panel_note))
        val preferences = getSharedPreferences("ui", MODE_PRIVATE)
        body.addView(androidx.appcompat.widget.SwitchCompat(this).apply {
            text = getString(R.string.reduce_motion); minHeight = dp(52); setTextColor(mint)
            isChecked = preferences.getBoolean("reduce_motion", false)
            setOnCheckedChangeListener { _, checked -> preferences.edit().putBoolean("reduce_motion", checked).apply() }
        })
        action(getString(R.string.onboarding_reopen),getString(R.string.onboarding_reopen)) { startActivity(Intent(this,OnboardingActivity::class.java)) }
    }
    private fun showLlama() {
        start(getString(R.string.llama_title),getString(R.string.llama_subtitle)); val s=AgentController.state.value
        card(getString(R.string.local_engine),"${s.status}\n${getString(R.string.loaded_model)}: ${s.stats.loadedModel ?: getString(R.string.none)}")
        engineStatus = TextView(this).apply { text = s.error ?: getString(R.string.engine_live,s.status,s.stats.promptTokPerSec,s.stats.genTokPerSec,s.stats.nCtx); setTextColor(0xFFB8ABC9.toInt()); setPadding(0,dp(8),0,dp(8)) }
        body.addView(engineStatus)
        liveRam = TextView(this).apply { setTextColor(0xFFB8ABC9.toInt()); setPadding(0,dp(8),0,dp(8)) }
        body.addView(liveRam)
        pageJob = lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (selected == R.id.tab_llama) {
                    val am = getSystemService(ACTIVITY_SERVICE) as ActivityManager
                    val mi = ActivityManager.MemoryInfo(); am.getMemoryInfo(mi)
                    val ps = withContext(Dispatchers.IO) { android.os.Debug.MemoryInfo().also { android.os.Debug.getMemoryInfo(it) }.totalPss / 1024 }
                    liveRam?.text = getString(R.string.ram_live, mi.availMem/1048576, ps)
                    kotlinx.coroutines.delay(1000)
                }
            }
        }
        action(getString(R.string.choose_model),getString(R.string.choose_model_desc)){pickModel()}; action(getString(R.string.unload_model),getString(R.string.unload_model_desc)){AgentController.unload(this)}
        val am=getSystemService(ACTIVITY_SERVICE) as ActivityManager; val mi=ActivityManager.MemoryInfo(); am.getMemoryInfo(mi)
        card(getString(R.string.available_memory),"${mi.availMem/1048576} MB / ${mi.totalMem/1048576} MB")
        val roots = mutableListOf(File(filesDir,"models"))
        if (android.os.Build.VERSION.SDK_INT >= 30 && android.os.Environment.isExternalStorageManager()) {
            roots.add(File(android.os.Environment.getExternalStorageDirectory(),"Red/models"))
        }
        val models=roots.flatMap { it.listFiles().orEmpty().toList() }.filter{it.isFile&&it.name.endsWith(".gguf",true)}.sortedBy{it.name}
        card(getString(R.string.models_title),if(models.isEmpty())getString(R.string.no_models) else models.joinToString("\n"){ "${it.name} · ${it.length()/1048576} MB" })
        val modelChoice=Spinner(this).apply { adapter=ArrayAdapter(this@MainActivity,android.R.layout.simple_spinner_dropdown_item,if(models.isEmpty()) listOf(getString(R.string.no_models)) else models.map{it.name}); isEnabled=models.isNotEmpty()&&s.stats.loadedModel==null }
        body.addView(TextView(this).apply{text=getString(R.string.model_to_load);setTextColor(mint)}); body.addView(modelChoice)
        val contextSeek=SeekBar(this).apply { max=1536; progress=((if(s.stats.nCtx > 0) s.stats.nCtx else 2048).coerceIn(512,2048)-512); isEnabled=s.stats.loadedModel==null }
        body.addView(TextView(this).apply { text=getString(R.string.context_control,512+contextSeek.progress); setTextColor(mint); id=R.id.context_label })
        contextSeek.setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener { override fun onProgressChanged(b:SeekBar?,p:Int,u:Boolean){findViewById<TextView>(R.id.context_label)?.text=getString(R.string.context_control,512+p)}; override fun onStartTrackingTouch(b:SeekBar?){}; override fun onStopTrackingTouch(b:SeekBar?){} }); body.addView(contextSeek)
        val cores=Runtime.getRuntime().availableProcessors().coerceIn(1,8)
        val threads=Spinner(this).apply { adapter=ArrayAdapter(this@MainActivity,android.R.layout.simple_spinner_dropdown_item,(1..cores).map { getString(R.string.thread_count,it) }); setSelection((cores.coerceAtMost(4)-1).coerceAtLeast(0)); isEnabled=s.stats.loadedModel==null }
        body.addView(TextView(this).apply{text=getString(R.string.thread_control);setTextColor(mint)}); body.addView(threads)
        action(getString(R.string.load_model),getString(R.string.load_model_desc)){ val f=models.getOrNull(modelChoice.selectedItemPosition); if(f==null) toast(getString(R.string.no_model)) else AgentController.load(this,f.absolutePath,LoadParams(512+contextSeek.progress,128,64,threads.selectedItemPosition+1)) }
        action(getString(R.string.refresh_ram),getString(R.string.refresh_ram)){ showLlama() }
        card(getString(R.string.benchmark_title),getString(R.string.benchmark_unavailable))
        card(getString(R.string.import_safety),getString(R.string.import_safety_note))
    }
    private fun pickModel(){ modelPicker.launch(Intent(Intent.ACTION_OPEN_DOCUMENT).apply{type="*/*";addCategory(Intent.CATEGORY_OPENABLE)}) }
    private fun importModel(uri:Uri) { lifecycleScope.launch {
        val result=withContext(Dispatchers.IO) { try {
            val name=queryDisplayName(uri).orEmpty().substringAfterLast('/').substringAfterLast('\\').replace(Regex("[^A-Za-z0-9._-]"),"_")
            if(!name.endsWith(".gguf",true)) throw IllegalArgumentException(getString(R.string.gguf_only))
            val declared=contentResolver.query(uri,arrayOf(OpenableColumns.SIZE),null,null,null)?.use { c -> if(c.moveToFirst()&&!c.isNull(0))c.getLong(0) else -1L } ?: -1L
            if(declared>RuntimePolicy.MAX_MODEL_BYTES) throw IllegalArgumentException(getString(R.string.model_too_large))
            val dir=File(filesDir,"models").apply{mkdirs()}; val target=File(dir,name)
            if(target.exists() && AgentController.state.value.stats.loadedModel?.let{File(it).canonicalFile==target.canonicalFile}==true) throw IllegalArgumentException(getString(R.string.cannot_replace_loaded))
            val temp=File(dir,".$name.${System.nanoTime()}.partial"); var count=0L
            try { val input=contentResolver.openInputStream(uri) ?: throw IllegalArgumentException(getString(R.string.cannot_open)); input.use { ins -> temp.outputStream().buffered().use { out -> val buf=ByteArray(65536); while(true){ val n=ins.read(buf); if(n<0)break; count+=n; if(count>RuntimePolicy.MAX_MODEL_BYTES)throw IllegalArgumentException(getString(R.string.model_too_large)); out.write(buf,0,n) } } }; if(!RuntimePolicy.validModel(name,count))throw IllegalArgumentException(getString(R.string.invalid_model)); if(target.exists())throw IllegalArgumentException(getString(R.string.file_exists)); if(!temp.renameTo(target))throw IllegalStateException(getString(R.string.atomic_import_failed)); getString(R.string.imported_model,name) }
            finally { if(temp.exists())temp.delete() }
        } catch(e:Exception){ getString(R.string.import_failed,e.localizedMessage?:e.javaClass.simpleName) } }
        toast(result); if(selected==R.id.tab_llama)showLlama()
    } }
    private fun queryDisplayName(uri:Uri):String? = contentResolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use { if(it.moveToFirst())it.getString(0) else null }
    private fun showLogs() {
        start(getString(R.string.logs_title), getString(R.string.logs_subtitle))
        val search = TextInputEditText(this).apply {
            hint = getString(R.string.search_logs); setTextColor(0xFFF6F0FF.toInt()); minHeight = dp(52)
        }
        body.addView(search)
        val crash = File(filesDir, "crashes/last-crash.txt")
        if (crash.isFile) card(getString(R.string.crash_card), runCatching { crash.readText().take(4000) }.getOrDefault(getString(R.string.crash_unreadable)))
        action(getString(R.string.copy_logs), getString(R.string.copy_logs)) {
            lifecycleScope.launch {
                val data = withContext(Dispatchers.IO) {
                    val entries = (application as GreenApp).store.logs(search.text?.toString().orEmpty()).joinToString("\n") {
                        "${it.timestamp} ${it.level} ${it.component} ${it.code.orEmpty()} ${it.message}"
                    }
                    val report = if (crash.isFile) runCatching { crash.readText().take(4000) }.getOrDefault("") else ""
                    (entries + "\n\n" + report).take(100000)
                }
                (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(android.content.ClipData.newPlainText(getString(R.string.logs_title), data))
                toast(getString(R.string.logs_copied))
            }
        }
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(list)
        var searchJob: kotlinx.coroutines.Job? = null
        fun refresh(query: String) {
            searchJob?.cancel()
            searchJob = lifecycleScope.launch {
                kotlinx.coroutines.delay(150)
                val rows = withContext(Dispatchers.IO) { (application as GreenApp).store.logs(query) }
                list.removeAllViews()
                rows.forEach { e ->
                    val line = "${e.level} · ${e.component}${e.code?.let { " · $it" } ?: ""}\n${e.message}"
                    list.addView(TextView(this@MainActivity).apply {
                        text = line; setTextColor(if (e.level in listOf("ERROR", "WARN")) 0xFFFFA69D.toInt() else 0xFFCEDDD1.toInt())
                        setPadding(dp(8), dp(12), dp(8), dp(12)); minHeight = dp(48)
                        setOnLongClickListener {
                            (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(android.content.ClipData.newPlainText(getString(R.string.logs_title), line))
                            toast(getString(R.string.logs_copied)); true
                        }
                    })
                }
            }
        }
        search.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { refresh(s?.toString().orEmpty()) }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
        refresh("")
    }
    private fun showFiles(){start(getString(R.string.files_title),getString(R.string.files_subtitle)); val files=File(filesDir,"models").listFiles()?.filter{it.isFile}?.joinToString("\n"){ "${it.name} · ${it.length()/1048576} MB" }.takeUnless{it.isNullOrBlank()}?:getString(R.string.no_models); card(getString(R.string.models_title),files); action("Plugins · Phase 7", "Pack storage, import, approval and rollback") { startActivity(Intent(this, PluginsActivity::class.java)) } }
    private fun card(title:String,text:String){val box=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(16),dp(14),dp(16),dp(14));background=rounded(surface,16)};box.addView(TextView(this).apply{this.text=title;textSize=16f;setTextColor(mint);typeface=android.graphics.Typeface.DEFAULT_BOLD});box.addView(TextView(this).apply{this.text=text;textSize=14f;setTextColor(0xFFB8ABC9.toInt());setPadding(0,dp(7),0,0)});body.addView(box,LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=dp(12)})}
    private fun action(label:String,desc:String,run:()->Unit){body.addView(MaterialButton(this).apply{text=label;contentDescription=desc;minHeight=dp(52);setOnClickListener{run()}},LinearLayout.LayoutParams(-1,dp(58)))}
    private fun toast(text:String){Toast.makeText(this,text,Toast.LENGTH_LONG).show()}
    private fun rounded(color:Int,radius:Int)=android.graphics.drawable.GradientDrawable().apply{setColor(color);cornerRadius=dp(radius).toFloat()}
    private fun dp(v:Int)=(v*resources.displayMetrics.density).toInt()
}
