package im.mrgreen.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import im.mrgreen.R
import com.google.android.material.button.MaterialButton

class OnboardingActivity : AppCompatActivity() {
    private lateinit var status: TextView
    private val bg=0xFF0D0A14.toInt(); private val surface=0xFF1C1529.toInt(); private val mint=0xFFB98AFF.toInt()
    override fun onCreate(savedInstanceState:Bundle?){super.onCreate(savedInstanceState); render()}
    override fun onResume(){super.onResume(); if(::status.isInitialized)updateStatus()}
    private fun render(){
        window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        val scroll=ScrollView(this); val col=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(22),dp(24),dp(22),dp(24));setBackgroundColor(bg)}
        scroll.addView(col); setContentView(scroll)
        col.addView(TextView(this).apply{text=getString(R.string.onboarding_title);textSize=27f;setTextColor(-1);typeface=android.graphics.Typeface.DEFAULT_BOLD})
        col.addView(TextView(this).apply{text=getString(R.string.onboarding_intro);textSize=15f;setTextColor(0xFFB8ABC9.toInt());setPadding(0,dp(10),0,dp(16))})
        section(col,R.string.storage_title,R.string.storage_reason,R.string.open_storage){openStorage()}
        section(col,R.string.overlay_title,R.string.overlay_reason,R.string.open_overlay){openOverlay()}
        section(col,R.string.battery_title,R.string.battery_reason,R.string.open_battery){openBattery()}
        val miui=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(15),dp(14),dp(15),dp(14));background=rounded(surface)}
        miui.addView(TextView(this).apply{text=getString(R.string.miui_title);textSize=17f;setTextColor(mint);typeface=android.graphics.Typeface.DEFAULT_BOLD})
        miui.addView(TextView(this).apply{text=getString(R.string.miui_reason);textSize=14f;setTextColor(0xFFB8ABC9.toInt());setPadding(0,dp(7),0,dp(6))})
        val reports = getSharedPreferences("owner_setup", MODE_PRIVATE)
        listOf(R.string.miui_check_autostart,R.string.miui_check_battery,R.string.miui_check_popup).forEach { id -> miui.addView(CheckBox(this).apply{
            text=getString(id);setTextColor(0xFFF6F0FF.toInt());buttonTintList=android.content.res.ColorStateList.valueOf(mint)
            val key = resources.getResourceEntryName(id)
            isChecked = reports.getBoolean(key,false)
            setOnCheckedChangeListener { _, checked -> reports.edit().putBoolean(key,checked).apply() }
        }) }
        col.addView(miui,LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=dp(12)})
        val later=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(15),dp(14),dp(15),dp(14));background=rounded(surface)}
        later.addView(TextView(this).apply{text=getString(R.string.not_implemented_title);textSize=17f;setTextColor(mint)})
        later.addView(TextView(this).apply{text=getString(R.string.not_implemented_note);textSize=14f;setTextColor(0xFFB8ABC9.toInt());setPadding(0,dp(6),0,0)})
        col.addView(later,LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=dp(12)})
        status=TextView(this).apply{textSize=14f;setTextColor(mint);setPadding(0,dp(8),0,dp(8))}; col.addView(status); updateStatus()
        col.addView(MaterialButton(this).apply{text=getString(R.string.setup_done);setOnClickListener{finish()}},LinearLayout.LayoutParams(-1,dp(54)))
        col.addView(MaterialButton(this).apply{text=getString(R.string.skip_setup);setOnClickListener{finish()}},LinearLayout.LayoutParams(-1,dp(54)))
    }
    private fun section(parent:LinearLayout,title:Int,reason:Int,button:Int,open:()->Unit){val box=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(15),dp(14),dp(15),dp(12));background=rounded(surface)}
        box.addView(TextView(this).apply{text=getString(title);textSize=17f;setTextColor(mint);typeface=android.graphics.Typeface.DEFAULT_BOLD})
        box.addView(TextView(this).apply{text=getString(reason);textSize=14f;setTextColor(0xFFB8ABC9.toInt());setPadding(0,dp(7),0,dp(4))})
        box.addView(MaterialButton(this).apply{text=getString(button);setOnClickListener{runCatching{open()}.onFailure{Toast.makeText(this@OnboardingActivity,it.localizedMessage?:"",Toast.LENGTH_LONG).show()}}})
        parent.addView(box,LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=dp(12)})
    }
    private fun updateStatus(){if(!::status.isInitialized)return
        val storage=if(Build.VERSION.SDK_INT>=30) Environment.isExternalStorageManager() else false
        val overlay=Settings.canDrawOverlays(this)
        val battery=if(Build.VERSION.SDK_INT>=23) (getSystemService(POWER_SERVICE) as android.os.PowerManager).isIgnoringBatteryOptimizations(packageName) else true
        status.text=getString(R.string.permission_status,mark(storage),mark(overlay),mark(battery))
    }
    private fun mark(value:Boolean)=if(value)getString(R.string.status_granted) else getString(R.string.status_not_granted)
    private fun openStorage(){if(Build.VERSION.SDK_INT>=30)startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,Uri.parse("package:$packageName"))) else startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:$packageName")))}
    private fun openOverlay(){startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,Uri.parse("package:$packageName")))}
    private fun openBattery(){if(Build.VERSION.SDK_INT>=23)startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,Uri.parse("package:$packageName"))) else startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:$packageName")))}
    private fun rounded(color:Int)=android.graphics.drawable.GradientDrawable().apply{setColor(color);cornerRadius=dp(16).toFloat()}
    private fun dp(v:Int)=(v*resources.displayMetrics.density).toInt()
}
