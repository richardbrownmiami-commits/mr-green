package im.mrgreen.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.speech.SpeechRecognizer
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/** No special access is granted by the app: every optional permission remains user initiated. */
class CapabilitySetupActivity : AppCompatActivity() {
    private lateinit var column: LinearLayout
    private var voice: im.mrgreen.extensions.AndroidVoice? = null
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); voice=im.mrgreen.extensions.AndroidVoice(this) }
    override fun onResume() { super.onResume(); render() }
    private fun render() {
        column=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(24,24,24,24) }
        column.addView(TextView(this).apply { text="Capability setup · optional access"; textSize=23f })
        column.addView(TextView(this).apply { text="Only enable what you use. MIUI autostart and pop-up behavior must be checked on your Redmi; these are not automated device results." })
        fun row(title:String, granted:Boolean, why:String, action:()->Unit) {
            column.addView(TextView(this).apply { text="$title · ${if(granted) "Enabled" else "Not enabled"}\n$why"; setPadding(0,18,0,0) })
            column.addView(Button(this).apply { text="Review $title"; setOnClickListener { runCatching(action).onFailure { Toast.makeText(this@CapabilitySetupActivity,"Not available on this device",Toast.LENGTH_LONG).show() } } })
        }
        fun permission(p:String) = ContextCompat.checkSelfPermission(this,p)==PackageManager.PERMISSION_GRANTED
        val enabled=Settings.Secure.getString(contentResolver,"enabled_accessibility_services").orEmpty()
        val accessibilityOn=Settings.Secure.getInt(contentResolver,Settings.Secure.ACCESSIBILITY_ENABLED,0)==1
        if(im.mrgreen.screen.ScreenReadPolicy.supportsSecureWindowCapture(android.os.Build.VERSION.SDK_INT))
            row("Screen assistant",accessibilityOn && CapabilityAccess.containsComponent(enabled,android.content.ComponentName(this,im.mrgreen.screen.ScreenAssistantService::class.java)),"Explicit bubble taps only; password/protected/blocklisted screens are denied.") { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        else column.addView(TextView(this).apply { text="Screen assistant unavailable: Android 11 cannot verify protected-window capture safely. No screen access is needed. Other capabilities remain available."; setPadding(0,18,0,0) })
        row("App recipes",accessibilityOn && CapabilityAccess.containsComponent(enabled,android.content.ComponentName(this,im.mrgreen.recipes.RecipeAccessibilityService::class.java)),"Optional automation. Exact messages need review before sending.") { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        val listeners=Settings.Secure.getString(contentResolver,"enabled_notification_listeners").orEmpty()
        row("Notification access",CapabilityAccess.containsComponent(listeners,android.content.ComponentName(this,im.mrgreen.events.GreenNotificationListener::class.java)),"Read selected notification events locally; text retention is opt-in.") { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
        row("Call log",permission(Manifest.permission.READ_CALL_LOG),"Optional missed-call events; never sent to cloud.") { requestPermissions(arrayOf(Manifest.permission.READ_CALL_LOG),91) }
        row("Microphone",permission(Manifest.permission.RECORD_AUDIO),"Voice input uses the installed Android recognizer. Install its offline language pack first.") { requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO),92) }
        row("Flashlight camera permission",permission(Manifest.permission.CAMERA),"Only needed for the torch action; no camera images are collected.") { requestPermissions(arrayOf(Manifest.permission.CAMERA),93) }
        row("Media library",permission(Manifest.permission.READ_EXTERNAL_STORAGE),"Optional local song/video lookup; document import uses a file picker.") { requestPermissions(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE),94) }
        row("Overlay",Settings.canDrawOverlays(this),"The accessibility bubble uses its service overlay. Broad overlay access is optional.") { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,Uri.parse("package:$packageName"))) }
        column.addView(Button(this).apply { text="Test TTS"; setOnClickListener { voice?.speak("Mr Green voice output is ready.") } })
        column.addView(Button(this).apply { text="Install voice data"; setOnClickListener { runCatching { startActivity(Intent(android.speech.tts.TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA)) } } })
        column.addView(Button(this).apply { text="Test speech availability"; setOnClickListener { Toast.makeText(this@CapabilitySetupActivity,if(SpeechRecognizer.isRecognitionAvailable(this@CapabilitySetupActivity)) "Recognizer found. Test offline recognition in voice controls with airplane mode." else "No speech recognizer installed. Typing remains available.",Toast.LENGTH_LONG).show() } })
        column.addView(Button(this).apply { text="Open Android app settings / MIUI checks"; setOnClickListener { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:$packageName"))) } })
        setContentView(ScrollView(this).apply { addView(column) })
    }
    override fun onDestroy() { voice?.close(); super.onDestroy() }
}
