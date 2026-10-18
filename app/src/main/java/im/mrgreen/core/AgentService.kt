package im.mrgreen.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.content.ContextCompat
import im.mrgreen.GreenApp
import im.mrgreen.ui.MainActivity

class AgentService : Service() {
    companion object {
        const val CHANNEL = "mrgreen_agent"
        const val STOP = "im.mrgreen.STOP_AGENT"
        fun ensure(context: Context) {
            ContextCompat.startForegroundService(context.applicationContext, Intent(context.applicationContext, AgentService::class.java))
        }
    }
    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, getString(im.mrgreen.R.string.engine_channel), NotificationManager.IMPORTANCE_LOW).apply {
            description = getString(im.mrgreen.R.string.engine_channel_description)
        })
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, AgentService::class.java).setAction(STOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(im.mrgreen.R.drawable.ic_mrgreen_notification)
            .setContentTitle(getString(im.mrgreen.R.string.engine_notification_title))
            .setContentText(getString(im.mrgreen.R.string.engine_notification_text))
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, getString(im.mrgreen.R.string.stop), stop).build())
            .build()
        startForeground(1, notification)
        (application as GreenApp).logger.write("INFO", "SYS", "Foreground service started")
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) {
            AgentController.cancel()
            (application as GreenApp).roadmap.events.stop()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        return START_NOT_STICKY // Never replay a task or silently reload a model after a kill.
    }
    override fun onDestroy() {
        AgentController.releaseForServiceStop()
        (application as GreenApp).logger.write("INFO", "SYS", "Foreground service stopped")
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
}
