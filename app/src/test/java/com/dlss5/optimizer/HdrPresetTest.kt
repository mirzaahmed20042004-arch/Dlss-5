package com.dlss5.optimizer

import com.dlss5.optimizer.core.HdrPreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HdrPresetTest {

    @Test
    fun `extreme hdr is the punchiest preset`() {
        val extreme = HdrPreset.EXTREME_HDR
        val vivid = HdrPreset.GAME_VIVID
        assertTrue(extreme.saturation > vivid.saturation)
        assertTrue(extreme.bloom > vivid.bloom)
        assertTrue(extreme.sharpen > vivid.sharpen)
    }

    @Test
    fun `every preset is inside safe uniform ranges`() {
        HdrPreset.ALL.forEach { preset ->
            val s = preset.sanitized()
            assertEquals(preset.exposure, s.exposure, 1e-5f)
            assertTrue("${preset.id} exposure", s.exposure in 0.7f..1.6f)
            assertTrue("${preset.id} bloom", s.bloom in 0f..0.9f)
            assertTrue("${preset.id} sharpen", s.sharpen in 0f..0.9f)
            assertTrue("${preset.id} blend sanitiser", s.captureScale in 0.5f..1.0f)
        }
    }

    @Test
    fun `absurd values are clamped by sanitized`() {
        // Mirrors what a malformed remote payload would produce after optDouble parsing.
        val wild = HdrPreset.GAME_VIVID.copy(
            exposure = 99f,
            saturation = -5f,
            bloom = 40f,
            sharpen = 12f,
            captureScale = 3f,
            targetFps = 500
        ).sanitized()
        assertEquals(1.6f, wild.exposure, 1e-5f)
        assertEquals(0.6f, wild.saturation, 1e-5f)
        assertEquals(0.9f, wild.bloom, 1e-5f)
        assertEquals(0.9f, wild.sharpen, 1e-5f)
        assertEquals(1.0f, wild.captureScale, 1e-5f)
        assertEquals(60, wild.targetFps)
    }

    @Test
    fun `null remote config leaves the preset untouched`() {
        val base = HdrPreset.EXTREME_HDR
        assertEquals(base, HdrPreset.withRemote(base, null))
    }

    @Test
    fun `preset cycling is stable and wraps`() {
        var id = HdrPreset.ALL.first().id
        repeat(HdrPreset.ALL.size * 3) { id = HdrPreset.next(id).id }
        assertEquals(HdrPreset.ALL.first().id, HdrPreset.ALL[(HdrPreset.ALL.size * 3) % HdrPreset.ALL.size].id)
        assertNotEquals(null, HdrPreset.byId(id))
    }

    @Test
    fun `unknown preset id falls back to a working preset`() {
        val preset = HdrPreset.byId("does_not_exist")
        assertTrue(HdrPreset.ALL.contains(preset))
    }

    @Test
    fun `presets declare capture scale and fps within engine limits`() {
        HdrPreset.ALL.forEach {
            assertTrue("${it.id}", it.captureScale in 0.5f..1.0f)
            assertTrue("${it.id}", it.targetFps in 20..60)
        }
    }
}

class ColorMathTest {

    @Test
    fun `gamma round trip is stable`() {
        val values = floatArrayOf(0f, 0.1f, 0.5f, 0.9f, 1f)
        values.forEach { v ->
            val roundTrip = com.dlss5.optimizer.core.ColorMath.toDisplay(
                com.dlss5.optimizer.core.ColorMath.toLinear(v)
            )
            assertEquals(v, roundTrip, 1e-3f)
        }
    }

    @Test
    fun `neutral preset leaves colours almost untouched`() {
        val neutral = com.dlss5.optimizer.core.HdrPreset.NATIVE.copy(
            exposure = 1f, contrast = 1f, saturation = 1f, temperature = 0f,
            shadowLift = 0f, highlightRolloff = 0f
        )
        val out = com.dlss5.optimizer.core.ColorMath.gradeChannel(0.4f, 0.6f, 0.2f, neutral)
        assertEquals(0.4f, out[0], 2e-2f)
        assertEquals(0.6f, out[1], 2e-2f)
        assertEquals(0.2f, out[2], 2e-2f)
    }

    @Test
    fun `extreme hdr increases contrast and vibrance`() {
        val preset = com.dlss5.optimizer.core.HdrPreset.EXTREME_HDR
        val low = com.dlss5.optimizer.core.ColorMath.gradeChannel(0.08f, 0.10f, 0.12f, preset)
        val high = com.dlss5.optimizer.core.ColorMath.gradeChannel(0.80f, 0.82f, 0.85f, preset)
        // shadows lifted above their input, highlights compressed by the shoulder
        assertTrue("shadow lift failed: ${low[2]}", low[2] > 0.10f)
        assertTrue("highlight rolloff failed: ${high[0]}", high[0] < 0.95f)
    }

    @Test
    fun `correction solve reconstructs the target`() {
        val original = 0.35f
        val target = 0.55f
        val alpha = 0.34f
        val src = com.dlss5.optimizer.core.ColorMath.correctionChannel(target, original, alpha)
        val composited = src * alpha + original * (1f - alpha)
        assertEquals(target, composited, 1e-4f)
    }

    @Test
    fun `saturation boost is luma preserving`() {
        val preset = com.dlss5.optimizer.core.HdrPreset.EXTREME_HDR.copy(temperature = 0f)
        val out = com.dlss5.optimizer.core.ColorMath.gradeChannel(0.5f, 0.5f, 0.5f, preset)
        // grey has nothing to saturate; it must stay grey
        assertEquals(out[0], out[1], 2e-3f)
        assertEquals(out[1], out[2], 2e-3f)
    }
}
