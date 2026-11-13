package com.dlss5.optimizer.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * A "look" = a stack of translucent colour layers painted over the game by [com.dlss5.optimizer.ui.ColorLayerView].
 *
 * **Why this exists:** the capture-based GPU grade is the highest-quality path, but it needs MediaProjection
 * *and* a driver that accepts our shaders. When either fails the user sees no change at all — which is
 * exactly the report "1% bhi change nahi dikha". A colour layer needs nothing except the overlay permission:
 * it is composited by SurfaceFlinger on top of the game on every Android device ever made, so the screen
 * *always* visibly changes. The capture grade then layers on top of it for real per-pixel work
 * (saturation / sharpness / bloom).
 *
 * Layer model (all optional, each painted full-screen):
 *  * `scrimColor/alpha`  — global dim/暖 tint, also used to fake exposure changes.
 *  * `topColor/bottomColor` + alphas — vertical gradient: this is what makes **NIGHT MODE** read as night
 *    (deep blue at the sky, slightly warmer near the ground) without touching the game's renderer.
 *  * `vignette`          — radial darkening, focuses attention and deepens contrast perceptually.
 *  * `warmth`            — a magenta/teal pair that shifts white balance perceptually.
 *  * `brightness`        — window brightness multiplier (0.35–1.0), applied through
 *    `WindowManager.LayoutParams.screenBrightness`, which is honoured by the system without WRITE_SETTINGS.
 */
