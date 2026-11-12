package com.dlss5.optimizer.core

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import com.dlss5.optimizer.BuildConfig
import com.dlss5.optimizer.network.LocalStore
import com.dlss5.optimizer.network.SyncWorker
import com.dlss5.optimizer.util.Logger
import com.dlss5.optimizer.util.Prefs
import com.dlss5.optimizer.util.Time
import org.json.JSONObject
import java.util.UUID

/**
 * Owns everything that happens while the engine is "on": the frame monitor, the adaptive governor, the
 * session record, telemetry sampling and the thermal listener.
 *
 * Runs on its own HandlerThread so the overlay UI thread is never blocked. Every callback into the UI is
 * marshalled to the caller-supplied handler (the overlay's main-thread handler) and wrapped in try/catch.
 */
class EngineController(
    private val context: Context,
    private val prefs: Prefs,
    private val store: LocalStore,
    private val profile: DeviceProfile,
    private val onStats: (LiveStats) -> Unit
) {

    data class LiveStats(
        val fps: Double,
        val frameMs: Double,
        val p95FrameMs: Double,
        val jitterMs: Double,
        val renderScale: Float,
        val targetFps: Int,
        val droppedFrames: Int,
        val thermal: Int,
        val mode: Int,
        val engineCostMs: Double,
        val sessionElapsedMs: Long,
        val hdr: Boolean = false
    )

    companion object {
        private const val TAG = "Engine"
        private const val TELEMETRY_INTERVAL_MS = 1000L
        private const val UI_PUSH_INTERVAL_MS = 400L
    }

    private val stats = EngineStats()
    private var thread: HandlerThread? = null
    private var worker: Handler? = null
    private var mainHandler: Handler? = null

    private var monitor: FrameMonitor? = null
    private val governor = FrameGovernor(
        targetFps = prefs.targetFps,
        minScale = 0.55f,
        maxScale = 1.0f,
        initialScale = if (prefs.safeMode) 0.75f else prefs.lastRenderScale.coerceIn(0.55f, 1.0f)
    )

    @Volatile
    private var running = false

    private var sessionId: String = UUID.randomUUID().toString()
    private var sessionStartMs: Long = 0L
    private var lastTelemetryMs: Long = 0L
    private var lastUiPushMs: Long = 0L
    private var thermalStatus: Int = 0
    private var adaptiveEnabled: Boolean = true
    private val thermalListener: ((Int) -> Unit)

    /** Latest snapshot for anything that needs it synchronously (notification text, UI refresh). */
    @Volatile
    var lastStats: LiveStats? = null
        private set

    init {
        thermalListener = { status -> onThermal(status) }
    }

    // ------------------------------------------------------------------ lifecycle

    @Synchronized
    fun start(uiHandler: Handler) {
        if (running) return
        try {
            mainHandler = uiHandler
            val t = HandlerThread("dlss-engine")
            t.start()
            thread = t
            worker = Handler(t.looper)

            applyRemoteConfig()

            sessionId = UUID.randomUUID().toString()
            sessionStartMs = Time.nowMs()
            lastTelemetryMs = 0L
            stats.reset()
            thermalStatus = readThermalStatus()
            governor.applyThermalStatus(thermalStatus)
            running = true
            prefs.engineRunning = true
            prefs.sessionCount = prefs.sessionCount + 1

            monitor = FrameMonitor(prefs.targetFps) { snapshot -> onFrameSnapshot(snapshot) }.also { it.start() }
            registerThermalListener()

            Logger.i(TAG, "engine started session=$sessionId mode=${SessionRecord.modeName(prefs.mode)}")
        } catch (t: Throwable) {
            Logger.e(TAG, "start failed", t)
            running = false
            prefs.engineRunning = false
        }
    }

    @Synchronized
    fun stop(reason: String = "user") {
        if (!running) return
        running = false
        try {
            monitor?.stop()
            unregisterThermalListener()
            finalizeSession(reason)
            mainHandler?.post {
                try {
                    onStats(
                        LiveStats(
                            fps = 0.0, frameMs = 0.0, p95FrameMs = 0.0, jitterMs = 0.0,
                            renderScale = governor.currentScale(), targetFps = prefs.targetFps,
                            droppedFrames = 0, thermal = thermalStatus, mode = prefs.mode,
                            engineCostMs = 0.0, sessionElapsedMs = 0L, hdr = hdrActive
                        )
                    )
                } catch (t: Throwable) {
                    Logger.w(TAG, "final UI push failed: ${t.message}")
                }
            }
            worker?.removeCallbacksAndMessages(null)
            thread?.quitSafely()
        } catch (t: Throwable) {
            Logger.e(TAG, "stop failed", t)
        } finally {
            thread = null
            worker = null
            prefs.engineRunning = false
            activeSessionId = null
        }
    }

    fun isRunning(): Boolean = running

    fun currentSessionId(): String? = if (running) sessionId else null

    /** Exposed so the capture engine can tag its GPU cost samples. */
    var activeSessionId: String? = null
        private set

    fun setMode(mode: Int) {
        try {
            prefs.mode = mode
            monitor?.resetWindow()
            Logger.i(TAG, "mode -> ${SessionRecord.modeName(mode)}")
        } catch (t: Throwable) {
            Logger.w(TAG, "setMode failed: ${t.message}")
        }
    }

    fun setTargetFps(fps: Int) {
        prefs.targetFps = fps
        governor.setTargetFps(fps)
        monitor?.stop()
        monitor = FrameMonitor(fps) { snapshot -> onFrameSnapshot(snapshot) }.also { it.start() }
    }

    fun setAdaptiveEnabled(enabled: Boolean) {
        adaptiveEnabled = enabled
    }

    fun restoreScale(scale: Float) {
        governor.reset(scale.coerceIn(0.55f, 1.0f))
    }

    // ------------------------------------------------------------------ frame path

    private fun onFrameSnapshot(snapshot: FrameMonitor.FrameSnapshot) {
        if (!running) return
        try {
            val now = Time.uptimeMs()
            val targetMs = 1000.0 / prefs.targetFps
            val scale = if (adaptiveEnabled) {
                governor.update(
                    observedFrameMs = snapshot.avgFrameMs.takeIf { it > 0.0 } ?: targetMs,
                    nowMs = now
                )
            } else {
                governor.currentScale()
            }
            prefs.lastRenderScale = scale
            stats.record(
                fps = snapshot.measuredFps,
                frameMs = snapshot.avgFrameMs,
                scale = scale,
                droppedFrames = snapshot.droppedFrames,
                thermal = thermalStatus,
                engineCostMs = 0L
            )

            val live = LiveStats(
                fps = snapshot.measuredFps,
                frameMs = snapshot.avgFrameMs,
                p95FrameMs = snapshot.p95FrameMs,
                jitterMs = snapshot.jitterMs,
                renderScale = scale,
                targetFps = prefs.targetFps,
                droppedFrames = snapshot.droppedFrames,
                thermal = thermalStatus,
                mode = prefs.mode,
                engineCostMs = stats.engineCostMs(),
                sessionElapsedMs = Time.nowMs() - sessionStartMs,
                hdr = hdrActive
            )
            lastStats = live

            if (now - lastUiPushMs >= UI_PUSH_INTERVAL_MS) {
                lastUiPushMs = now
                mainHandler?.post {
                    try {
                        onStats(live)
                    } catch (t: Throwable) {
                        Logger.w(TAG, "stats callback failed: ${t.message}")
                    }
                }
            }
            maybeSampleTelemetry(snapshot, scale)
        } catch (t: Throwable) {
            Logger.e(TAG, "frame handling failed", t)
        }
    }

    /** Records the cost of a GPU upscale pass so it shows up in telemetry next to the frame numbers. */
    fun reportEngineCost(costMs: Long) {
        val s = lastStats ?: return
        stats.record(s.fps, s.frameMs, s.renderScale, s.droppedFrames, s.thermal, costMs)
    }

    private fun maybeSampleTelemetry(snapshot: FrameMonitor.FrameSnapshot, scale: Float) {
        val now = Time.nowMs()
        if (now - lastTelemetryMs < TELEMETRY_INTERVAL_MS) return
        lastTelemetryMs = now
        if (!prefs.analyticsOptIn) return
        try {
            val row: JSONObject = SyncWorker.sampleRow(
                sessionId = sessionId,
                deviceId = prefs.deviceId,
                fps = snapshot.measuredFps,
                frameMs = snapshot.avgFrameMs,
                renderScale = scale,
                thermal = thermalStatus,
                gpuCostMs = stats.engineCostMs()
            ) ?: return
            store.enqueueTelemetry(row)
        } catch (t: Throwable) {
            Logger.w(TAG, "telemetry sample failed: ${t.message}")
        }
    }

    // ------------------------------------------------------------------ thermal

    private fun readThermalStatus(): Int = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            pm?.currentThermalStatus ?: 0
        } else {
            0
        }
    } catch (t: Throwable) {
        0
    }

    private var powerManager: PowerManager? = null
    private var thermalCallback: PowerManager.OnThermalStatusChangedListener? = null

    private fun registerThermalListener() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
            powerManager = pm
            val cb = PowerManager.OnThermalStatusChangedListener { status -> thermalListener(status) }
            thermalCallback = cb
            pm.addThermalStatusListener(context.mainExecutor, cb)
            Logger.d(TAG, "thermal listener attached")
        } catch (t: Throwable) {
            Logger.w(TAG, "thermal listener unavailable: ${t.message}")
        }
    }

    private fun unregisterThermalListener() {
        try {
            val pm = powerManager
            val cb = thermalCallback
            if (pm != null && cb != null) pm.removeThermalStatusListener(cb)
        } catch (t: Throwable) {
            Logger.w(TAG, "thermal detach failed: ${t.message}")
        } finally {
            powerManager = null
            thermalCallback = null
        }
    }

    private fun onThermal(status: Int) {
        try {
            thermalStatus = status
            governor.applyThermalStatus(status)
            if (status >= 3) {
                Logger.w(TAG, "thermal level $status, capping render scale at ${governor.effectiveMaxScale()}")
            }
        } catch (t: Throwable) {
            Logger.w(TAG, "thermal handling failed: ${t.message}")
        }
    }

    // ------------------------------------------------------------------ config & session

    private fun applyRemoteConfig() {
        try {
            val raw = prefs.remoteConfigJson ?: return
            val cfg = JSONObject(raw)
            if (cfg.has("target_fps")) {
                prefs.targetFps = cfg.optInt("target_fps", prefs.targetFps)
                governor.setTargetFps(prefs.targetFps)
            }
            val min = cfg.optDouble("min_scale", governor.minScale.toDouble()).toFloat()
            val max = cfg.optDouble("max_scale", governor.maxScale.toDouble()).toFloat()
            governor.setBounds(min, max)
            Logger.d(TAG, "remote config applied: ${cfg}")
        } catch (t: Throwable) {
            Logger.w(TAG, "remote config ignored: ${t.message}")
        }
    }

    private fun finalizeSession(reason: String) {
        try {
            val started = if (sessionStartMs == 0L) Time.nowMs() else sessionStartMs
            val record = SessionRecord(
                sessionId = sessionId,
                deviceId = prefs.deviceId,
                startedAtMs = started,
                endedAtMs = Time.nowMs(),
                mode = prefs.mode,
                targetFps = prefs.targetFps,
                avgFps = stats.avgFps(),
                p95FrameMs = stats.p95FrameMs(),
                droppedFrames = stats.droppedFrames(),
                avgRenderScale = stats.avgScale(),
                finalRenderScale = governor.currentScale(),
                thermalMax = stats.thermalMax(),
                socTier = profile.tier.key,
                lanczos = settingsLanczos,
                hdrPreset = if (hdrActive) prefs.hdrPresetId else null,
                engineCostMs = stats.engineCostMs()
            )
            store.appendSession(record)
            Logger.i(TAG, "session closed (${reason}) fps=${"%.1f".format(record.avgFps)}")
            SyncWorker.enqueue(context)
        } catch (t: Throwable) {
            Logger.e(TAG, "finalizeSession failed", t)
        }
    }

    /** Set by the overlay service when it decides which kernel the GPU can afford. */
    var settingsLanczos: Boolean = false

    /** True while the HDR compositor owns the presented frames (set by the overlay service). */
    @Volatile
    var hdrActive: Boolean = false
        private set

    fun setHdrActive(active: Boolean) {
        hdrActive = active
        if (active) Logger.i(TAG, "HDR compositor active, preset=${prefs.hdrPresetId}")
    }
}
