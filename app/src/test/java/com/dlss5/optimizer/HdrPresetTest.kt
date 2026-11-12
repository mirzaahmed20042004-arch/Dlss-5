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
