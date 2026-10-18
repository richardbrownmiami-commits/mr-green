package im.mrgreen.events

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import im.mrgreen.core.AgentService

/** Does not replay actions, restore a model, grant access, or enable monitoring by itself. */
class MonitoringBootReceiver : BroadcastReceiver() {
    override fun onReceive(context:Context,intent:Intent) {
        if(intent.action!=Intent.ACTION_BOOT_COMPLETED) return
        val prefs=context.getSharedPreferences("phase4_events",Context.MODE_PRIVATE)
        if(!prefs.getBoolean("enabled",false) || !prefs.getBoolean("restore_on_boot",false)) return
        try { AgentService.ensure(context) } catch(_:Exception) { /* MIUI may still prohibit autostart; reopen app manually. */ }
    }
}
