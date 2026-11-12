package com.dlss5.optimizer.core

import org.json.JSONObject

/**
 * HDR / colour-grade presets for the live compositor.
 *
 * These are *real* shader uniforms — every field maps to a GPU operation in [HdrCompositor]:
 *
 *  | field            | shader operation                                            |
 *  |------------------|-------------------------------------------------------------|
 *  | exposure         | linear multiply before tone mapping                         |
 *  | contrast         | pivot at 0.5 in linear space                                |
 *  | saturation       | luma-preserving mix                                          |
 *  | temperature      | per-channel warm/cool shift                                 |
 *  | shadowLift       | lifts blacks so dark corners of Erangel are readable        |
 *  | highlightRolloff | Reinhard-style shoulder = the "HDR" highlight recovery      |
 *  | bloom            | added blurred bright-pass (sun, muzzle flashes, red zone)   |
 *  | sharpen          | post-composite unsharp mask                                 |
 *
 * The presets are tuned for PUBG Mobile, not for photos: shadow lift and mild saturation win fights,
 * crushed blacks and heavy bloom lose them.
 */
data class HdrPreset(
    val id: String,
    val label: String,
    val description: String,
    val exposure: Float,
    val contrast: Float,
    val saturation: Float,
    val temperature: Float,
    val shadowLift: Float,
    val highlightRolloff: Float,
    val bloom: Float,
    val bloomThreshold: Float,
    val sharpen: Float,
    val targetFps: Int,
    val captureScale: Float
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("exposure", exposure.toDouble())
        put("contrast", contrast.toDouble())
        put("saturation", saturation.toDouble())
        put("temperature", temperature.toDouble())
        put("shadow_lift", shadowLift.toDouble())
        put("highlight_rolloff", highlightRolloff.toDouble())
        put("bloom", bloom.toDouble())
        put("bloom_threshold", bloomThreshold.toDouble())
        put("sharpen", sharpen.toDouble())
    }

    /** Clamps every uniform to a sane range so a bad remote config can never produce a black screen. */
    fun sanitized(): HdrPreset = copy(
        exposure = exposure.coerceIn(0.70f, 1.60f),
        contrast = contrast.coerceIn(0.80f, 1.45f),
        saturation = saturation.coerceIn(0.60f, 1.80f),
        temperature = temperature.coerceIn(-0.12f, 0.12f),
        shadowLift = shadowLift.coerceIn(0f, 0.16f),
        highlightRolloff = highlightRolloff.coerceIn(0f, 1.2f),
        bloom = bloom.coerceIn(0f, 0.9f),
        bloomThreshold = bloomThreshold.coerceIn(0.40f, 0.95f),
        sharpen = sharpen.coerceIn(0f, 0.9f),
        targetFps = targetFps.coerceIn(20, 60),
        captureScale = captureScale.coerceIn(0.5f, 1.0f)
    )

    companion object {
        /** Off — pure DLSS path, no colour work (what the app shipped before). */
        val NATIVE = HdrPreset(
            id = "native",
            label = "NATIVE",
            description = "No colour processing. Upscale + sharpen only.",
            exposure = 1f, contrast = 1f, saturation = 1f, temperature = 0f,
            shadowLift = 0f, highlightRolloff = 0f, bloom = 0f, bloomThreshold = 0.8f,
            sharpen = 0.30f, targetFps = 30, captureScale = 0.75f
        )

        /** Punchy but faithful — a good default for ranked play. */
        val GAME_VIVID = HdrPreset(
            id = "vivid",
            label = "GAME VIVID",
            description = "Balanced HDR look: readable shadows, crisp highlights, +12% vibrance.",
            exposure = 1.05f, contrast = 1.10f, saturation = 1.12f, temperature = 0.015f,
            shadowLift = 0.045f, highlightRolloff = 0.55f, bloom = 0.28f, bloomThreshold = 0.66f,
            sharpen = 0.38f, targetFps = 30, captureScale = 0.80f
        )

        /**
         * EXTREME HDR — the flagship look requested by the product brief.
         * Deep contrast, strong highlight recovery, real bloom on sun/explosions, heavy micro-contrast.
         */
        val EXTREME_HDR = HdrPreset(
            id = "extreme_hdr",
            label = "EXTREME HDR",
            description = "Maximum dynamic range: glowing highlights, deep blacks, +35% vibrance.",
            exposure = 1.10f, contrast = 1.20f, saturation = 1.35f, temperature = 0.025f,
            shadowLift = 0.055f, highlightRolloff = 0.85f, bloom = 0.52f, bloomThreshold = 0.55f,
            sharpen = 0.48f, targetFps = 30, captureScale = 0.75f
        )

        /** Film-like, softer, for sniping / long-range spotting. */
        val CINEMATIC = HdrPreset(
            id = "cinematic",
            label = "CINEMATIC",
            description = "Filmic curve, teal shadows, gentle bloom. Looks great, hides less.",
            exposure = 1.02f, contrast = 1.12f, saturation = 1.05f, temperature = -0.02f,
            shadowLift = 0.075f, highlightRolloff = 0.70f, bloom = 0.40f, bloomThreshold = 0.62f,
            sharpen = 0.26f, targetFps = 30, captureScale = 0.80f
        )

        /** Maximum enemy visibility: lifted shadows, high sharpen, no bloom wash. */
        val ESP_VISIBILITY = HdrPreset(
            id = "visibility",
            label = "VISIBILITY",
            description = "Competitive edge: shadows lifted, edges sharpened, minimal bloom.",
            exposure = 1.12f, contrast = 0.98f, saturation = 1.18f, temperature = 0.005f,
            shadowLift = 0.115f, highlightRolloff = 0.35f, bloom = 0.10f, bloomThreshold = 0.80f,
            sharpen = 0.62f, targetFps = 30, captureScale = 0.82f
        )

        val ALL: List<HdrPreset> = listOf(NATIVE, GAME_VIVID, EXTREME_HDR, CINEMATIC, ESP_VISIBILITY)

        fun byId(id: String?): HdrPreset =
            ALL.firstOrNull { it.id == id } ?: GAME_VIVID

        fun indexOf(id: String?): Int = ALL.indexOfFirst { it.id == id }.coerceAtLeast(0)

        fun next(id: String?): HdrPreset = ALL[(indexOf(id) + 1) % ALL.size]

        /** Applies remote tuning payload on top of a preset, clamped. */
        fun withRemote(preset: HdrPreset, remote: JSONObject?): HdrPreset {
            if (remote == null) return preset
            return preset.copy(
                exposure = remote.optDouble("exposure", preset.exposure.toDouble()).toFloat(),
                contrast = remote.optDouble("contrast", preset.contrast.toDouble()).toFloat(),
                saturation = remote.optDouble("saturation", preset.saturation.toDouble()).toFloat(),
                temperature = remote.optDouble("temperature", preset.temperature.toDouble()).toFloat(),
                shadowLift = remote.optDouble("shadow_lift", preset.shadowLift.toDouble()).toFloat(),
                highlightRolloff = remote.optDouble("highlight_rolloff", preset.highlightRolloff.toDouble()).toFloat(),
                bloom = remote.optDouble("bloom", preset.bloom.toDouble()).toFloat(),
                bloomThreshold = remote.optDouble("bloom_threshold", preset.bloomThreshold.toDouble()).toFloat(),
                sharpen = remote.optDouble("sharpen", preset.sharpen.toDouble()).toFloat()
            ).sanitized()
        }
    }
}

/** Immutable render settings the compositor and the session record both read. */
data class HdrSettings(
    val preset: HdrPreset,
    val enabled: Boolean,
    val blend: Float
) {
    companion object {
        fun disabled(): HdrSettings = HdrSettings(HdrPreset.NATIVE, enabled = false, blend = 0f)
    }
}
