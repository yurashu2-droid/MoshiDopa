package com.example.timecostview.tracking

import android.app.*
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.*
import android.os.*
import android.provider.Settings
import androidx.core.app.NotificationCompat
import com.example.timecostview.MainActivity
import com.example.timecostview.data.Store
import com.example.timecostview.domain.Cost
import com.example.timecostview.domain.MoneyDropScheduler
import com.example.timecostview.domain.Record
import com.example.timecostview.domain.TrackingSession
import com.example.timecostview.domain.CompletionGate
import com.example.timecostview.overlay.PriceTag
import com.example.timecostview.overlay.SessionReceiptOverlay
import java.time.Instant
import java.time.ZoneId

data class LiveState(val running: Boolean = false, val manual: Boolean = false, val title: String = "",
    val mode: String = "SPEND", val elapsed: Long = 0, val amount: Double = 0.0, val today: Double = 0.0,
    val result: Record? = null, val message: String = "")

class TrackingService : Service() {
    companion object {
        const val AUTO = "AUTO"
        const val MANUAL = "MANUAL"
        const val STOP = "STOP"
        @Volatile var state = LiveState()
            private set
    }
    private lateinit var store: Store
    private lateinit var tag: PriceTag
    private lateinit var receiptOverlay: SessionReceiptOverlay
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var session: TrackingSession
    private var manual = false
    private var foreground: String? = null
    private var lastPoll = 0L
    private var lastSave = 0L
    private var cursor = 0L
    private var lastCompleted: Record? = null
    private var notifiedCompletionId = -1L
    private var receiptEnabledForCompletion = true
    private val completion = CompletionGate()
    private val dropScheduler = MoneyDropScheduler()
    private val seen = mutableSetOf<String>()
    private var screenAvailable = false
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if(intent.action == Intent.ACTION_SCREEN_OFF) {
                if(!manual) finishSegment(System.currentTimeMillis(), SystemClock.elapsedRealtime())
                foreground = null; tag.hide(); receiptOverlay.hide(); screenAvailable = false
            }
        }
    }
    override fun onCreate() {
        super.onCreate(); store = Store(this); session = TrackingSession(store); tag = PriceTag(this)
        receiptOverlay = SessionReceiptOverlay(this) { completion.dismiss() }
        registerReceiver(screenReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF))
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("tracking", "時間の金額表示", NotificationManager.IMPORTANCE_LOW))
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if(intent == null || intent.action == STOP) { stopSelf(); return START_NOT_STICKY }
        if(state.running) return START_NOT_STICKY
        manual = intent.action == MANUAL
        lastCompleted = null
        notifiedCompletionId = -1L
        receiptOverlay.hide()
        if(!manual && (!Access.usage(this) || !Settings.canDrawOverlays(this))) {
            state = LiveState(message = "使用状況へのアクセスと重ねて表示の許可が必要です。")
            stopSelf(); return START_NOT_STICKY
        }
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 1, Intent(this, TrackingService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE)
        startForeground(10, NotificationCompat.Builder(this, "tracking").setSmallIcon(android.R.drawable.ic_menu_recent_history)
            .setContentTitle("TIME COST · 計測中").setContentText(if(manual) "手動の活動を計測しています" else "選択したアプリの時間を金額で表示しています")
            .setContentIntent(open).setOngoing(true).addAction(0, "計測を終了", stop).build())
        val now = System.currentTimeMillis()
        sessionStart = now
        cursor = now; seen.clear(); lastPoll = 0L; screenAvailable = unlocked()
        state = LiveState(running = true, manual = manual)
        if(manual) begin(
            app = "manual",
            title = intent.getStringExtra("title")?.take(60)?.ifBlank { "活動" } ?: "活動",
            mode = if(intent.getStringExtra("mode") == "INVEST") "INVEST" else "SPEND",
            wall = now,
            mono = SystemClock.elapsedRealtime(),
            project = intent.getStringExtra("project")?.take(80).orEmpty(),
            category = intent.getStringExtra("category")?.take(30).orEmpty(),
            note = intent.getStringExtra("note")?.take(240).orEmpty(),
        )
        handler.post(tick)
        return START_NOT_STICKY
    }
    private fun unlocked() = getSystemService(PowerManager::class.java).isInteractive && !getSystemService(KeyguardManager::class.java).isKeyguardLocked
    private fun begin(
        app: String,
        title: String,
        mode: String,
        wall: Long,
        mono: Long,
        project: String = "",
        category: String = "",
        note: String = "",
    ) {
        val r = Record(0, app, title, mode, wall, wall, 0, store.rate, project, category, note)
        session.begin(r, mono)
        dropScheduler.reset(requireNotNull(session.active).id, currentAmount = 0.0, nowMs = mono)
    }
    private fun finishSegment(wall: Long, mono: Long): Record? {
        val result = session.finish(wall, mono, manual) ?: return null
        dropScheduler.clear()
        tag.clearDropAnimation()
        return result
    }
    private fun rememberCompletion(record: Record?) {
        if(record != null && record.duration > 0) {
            // Capture the preference at session end so later setting changes
            // apply to the next completion, as the settings copy promises.
            receiptEnabledForCompletion = store.prefs.getBoolean("showSessionReceipt", true)
            completion.leave(record)
        }
    }
    private fun notifyCompletion(record: Record) {
        val open = PendingIntent.getActivity(this, 2, Intent(this, MainActivity::class.java).putExtra("receiptId", record.id), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val invest = record.mode == "INVEST"
        val first = if(invest) "自己投資明細 · ${record.title}" else "もしも給与明細 · ${record.title}"
        val second = if(invest) {
            "今回 ${Cost.time(record.duration)} · 制作への時間投資 ${Cost.money(record.amount)}"
        } else {
            "今回 ${Cost.time(record.duration)} · この時間、働いていたら ${Cost.money(record.amount)}"
        }
        getSystemService(NotificationManager::class.java).notify(11, NotificationCompat.Builder(this, "tracking")
            .setSmallIcon(android.R.drawable.ic_menu_recent_history)
            .setContentTitle(first)
            .setContentText(second)
            .setStyle(NotificationCompat.BigTextStyle().bigText("$first\n$second"))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setTimeoutAfter(30_000)
            .build())
    }
    @Suppress("DEPRECATION")
    private fun poll(now: Long, mono: Long) {
        // Overlap queries to tolerate a short delivery delay; each event is applied once.
        val events = getSystemService(UsageStatsManager::class.java).queryEvents((cursor - 3000).coerceAtLeast(0), now) ?: return
        val e = UsageEvents.Event()
        while(events.hasNextEvent()) {
            events.getNextEvent(e)
            if(e.timeStamp < cursor - 3000) continue
            val key = "${e.timeStamp}:${e.eventType}:${e.packageName}:${e.className}"
            if(!seen.add(key)) continue
            // Never import activity before the user's explicit start.
            if(e.timeStamp < sessionStart) continue
            val wall = e.timeStamp.coerceAtMost(now)
            val eventMono = mono - (now - wall).coerceAtLeast(0)
            when(e.eventType) {
                UsageEvents.Event.MOVE_TO_FOREGROUND -> {
                    val pkg = e.packageName ?: continue
                    if(foreground != pkg) {
                        rememberCompletion(finishSegment(wall, eventMono))
                        foreground = pkg
                        if(pkg in store.targets && unlocked()) {
                            val label = runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
                            begin(pkg, label, "SPEND", wall, eventMono)
                        }
                    }
                }
                UsageEvents.Event.MOVE_TO_BACKGROUND -> if(e.packageName == foreground) {
                    rememberCompletion(finishSegment(wall, eventMono)); foreground = null
                }
                UsageEvents.Event.SCREEN_NON_INTERACTIVE, UsageEvents.Event.KEYGUARD_SHOWN -> {
                    finishSegment(wall, eventMono); foreground = null
                }
            }
        }
        cursor = now
        seen.removeAll { it.substringBefore(':').toLong() < cursor - 3000 }
    }
    private var sessionStart = System.currentTimeMillis()
    private val tick = object : Runnable {
        override fun run() {
            try {
                val now = System.currentTimeMillis(); val mono = SystemClock.elapsedRealtime()
                val visible = unlocked()
                if(!manual && (!Access.usage(this@TrackingService) || !Settings.canDrawOverlays(this@TrackingService))) {
                    state = state.copy(message = "権限が解除されたため、計測を終了しました。")
                    stopSelf(); return
                }
                if(!manual) {
                    if(!visible) { finishSegment(now, mono); foreground = null }
                    if(visible && now - lastPoll >= 1000) { poll(now, mono); lastPoll = now }
                }
                completion.ready()?.takeIf { it.id != notifiedCompletionId }?.let {
                    lastCompleted = it
                    if(android.os.Build.VERSION.SDK_INT < 33 || checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED) runCatching { notifyCompletion(it) }
                    notifiedCompletionId = it.id
                }
                screenAvailable = visible
                var r = session.active
                if(r != null && !manual) {
                    val zone = ZoneId.systemDefault()
                    val nextDay = Instant.ofEpochMilli(r.start).atZone(zone).toLocalDate().plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
                    if(now >= nextDay) {
                        val boundaryMono = mono - (now - nextDay)
                        finishSegment(nextDay, boundaryMono)
                        begin(r.app, r.title, r.mode, nextDay, boundaryMono); r = session.active
                    }
                }
                if(r != null) {
                    val elapsed = session.elapsed(mono)
                    val amount = Cost.yen(elapsed, r.rate)
                    state = LiveState(true, manual, r.title, r.mode, elapsed, amount, session.todayBase + amount, lastCompleted)
                    if(now - lastSave >= 5000) { session.checkpoint(now, mono); lastSave = now }
                    val milestonesEnabled = store.prefs.getBoolean("milestones", true)
                    val motionEnabled = store.prefs.getBoolean("motion", true)
                    val overlayVisible = visible && !MainActivity.isVisible && Settings.canDrawOverlays(this@TrackingService)
                    val drop = if(!manual) dropScheduler.observe(
                        newSessionId = r.id,
                        amount = amount,
                        nowMs = mono,
                        enabled = milestonesEnabled,
                        overlayVisible = overlayVisible,
                        existingObject = tag.hasActiveDropAnimation,
                        dragging = tag.isDragging
                    ) else null
                    if(overlayVisible) {
                        receiptOverlay.hide()
                        tag.show("${r.title} · 今回", Cost.money(amount), Cost.money(session.todayBase + amount), spend = r.mode == "SPEND", motion = motionEnabled)
                        if(!milestonesEnabled) tag.clearDropAnimation()
                        drop?.let { tag.drop(it.item, motionEnabled) }
                    } else tag.hide()
                } else {
                    state = LiveState(true, manual, result = lastCompleted, message = "対象アプリを開くと表示が始まります")
                    tag.hide()
                    val completed = completion.ready()
                    if(completed != null && receiptEnabledForCompletion && visible && !MainActivity.isVisible && Settings.canDrawOverlays(this@TrackingService)) {
                        receiptOverlay.show(completed, store.prefs.getBoolean("motion", true))
                    } else receiptOverlay.hide()
                }
                handler.postDelayed(this, if(visible) 100 else 1000)
            } catch(e: Exception) {
                state = state.copy(message = "計測を停止しました。権限を確認して再開してください。")
                stopSelf()
            }
        }
    }
    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        val result = finishSegment(System.currentTimeMillis(), SystemClock.elapsedRealtime())
        tag.hide(); receiptOverlay.hide(); unregisterReceiver(screenReceiver); store.close()
        state = LiveState(result = result ?: completion.record ?: lastCompleted, message = state.message)
        super.onDestroy()
    }
}
