package com.dlss5.optimizer.core

import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.view.Choreographer
import com.dlss5.optimizer.util.Logger
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Measures real frame pacing of the device's compositor.
 *
 * Strategy:
 *  * Primary: `Choreographer` on a dedicated thread reports the vsync-aligned cadence the system is
 *    actually delivering to apps. When the foreground game overloads the GPU, callbacks are coalesced
 *    and the deltas grow — exactly the signal we need.
 *  * Fallback: if the choreographer thread is silent (a few OEM ROMs starve background loops), we
 *    synthesise deltas from `SystemClock.elapsedRealtimeNanos()` so the governor still gets sane input.
 *
 * All state is guarded by `this`; readers copy values under the lock, so the UI thread never blocks long.
 */
class FrameMonitor(
    private val targetFps: Int,
    private val onSample: (FrameSnapshot) -> Unit
) {

    data class FrameSnapshot(
        val avgFrameMs: Double,
        val p95FrameMs: Double,
        val instantaneousMs: Double,
        val measuredFps: Double,
        val jitterMs: Double,
        val droppedFrames: Int,
        val sampledFrames: Int,
        val usingFallbackClock: Boolean
    )

    private companion object {
        const val TAG = "FrameMonitor"
        const val RING = 120
        const val REPORT_INTERVAL_MS = 500L
        const val SILENCE_TIMEOUT_MS = 1200L
    }

    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var choreographer: Choreographer? = null
    private var running = false

    private val deltas = DoubleArray(RING)
    private var deltaCount = 0
    private var deltaCursor = 0
    private var dropped = 0

    private var lastVsyncMs = 0L
    private var lastReportMs = 0L
    private var lastCallbackMs = 0L
    private var fallbackActive = false
    private var totalFrames = 0

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!running) return
            try {
                onFrame(frameTimeNanos / 1_000_000L, false)
                choreographer?.postFrameCallback(this)
            } catch (t: Throwable) {
                Logger.e(TAG, "frame callback failed", t)
                stop()
            }
        }
    }

    private val ticker = object : Runnable {
        override fun run() {
            if (!running) return
            try {
                val now = SystemClock.elapsedRealtime()
                if (now - lastCallbackMs > SILENCE_TIMEOUT_MS) {
                    fallbackActive = true
                    onFrame(now, true)
                    reportIfDue(now)
                }
                handler?.postDelayed(this, 8L)
            } catch (t: Throwable) {
                Logger.e(TAG, "ticker failed", t)
                stop()
            }
        }
    }

    fun start() {
        if (running) return
        running = true
        deltaCursor = 0
        deltaCount = 0
        dropped = 0
        totalFrames = 0
        lastVsyncMs = 0L
        lastCallbackMs = 0L
        lastReportMs = SystemClock.elapsedRealtime()
        fallbackActive = false
        try {
            val t = HandlerThread("dlss-frame-monitor")
            t.start()
            thread = t
            val h = Handler(t.looper)
            handler = h
            h.post {
                try {
                    choreographer = Choreographer.getInstance()
                    choreographer?.postFrameCallback(frameCallback)
                    // Choreographer needs a Looper that keeps pumping; the HandlerThread's own
                    // message loop already does that as long as we post to it.
                    h.postDelayed(ticker, 32L)
                } catch (t2: Throwable) {
                    Logger.w(TAG, "choreographer unavailable: ${t2.message}")
                    fallbackActive = true
                }
            }
            Logger.i(TAG, "frame monitor started (target=${targetFps}fps)")
        } catch (t: Throwable) {
            Logger.e(TAG, "start failed", t)
            running = false
        }
    }

    fun stop() {
        running = false
        try {
            handler?.removeCallbacksAndMessages(null)
            thread?.quitSafely()
        } catch (t: Throwable) {
            Logger.w(TAG, "stop issue: ${t.message}")
        } finally {
            handler = null
            thread = null
            choreographer = null
        }
    }

    fun isRunning(): Boolean = running

    private fun onFrame(nowMs: Long, fromFallback: Boolean) {
        lastCallbackMs = nowMs
        if (lastVsyncMs != 0L) {
            val raw = (nowMs - lastVsyncMs).toDouble()
            if (raw > 0.0 && raw < 1000.0) {
                val normalized = if (fromFallback) min(raw, 1000.0 / targetFps) else raw
                synchronized(this) {
                    deltas[deltaCursor] = normalized
                    deltaCursor = (deltaCursor + 1) % RING
                    if (deltaCount < RING) deltaCount++
                    totalFrames++
                    val budget = 1000.0 / targetFps
                    if (normalized > budget * 1.6) dropped++
                }
            }
        }
        lastVsyncMs = nowMs
        reportIfDue(nowMs)
    }

    private fun reportIfDue(nowMs: Long) {
        if (nowMs - lastReportMs < REPORT_INTERVAL_MS) return
        lastReportMs = nowMs
        emitSnapshot()
    }

    fun emitSnapshot() {
        val copy: DoubleArray
        val count: Int
        val droppedCopy: Int
        val frames: Int
        val fallback: Boolean
        synchronized(this) {
            copy = deltas.copyOf()
            count = deltaCount
            droppedCopy = dropped
            frames = totalFrames
            fallback = fallbackActive
        }
        if (count == 0) {
            onSample(
                FrameSnapshot(
                    avgFrameMs = 1000.0 / targetFps,
                    p95FrameMs = 1000.0 / targetFps,
                    instantaneousMs = 1000.0 / targetFps,
                    measuredFps = targetFps.toDouble(),
                    jitterMs = 0.0,
                    droppedFrames = 0,
                    sampledFrames = 0,
                    usingFallbackClock = fallback
                )
            )
            return
        }
        val valid = copy.take(count).filter { it > 0.0 }
        if (valid.isEmpty()) return
        val avg = valid.average()
        val sorted = valid.sorted()
        val p95 = sorted[((sorted.size - 1) * 0.95).toInt().coerceIn(0, sorted.size - 1)]
        val variance = valid.sumOf { (it - avg) * (it - avg) } / valid.size
        val instantaneous = valid.last()
        onSample(
            FrameSnapshot(
                avgFrameMs = avg,
                p95FrameMs = p95,
                instantaneousMs = instantaneous,
                measuredFps = if (avg > 0.0) 1000.0 / avg else 0.0,
                jitterMs = Math.sqrt(max(variance, 0.0)),
                droppedFrames = droppedCopy,
                sampledFrames = frames,
                usingFallbackClock = fallback
            )
        )
    }

    /** Resets the pacing window, e.g. right after a mode change so stale deltas don't fight the governor. */
    fun resetWindow() {
        synchronized(this) {
            deltaCount = 0
            deltaCursor = 0
            dropped = 0
            totalFrames = 0
        }
        lastVsyncMs = 0L
    }

    /** Convenience: how far the current instantaneous cadence is from the requested target (ms). */
    fun errorMs(): Double {
        val target = 1000.0 / targetFps
        val last = synchronized(this) { deltas[(deltaCursor - 1 + RING) % RING] }
        return if (last > 0.0) abs(last - target) else 0.0
    }
}
