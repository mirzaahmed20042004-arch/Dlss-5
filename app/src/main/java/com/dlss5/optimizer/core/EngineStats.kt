package com.dlss5.optimizer.core

import org.json.JSONObject

/** One session of active rendering, as persisted locally and synced to Supabase. */
data class SessionRecord(
    val sessionId: String,
    val deviceId: String,
    val startedAtMs: Long,
    val endedAtMs: Long,
    val mode: Int,
    val targetFps: Int,
    val avgFps: Double,
    val p95FrameMs: Double,
    val droppedFrames: Int,
    val avgRenderScale: Float,
    val finalRenderScale: Float,
    val thermalMax: Int,
    val socTier: String,
    val lanczos: Boolean,
    val engineCostMs: Double,
    val synced: Boolean = false
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("session_id", sessionId)
        put("device_id", deviceId)
        put("started_at", com.dlss5.optimizer.util.Time.isoFromEpochMs(startedAtMs))
        put("ended_at", com.dlss5.optimizer.util.Time.isoFromEpochMs(endedAtMs))
        put("mode", modeName(mode))
        put("target_fps", targetFps)
        put("avg_fps", round2(avgFps))
        put("p95_frame_ms", round2(p95FrameMs))
        put("dropped_frames", droppedFrames)
        put("avg_render_scale", round2(avgRenderScale.toDouble()))
        put("final_render_scale", round2(finalRenderScale.toDouble()))
        put("thermal_max", thermalMax)
        put("soc_tier", socTier)
        put("lanczos", lanczos)
        put("engine_cost_ms", round2(engineCostMs))
    }

    fun toLocalJson(): String = toJson().put("synced", synced).toString()

    companion object {
        fun round2(v: Double): Double = if (v.isFinite()) Math.round(v * 100.0) / 100.0 else 0.0

        fun modeName(mode: Int): String = when (mode) {
            0 -> "GOVERNOR"
            1 -> "UPSCALE"
            else -> "TRUE_DLSS"
        }

        fun fromLocalJson(raw: String): SessionRecord? = try {
            val o = JSONObject(raw)
            SessionRecord(
                sessionId = o.getString("session_id"),
                deviceId = o.getString("device_id"),
                startedAtMs = parseIso(o.optString("started_at")),
                endedAtMs = parseIso(o.optString("ended_at")),
                mode = o.optInt("mode_int", when (o.optString("mode")) {
                    "UPSCALE" -> 1
                    "TRUE_DLSS" -> 2
                    else -> 0
                }),
                targetFps = o.optInt("target_fps", 60),
                avgFps = o.optDouble("avg_fps", 0.0),
                p95FrameMs = o.optDouble("p95_frame_ms", 0.0),
                droppedFrames = o.optInt("dropped_frames", 0),
                avgRenderScale = o.optDouble("avg_render_scale", 0.7).toFloat(),
                finalRenderScale = o.optDouble("final_render_scale", 0.7).toFloat(),
                thermalMax = o.optInt("thermal_max", 0),
                socTier = o.optString("soc_tier", "mid"),
                lanczos = o.optBoolean("lanczos", false),
                engineCostMs = o.optDouble("engine_cost_ms", 0.0),
                synced = o.optBoolean("synced", false)
            )
        } catch (t: Throwable) {
            null
        }

        private fun parseIso(iso: String): Long = try {
            val fmt = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
            fmt.timeZone = java.util.TimeZone.getTimeZone("UTC")
            fmt.parse(iso)?.time ?: System.currentTimeMillis()
        } catch (t: Throwable) {
            System.currentTimeMillis()
        }
    }
}

/** Rolling in-memory statistics for the live UI. */
class EngineStats {

    private val fpsWindow = ArrayDeque<Double>()
    private val frameMsWindow = ArrayDeque<Double>()
    private val scaleWindow = ArrayDeque<Float>()
    private var dropped = 0
    private var thermalMax = 0
    private var engineCostSum = 0.0
    private var engineCostCount = 0

    @Synchronized
    fun record(fps: Double, frameMs: Double, scale: Float, droppedFrames: Int, thermal: Int, engineCostMs: Long) {
        fpsWindow.addLast(fps)
        if (fpsWindow.size > 60) fpsWindow.removeFirst()
        frameMsWindow.addLast(frameMs)
        if (frameMsWindow.size > 60) frameMsWindow.removeFirst()
        scaleWindow.addLast(scale)
        if (scaleWindow.size > 60) scaleWindow.removeFirst()
        dropped = maxOf(dropped, droppedFrames)
        thermalMax = maxOf(thermalMax, thermal)
        if (engineCostMs in 0..500) {
            engineCostSum += engineCostMs
            engineCostCount++
        }
    }

    @Synchronized
    fun avgFps(): Double = if (fpsWindow.isEmpty()) 0.0 else fpsWindow.average()

    @Synchronized
    fun p95FrameMs(): Double {
        if (frameMsWindow.isEmpty()) return 0.0
        val sorted = frameMsWindow.sorted()
        return sorted[((sorted.size - 1) * 0.95).toInt().coerceIn(0, sorted.size - 1)]
    }

    @Synchronized
    fun avgScale(): Float = if (scaleWindow.isEmpty()) 0.7f else scaleWindow.average().toFloat()

    @Synchronized
    fun droppedFrames(): Int = dropped

    @Synchronized
    fun thermalMax(): Int = thermalMax

    @Synchronized
    fun engineCostMs(): Double = if (engineCostCount == 0) 0.0 else engineCostSum / engineCostCount

    @Synchronized
    fun reset() {
        fpsWindow.clear()
        frameMsWindow.clear()
        scaleWindow.clear()
        dropped = 0
        thermalMax = 0
        engineCostSum = 0.0
        engineCostCount = 0
    }
}