data class ColorLook(
    val id: String,
    val label: String,
    val description: String,
    val scrimColor: Int = 0x00000000,
    val scrimAlpha: Float = 0f,
    val topColor: Int = 0x00000000,
    val topAlpha: Float = 0f,
    val bottomColor: Int = 0x00000000,
    val bottomAlpha: Float = 0f,
    val vignette: Float = 0f,
    val warmth: Float = 0f,
    val brightness: Float = 1.0f,
    val night: Boolean = false
) {

    fun sanitized(): ColorLook = copy(
        scrimAlpha = scrimAlpha.coerceIn(0f, 0.85f),
        topAlpha = topAlpha.coerceIn(0f, 0.9f),
        bottomAlpha = bottomAlpha.coerceIn(0f, 0.9f),
        vignette = vignette.coerceIn(0f, 0.85f),
        warmth = warmth.coerceIn(-1f, 1f),
        brightness = brightness.coerceIn(0.30f, 1.0f)
    )

    /** True when this look paints nothing (pure capture-grade path). */
    fun isEmpty(): Boolean =
        scrimAlpha <= 0.001f && topAlpha <= 0.001f && bottomAlpha <= 0.001f && vignette <= 0.001f

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("label", label)
        put("scrim_alpha", scrimAlpha.toDouble())
        put("top_alpha", topAlpha.toDouble())
        put("bottom_alpha", bottomAlpha.toDouble())
        put("vignette", vignette.toDouble())
        put("warmth", warmth.toDouble())
        put("brightness", brightness.toDouble())
    }

    companion object {

        // ---------------------------------------------------------------- helpers
        private fun argb(a: Int, r: Int, g: Int, b: Int) =
            ((a.coerceIn(0, 255)) shl 24) or ((r.coerceIn(0, 255)) shl 16) or
                ((g.coerceIn(0, 255)) shl 8) or b.coerceIn(0, 255)

        // ---------------------------------------------------------------- looks

        /** No layers at all — only the capture grade paints. */
        val PURE = ColorLook(
            id = "pure", label = "PURE (no layers)",
            description = "No colour layers. Only the capture-based GPU grade paints the screen.",
            brightness = 1.0f
        )

        /** The default "wow" look: punchy, warm, deep corners. Instantly visible. */
        val DLSS_EXTREME = ColorLook(
            id = "dlss_extreme",
            label = "DLSS EXTREME",
            description = "Warm cinematic punch: lifted mids, deep corners, rich contrast. Instant and visible.",
            scrimColor = argb(255, 255, 150, 40), scrimAlpha = 0.10f,
            topColor = argb(255, 10, 30, 60), topAlpha = 0.22f,
            bottomColor = argb(255, 255, 120, 20), bottomAlpha = 0.14f,
            vignette = 0.42f, warmth = 0.35f, brightness = 1.0f
        )

        /** Vivid: strong colour push with a slight cool top — the "HDR pop" people expect. */
        val VIVID_HDR = ColorLook(
            id = "vivid_hdr",
            label = "VIVID HDR",
            description = "Maximum colour pop: cool sky, warm ground, strong vignette. Works on every phone.",
            scrimColor = argb(255, 20, 60, 255), scrimAlpha = 0.10f,
            topColor = argb(255, 0, 90, 255), topAlpha = 0.26f,
            bottomColor = argb(255, 255, 170, 40), bottomAlpha = 0.18f,
            vignette = 0.35f, warmth = 0.45f
        )

        // ---------------------------------------------------------------- night family

        /** NIGHT MODE — the sky reads as night: deep blue top, muted ground, dimmed panel. */
        val NIGHT_MODE = ColorLook(
            id = "night_mode",
            label = "NIGHT MODE",
            description = "Turns the sky night-blue, cools the ground, dims the screen. Real night mood in-game.",
            scrimColor = argb(255, 10, 20, 60), scrimAlpha = 0.30f,
            topColor = argb(255, 4, 10, 45), topAlpha = 0.62f,
            bottomColor = argb(255, 18, 28, 70), bottomAlpha = 0.34f,
            vignette = 0.46f, warmth = -0.55f, brightness = 0.72f, night = true
        )

        /** NIGHT VISION — green/cyan night optics look. */
        val NIGHT_VISION = ColorLook(
            id = "night_vision",
            label = "NIGHT VISION",
            description = "Green night-optics tint with lifted shadows — spot movement in dark areas.",
            scrimColor = argb(255, 0, 60, 30), scrimAlpha = 0.26f,
            topColor = argb(255, 0, 90, 40), topAlpha = 0.44f,
            bottomColor = argb(255, 40, 120, 60), bottomAlpha = 0.26f,
            vignette = 0.52f, warmth = -0.35f, brightness = 0.92f, night = true
        )

        /** MOONLIGHT — pale blue, very dim, cinematic night. */
        val MOONLIGHT = ColorLook(
            id = "moonlight",
            label = "MOONLIGHT",
            description = "Pale moonlit blue with heavy vignette. Cinematic night, easiest on the eyes.",
            scrimColor = argb(255, 20, 35, 80), scrimAlpha = 0.36f,
            topColor = argb(255, 30, 55, 120), topAlpha = 0.46f,
            bottomColor = argb(255, 10, 18, 45), bottomAlpha = 0.40f,
            vignette = 0.58f, warmth = -0.6f, brightness = 0.62f, night = true
        )

        /** AMBER NIGHT — warm night for comfortable long sessions (blue-light reduction). */
        val AMBER_NIGHT = ColorLook(
            id = "amber_night",
            label = "AMBER NIGHT",
            description = "Warm amber night filter — cuts blue light, comfortable for long sessions.",
            scrimColor = argb(255, 120, 60, 0), scrimAlpha = 0.30f,
            topColor = argb(255, 150, 70, 10), topAlpha = 0.32f,
            bottomColor = argb(255, 90, 40, 0), bottomAlpha = 0.26f,
            vignette = 0.40f, warmth = 0.6f, brightness = 0.78f, night = true
        )

        // ---------------------------------------------------------------- competitive / stylistic

        /** VISIBILITY — lifts the mid-tones so players in shadows pop. */
        val VISIBILITY = ColorLook(
            id = "visibility",
            label = "VISIBILITY",
            description = "Lifts shadows and cools mid-tones — enemies in dark corners stand out.",
            scrimColor = argb(255, 200, 240, 255), scrimAlpha = 0.09f,
            topColor = argb(255, 120, 200, 255), topAlpha = 0.12f,
            bottomColor = argb(255, 255, 255, 255), bottomAlpha = 0.10f,
            vignette = 0.14f, warmth = -0.15f, brightness = 1.0f
        )

        /** CINEMATIC — teal/orange grade, the classic film look. */
        val CINEMATIC = ColorLook(
            id = "cinematic",
            label = "CINEMATIC",
            description = "Teal shadows + orange highlights (the film look), gentle vignette.",
            scrimColor = argb(255, 0, 60, 90), scrimAlpha = 0.16f,
            topColor = argb(255, 0, 70, 100), topAlpha = 0.26f,
            bottomColor = argb(255, 220, 120, 30), bottomAlpha = 0.16f,
            vignette = 0.34f, warmth = 0.2f, brightness = 0.98f
        )

        /** NOIR — desaturated high-contrast look (reads as black & white-ish through layers). */
        val NOIR = ColorLook(
            id = "noir",
            label = "NOIR",
            description = "High-contrast dark mood with crushed corners. Dramatic, low-glare.",
            scrimColor = argb(255, 0, 0, 0), scrimAlpha = 0.34f,
            topColor = argb(255, 0, 0, 0), topAlpha = 0.40f,
            bottomColor = argb(255, 0, 0, 0), bottomAlpha = 0.30f,
            vignette = 0.66f, warmth = -0.1f, brightness = 0.85f
        )

        /** DESERT / SUNSET — heavy warm push. */
        val SUNSET = ColorLook(
            id = "sunset",
            label = "SUNSET",
            description = "Golden-hour grade: warm sky, orange ground, soft vignette.",
            scrimColor = argb(255, 255, 90, 30), scrimAlpha = 0.20f,
            topColor = argb(255, 255, 120, 40), topAlpha = 0.30f,
            bottomColor = argb(255, 200, 60, 20), bottomAlpha = 0.26f,
            vignette = 0.30f, warmth = 0.6f, brightness = 0.98f
        )

        val ALL: List<ColorLook> = listOf(
            DLSS_EXTREME, VIVID_HDR,
            NIGHT_MODE, NIGHT_VISION, MOONLIGHT, AMBER_NIGHT,
            VISIBILITY, CINEMATIC, SUNSET, NOIR, PURE
        )

        fun byId(id: String?): ColorLook = ALL.firstOrNull { it.id == id } ?: DLSS_EXTREME

        fun indexOf(id: String?): Int = ALL.indexOfFirst { it.id == id }.coerceAtLeast(0)

        fun next(id: String?): ColorLook = ALL[(indexOf(id) + 1) % ALL.size]

        fun previous(id: String?): ColorLook = ALL[(indexOf(id) - 1 + ALL.size) % ALL.size]

        fun nights(): List<ColorLook> = ALL.filter { it.night }

        /**
         * Blends a look toward "no layers" by [strength] (0 = off, 1 = full look). Lets the user dial the
         * effect without losing the preset.
         */
        fun scaled(look: ColorLook, strength: Float): ColorLook {
            val s = strength.coerceIn(0f, 1f)
            return look.copy(
                scrimAlpha = look.scrimAlpha * s,
                topAlpha = look.topAlpha * s,
                bottomAlpha = look.bottomAlpha * s,
                vignette = look.vignette * s,
                warmth = look.warmth * s,
                brightness = 1f - (1f - look.brightness) * s
            )
        }

        /** Automatic night look by wall-clock hour: sunset→midnight→dawn. */
        fun autoNightFor(hourOfDay: Int): ColorLook? = when (hourOfDay) {
            in 20..23 -> NIGHT_MODE
            in 0..5 -> MOONLIGHT
            in 6..6 -> AMBER_NIGHT
            else -> null
        }

        fun fromJson(raw: String?): ColorLook? = try {
            if (raw.isNullOrBlank()) null
            val o = JSONObject(raw)
            byId(o.optString("id")).copy(
                scrimAlpha = o.optDouble("scrim_alpha", 0.0).toFloat(),
                topAlpha = o.optDouble("top_alpha", 0.0).toFloat(),
                bottomAlpha = o.optDouble("bottom_alpha", 0.0).toFloat(),
                vignette = o.optDouble("vignette", 0.0).toFloat(),
                warmth = o.optDouble("warmth", 0.0).toFloat(),
                brightness = o.optDouble("brightness", 1.0).toFloat()
            ).sanitized()
        } catch (t: Throwable) {
            null
        }

        fun allAsJson(): String {
            val arr = JSONArray()
            ALL.forEach { arr.put(it.toJson()) }
            return arr.toString()
        }
    }
}
