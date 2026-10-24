package im.mrgreen.events

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.*
import android.provider.CallLog
import android.provider.MediaStore
import android.provider.Settings
import android.media.AudioManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.content.ContextCompat
import im.mrgreen.GreenApp
import im.mrgreen.data.GreenStore
import java.util.Calendar
import java.util.concurrent.Executors

/** Local-only, bounded event handling. Notification contents are never sent to a provider. */
class EventRuntime(context: Context, @Suppress("unused") private val store: GreenStore) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("phase4_events", Context.MODE_PRIVATE)
    private val lock = Any()
    private val io = Executors.newSingleThreadExecutor()
    @Volatile private var callObserver: ContentObserver? = null
    @Volatile private var mediaObserver: ContentObserver? = null
    @Volatile private var pendingMediaKind:String?=null
    @Volatile private var pendingMediaUntil=0L
    private val observerThreads = mutableMapOf<ContentObserver, HandlerThread>()

    fun start() { prefs.edit().putBoolean(KEY_ENABLED, true).apply(); refreshObservers(); if(app is GreenApp) try { im.mrgreen.core.AgentService.ensure(app) } catch(_:Exception) { store.log("WARN","EVENTS","E_PERMISSION","Android declined the monitoring foreground service","background") } }
    fun stop() { prefs.edit().putBoolean(KEY_ENABLED, false).apply(); unregisterObservers() }
    fun setMissedCallMonitoring(enabled: Boolean) { prefs.edit().putBoolean(KEY_CALLS, enabled).apply(); refreshObservers() }
    fun setMediaMonitoring(enabled: Boolean) { prefs.edit().putBoolean(KEY_MEDIA, enabled).apply(); refreshObservers() }
    fun isRunning() = prefs.getBoolean(KEY_ENABLED, false)
    fun isCallMonitoringEnabled() = prefs.getBoolean(KEY_CALLS, false)
    fun isMediaMonitoringEnabled() = prefs.getBoolean(KEY_MEDIA, false)
    fun notificationTextRetentionEnabled() = prefs.getBoolean(KEY_RETAIN_TEXT, false)
    fun setNotificationTextRetention(enabled: Boolean) { prefs.edit().putBoolean(KEY_RETAIN_TEXT, enabled).apply() }
    fun setProactive(enabled: Boolean) { prefs.edit().putBoolean("proactive", enabled).apply() }
    fun setQuietHours(from: Int, until: Int) { require(from in 0..23 && until in 0..23); prefs.edit().putInt("quiet_from", from).putInt("quiet_until", until).apply() }
    fun setMinimumAlertIntervalMinutes(minutes: Int) { require(minutes in 1..1440); prefs.edit().putInt("alert_interval_minutes", minutes).apply() }
    fun setAllowedPackages(packages: Set<String>) { prefs.edit().putStringSet("allowed_packages", packages.map { safePkg(it) }.filter { it.isNotEmpty() }.toSet()).apply() }
    fun hasNotificationAccess(): Boolean = Settings.Secure.getString(app.contentResolver, "enabled_notification_listeners")?.split(':')?.any { ComponentName.unflattenFromString(it)?.packageName == app.packageName } == true

    internal fun recordNotification(pkg: String, title: String?, body: String?) = synchronized(lock) {
        val allowed = prefs.getStringSet("allowed_packages", emptySet()).orEmpty()
        if (!isRunning() || (allowed.isNotEmpty() && safePkg(pkg) !in allowed)) return@synchronized
        val keep = notificationTextRetentionEnabled()
        append(EventRecord("notification", safePkg(pkg), if (keep) clean(title) else "", if (keep) clean(body) else "", System.currentTimeMillis()))
        emitProactive("A new notification arrived.")
    }
    internal fun recordMissedCall(time: Long) = synchronized(lock) { if (isRunning()) { append(EventRecord("missed_call", "phone", "", "", time)); emitProactive("You may have a missed call.") } }
    fun recordTaskDue(taskLabel: String) = synchronized(lock) { if (isRunning()) { append(EventRecord("task_due", "task", clean(taskLabel), "", System.currentTimeMillis())); emitProactive("A task is due.") } }
    fun events(): List<EventRecord> = synchronized(lock) { readRecords() }
    fun recordTimerFired(timerLabel: String = "Timer") = synchronized(lock) { if (isRunning()) { append(EventRecord("timer_fired", "timer", clean(timerLabel), "", System.currentTimeMillis())); emitProactive("A timer finished.") } }
    fun dailySummary(): String {
        val since = System.currentTimeMillis() - 24L * 60 * 60 * 1000
        val counts = events().filter { it.time >= since }.groupingBy { it.kind }.eachCount()
        return if (counts.isEmpty()) "No events recorded in the last 24 hours." else "Last 24 hours (on this device): " + counts.entries.joinToString(", ") { "${it.value} ${it.key.replace('_',' ')}" } + "."
    }

    suspend fun execute(request: String): String? {
        val q = request.trim().lowercase(); if (q.isEmpty()) return null
        if(pendingMediaKind!=null && android.os.SystemClock.elapsedRealtime()<pendingMediaUntil && !(q.contains("play") && (q.contains("music")||q.contains("song")||q.contains("video")))) {
            if(q in setOf("cancel","stop","never mind")) {pendingMediaKind=null;return "Media request cancelled."}
            if(q.length<=120 && !Regex("^(what|how|why|when|who|can|do|is|are|set|open|save|read|show|check|summarize|search)\\b").containsMatchIn(q)) {
                val kind=pendingMediaKind;pendingMediaKind=null;return execute("play $kind $request")
            }
        }
        if (q.contains("summary") && (q.contains("daily") || q.contains("event"))) return dailySummary()
        if (q.contains("notification") && (q.contains("read") || q.contains("show"))) {
            if (!hasNotificationAccess()) return "Notification access is off. Enable it in Android Settings to collect new notifications."
            val notes=events().filter{it.kind=="notification"}.takeLast(5)
            return if(notificationTextRetentionEnabled()) { if(notes.isEmpty()) "No collected notifications." else "Recent local notifications: " + notes.joinToString("; "){"${it.source}: ${it.title} ${it.body}"} } else "${events().count { it.kind == "notification" }} notification events recorded locally. Text retention is off; enable it explicitly to read future notification text."
        }
        if (q.contains("missed call")) return if (ContextCompat.checkSelfPermission(app, Manifest.permission.READ_CALL_LOG) != PackageManager.PERMISSION_GRANTED) "Missed-call monitoring needs your opt-in and Android Call log permission." else "Missed-call monitoring is ${if (isCallMonitoringEnabled()) "on" else "off"}; ${events().count { it.kind == "missed_call" }} events recorded locally."
        if (q.contains("play") && (q.contains("music") || q.contains("song") || q.contains("video"))) {
            val video = q.contains("video"); val query = q.replace(Regex("\\b(play|music|song|video|please|a|the)\\b"), " ").trim()
            if (query.isBlank()) {pendingMediaKind=if(video) "video" else "music";pendingMediaUntil=android.os.SystemClock.elapsedRealtime()+90_000;return "Tell me the song, artist, or video to search for."}
            pendingMediaKind=null
            val matches = findMedia(query, video)
            if (matches.size > 1) return "I found ${matches.size} matches for '$query'. Please specify the artist or a more specific title."
            val item = matches.firstOrNull()
            if (item != null) {
                val intent = Intent(Intent.ACTION_VIEW).setDataAndType(item.uri, item.mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                val mx = listOf("com.mxtech.videoplayer.ad","com.mxtech.videoplayer.pro").map{Intent(intent).setPackage(it)}.firstOrNull{it.resolveActivity(app.packageManager)!=null}
                try {
                    if (mx != null) app.startActivity(mx)
                    else if (intent.resolveActivity(app.packageManager) != null) app.startActivity(Intent.createChooser(intent, "Play with").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    else return "Found ${item.label}, but no compatible player is installed."
                } catch (_: Exception) { return "Could not open ${item.label}. Playback was not verified." }
                kotlinx.coroutines.delay(500)
                val active=(app.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager)?.isMusicActive==true
                return if(active) "Opened ${item.label}; Android reports active audio. Track identity remains unverified." else "Opened ${item.label} in a player. Playback is unverified; Android does not yet report active music."
            }
            val web = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(query))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try { app.startActivity(web) } catch (_: Exception) { return "No matching local media or web player could be opened." }
            return "Opened a YouTube search for $query; playback is not verified."
        }
        return null
    }

    private data class MediaItem(val uri: Uri, val mime: String, val label: String)
    private fun findMedia(query: String, video: Boolean): List<MediaItem> {
        if (ContextCompat.checkSelfPermission(app, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) return emptyList()
        val base = if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = if (video) arrayOf(MediaStore.Video.Media._ID, MediaStore.Video.Media.TITLE, MediaStore.Video.Media.MIME_TYPE) else arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.MIME_TYPE)
        val result = mutableListOf<MediaItem>()
        try { app.contentResolver.query(base, projection, null, null, "${if (video) MediaStore.Video.Media.DATE_ADDED else MediaStore.Audio.Media.DATE_ADDED} DESC")?.use { c ->
            val id = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID); val title = c.getColumnIndexOrThrow(MediaStore.MediaColumns.TITLE); val mime = c.getColumnIndex(MediaStore.MediaColumns.MIME_TYPE); val artist = if (video) -1 else c.getColumnIndex(MediaStore.Audio.Media.ARTIST)
            var scanned = 0
            while (c.moveToNext() && scanned++ < MAX_SCAN && result.size < MAX_MATCHES) {
                val name = c.getString(title) ?: continue; val creator = if (artist >= 0) c.getString(artist).orEmpty() else ""
                if (!"$name $creator".contains(query, true)) continue
                val uri = Uri.withAppendedPath(base, c.getLong(id).toString())
                result += MediaItem(uri, if (mime >= 0) c.getString(mime) ?: if (video) "video/*" else "audio/*" else if (video) "video/*" else "audio/*", if (creator.isBlank()) name else "$name — $creator")
            }
        } } catch (_: SecurityException) { }
        return result
    }

    fun isMusicActive(): Boolean = (app.getSystemService(Context.AUDIO_SERVICE) as AudioManager).isMusicActive
    fun awaitPlaybackVerification(timeoutMs: Long = 5000, intervalMs: Long = 250, callback: (Boolean) -> Unit) {
        io.execute { val until = System.currentTimeMillis() + timeoutMs; var active = false; while (System.currentTimeMillis() < until) { if (isMusicActive()) { active = true; break }; Thread.sleep(intervalMs) }; callback(active) }
    }
    fun canNotifyProactively(now: Calendar = Calendar.getInstance()): Boolean = isRunning() && ProactivePolicy.allowed(prefs.getBoolean("proactive", false), now.get(Calendar.HOUR_OF_DAY), prefs.getInt("quiet_from", 22), prefs.getInt("quiet_until", 8), System.currentTimeMillis() - prefs.getLong("last_proactive", 0L), prefs.getInt("alert_interval_minutes", 60))
    fun markProactiveSent(): Boolean = synchronized(lock) { if (!canNotifyProactively()) false else { prefs.edit().putLong("last_proactive", System.currentTimeMillis()).apply(); true } }
    private fun emitProactive(message: String) {
        if (!markProactiveSent()) return
        val manager = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager; val channel = "phase4_events"
        if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(NotificationChannel(channel, "Event alerts", NotificationManager.IMPORTANCE_DEFAULT))
        @Suppress("DEPRECATION") val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(app, channel) else Notification.Builder(app)
        builder.setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle("Mr Green event alert").setContentText(message).setAutoCancel(true)
        runCatching { manager.notify(4104, builder.build()) }
    }
    private fun refreshObservers() {
        unregisterObservers()
        if (!isRunning()) return
        if (isCallMonitoringEnabled() && ContextCompat.checkSelfPermission(app, Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED) register(CallLog.Calls.CONTENT_URI) { pollMissedCalls() }?.also { callObserver = it }
        if (isMediaMonitoringEnabled() && ContextCompat.checkSelfPermission(app, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) register(MediaStore.Files.getContentUri("external")) { scanRecentMedia() }?.also { mediaObserver = it }
    }
    private fun register(uri: Uri, action: () -> Unit): ContentObserver? {
        val handlerThread = HandlerThread("event-observer").apply { start() }
        val obs = object : ContentObserver(Handler(handlerThread.looper)) { override fun onChange(selfChange: Boolean, changed: Uri?) { io.execute(action) } }
        return try { app.contentResolver.registerContentObserver(uri, true, obs); synchronized(observerThreads) { observerThreads[obs] = handlerThread }; obs } catch (_: SecurityException) { handlerThread.quitSafely(); null }
    }
    private fun unregisterObservers() { listOfNotNull(callObserver, mediaObserver).forEach { observer -> runCatching { app.contentResolver.unregisterContentObserver(observer) }; synchronized(observerThreads) { observerThreads.remove(observer) }?.quitSafely() }; callObserver = null; mediaObserver = null }
    private fun pollMissedCalls() {
        if (!isRunning() || !isCallMonitoringEnabled() || ContextCompat.checkSelfPermission(app, Manifest.permission.READ_CALL_LOG) != PackageManager.PERMISSION_GRANTED) return
        val since = initialCursor("call_cursor", CallLog.Calls.CONTENT_URI, CallLog.Calls.DATE)
        try { app.contentResolver.query(CallLog.Calls.CONTENT_URI, arrayOf(CallLog.Calls.DATE, CallLog.Calls.TYPE), "${CallLog.Calls.DATE}>? AND ${CallLog.Calls.TYPE}=?", arrayOf(since.toString(), CallLog.Calls.MISSED_TYPE.toString()), "${CallLog.Calls.DATE} ASC")?.use { c -> var max = since; var count = 0; while (count++ < MAX_SCAN && c.moveToNext()) { val t=c.getLong(0); recordMissedCall(t); max=maxOf(max,t) }; if(max>since) prefs.edit().putLong("call_cursor",max).apply() } } catch (_: SecurityException) { }
    }
    private fun scanRecentMedia() {
        if (!isRunning() || !isMediaMonitoringEnabled() || ContextCompat.checkSelfPermission(app, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) return
        val cursor = initialCursor("media_cursor", MediaStore.Files.getContentUri("external"), MediaStore.MediaColumns.DATE_ADDED)
        val uri = MediaStore.Files.getContentUri("external"); val date = MediaStore.MediaColumns.DATE_ADDED
        try { app.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns._ID, date, MediaStore.MediaColumns.MIME_TYPE), "$date>?", arrayOf((cursor / 1000).toString()), "$date ASC")?.use { c -> var max=cursor; var n=0; while(n++ < MAX_SCAN && c.moveToNext()) { val t=c.getLong(1)*1000; max=maxOf(max,t); val mime=c.getString(2).orEmpty(); if(mime.startsWith("audio/")||mime.startsWith("video/")) synchronized(lock) { append(EventRecord("media_added", if(mime.startsWith("audio/")) "audio" else "video", "", "", t)) } }; if(max>cursor) prefs.edit().putLong("media_cursor",max).apply() } } catch (_: SecurityException) { }
    }
    private fun initialCursor(key: String, uri: Uri, column: String): Long { if (prefs.contains(key)) return prefs.getLong(key, 0L); val now=System.currentTimeMillis(); prefs.edit().putLong(key,now).apply(); return now }
    private fun readRecords(): List<EventRecord> = runCatching { prefs.getString("records", "")!!.lineSequence().filter { it.isNotBlank() }.mapNotNull { val p=it.split('\t'); if(p.size==5) EventRecord(p[0],p[1],p[2],p[3],p[4].toLongOrNull()?:0L) else null }.toList() }.getOrDefault(emptyList())
    private fun append(item: EventRecord) { prefs.edit().putString("records", (readRecords()+item).takeLast(MAX_EVENTS).joinToString("\n") { listOf(it.kind,it.source,it.title,it.body,it.time.toString()).joinToString("\t") }).apply() }
    private fun safePkg(s:String)=s.take(120).filter { it.isLetterOrDigit() || it in "._-" }
    private fun clean(s:String?)=(s?:"").replace(Regex("[\\t\\r\\n]")," ").take(300)
    companion object {
        @Volatile private var shared: EventRuntime? = null
        fun shared(context: Context, store: GreenStore): EventRuntime = shared ?: synchronized(this) { shared ?: EventRuntime(context.applicationContext, store).also { shared = it } }
        private const val KEY_ENABLED="enabled"; private const val KEY_CALLS="calls"; private const val KEY_MEDIA="media"; private const val KEY_RETAIN_TEXT="retain_notification_text"; private const val MAX_EVENTS=200; private const val MAX_SCAN=250; private const val MAX_MATCHES=8
    }
}

data class EventRecord(val kind:String,val source:String,val title:String,val body:String,val time:Long)

class GreenNotificationListener : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName) return
        val app = application as? GreenApp ?: return
        val extras=sbn.notification.extras
        EventRuntime.shared(applicationContext, app.store).recordNotification(sbn.packageName, extras.getCharSequence("android.title")?.toString(), extras.getCharSequence("android.text")?.toString())
    }
}
