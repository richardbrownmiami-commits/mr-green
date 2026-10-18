package im.mrgreen.extensions

import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import java.util.Locale

/** Permission-gated controls for Android-managed wallpaper, Bluetooth, appearance and speech. */
class ExtensionsActivity : AppCompatActivity() {
    private lateinit var root: LinearLayout
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 20, 24, 24); setBackgroundColor(ThemeManager.palette(this@ExtensionsActivity).background) }
        val scroll = ScrollView(this).apply { addView(root) }; setContentView(scroll)
        label("Device & appearance", 24f)
        label("These controls use Android's own settings and confirmation dialogs. No setting changes silently.")
        button("Set wallpaper…") { startActivity(Intent(Intent.ACTION_ATTACH_DATA).setDataAndType(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "image/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }
        button("Bluetooth settings…") { startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
        button("Set system theme…") { startActivity(Intent(Settings.ACTION_DISPLAY_SETTINGS)) }
        label("Mr Green appearance", 18f)
        button("Choose app theme") { chooseTheme() }
        button("Choose accent colour") { chooseAccent() }
        label("Companion tools (separately installed apps)", 18f)
        button("Review installed companions…") { reviewCompanions() }
        label("Signer approval is explicit; each invocation confirms exact arguments.")
        label("Speech (Android built-in; no online speech service)", 18f)
        button("Text-to-speech settings / install voice data") { startActivity(Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA)) }
        button("Test Android text-to-speech") { AndroidVoice(this).speak("Text to speech is provided by your installed Android speech engine.") }
        button("Test offline speech recognition") { launchOfflineStt() }
        label("Speech recognition requires an installed offline language pack. If unavailable, typing remains available.")
    }
    @Suppress("DEPRECATION")
    private fun reviewCompanions() {
        val services = packageManager.queryIntentServices(Intent(CompanionWire.ACTION), PackageManager.GET_META_DATA)
        val packages = services.mapNotNull { it.serviceInfo?.packageName }.distinct().filter { it != packageName }
        if (packages.isEmpty()) { AlertDialog.Builder(this).setMessage("No installed companion advertises the Mr Green contract.").setPositiveButton("Close", null).show(); return }
        AlertDialog.Builder(this).setTitle("Select companion").setItems(packages.toTypedArray()) { _, i -> reviewSigner(packages[i]) }.show()
    }
    @Suppress("DEPRECATION")
    private fun reviewSigner(pkg: String) {
        val info = packageManager.getPackageInfo(pkg, if (android.os.Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES)
        val bytes = if (android.os.Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners?.firstOrNull()?.toByteArray() else info.signatures?.firstOrNull()?.toByteArray()
        if (bytes == null) { Toast.makeText(this, "Installed package has no readable signing certificate.", Toast.LENGTH_LONG).show(); return }
        val signer = CompanionTrust.sha256(bytes)
        val store = PersistentCompanionApprovalStore(this)
        val client = CompanionMessengerClient(this, CompanionTrust(this, store), store)
        AlertDialog.Builder(this).setTitle("Trust review: $pkg")
            .setMessage("Installed signer SHA-256:\n$signer\n\nApprove this exact package and signer only if independently verified.")
            .setNegativeButton("Cancel", null)
            .setNeutralButton("Revoke") { _, _ -> store.revoke(pkg) }
            .setPositiveButton("Approve signer") { _, _ -> store.approveAfterUserConfirmation(pkg, signer); inspectTools(client, pkg, signer) }
            .show()
    }

    private fun inspectTools(client: CompanionMessengerClient, pkg: String, signer: String) {
        Thread {
            val found = runCatching { client.inspect(pkg, signer) }
            runOnUiThread {
                val descriptor = found.getOrElse {
                    Toast.makeText(this, "Inspection failed: ${it.message}", Toast.LENGTH_LONG).show()
                    return@runOnUiThread
                }
                AlertDialog.Builder(this).setTitle("Review tools: $pkg")
                    .setItems(descriptor.tools.map { "${it.name} — ${it.description}" }.toTypedArray()) { _, index ->
                        val tool = descriptor.tools[index]
                        val input = EditText(this).apply { hint = "JSON arguments"; setText("{}") }
                        AlertDialog.Builder(this).setTitle("Call ${tool.name}")
                            .setMessage("Package: $pkg\nTool: ${tool.name}\nArguments:")
                            .setView(input)
                            .setNegativeButton("Cancel", null)
                            .setPositiveButton("Review exact call") { _, _ ->
                                val args = input.text.toString()
                                AlertDialog.Builder(this).setTitle("Confirm invocation")
                                    .setMessage("Package: $pkg\nTool: ${tool.name}\nArguments sent verbatim:\n$args")
                                    .setNegativeButton("Cancel", null)
                                    .setPositiveButton("Confirm") { _, _ -> Thread {
                                        val answer = runCatching {
                                            client.invoke(pkg, signer, tool.name, args, setOf(tool.name), { _, _, _ -> true })
                                        }
                                        runOnUiThread {
                                            Toast.makeText(this, answer.fold({ "Result: $it" }, { "Call failed: ${it.message}" }), Toast.LENGTH_LONG).show()
                                        }
                                    }.start() }.show()
                            }.show()
                    }.setNegativeButton("Close", null).show()
            }
        }.start()
    }
    private fun chooseTheme() { val modes = ThemeManager.Mode.values(); AlertDialog.Builder(this).setTitle("App theme").setItems(modes.map { it.name.lowercase().replaceFirstChar(Char::uppercase) }.toTypedArray()) { _, i -> ThemeManager.setMode(this, modes[i]); recreate() }.show() }
    private fun chooseAccent() { AlertDialog.Builder(this).setTitle("Accent colour").setItems(ThemeManager.accents.indices.map { "Accent ${it + 1}" }.toTypedArray()) { _, i -> ThemeManager.setAccent(this, i); recreate() }.show() }
    private fun launchOfflineStt() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) { AlertDialog.Builder(this).setMessage("Speech recognition is not installed. Install an offline language pack in your Android speech settings, or type your message.").setPositiveButton("Settings") { _, _ -> startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) }.setNegativeButton("Close", null).show(); return }
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM).putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true).putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
        runCatching { startActivityForResult(i, 1001) }.onFailure { Toast.makeText(this, "Offline recognition unavailable; typing still works.", Toast.LENGTH_LONG).show() }
    }
    @Deprecated("Android recognition activity result API") override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) { super.onActivityResult(requestCode, resultCode, data); if (requestCode == 1001 && resultCode == Activity.RESULT_OK) Toast.makeText(this, data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull() ?: "No speech result", Toast.LENGTH_LONG).show() }
    private fun label(value: String, size: Float = 15f) { root.addView(TextView(this).apply { text = value; textSize = size; setTextColor(ThemeManager.palette(this@ExtensionsActivity).text); setPadding(0, 10, 0, 10) }) }
    private fun button(text: String, action: () -> Unit) { root.addView(Button(this).apply { this.text = text; minHeight = 52; setOnClickListener { action() } }) }
}

/** Small TTS facade; caller owns lifecycle and must close it. */
class AndroidVoice(context: Context) : TextToSpeech.OnInitListener, AutoCloseable {
    private val tts = TextToSpeech(context.applicationContext, this)
    @Volatile private var ready = false
    override fun onInit(status: Int) { ready = status == TextToSpeech.SUCCESS }
    fun speak(text: String, rate: Float = 1f) { if (!ready || text.isBlank()) return; tts.setSpeechRate(rate.coerceIn(0.5f, 2f)); if (android.os.Build.VERSION.SDK_INT >= 21) tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "mrgreen-${text.hashCode()}") else @Suppress("DEPRECATION") tts.speak(text, TextToSpeech.QUEUE_FLUSH, null) }
    override fun close() { tts.stop(); tts.shutdown() }
}
