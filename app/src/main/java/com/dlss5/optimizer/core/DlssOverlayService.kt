package com.dlss5.optimizer.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.dlss5.optimizer.BuildConfig
import com.dlss5.optimizer.MainActivity
import com.dlss5.optimizer.R
import com.dlss5.optimizer.network.LocalStore
import com.dlss5.optimizer.network.SyncWorker
import com.dlss5.optimizer.util.DeviceGuards
import com.dlss5.optimizer.util.Haptics
import com.dlss5.optimizer.util.Logger
import com.dlss5.optimizer.util.Permissions
import com.dlss5.optimizer.util.Prefs
import com.dlss5.optimizer.util.Time

/**
 * Foreground service that owns the overlay + engine.
 *
 * Lifecycle contract (guarantees, all enforced here):
 *  * Started via [start]; returns `START_STICKY` so an OEM kill gets recreated with a null intent,
 *    which we handle by re-attaching if the user still had the engine on.
 *  * If the overlay permission has been revoked while we were alive, we stop cleanly and post a
 *    notification telling the user — we never attempt `addView` without permission.
 *  * Every entry point is wrapped: an exception in the engine or overlay tears the feature down and
 *    leaves the app alive.
 */
class DlssOverlayService : Service() {

    companion object {
        private const val TAG = "Service"
        private const val CHANNEL_ID = "dlss5_engine"
        private const val NOTIF_ID = 1001
        private const val ACTION_STOP = "com.dlss5.optimizer.action.STOP_ENGINE"
        private const val ACTION_TOGGLE_HUD = "com.dlss5.optimizer.action.TOGGLE_HUD"
        private const val ACTION_START = "com.dlss5.optimizer.action.START_ENGINE"

        const val EXTRA_CAPTURE_RESULT_CODE = "capture_result_code"
        const val EXTRA_CAPTURE_DATA = "capture_data"
        const val EXTRA_MODE = "mode"

        fun start(context: Context, mode: Int = -1) {
            try {
                val intent = Intent(context, DlssOverlayService::class.java).apply {
                    action = ACTION_START
                    if (mode >= 0) putExtra(EXTRA_MODE, mode)
                }
                ContextCompat.startForegroundService(context, intent)
            } catch (t: Throwable) {
                Logger.e(TAG, "start service failed: ${t.message}", t)
            }
        }

        fun stop(context: Context) {
            try {
                context.startService(
                    Intent(context, DlssOverlayService::class.java).apply { action = ACTION_STOP }
                )
            } catch (t: Throwable) {
                Logger.w(TAG, "stop service failed: ${t.message}")
            }
        }

        fun isRunning(context: Context): Boolean = Prefs.get(context).engineRunning
    }

    private lateinit var prefs: Prefs
    private lateinit var store: LocalStore
    private lateinit var profile: DeviceProfile
    private var overlay: OverlayController? = null
    private var engine: EngineController? = null
    private var capture: ScreenCaptureEngine? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastNotificationUpdate = 0L
    private var glFallbackToastShown = false
    private var hdrFrames = 0L

