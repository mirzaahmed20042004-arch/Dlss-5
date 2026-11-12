package com.dlss5.optimizer

import com.dlss5.optimizer.core.FilterKernels
import com.dlss5.optimizer.core.FrameGovernor
import com.dlss5.optimizer.core.SocTier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameGovernorTest {

    private fun run(governor: FrameGovernor, observedMs: Double, updates: Int, startAt: Long = 0L): Float {
        var now = startAt
        var scale = governor.currentScale()
        repeat(updates) {
            now += 1000L
            scale = governor.update(observedMs, now)
        }
        return scale
    }

    @Test
    fun `shrinks scale when frames are late`() {
        val g = FrameGovernor(targetFps = 60, initialScale = 0.9f)
        val scale = run(g, observedMs = 25.0, updates = 40)
        assertTrue("expected shrink, got $scale", scale < 0.9f)
        assertTrue(scale >= 0.6f)
    }

    @Test
    fun `grows scale when there is headroom`() {
        val g = FrameGovernor(targetFps = 60, initialScale = 0.7f)
        val scale = run(g, observedMs = 10.0, updates = 40)
        assertTrue("expected growth, got $scale", scale > 0.7f)
        assertTrue(scale <= 1.0f)
    }

    @Test
    fun `dead band ignores noise`() {
        val g = FrameGovernor(targetFps = 60, initialScale = 0.75f)
        val scale = run(g, observedMs = 1000.0 / 60.0 + 0.8, updates = 30)
        assertEquals(0.75f, scale, 0.001f)
    }

    @Test
    fun `slew limit caps per-update change`() {
        val g = FrameGovernor(targetFps = 60, initialScale = 0.95f)
        val scale = run(g, observedMs = 60.0, updates = 1, startAt = 10_000L)
        assertTrue("change too large: $scale", 0.95f - scale <= 0.0201f)
    }

    @Test
    fun `thermal clamp reduces ceiling`() {
        val g = FrameGovernor(targetFps = 60, initialScale = 0.8f, minScale = 0.5f, maxScale = 1.0f)
        g.applyThermalStatus(4)
        val ceiling = g.effectiveMaxScale()
        assertTrue("ceiling should drop under SEVERE, got $ceiling", ceiling < 1.0f)
        g.applyThermalStatus(0)
        assertEquals(1.0f, g.effectiveMaxScale(), 0.001f)
    }

    @Test
    fun `never escapes bounds`() {
        val g = FrameGovernor(targetFps = 60, initialScale = 0.7f, minScale = 0.6f, maxScale = 0.85f)
        run(g, observedMs = 100.0, updates = 100)
        val low = g.currentScale()
        assertTrue(low >= 0.6f - 0.0001f)
        run(g, observedMs = 2.0, updates = 100)
        val high = g.currentScale()
        assertTrue("high=$high", high <= 0.85f + 0.0001f)
    }
}

class FilterKernelsTest {

    @Test
    fun `bicubic weights sum to one`() {
        for (t in listOf(0f, 0.15f, 0.5f, 0.85f, 1f)) {
            val sum = FilterKernels.bicubicWeights(t).sum()
            assertEquals("t=$t", 1f, sum, 1e-4f)
        }
    }

    @Test
    fun `lanczos weights are normalised and positive at centre`() {
        val w = FilterKernels.lanczos3Weights(0f)
        assertEquals(6, w.size)
        assertEquals(1f, w.sum(), 1e-3f)
        assertTrue(w[2] > 0f)
    }

    @Test
    fun `unsharp strengthens centre`() {
        val w = FilterKernels.unsharpWeights(0.35f)
        assertEquals(1f + 4f * 0.35f, w[2], 1e-4f)
        assertEquals(-0.35f, w[0], 1e-4f)
    }

    @Test
    fun `temporal alpha decreases with jitter`() {
        val calm = FilterKernels.temporalAlpha(0.2, 16.6)
        val jittery = FilterKernels.temporalAlpha(12.0, 16.6)
        assertTrue("calm=$calm jittery=$jittery", calm > jittery)
    }

    @Test
    fun `lanczos not affordable on entry tier`() {
        assertFalse(FilterKernels.canAffordLanczos(SocTier.ENTRY, 720))
        assertTrue(FilterKernels.canAffordLanczos(SocTier.FLAGSHIP, 1440))
        assertFalse(FilterKernels.canAffordLanczos(SocTier.MID, 2000))
    }

    @Test
    fun `cpu upscale doubles size and stays in range`() {
        val src = FloatArray(4 * 4) { 0.5f }
        val dst = FloatArray(8 * 8)
        FilterKernels.upscaleBicubic(src, 4, 4, dst, 8, 8)
        assertTrue(dst.all { it in 0f..1f })
        assertTrue(dst.average() > 0.4)
    }

    @Test
    fun `unsharp keeps flat image flat`() {
        val src = FloatArray(9) { 0.5f }
        val dst = FloatArray(9)
        FilterKernels.applyUnsharp(src, 3, 3, dst, 0.5f)
        dst.forEach { assertEquals(0.5f, it, 1e-5f) }
    }
}
