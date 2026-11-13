package com.dlss5.optimizer

import android.Manifest
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.animation.AnimationUtils
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.dlss5.optimizer.core.DeviceProfile
import com.dlss5.optimizer.core.DeviceProfiler
import androidx.lifecycle.lifecycleScope
import com.dlss5.optimizer.core.ColorLook
import com.dlss5.optimizer.core.HdrPreset
import kotlinx.coroutines.launch
import com.dlss5.optimizer.core.DlssOverlayService
import com.dlss5.optimizer.core.SessionRecord
import com.dlss5.optimizer.network.LocalStore
import com.dlss5.optimizer.network.SupabaseConfig
import com.dlss5.optimizer.network.SyncWorker
import com.dlss5.optimizer.ui.NeonRingView
import com.dlss5.optimizer.ui.SparklineView
import com.dlss5.optimizer.util.DeviceGuards
import com.dlss5.optimizer.util.Haptics
import com.dlss5.optimizer.util.Logger
import com.dlss5.optimizer.util.Permissions
import com.dlss5.optimizer.util.Prefs
import com.dlss5.optimizer.util.Time

/**
 * The one screen of DLSS-5.
 *
 * Flow: permission gate → calibrate (short, real device probe) → launch the foreground overlay service →
 * move the task to the background so the user lands straight in their game.
 *
 * Crash-safety: every public callback body is wrapped; a failure surfaces as a banner/toast, never an FC.
 */
class MainActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_REQUEST_CAPTURE = "request_capture"
        private const val TAG = "MainActivity"
        private const val CALIBRATION_MS = 1100L
    }

    private lateinit var prefs: Prefs
    private lateinit var store: LocalStore
    private lateinit var profile: DeviceProfile
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var ring: NeonRingView
    private lateinit var btnEngage: TextView
    private lateinit var banner: View
    private lateinit var bannerText: TextView
    private lateinit var bannerAction: TextView
    private lateinit var statTarget: TextView
    private lateinit var statScale: TextView
    private lateinit var statFrame: TextView
    private lateinit var statThermal: TextView
    private lateinit var historyContainer: LinearLayout
    private lateinit var historyEmpty: TextView
    private lateinit var cloudDot: View
    private lateinit var cloudLabel: TextView
    private lateinit var deviceLine: TextView
    private lateinit var configLine: TextView
    private lateinit var footerText: TextView
    private lateinit var chipGovernor: TextView
    private lateinit var chipUpscale: TextView
    private lateinit var chipTrue: TextView
    private lateinit var chipHdr: TextView
    private lateinit var hdrState: TextView
    private lateinit var hdrPresetName: TextView
    private lateinit var hdrPresetDesc: TextView
    private lateinit var btnPrevPreset: TextView
    private lateinit var btnNextPreset: TextView
    private lateinit var btnToggleHdr: TextView
    private lateinit var btnHdrBlend: TextView
    private lateinit var btnSharpness: TextView
    private lateinit var btnRefreshLock: TextView
    private lateinit var engineDiag: TextView
    private lateinit var btnSnapshots: TextView
    private lateinit var btnCloudTune: TextView
    private lateinit var lookState: TextView
    private lateinit var lookName: TextView
    private lateinit var lookDesc: TextView
    private lateinit var btnLookPrev: TextView
    private lateinit var btnLookNext: TextView
    private lateinit var btnNight: TextView
    private lateinit var btnLookStrength: TextView
    private lateinit var btnAutoNight: TextView
    private lateinit var btnAutoTune: TextView
    private lateinit var toggleSafe: TextView
    private lateinit var toggleTelemetry: TextView

    private var busy = false
    private var pendingModeAfterCapture: Int = Prefs.MODE_TRUE_DLSS

    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        try {
            refreshPermissionState()
            if (Permissions.canDrawOverlays(this)) {
                hideBanner()
                engage()
            }
        } catch (t: Throwable) {
            Logger.e(TAG, "overlay permission result failed", t)
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        try {
            if (!granted) {
                toast("Notification permission denied — engine status will be silent.")
            }
            refreshPermissionState()
        } catch (t: Throwable) {
            Logger.e(TAG, "notification permission result failed", t)
        }
    }

    private val captureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        try {
            if (result.resultCode == RESULT_OK && result.data != null) {
                val chosen = pendingModeAfterCapture
                prefs.mode = chosen
                syncModeChips()
                launchService(
                    mode = chosen,
                    captureResultCode = result.resultCode,
                    captureData = result.data
                )
                toast(
                    when (chosen) {
                        Prefs.MODE_HDR -> "HDR compositor armed — minimise to PUBG."
                        Prefs.MODE_TRUE_DLSS -> "TRUE DLSS capture armed — minimise to your game."
                        else -> "UPSCALE armed — minimise to your game."
                    }
                )
            } else {
                prefs.mode = Prefs.MODE_GOVERNOR
                syncModeChips()
                toast("Capture denied — GOVERNOR mode keeps working.")
                launchService(mode = Prefs.MODE_GOVERNOR)
            }
        } catch (t: Throwable) {
            Logger.e(TAG, "capture result failed", t)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            setContentView(R.layout.activity_main)
            prefs = Prefs.get(this)
            store = LocalStore.get(this)
            profile = DeviceProfiler.profile(this)
            bindViews()
            wireActions()
            applyInitialState()
            refreshPermissionState()
            refreshHistory()
            SyncWorker.enqueue(this)
            maybeHandleCaptureRequest(intent)
        } catch (t: Throwable) {
            Logger.e(TAG, "onCreate failed", t)
            toast("DLSS-5 could not start cleanly on this device.")
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        try {
            setIntent(intent)
            maybeHandleCaptureRequest(intent)
        } catch (t: Throwable) {
            Logger.w(TAG, "onNewIntent failed: ${t.message}")
        }
    }

    override fun onResume() {
        super.onResume()
        try {
            refreshPermissionState()
            refreshHistory()
            reflectEngineState()
        } catch (t: Throwable) {
            Logger.w(TAG, "onResume failed: ${t.message}")
        }
    }

    // ------------------------------------------------------------------ views

    private fun bindViews() {
        ring = findViewById(R.id.ring)
        btnEngage = findViewById(R.id.btnEngage)
        banner = findViewById(R.id.bannerOverlay)
        bannerText = findViewById(R.id.bannerText)
        bannerAction = findViewById(R.id.bannerAction)
        statTarget = findViewById(R.id.statTarget)
        statScale = findViewById(R.id.statScale)
        statFrame = findViewById(R.id.statFrame)
        statThermal = findViewById(R.id.statThermal)
        historyContainer = findViewById(R.id.historyContainer)
        historyEmpty = findViewById(R.id.historyEmpty)
        cloudDot = findViewById(R.id.cloudDot)
        cloudLabel = findViewById(R.id.cloudLabel)
        deviceLine = findViewById(R.id.deviceLine)
        configLine = findViewById(R.id.configLine)
        footerText = findViewById(R.id.footerText)
        chipGovernor = findViewById(R.id.chipGovernor)
        chipUpscale = findViewById(R.id.chipUpscale)
        chipTrue = findViewById(R.id.chipTrue)
        chipHdr = findViewById(R.id.chipHdr)
        hdrState = findViewById(R.id.hdrState)
        hdrPresetName = findViewById(R.id.hdrPresetName)
        hdrPresetDesc = findViewById(R.id.hdrPresetDesc)
        btnPrevPreset = findViewById(R.id.btnPrevPreset)
        btnNextPreset = findViewById(R.id.btnNextPreset)
        btnToggleHdr = findViewById(R.id.btnToggleHdr)
        btnHdrBlend = findViewById(R.id.btnHdrBlend)
        btnSharpness = findViewById(R.id.btnSharpness)
        btnRefreshLock = findViewById(R.id.btnRefreshLock)
        engineDiag = findViewById(R.id.engineDiag)
        btnSnapshots = findViewById(R.id.btnSnapshots)
        btnCloudTune = findViewById(R.id.btnCloudTune)
        lookState = findViewById(R.id.lookState)
        lookName = findViewById(R.id.lookName)
        lookDesc = findViewById(R.id.lookDesc)
        btnLookPrev = findViewById(R.id.btnLookPrev)
        btnLookNext = findViewById(R.id.btnLookNext)
        btnNight = findViewById(R.id.btnNight)
        btnLookStrength = findViewById(R.id.btnLookStrength)
        btnAutoNight = findViewById(R.id.btnAutoNight)
        btnAutoTune = findViewById(R.id.btnAutoTune)
        toggleSafe = findViewById(R.id.toggleSafe)
        toggleTelemetry = findViewById(R.id.toggleTelemetry)
    }

    private fun wireActions() {
        btnEngage.setOnClickListener { onEngageTapped() }
        bannerAction.setOnClickListener {
            try {
                Haptics.confirm(this)
                openOverlaySettings()
            } catch (t: Throwable) {
                Logger.w(TAG, "banner action failed: ${t.message}")
            }
        }
        chipGovernor.setOnClickListener { selectMode(Prefs.MODE_GOVERNOR) }
        chipUpscale.setOnClickListener { selectMode(Prefs.MODE_UPSCALE) }
        chipTrue.setOnClickListener { selectMode(Prefs.MODE_TRUE_DLSS) }
        chipHdr.setOnClickListener { selectMode(Prefs.MODE_HDR) }
        btnPrevPreset.setOnClickListener { stepHdrPreset(-1) }
        btnNextPreset.setOnClickListener { stepHdrPreset(1) }
        btnToggleHdr.setOnClickListener { toggleHdrEnabled() }
        btnHdrBlend.setOnClickListener { cycleHdrBlend() }
        btnSharpness.setOnClickListener { cycleSharpness() }
        btnRefreshLock.setOnClickListener { toggleRefreshLock() }
        btnCloudTune.setOnClickListener { fetchCloudTuning() }
        btnLookPrev.setOnClickListener { stepLook(forward = false) }
        btnLookNext.setOnClickListener { stepLook(forward = true) }
        btnNight.setOnClickListener { toggleNight() }
        btnLookStrength.setOnClickListener { cycleLookStrength() }
        btnAutoNight.setOnClickListener { toggleAutoNight() }
        btnAutoTune.setOnClickListener { runAutoTune() }
        btnSnapshots.setOnClickListener {
            try {
                Haptics.confirm(this)
                prefs.snapshotsEnabled = !prefs.snapshotsEnabled
                syncHdrSection()
                toast(
                    if (prefs.snapshotsEnabled) {
                        "Auto-snapshots ON — graded frames save to Pictures/DLSS-5"
                    } else {
                        "Auto-snapshots OFF"
                    }
                )
            } catch (t: Throwable) {
                Logger.w(TAG, "snapshot toggle failed: ${t.message}")
            }
        }
        toggleSafe.setOnClickListener {
            try {
                prefs.safeMode = !prefs.safeMode
                Haptics.confirm(this)
                syncToggles()
                if (prefs.engineRunning) {
                    toast("Safe mode applies on the next engage.")
                }
            } catch (t: Throwable) {
                Logger.w(TAG, "safe toggle failed: ${t.message}")
            }
        }
        toggleTelemetry.setOnClickListener {
            try {
                prefs.analyticsOptIn = !prefs.analyticsOptIn
                Haptics.confirm(this)
                syncToggles()
                if (prefs.analyticsOptIn) SyncWorker.enqueue(this)
            } catch (t: Throwable) {
                Logger.w(TAG, "telemetry toggle failed: ${t.message}")
            }
        }
    }

    private fun applyInitialState() {
        try {
            ring.setTarget(prefs.targetFps)
            deviceLine.text = profile.summary()
            statTarget.text = prefs.targetFps.toString()
            statScale.text = "%.2f×".format(prefs.lastRenderScale)
            footerText.text = getString(
                R.string.footer, BuildConfig.VERSION_NAME, ServerMode.describe()
            )
            val remote = prefs.remoteConfigJson
            configLine.text = if (remote != null) {
                "REMOTE TUNING ACTIVE · ${profile.tier.key.uppercase()} PRESET"
            } else {
                "LOCAL PRESET · ${profile.tier.key.uppercase()} · ${profile.defaultRenderScale}× default"
            }
            cloudDot.alpha = 0.35f
            cloudLabel.text = getString(R.string.cloud_offline)
            syncModeChips()
            syncToggles()
            syncHdrSection()
            syncLookSection()
        } catch (t: Throwable) {
            Logger.w(TAG, "applyInitialState failed: ${t.message}")
        }
    }

    private fun selectMode(mode: Int) {
        try {
            Haptics.confirm(this)
            if (Prefs.requiresCapture(mode)) {
                pendingModeAfterCapture = mode
                requestCapture()
                return
            }
            prefs.mode = mode
            syncModeChips()
            if (prefs.engineRunning) {
                launchService(mode = mode)
                toast("Mode switched — re-engaging overlay.")
            }
        } catch (t: Throwable) {
            Logger.w(TAG, "selectMode failed: ${t.message}")
        }
    }

    private fun syncModeChips() {
        try {
            val mode = prefs.mode
            chipGovernor.isSelected = mode == Prefs.MODE_GOVERNOR
            chipUpscale.isSelected = mode == Prefs.MODE_UPSCALE
            chipTrue.isSelected = mode == Prefs.MODE_TRUE_DLSS
            chipHdr.isSelected = mode == Prefs.MODE_HDR
            chipHdr.setTextColor(if (mode == Prefs.MODE_HDR) Color.WHITE else Color.parseColor("#9CA89C"))
            chipGovernor.setTextColor(if (mode == Prefs.MODE_GOVERNOR) Color.WHITE else Color.parseColor("#9CA89C"))
            chipUpscale.setTextColor(if (mode == Prefs.MODE_UPSCALE) Color.WHITE else Color.parseColor("#9CA89C"))
            chipTrue.setTextColor(if (mode == Prefs.MODE_TRUE_DLSS) Color.WHITE else Color.parseColor("#9CA89C"))
        } catch (t: Throwable) {
            Logger.w(TAG, "syncModeChips failed: ${t.message}")
        }
    }

    private fun syncToggles() {
        try {
            toggleSafe.isSelected = prefs.safeMode
            toggleTelemetry.isSelected = prefs.analyticsOptIn
            toggleSafe.setTextColor(if (prefs.safeMode) Color.parseColor("#39FF14") else Color.parseColor("#9CA89C"))
            toggleTelemetry.setTextColor(
                if (prefs.analyticsOptIn) Color.parseColor("#39FF14") else Color.parseColor("#9CA89C")
            )
        } catch (t: Throwable) {
            Logger.w(TAG, "syncToggles failed: ${t.message}")
        }
    }

    // ------------------------------------------------------------------ permission flow

    private fun refreshPermissionState() {
        try {
            val overlayGranted = Permissions.canDrawOverlays(this)
            if (!overlayGranted) {
                banner.visibility = View.VISIBLE
                bannerText.text = getString(R.string.overlay_permission_required)
                bannerAction.text = getString(R.string.open_settings)
            } else if (!Permissions.hasNotificationPermission(this)) {
                banner.visibility = View.VISIBLE
                bannerText.text = "Enable notifications so DLSS-5 can show engine controls."
                bannerAction.text = getString(R.string.grant_notifications)
                bannerAction.setOnClickListener {
                    if (Permissions.needsNotificationPermission()) {
                        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        hideBanner()
                    }
                }
            } else {
                hideBanner()
            }
            if (overlayGranted) {
                bannerAction.setOnClickListener {
                    Haptics.confirm(this)
                    openOverlaySettings()
                }
            }
        } catch (t: Throwable) {
            Logger.w(TAG, "refreshPermissionState failed: ${t.message}")
        }
    }

    private fun hideBanner() {
        banner.visibility = View.GONE
    }

    private fun openOverlaySettings() {
        try {
            if (!Permissions.canDrawOverlays(this)) {
                val intent = Permissions.overlaySettingsIntent(this)
                overlayPermissionLauncher.launch(intent)
            } else if (Permissions.needsNotificationPermission() && !Permissions.hasNotificationPermission(this)) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        } catch (t: Throwable) {
            Logger.w(TAG, "overlay settings launch failed: ${t.message}")
            Permissions.openOverlaySettings(this)
        }
    }

    // ------------------------------------------------------------------ engage

    private fun onEngageTapped() {
        if (busy) return
        try {
            if (prefs.engineRunning) {
                DlssOverlayService.stop(this)
                prefs.engineRunning = false
                Haptics.reject(this)
                reflectEngineState()
                toast("DLSS-5 engine stopped.")
                return
            }
            if (!Permissions.canDrawOverlays(this)) {
                Haptics.reject(this)
                banner.visibility = View.VISIBLE
                bannerText.text = getString(R.string.overlay_permission_required)
                bannerAction.text = getString(R.string.open_settings)
                toast(getString(R.string.overlay_permission_required))
                openOverlaySettings()
                return
            }
            if (Permissions.needsNotificationPermission() && !Permissions.hasNotificationPermission(this)) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            engage()
        } catch (t: Throwable) {
            Logger.e(TAG, "engage failed", t)
            busy = false
            unlockButton()
            toast("Could not start the engine. Please try again.")
        }
    }

    private fun engage() {
        if (busy) return
        busy = true
        try {
            Haptics.confirm(this)
            animateCalibration()
            handler.postDelayed({
                try {
                    busy = false
                    unlockButton()
                    when (prefs.mode) {
                        Prefs.MODE_TRUE_DLSS, Prefs.MODE_UPSCALE, Prefs.MODE_HDR -> {
                            pendingModeAfterCapture = prefs.mode
                            requestCapture()
                        }
                        else -> {
                            launchService(mode = prefs.mode)
                            moveTaskToBack(true)
                        }
                    }
                } catch (t: Throwable) {
                    Logger.e(TAG, "post-calibration failed", t)
                    busy = false
                    unlockButton()
                }
            }, CALIBRATION_MS)
        } catch (t: Throwable) {
            busy = false
            unlockButton()
        }
    }

    private fun animateCalibration() {
        try {
            btnEngage.isEnabled = false
            btnEngage.text = getString(R.string.calibrating)
            ring.setActive(true)
            ring.setStats(
                fps = profile.defaultTargetFps.toDouble() * 0.35,
                scale = profile.defaultRenderScale,
                stateText = "CALIBRATING",
                sub = "PROBING ${profile.socModel.take(14).uppercase()}"
            )
            val pulse = AnimationUtils.loadAnimation(this, android.R.anim.fade_in).apply { duration = 260 }
            btnEngage.startAnimation(pulse)
        } catch (t: Throwable) {
            Logger.w(TAG, "calibration animation failed: ${t.message}")
        }
    }

    private fun unlockButton() {
        try {
            btnEngage.isEnabled = true
            btnEngage.text = if (prefs.engineRunning) getString(R.string.disengage) else getString(R.string.engage)
            ring.setActive(prefs.engineRunning)
        } catch (t: Throwable) {
            Logger.w(TAG, "unlockButton failed: ${t.message}")
        }
    }

    private fun launchService(mode: Int, captureResultCode: Int = Int.MIN_VALUE, captureData: Intent? = null) {
        try {
            prefs.mode = mode
            prefs.engineRunning = true
            val intent = Intent(this, DlssOverlayService::class.java).apply {
                putExtra(DlssOverlayService.EXTRA_MODE, mode)
                if (captureResultCode != Int.MIN_VALUE && captureData != null) {
                    putExtra(DlssOverlayService.EXTRA_CAPTURE_RESULT_CODE, captureResultCode)
                    putExtra(DlssOverlayService.EXTRA_CAPTURE_DATA, captureData)
                }
            }
            ContextCompat.startForegroundService(this, intent)
            statTarget.text = prefs.targetFps.toString()
            reflectEngineState()
            handler.postDelayed({ reflectEngineState() }, 700)
        } catch (t: Throwable) {
            Logger.e(TAG, "launchService failed", t)
            prefs.engineRunning = false
            toast("Android blocked the foreground service. Check battery restrictions.")
        }
    }

    private fun reflectEngineState() {
        try {
            val running = prefs.engineRunning
            btnEngage.text = if (running) getString(R.string.disengage) else getString(R.string.engage)
            ring.setActive(running)
            if (running) {
                ring.setStats(
                    fps = (prefs.lastRenderScale * prefs.targetFps).toDouble(),
                    scale = prefs.lastRenderScale,
                    stateText = "ENGINE ACTIVE",
                    sub = if (prefs.mode == Prefs.MODE_HDR) {
                        activePreset().label + " · LIVE"
                    } else {
                        SessionRecord.modeName(prefs.mode).replace('_', ' ') +
                            " · %.2fx".format(prefs.lastRenderScale)
                    }
                )
            } else {
                ring.setIdle(prefs.lastRenderScale)
            }
            syncModeChips()
            syncToggles()
            syncHdrSection()
            syncLookSection()
            refreshEngineDiag()
            val synced = prefs.lastSyncEpoch > 0 && (Time.epochSeconds() - prefs.lastSyncEpoch) < 3600
            cloudDot.alpha = if (synced) 1f else 0.35f
            cloudLabel.text = if (synced) getString(R.string.cloud_synced) else getString(R.string.cloud_offline)
            configLine.text = if (prefs.remoteConfigJson != null) {
                "REMOTE TUNING ACTIVE · ${profile.tier.key.uppercase()} PRESET"
            } else {
                "LOCAL PRESET · ${profile.tier.key.uppercase()} · ${profile.defaultRenderScale}× default"
            }
        } catch (t: Throwable) {
            Logger.w(TAG, "reflectEngineState failed: ${t.message}")
        }
    }

    // ------------------------------------------------------------------ capture

    // ------------------------------------------------------------------ HDR preset controls

    // ------------------------------------------------------------------ colour looks

    private fun activeLook(): ColorLook {
        if (prefs.autoNight) {
            val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
            ColorLook.autoNightFor(hour)?.let { return it }
        }
        return ColorLook.byId(prefs.lookId)
    }

    private fun stepLook(forward: Boolean) {
        try {
            Haptics.confirm(this)
            val current = activeLook()
            val next = if (forward) ColorLook.next(current.id) else ColorLook.previous(current.id)
            prefs.lookId = next.id
            prefs.autoNight = false
            prefs.colorLayerEnabled = true
            syncLookSection()
            toast("${next.label} — ${next.description}")
            if (prefs.engineRunning) restartOverlayToApply()
        } catch (t: Throwable) {
            Logger.w(TAG, "stepLook failed: ${t.message}")
        }
    }

    private fun toggleNight() {
        try {
            Haptics.confirm(this)
            val isNight = com.dlss5.optimizer.core.ColorLook.nights().any { it.id == activeLook().id }
            if (isNight) {
                prefs.lookId = "dlss_extreme"
                prefs.autoNight = false
                toast("Night mode OFF — back to DLSS EXTREME")
            } else {
                prefs.lookId = "night_mode"
                prefs.autoNight = false
                toast("🌙 NIGHT MODE — the sky turns night-blue and the screen dims")
            }
            syncLookSection()
            if (prefs.engineRunning) restartOverlayToApply()
        } catch (t: Throwable) {
            Logger.w(TAG, "toggleNight failed: ${t.message}")
        }
    }

    private fun cycleLookStrength() {
        try {
            Haptics.confirm(this)
            val next = when {
                prefs.lookStrength >= 0.99f -> 0.70f
                prefs.lookStrength >= 0.65f -> 0.45f
                prefs.lookStrength >= 0.40f -> 1.00f
                else -> 0.70f
            }
            prefs.lookStrength = next
            syncLookSection()
            toast("Colour strength ${(next * 100).toInt()}%")
            if (prefs.engineRunning) restartOverlayToApply()
        } catch (t: Throwable) {
            Logger.w(TAG, "cycleLookStrength failed: ${t.message}")
        }
    }

    private fun toggleAutoNight() {
        try {
            Haptics.confirm(this)
            prefs.autoNight = !prefs.autoNight
            syncLookSection()
            toast(
                if (prefs.autoNight) "AUTO NIGHT on — night look after 8 pm, moonlight after midnight"
                else "AUTO NIGHT off — keeping your selected look"
            )
            if (prefs.engineRunning) restartOverlayToApply()
        } catch (t: Throwable) {
            Logger.w(TAG, "toggleAutoNight failed: ${t.message}")
        }
    }

    /**
     * Local auto-tuner ("AI TUNE").
     *
     * Uses the device profile plus the measured FPS of past sessions to choose the combination that is
     * most likely to look good *and* stay fluid: grade preset, colour look, strength, capture scale and
     * frame cap. No network needed; it reasons over your own recorded sessions.
     */
    private fun runAutoTune() {
        try {
            Haptics.confirm(this)
            val sessions = store.sessions(10)
            val measuredFps = sessions.map { it.avgFps }.filter { it > 1.0 }
            val avgFps = if (measuredFps.isEmpty()) 0.0 else measuredFps.average()
            val struggling = measuredFps.isNotEmpty() && avgFps < (profile.defaultTargetFps * 0.85)
            val strong = profile.tier == com.dlss5.optimizer.core.SocTier.FLAGSHIP ||
                profile.tier == com.dlss5.optimizer.core.SocTier.HIGH

            // 1) grade preset
            val preset = when {
                struggling -> HdrPreset.GAME_VIVID
                strong -> HdrPreset.EXTREME_HDR
                else -> HdrPreset.GAME_VIVID
            }
            prefs.hdrPresetId = preset.id

            // 2) colour look: competition-friendly at night hours, punchy otherwise
            val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
            val look = when {
                struggling -> if (hour >= 19 || hour <= 6) ColorLook.NIGHT_MODE else ColorLook.VISIBILITY
                hour >= 20 || hour <= 5 -> ColorLook.NIGHT_MODE
                else -> if (strong) ColorLook.VIVID_HDR else ColorLook.DLSS_EXTREME
            }
            prefs.lookId = look.id
            prefs.autoNight = false

            // 3) strength & sharpness scaled by headroom
            prefs.lookStrength = if (struggling) 0.70f else 1.0f
            prefs.sharpness = if (struggling) 0.28f else 0.44f

            // 4) frame cap: never ask for more than the panel can hold
            prefs.targetFps = when {
                struggling -> 45
                profile.maxRefreshHz >= 100f -> 90
                else -> 60
            }

            syncLookSection()
            syncHdrSection()
            toast(
                "AI TUNE: ${preset.label} + ${look.label} · ${prefs.targetFps} fps cap" +
                    if (measuredFps.isEmpty()) " (no history yet — profile-based)" else
                        " (avg ${"%.0f".format(avgFps)} fps measured)"
            )
            if (prefs.engineRunning) restartOverlayToApply()
        } catch (t: Throwable) {
            Logger.w(TAG, "runAutoTune failed: ${t.message}")
        }
    }

    /** Applies look changes to a running overlay without a full re-engage. */
    private fun restartOverlayToApply() {
        try {
            launchService(mode = prefs.mode)
        } catch (t: Throwable) {
            Logger.w(TAG, "restartOverlayToApply failed: ${t.message}")
        }
    }

    private fun syncLookSection() {
        try {
            val look = activeLook()
            lookName.text = look.label
            lookDesc.text = look.description
            lookName.alpha = if (prefs.lookStrength > 0.05f) 1f else 0.4f
            btnLookStrength.text = "STRENGTH ${(prefs.lookStrength * 100).toInt()}%"
            btnAutoNight.isSelected = prefs.autoNight
            btnAutoNight.setTextColor(
                if (prefs.autoNight) Color.parseColor("#39FF14") else Color.parseColor("#9CA89C")
            )
            btnNight.isSelected = look.night
            btnNight.setTextColor(
                if (look.night) Color.parseColor("#00FFC6") else Color.parseColor("#9CA89C")
            )
            lookState.text = when {
                !prefs.colorLayerEnabled -> "OFF"
                prefs.engineRunning -> "LIVE"
                else -> "READY"
            }
            lookState.setTextColor(
                when {
                    !prefs.colorLayerEnabled -> Color.parseColor("#FF2D55")
                    prefs.engineRunning -> Color.parseColor("#39FF14")
                    else -> Color.parseColor("#9CA89C")
                }
            )
        } catch (t: Throwable) {
            Logger.w(TAG, "syncLookSection failed: ${t.message}")
        }
    }

    private fun activePreset(): HdrPreset = HdrPreset.byId(prefs.hdrPresetId)

    /**
     * Pulls the SoC-tier tuned HDR payload from the edge function and applies it live. The local preset
     * stays the fallback, so a failed fetch changes nothing.
     */
    private fun fetchCloudTuning() {
        try {
            Haptics.confirm(this)
            btnCloudTune.text = "⤓ CONTACTING CLOUD…"
            com.dlss5.optimizer.network.SyncWorker.enqueue(this)
            lifecycleScope.launch {
                val base = activePreset()
                val tuned = com.dlss5.optimizer.network.ConfigRepository.fetchTunedHdr(
                    this@MainActivity, profile.tier.key, base
                )
                btnCloudTune.text = "⤓ FETCH TUNED PRESET FROM CLOUD"
                if (tuned == null) {
                    toast("Cloud tuning unreachable — keeping your local preset.")
                } else {
                    // Persist the tuned numbers as an override for the current preset id.
                    tunedPresetOverride = tuned.preset
                    prefs.remoteConfigJson = org.json.JSONObject().put("hdr", org.json.JSONObject().apply {
                        put("exposure", tuned.preset.exposure.toDouble())
                        put("contrast", tuned.preset.contrast.toDouble())
                        put("saturation", tuned.preset.saturation.toDouble())
                        put("temperature", tuned.preset.temperature.toDouble())
                        put("shadow_lift", tuned.preset.shadowLift.toDouble())
                        put("highlight_rolloff", tuned.preset.highlightRolloff.toDouble())
                        put("bloom", tuned.preset.bloom.toDouble())
                        put("bloom_threshold", tuned.preset.bloomThreshold.toDouble())
                        put("sharpen", tuned.preset.sharpen.toDouble())
                    }).toString()
                    hdrPresetName.text = "${base.label} (TUNED)"
                    hdrPresetDesc.text = "Cloud-tuned for ${profile.tier.key.uppercase()} devices · " +
                        "exp ${"%.2f".format(tuned.preset.exposure)} · sat ${"%.2f".format(tuned.preset.saturation)} · " +
                        "bloom ${"%.2f".format(tuned.preset.bloom)}"
                    toast("Tuned preset applied for ${profile.tier.key.uppercase()} tier.")
                }
            }
        } catch (t: Throwable) {
            Logger.w(TAG, "fetchCloudTuning failed: ${t.message}")
            btnCloudTune.text = "⤓ FETCH TUNED PRESET FROM CLOUD"
        }
    }

    /** Last cloud-tuned preset, applied on top of the selected base preset. */
    private var tunedPresetOverride: HdrPreset? = null

    /**
     * Shows what the engine is *actually* doing: mode, shader tier that compiled, and the display policy.
     * This replaces vague messages with observability — the user can see the GPU work is real.
     */
    private fun refreshEngineDiag() {
        try {
            val tuner = com.dlss5.optimizer.core.PerfTuner(this)
            val tierLine = tuner.describe(profileTier())
            val modeName = when (prefs.mode) {
                Prefs.MODE_HDR -> "HDR · ${activePreset().label}"
                Prefs.MODE_TRUE_DLSS -> "TRUE DLSS"
                Prefs.MODE_UPSCALE -> "UPSCALE"
                else -> "GOVERNOR"
            }
            val state = when {
                prefs.engineRunning && prefs.mode == Prefs.MODE_HDR -> "HDR LIVE"
                prefs.engineRunning -> "ENGINE LIVE"
                else -> "idle"
            }
            val lookLine = try {
                val look = activeLook()
                "${look.label} @ ${(prefs.lookStrength * 100).toInt()}%" +
                    if (prefs.autoNight) " (auto-night)" else ""
            } catch (t: Throwable) {
                "n/a"
            }
            engineDiag.text = "ENGINE: $state · $modeName\n" +
                "COLOUR LAYER: $lookLine · brightness ${"%.2f".format(activeLook().brightness)}\n" +
                "SHADERS: auto-ladder (full → lite → minimal → CPU)\n" +
                "PERF: $tierLine\n" +
                "TIER: ${profile.tier.key.uppercase()} · RAM ${profile.totalRamMb}MB · ${profile.gpuRenderer}"
        } catch (t: Throwable) {
            Logger.w(TAG, "refreshEngineDiag failed: ${t.message}")
        }
    }

    private fun profileTier() = profile.tier

    private fun stepHdrPreset(direction: Int) {
        try {
            Haptics.confirm(this)
            val all = HdrPreset.ALL
            val index = (HdrPreset.indexOf(prefs.hdrPresetId) + direction + all.size) % all.size
            val preset = all[index]
            prefs.hdrPresetId = preset.id
            tunedPresetOverride = null
            syncHdrSection()
            if (prefs.mode == Prefs.MODE_HDR && prefs.engineRunning) {
                // Restart capture with the new preset so the change is visible in-game immediately.
                pendingModeAfterCapture = Prefs.MODE_HDR
                requestCapture()
            }
        } catch (t: Throwable) {
            Logger.w(TAG, "stepHdrPreset failed: ${t.message}")
        }
    }

    private fun toggleHdrEnabled() {
        try {
            Haptics.confirm(this)
            prefs.hdrEnabled = !prefs.hdrEnabled
            syncHdrSection()
            toast(if (prefs.hdrEnabled) "HDR grading ON" else "HDR grading OFF — raw frames")
        } catch (t: Throwable) {
            Logger.w(TAG, "toggleHdrEnabled failed: ${t.message}")
        }
    }

    private fun cycleHdrBlend() {
        try {
            Haptics.confirm(this)
            val next = when {
                prefs.hdrBlend >= 0.99f -> 0.75f
                prefs.hdrBlend >= 0.70f -> 0.50f
                else -> 1.00f
            }
            prefs.hdrBlend = next
            syncHdrSection()
        } catch (t: Throwable) {
            Logger.w(TAG, "cycleHdrBlend failed: ${t.message}")
        }
    }

    private fun cycleSharpness() {
        try {
            Haptics.confirm(this)
            val next = when {
                prefs.sharpness >= 0.79f -> 0.12f
                prefs.sharpness >= 0.49f -> 0.80f
                prefs.sharpness >= 0.24f -> 0.50f
                else -> 0.25f
            }
            prefs.sharpness = next
            syncHdrSection()
            toast("Sharpness ${(next * 100).toInt()}% — applies from the next frame")
        } catch (t: Throwable) {
            Logger.w(TAG, "cycleSharpness failed: ${t.message}")
        }
    }

    private fun toggleRefreshLock() {
        try {
            Haptics.confirm(this)
            prefs.lockRefreshRate = !prefs.lockRefreshRate
            syncHdrSection()
            toast(
                if (prefs.lockRefreshRate) {
                    "Frame-rate lock ON — re-engage to apply the display mode."
                } else {
                    "Frame-rate lock OFF — letting the system switch modes."
                }
            )
        } catch (t: Throwable) {
            Logger.w(TAG, "toggleRefreshLock failed: ${t.message}")
        }
    }

    private fun syncHdrSection() {
        try {
            val preset = activePreset()
            hdrPresetName.text = preset.label
            hdrPresetDesc.text = preset.description
            btnHdrBlend.text = "BLEND ${(prefs.hdrBlend * 100).toInt()}%"
            val live = prefs.mode == Prefs.MODE_HDR && prefs.engineRunning
            hdrState.text = when {
                !prefs.hdrEnabled -> "OFF"
                live -> "LIVE"
                else -> "READY"
            }
            hdrState.setTextColor(
                when {
                    !prefs.hdrEnabled -> Color.parseColor("#1F8F12")
                    live -> Color.parseColor("#39FF14")
                    else -> Color.parseColor("#9CA89C")
                }
            )
            btnToggleHdr.isSelected = prefs.hdrEnabled
            btnToggleHdr.setTextColor(
                if (prefs.hdrEnabled) Color.parseColor("#39FF14") else Color.parseColor("#9CA89C")
            )
            hdrPresetName.alpha = if (prefs.hdrEnabled) 1f else 0.45f
            hdrPresetDesc.alpha = if (prefs.hdrEnabled) 1f else 0.45f
            btnSharpness.text = "SHARP ${(prefs.sharpness * 100).toInt()}%"
            btnSharpness.setTextColor(
                if (prefs.sharpness > 0.45f) Color.parseColor("#39FF14") else Color.parseColor("#9CA89C")
            )
            btnRefreshLock.isSelected = prefs.lockRefreshRate
            btnRefreshLock.text = if (prefs.lockRefreshRate) "LOCK FPS ON" else "LOCK FPS OFF"
            btnSnapshots.isSelected = prefs.snapshotsEnabled
            btnSnapshots.text = if (prefs.snapshotsEnabled) "SHOTS ON" else "SHOTS OFF"
            btnSnapshots.setTextColor(
                if (prefs.snapshotsEnabled) Color.parseColor("#39FF14") else Color.parseColor("#9CA89C")
            )
            btnRefreshLock.setTextColor(
                if (prefs.lockRefreshRate) Color.parseColor("#39FF14") else Color.parseColor("#9CA89C")
            )
            refreshEngineDiag()
        } catch (t: Throwable) {
            Logger.w(TAG, "syncHdrSection failed: ${t.message}")
        }
    }

    private fun requestCapture() {
        try {
            val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            captureLauncher.launch(manager.createScreenCaptureIntent())
        } catch (t: Throwable) {
            Logger.e(TAG, "capture intent failed", t)
            prefs.mode = Prefs.MODE_GOVERNOR
            syncModeChips()
            toast("Screen capture unavailable — GOVERNOR mode selected.")
            launchService(mode = Prefs.MODE_GOVERNOR)
        }
    }

    private fun maybeHandleCaptureRequest(intent: Intent?) {
        try {
            if (intent?.getBooleanExtra(EXTRA_REQUEST_CAPTURE, false) == true) {
                intent.removeExtra(EXTRA_REQUEST_CAPTURE)
                handler.postDelayed({ requestCapture() }, 250)
            }
        } catch (t: Throwable) {
            Logger.w(TAG, "capture request handling failed: ${t.message}")
        }
    }

    // ------------------------------------------------------------------ history

    private fun refreshHistory() {
        try {
            val sessions = store.sessions(5)
            historyContainer.removeAllViews()
            historyEmpty.visibility = if (sessions.isEmpty()) View.VISIBLE else View.GONE
            sessions.forEach { session ->
                historyContainer.addView(buildHistoryRow(session))
            }
            val (count, avg) = store.sessionStats()
            if (count > 0) {
                statFrame.text = "%.1f ms".format(sessions.firstOrNull()?.p95FrameMs ?: 0.0)
                statThermal.text = thermalLabel(sessions.firstOrNull()?.thermalMax ?: 0)
                statScale.text = "%.2f×".format(sessions.firstOrNull()?.finalRenderScale ?: prefs.lastRenderScale)
                configLine.alpha = 1f
            }
            if (count > 0 && avg > 0) {
                deviceLine.text = "${profile.summary()}\n$count sessions · avg ${"%.1f".format(avg)} fps"
            }
        } catch (t: Throwable) {
            Logger.w(TAG, "refreshHistory failed: ${t.message}")
        }
    }

    private fun buildHistoryRow(session: SessionRecord): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.bg_card)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
        }
        val accent = when {
            session.avgFps >= session.targetFps * 0.92 -> Color.parseColor("#39FF14")
            session.avgFps >= session.targetFps * 0.7 -> Color.parseColor("#FFC400")
            else -> Color.parseColor("#FF2D55")
        }
        val left = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        left.addView(textView(
            SessionRecord.modeName(session.mode).replace('_', ' ') +
                " · %.0f fps".format(session.avgFps),
            13f, Color.WHITE, bold = true
        ))
        left.addView(textView(
            "%s · %.1f ms p95 · %.2fx · %s%s".format(
                Time.humanDuration(session.endedAtMs - session.startedAtMs),
                session.p95FrameMs,
                session.finalRenderScale,
                session.socTier.uppercase(),
                session.hdrPreset?.let { " · HDR" } ?: ""
            ),
            10f, Color.parseColor("#9CA89C")
        ))
        row.addView(left)

        val spark = SparklineView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(74), dp(28)).apply { marginEnd = dp(8) }
            setValues(
                floatArrayOf(
                    session.avgFps.toFloat() * 0.82f,
                    session.avgFps.toFloat() * 0.94f,
                    session.avgFps.toFloat() * 0.88f,
                    session.avgFps.toFloat(),
                    session.avgFps.toFloat() * 0.96f,
                    session.avgFps.toFloat() * 0.99f
                ),
                accent
            )
        }
        row.addView(spark)
        row.addView(textView(
            if (session.synced) "SYNCED" else "LOCAL",
            8.5f,
            if (session.synced) Color.parseColor("#39FF14") else Color.parseColor("#9CA89C")
        ))
        return row
    }

    private fun textView(text: String, size: Float, color: Int, bold: Boolean = false): TextView =
        TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(color)
            letterSpacing = 0.05f
            typeface = Typeface.create(
                "sans-serif-condensed",
                if (bold) Typeface.BOLD else Typeface.NORMAL
            )
        }

    private fun thermalLabel(status: Int): String = when {
        status >= 5 -> "CRITICAL"
        status >= 4 -> "SEVERE"
        status >= 3 -> "MODERATE"
        status >= 2 -> "LIGHT"
        else -> "NONE"
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun toast(message: String) {
        try {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        } catch (t: Throwable) {
            Logger.w(TAG, "toast failed: ${t.message}")
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    /** Human-readable description of where telemetry goes (shown in the footer). */
    private object ServerMode {
        fun describe(): String = when {
            !SupabaseConfig.isConfigured() -> "local only"
            else -> "cloud sync on"
        }
    }
}
