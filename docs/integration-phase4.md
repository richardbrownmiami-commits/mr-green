# Phase 4 integration (events, proactive rules, media)

## Parent-owned wiring

The parent owns manifest and navigation wiring. This package requires `READ_CALL_LOG` and `READ_EXTERNAL_STORAGE`, the `GreenNotificationListener` notification-listener service, and `EventsActivity`. Do not replace existing application/activity/service declarations. Notification access is a special user-granted system setting, not a runtime permission. Call-log and external-storage permissions are requested at the point the user enables the corresponding opt-in. Target SDK 30 behavior applies. No phone-state or outgoing-call permission is used.

## Runtime API and storage

`im.mrgreen.events.EventRuntime(context, store)` exposes monitoring controls, `suspend execute(request)`, event queries, daily summaries, task-due and timer-fired hooks, playback status, and playback-verification polling. `GreenNotificationListener` routes posted notifications to local storage using the application-owned `GreenApp.store`; it does not construct a separate GreenStore per notification. Events are a private SharedPreferences ring capped at 200 records; the existing GreenStore schema is unchanged. Notification title/body retention is disabled by default and controlled by an explicit sensitive-data opt-in. An empty source allowlist means all third-party notification sources; otherwise only named package identifiers are stored.

## Behavior and safety

- Listener stores events only while monitoring is enabled and excludes Mr Green's own notifications. Notification content never goes to an AI/cloud provider. Generic local alert text does not contain event content.
- Missed-call polling and MediaStore observation are enabled only when runtime, the explicit feature toggle, and corresponding Android permission are all active. Observers dispatch work onto a background executor. Initial cursors are set to the current time, avoiding historical imports; polling scans are capped at 250 rows per pass, and retained event history is capped at 200. Android can delay observer delivery while the process is stopped; this is not a guaranteed background poller.
- Local audio/video lookup searches MediaStore titles and audio artist metadata, with at most 250 scanned rows and 8 matches per request. Any multiple-match result asks for artist or a more specific title rather than guessing. A selected media item is opened by content URI with read grant, using MX Player when available and then a compatible Android player. If there is no local match, a web search is opened. `AudioManager.isMusicActive` and `awaitPlaybackVerification` can verify active playback after launch; a successful intent launch alone is never reported as verified playback.
- Proactive alerts default off, respect configurable quiet-hour bounds and a configurable 1–1440 minute minimum interval (default 60). Sources include newly recorded notifications, missed calls, and the public `recordTaskDue` and `recordTimerFired` hooks. `dailySummary()` produces an on-device aggregate without exporting event text.
- `EventsActivity` shows current access/permission state, requests the respective runtime permissions, and persists displayed feature toggles. Notification access remains a separate system setting. No permission grant or media-player behavior is claimed until observed on a device.

## Validation and limitations

Host policy tests are in `app/src/test/java/im/mrgreen/events/ProactivePolicyTest.kt`; they cover quiet-hour and rate-limit logic. Device acceptance still requires testing notification-listener authorization, permission denial/revocation, MIUI lifecycle behavior, real MediaStore content grants, MX Player/chooser behavior, and `isMusicActive` verification. No physical-device test is claimed. Existing external UI outcome remains pending; this implementation does not label external actions verified without a runtime signal.
