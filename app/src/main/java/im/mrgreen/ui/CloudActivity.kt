package im.mrgreen.ui

import android.os.Bundle
import android.view.Gravity
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/** Retained route for backward-compatible intents; cloud inference/settings are intentionally disabled. */
class CloudActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val message = TextView(this).apply {
            text = "Mr Green runs inference locally on this device. Cloud inference, model discovery, and credential settings are disabled."
            textSize = 18f; gravity = Gravity.CENTER; setPadding(48, 48, 48, 48)
        }
        setContentView(LinearLayout(this).apply { gravity = Gravity.CENTER; addView(message) })
    }
}