    private val stopReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            try {
                when (intent?.action) {
                    ACTION_STOP -> {
                        prefs.engineRunning = false
                        stopSelf()
                    }
                    ACTION_TOGGLE_HUD -> overlay?.toggleHud()
                }
            } catch (t: Throwable) {
                Logger.w(TAG, "receiver action failed: ${t.message}")
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        try {
            prefs = Prefs.get(this)
            store = LocalStore.get(this)
            profile = DeviceProfiler.profile(this)
            createChannel()
            val filter = IntentFilter().apply {
                addAction(ACTION_STOP)
                addAction(ACTION_TOGGLE_HUD)
            }
            ContextCompat.registerReceiver(
                this, stopReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED
            )
        } catch (t: Throwable) {
            Logger.e(TAG, "onCreate failed", t)
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return try {
            if (intent?.action == ACTION_STOP) {
                prefs.engineRunning = false
                shutdown("notification stop")
                return START_NOT_STICKY
            }

            // Android 14 requires the FGS type to be declared at start time.
            val foregroundOk = goForeground()
            if (!foregroundOk) {
                Logger.e(TAG, "could not enter foreground, aborting")
                stopSelf()
                return START_NOT_STICKY
            }

            if (intent?.hasExtra(EXTRA_MODE) == true) {
                val mode = intent.getIntExtra(EXTRA_MODE, prefs.mode)
                prefs.mode = mode
            }

            if (overlay?.isAttached() != true) {
                if (!Permissions.canDrawOverlays(this)) {
                    Logger.w(TAG, "overlay permission revoked, stopping")
                    notifyPermissionLost()
                    prefs.engineRunning = false
                    stopSelf()
                    return START_NOT_STICKY
                }
                if (!attachOverlay()) {
                    prefs.engineRunning = false
                    stopSelf()
                    return START_NOT_STICKY
                }
            }

            startEngine()
            acquireWakeLock()
            applyDisplayPolicy()

            val resultCode = intent?.getIntExtra(EXTRA_CAPTURE_RESULT_CODE, Int.MIN_VALUE) ?: Int.MIN_VALUE
            @Suppress("DEPRECATION")
            val projectionData = intent?.getParcelableExtra<Intent>(EXTRA_CAPTURE_DATA)
            if (resultCode != Int.MIN_VALUE && projectionData != null) {
                promoteForegroundForCapture()
                startCapture(resultCode, projectionData)
            }

            prefs.engineRunning = true
            SyncWorker.enqueue(this)
            updateNotification()
            START_STICKY
        } catch (t: Throwable) {
            Logger.e(TAG, "onStartCommand failed", t)
            shutdown("start failure")
            START_NOT_STICKY
        }
    }

    // ------------------------------------------------------------------ foreground

    @Volatile
    private var captureActive = false

    private fun goForeground(): Boolean = try {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notification, foregroundType())
        } else {
            startForeground(NOTIF_ID, notification)
        }
        true
    } catch (t: Throwable) {
        Logger.e(TAG, "startForeground failed: ${t.message}", t)
        // On API 34 a missing type declaration throws; retry with the plain overload once.
        try {
            startForeground(NOTIF_ID, buildNotification())
            true
        } catch (t2: Throwable) {
            false
        }
    }

    /**
     * Android 14 requires the mediaProjection type to be declared *at start time* for a projection
     * session to be allowed. We advertise it as soon as capture is requested and keep `specialUse` for
     * the overlay/governor part.
     */
    private fun foregroundType(): Int {
        var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        if (captureActive && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        }
        return type
    }

    private fun promoteForegroundForCapture() {
        try {
            captureActive = true
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIF_ID, buildNotification(), foregroundType())
            }
        } catch (t: Throwable) {
            Logger.w(TAG, "capture foreground promotion failed: ${t.message}")
        }
    }

    private fun createChannel() {
        try {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                    val channel = NotificationChannel(
                        CHANNEL_ID,
                        getString(R.string.notif_channel_name),
                        NotificationManager.IMPORTANCE_LOW
                    ).apply {
                        description = getString(R.string.notif_channel_desc)
                        enableLights(false)
                        enableVibration(false)
                        setShowBadge(false)
                        lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                    }
                    manager.createNotificationChannel(channel)
                }
            }
        } catch (t: Throwable) {
            Logger.w(TAG, "channel creation failed: ${t.message}")
        }
    }

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getBroadcast(
            this,
            1,
            Intent(ACTION_STOP).setPackage(packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val hudIntent = PendingIntent.getBroadcast(
            this,
            2,
            Intent(ACTION_TOGGLE_HUD).setPackage(packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val live = engine?.lastStats
        val text = if (live != null) {
            "%.0f fps · %.1f ms · %.2fx".format(live.fps, live.frameMs, live.renderScale)
        } else {
            "Initialising render governor…"
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText("${SessionRecord.modeName(prefs.mode).replace('_', ' ')} · $text")
            .setColor(Color.parseColor("#39FF14"))
            .setColorized(true)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openIntent)
            .addAction(0, getString(R.string.notif_action_stop), stopIntent)
            .addAction(0, "HUD", hudIntent)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun updateNotification() {
        try {
            val now = Time.nowMs()
            if (now - lastNotificationUpdate < 1500L) return
            lastNotificationUpdate = now
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(NOTIF_ID, buildNotification())
        } catch (t: Throwable) {
            Logger.w(TAG, "notification update failed: ${t.message}")
        }
    }

    private fun notifyPermissionLost() {
        try {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val intent = PendingIntent.getActivity(
                this,
                3,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            manager.notify(
                NOTIF_ID + 1,
                NotificationCompat.Builder(this, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle("DLSS-5 paused")
                    .setContentText(getString(R.string.overlay_permission_required))
                    .setAutoCancel(true)
                    .setContentIntent(intent)
                    .build()
            )
        } catch (t: Throwable) {
            Logger.w(TAG, "permission notification failed: ${t.message}")
        }
    }

    // ------------------------------------------------------------------ overlay & engine

    private fun attachOverlay(): Boolean {
        return try {
            val controller = OverlayController(
                context = this,
                onPuckTap = { overlay?.toggleHud() },
                onPuckLongPress = { overlay?.openMenu() },
                onMenuAction = { action -> handleMenuAction(action) }
            )
            val ok = controller.attach()
            if (!ok) return false
            overlay = controller
            controller.setStatus("ENGINE START", Color.parseColor("#39FF14"))
            controller.hud()?.setPanelVisible(true)
            true
        } catch (t: Throwable) {
            Logger.e(TAG, "attachOverlay failed", t)
            false
        }
    }

    private fun startEngine() {
        if (engine?.isRunning() == true) return
        try {
            val controller = EngineController(
                context = this,
                prefs = prefs,
                store = store,
                profile = profile,
                onStats = { stats ->
                    overlay?.updateStats(stats)
                    updateNotification()
                }
            )
            controller.settingsLanczos =
                FilterKernels.canAffordLanczos(profile.tier, resources.displayMetrics.widthPixels) &&
                prefs.mode != Prefs.MODE_GOVERNOR
            controller.start(mainHandler)
            engine = controller
            overlay?.setStatus("ENGINE LIVE", Color.parseColor("#39FF14"))
            Logger.i(TAG, "engine bound, mode=${SessionRecord.modeName(prefs.mode)}")

            if (prefs.safeMode) {
                controller.setAdaptiveEnabled(false)
                controller.restoreScale(0.75f)
                overlay?.setStatus("SAFE MODE", Color.parseColor("#FFC400"))
            }
            if (DeviceGuards.isBatterySaverOn(this)) {
                toastOnce("Battery saver detected — FPS boost will be limited.")
            }
        } catch (t: Throwable) {
            Logger.e(TAG, "startEngine failed", t)
            overlay?.setStatus("ENGINE ERROR", Color.parseColor("#FF2D55"))
            toastOnce(getString(R.string.gl_fallback))
        }
    }

    private fun handleMenuAction(action: OverlayController.MenuAction) {
        try {
            when (action) {
                OverlayController.MenuAction.STOP -> {
                    prefs.engineRunning = false
                    shutdown("puck menu")
                }
                OverlayController.MenuAction.TOGGLE_HUD -> overlay?.toggleHud()
                OverlayController.MenuAction.NEXT_MODE -> {
                    val next = (prefs.mode + 1) % 3
                    prefs.mode = next
                    engine?.setMode(next)
                    val label = SessionRecord.modeName(next).replace('_', ' ')
                    overlay?.setStatus(label, Color.parseColor("#00FFC6"))
                    toastOnce("Mode: $label")
                    Haptics.confirm(this)
                }
                OverlayController.MenuAction.TOGGLE_CAPTURE -> requestCaptureFromOverlay()
                OverlayController.MenuAction.TOGGLE_HDR -> {
                    val showing = overlay?.hdr()?.isShowing() == true
                    overlay?.setHdrVisible(!showing)
                    toastOnce(if (showing) "HDR layer hidden — raw game visible" else "HDR layer shown")
                }
                OverlayController.MenuAction.NEXT_HDR_PRESET -> cycleHdrPreset()
                OverlayController.MenuAction.SNAPSHOT -> requestSnapshot()
            }
        } catch (t: Throwable) {
            Logger.e(TAG, "menu action failed", t)
        }
    }

    /**
     * Turns a capture failure into something true and actionable.
     *
     *  * `hdr_unavailable`   → every HDR tier was rejected; we keep the upscale path instead of dumping
     *                          raw frames onto the screen (which would recurse through the capture).
     *  * `upscale_unavailable` → DLSS path could not render on this driver; governor keeps running.
     *  * anything else        → projection itself ended (revoked, screen off, another capture started).
     */
    private fun handleCaptureFailure(reason: String) {
        try {
            capture?.shutdown("failure: $reason")
            capture = null
            overlay?.setHdrVisible(false)
            when (reason) {
                "hdr_unavailable" -> {
                    // Do not leave the user staring at a black layer: switch to the DLSS upscale mode,
                    // which uses the same capture session with a different (simpler) shader chain.
                    overlay?.setStatus("UPSCALE MODE", Color.parseColor("#FFC400"))
                    val hadCapture = capture != null
                    if (!hadCapture) {
                        toastOnce("HDR shaders unavailable on this GPU — restarting in UPSCALE mode.")
                    }
                    prefs.mode = Prefs.MODE_UPSCALE
                    engine?.setMode(Prefs.MODE_UPSCALE)
                }
                "upscale_unavailable" -> {
                    overlay?.setStatus("GOVERNOR MODE", Color.parseColor("#FFC400"))
                    toastOnce("GPU upscaling is not supported here — GOVERNOR frame pacing is still active.")
                    prefs.mode = Prefs.MODE_GOVERNOR
                    engine?.setMode(Prefs.MODE_GOVERNOR)
                }
                else -> {
                    overlay?.setStatus("GOVERNOR MODE", Color.parseColor("#FFC400"))
                    toastOnce("Screen capture ended — GOVERNOR frame pacing continues.")
                    prefs.mode = Prefs.MODE_GOVERNOR
                    engine?.setMode(Prefs.MODE_GOVERNOR)
                }
            }
            SyncWorker.enqueue(this)
        } catch (t: Throwable) {
            Logger.e(TAG, "handleCaptureFailure failed", t)
        }
    }

    /** Grabs the next graded frame and writes it to the gallery as proof/share material. */
    private fun requestSnapshot() {
        try {
            if (prefs.mode != Prefs.MODE_HDR || capture == null) {
                toastOnce("Start HDR mode first, then take a snapshot.")
                return
            }
            pendingSnapshot = true
            toastOnce("Snapshot queued — saved to Pictures/${com.dlss5.optimizer.core.SnapshotSaver.albumName()}")
            Haptics.confirm(this)
        } catch (t: Throwable) {
            Logger.e(TAG, "requestSnapshot failed", t)
        }
    }

    @Volatile
    private var pendingSnapshot = false
    private var lastAutoSnapshotMs = 0L

    private fun maybeSaveSnapshot(bitmap: android.graphics.Bitmap, costMs: Long) {
        try {
            val wantManual = pendingSnapshot
            val autoDue = prefs.snapshotsEnabled &&
                (Time.nowMs() - lastAutoSnapshotMs) > 20_000L &&
                costMs in 0..200
            if (!wantManual && !autoDue) return
            pendingSnapshot = false
            lastAutoSnapshotMs = Time.nowMs()
            val label = HdrPreset.byId(prefs.hdrPresetId).label
            mainHandler.post {
                val saved = com.dlss5.optimizer.core.SnapshotSaver.save(
                    applicationContext, bitmap.copy(android.graphics.Bitmap.Config.ARGB_8888, false), label
                )
                toastOnce(
                    if (saved != null) "Saved to gallery: Pictures/${com.dlss5.optimizer.core.SnapshotSaver.albumName()}"
                    else "Could not save snapshot (storage busy)."
                )
            }
        } catch (t: Throwable) {
            Logger.w(TAG, "snapshot failed: ${t.message}")
        }
    }

    /** Cycles EXTREME HDR → CINEMATIC → VISIBILITY → VIVID → NATIVE and applies it live. */
    private fun cycleHdrPreset() {
        try {
            val next = HdrPreset.next(prefs.hdrPresetId)
            prefs.hdrPresetId = next.id
            capture?.setHdrPreset(next)
            overlay?.setHdrPresetLabel(next.label)
            overlay?.setStatus(next.label, Color.parseColor("#39FF14"))
            toastOnce("HDR preset: ${next.label} — ${next.description}")
            Haptics.confirm(this)
        } catch (t: Throwable) {
            Logger.e(TAG, "cycleHdrPreset failed", t)
        }
    }

    private fun requestCaptureFromOverlay() {
        try {
            if (capture?.isRunning() == true) {
                capture?.shutdown("user disabled capture")
                capture = null
                overlay?.setPreview(null, 0f)
                toastOnce("Capture disabled — governor only")
                return
            }
            val launch = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra(MainActivity.EXTRA_REQUEST_CAPTURE, true)
            }
            startActivity(launch)
        } catch (t: Throwable) {
            Logger.e(TAG, "capture request failed: ${t.message}")
        }
    }

    // ------------------------------------------------------------------ capture

    private fun startCapture(resultCode: Int, data: Intent) {
        try {
            capture?.shutdown("restart")
            capture = null
            promoteForegroundForCapture()

            val mode = prefs.mode
            val hdr = mode == Prefs.MODE_HDR
            // Base preset + any cloud-tuned override stored by the app (SoC-tier specific).
            val preset = HdrPreset.byId(prefs.hdrPresetId).let {
                HdrPreset.withRemote(it, prefs.remoteConfigJson?.let { raw ->
                    runCatching { org.json.JSONObject(raw).optJSONObject("hdr") }.getOrNull()
                })
            }
            Logger.i(TAG, "HDR preset ${preset.id} exp=${preset.exposure} sat=${preset.saturation} bloom=${preset.bloom}")
            // HDR mode runs the compositor at the preset's cadence; DLSS modes keep their own rates.
            // UPSCALE = cheap bicubic chain at a faster preview rate.
            // TRUE DLSS = Lanczos-3 + unsharp at a lower preview rate (quality over cadence).
            val trueDlss = mode == Prefs.MODE_TRUE_DLSS

            if (hdr) {
                overlay?.setHdrPresetLabel(preset.label)
                prefs.hdrPresetId = preset.id
            }

            capture = ScreenCaptureEngine(
                context = this,
                resultCode = resultCode,
                projectionData = data,
                profile = profile,
                onFrame = { bitmap, costMs ->
                    try {
                        if (hdr) {
                            overlay?.submitHdrFrame(bitmap, costMs)
                            overlay?.setHdrVisible(true)
                            maybeSaveSnapshot(bitmap, costMs)
                        } else {
                            // Preview of the upscale chain, deliberately faint so the real game stays
                            // dominant (an opaque copy would be re-captured by MediaProjection).
                            overlay?.setPreview(bitmap, 0.22f)
                        }
                        engine?.reportEngineCost(costMs)
                        hdrFrames++
                    } catch (t: Throwable) {
                        Logger.w(TAG, "frame delivery failed: ${t.message}")
                    }
                },
                onFailure = { reason ->
                    mainHandler.post {
                        try {
                            overlay?.setHdrVisible(false)
                            handleCaptureFailure(reason)
                        } catch (t: Throwable) {
                            Logger.w(TAG, "capture failure handling: ${t.message}")
                        }
                    }
                },
                preferLanczos = trueDlss,
                maxFps = when {
                    hdr -> preset.targetFps
                    trueDlss -> 12
                    else -> 20
                },
                hdrEnabled = hdr,
                hdrPreset = preset,
                hdrBlend = prefs.hdrBlend,
                captureScaleOverride = if (hdr) preset.captureScale else -1f,
                useFeedbackNeutralisation = prefs.feedbackNeutralise,
                upscaleOutput = !hdr
            ).also {
                it.setSharpness(prefs.sharpness)
                it.start()
            }

            overlay?.setStatus(
                when {
                    hdr -> preset.label
                    trueDlss -> "TRUE DLSS"
                    else -> "UPSCALE"
                },
                if (hdr) Color.parseColor("#39FF14") else Color.parseColor("#00FFC6")
            )
            engine?.setMode(mode)
            engine?.setHdrActive(hdr)
            Logger.i(TAG, "capture engine started mode=${SessionRecord.modeName(mode)} hdr=$hdr preset=${preset.id}")

            // Report which shader tier actually loaded (visible proof the GPU work is running), and if a
            // tier had to be downgraded say so precisely instead of blaming the device.
            mainHandler.postDelayed({
                try {
                    val active = capture ?: return@postDelayed
                    if (hdr) {
                        if (active.hdrActive()) {
                            val tier = active.hdrTier()
                            val bloom = if (active.compositorBloom()) "bloom" else "no-bloom"
                            overlay?.setStatus(preset.label, Color.parseColor("#39FF14"))
                            Logger.i(TAG, "HDR live tier=$tier $bloom frames=${active.framesEmitted()}")
                            when (tier) {
                                "software_cpu" -> toastOnce(
                                    "HDR running on the CPU (compatibility mode) — colour grade active, lower cadence."
                                )
                                "hdr_full" -> Unit
                                else -> toastOnce(
                                    "HDR compatibility tier active ($tier) — full colour grade still applies."
                                )
                            }
                        }
                    } else {
                        val tier = active.upscaleTier()
                        Logger.i(TAG, "upscale live tier=$tier frames=${active.framesEmitted()}")
                        if (tier == "linear_sharpen") {
                            toastOnce("Upscaling in compatibility mode — sharpen active.")
                        }
                    }
                } catch (t: Throwable) {
                    Logger.w(TAG, "health check failed: ${t.message}")
                }
            }, 2500L)
        } catch (t: Throwable) {
            Logger.e(TAG, "startCapture failed", t)
            overlay?.setHdrVisible(false)
            engine?.setMode(Prefs.MODE_GOVERNOR)
        }
    }

    // ------------------------------------------------------------------ power

    private val perfTuner by lazy { com.dlss5.optimizer.core.PerfTuner(this) }

    /**
     * Pins the display to the highest refresh rate this SoC class can actually hold. Frame-time variance
     * on 90/120 Hz panels is dominated by mode switching; locking it is one of the few *real* FPS wins
     * available to a non-root app.
     */
    private fun applyDisplayPolicy() {
        try {
            if (!prefs.lockRefreshRate) return
            val caps = perfTuner.capabilities()
            val target = perfTuner.recommendedRefreshRate(profile.tier, caps.supportedRefreshRates)
            val applied = overlay?.applyPreferredRefreshRate(target, profile.tier) == true
            if (applied) {
                Logger.i(TAG, "display pinned to ${"%.0f".format(target)}Hz (${paramsSummary(target)})")
            }
        } catch (t: Throwable) {
            Logger.w(TAG, "display policy failed: ${t.message}")
        }
    }

    private fun paramsSummary(hz: Float): String = "tier=${profile.tier.key} target=${hz.toInt()}Hz"

    private fun acquireWakeLock() {
        try {
            if (wakeLock?.isHeld == true) return
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DLSS5:engine").apply {
                setReferenceCounted(false)
                acquire(4 * 60 * 60 * 1000L) // hard 4h cap so a forgotten overlay can't drain the battery
            }
            Logger.d(TAG, "wake lock acquired")
        } catch (t: Throwable) {
            Logger.w(TAG, "wake lock failed: ${t.message}")
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (t: Throwable) {
            Logger.w(TAG, "wake release failed: ${t.message}")
        } finally {
            wakeLock = null
        }
    }

    // ------------------------------------------------------------------ config / teardown

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        try {
            profile = DeviceProfiler.profile(this)
            overlay?.onConfigurationChanged()
        } catch (t: Throwable) {
            Logger.w(TAG, "config change failed: ${t.message}")
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        // Swiping the app away must not kill a running overlay; START_STICKY + this re-post keeps it.
        try {
            if (prefs.engineRunning) {
                Logger.i(TAG, "task removed while running, keeping service alive")
            }
        } catch (t: Throwable) {
            Logger.w(TAG, "onTaskRemoved failed: ${t.message}")
        }
    }

    override fun onDestroy() {
        try {
            shutdown("service destroy")
        } catch (t: Throwable) {
            Logger.e(TAG, "onDestroy failed", t)
        }
        try {
            unregisterReceiver(stopReceiver)
        } catch (t: Throwable) {
            // already unregistered
        }
        super.onDestroy()
    }

    private fun shutdown(reason: String) {
        Logger.i(TAG, "shutting down: $reason")
        try {
            capture?.shutdown(reason)
        } catch (t: Throwable) {
            Logger.w(TAG, "capture shutdown: ${t.message}")
        }
        try {
            overlay?.setHdrVisible(false)
        } catch (t: Throwable) {
            Logger.w(TAG, "HDR layer shutdown: ${t.message}")
        }
        try {
            engine?.stop(reason)
        } catch (t: Throwable) {
            Logger.w(TAG, "engine shutdown: ${t.message}")
        }
        try {
            overlay?.detach()
        } catch (t: Throwable) {
            Logger.w(TAG, "overlay shutdown: ${t.message}")
        }
        try {
            overlay?.applyPreferredRefreshRate(0f, profile.tier)
        } catch (t: Throwable) {
            Logger.w(TAG, "display policy reset failed: ${t.message}")
        }
        releaseWakeLock()
        capture = null
        engine = null
        overlay = null
        prefs.engineRunning = false
        SyncWorker.enqueue(this)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        } catch (t: Throwable) {
            // ignore
        }
        try {
            stopSelf()
        } catch (t: Throwable) {
            // ignore
        }
    }

    private fun toastOnce(message: String) {
        try {
            mainHandler.post { Toast.makeText(applicationContext, message, Toast.LENGTH_LONG).show() }
        } catch (t: Throwable) {
            Logger.w(TAG, "toast failed: ${t.message}")
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
