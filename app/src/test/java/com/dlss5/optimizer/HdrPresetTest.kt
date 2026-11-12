package com.dlss5.optimizer

import com.dlss5.optimizer.core.HdrPreset
import org.json.JSONObject
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
    fun `garbage remote config cannot break a preset`() {
        val wild = JSONObject()
            .put("exposure", 99.0)
            .put("saturation", -5.0)
            .put("bloom", 40.0)
        val result = HdrPreset.withRemote(HdrPreset.GAME_VIVID, wild)
        assertTrue(result.exposure <= 1.6f)
        assertTrue(result.saturation >= 0.6f)
        assertTrue(result.bloom <= 0.9f)
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
