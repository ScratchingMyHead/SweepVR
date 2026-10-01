/* SweepVR Player — GPL-3.0-only.
 * See LICENSE in the repository root.
 */
package net.sweepvr.player.sweep

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Dwell fallback for sweep-disabled mode: the whole rect counts (no sides,
 * no corners), still gaze fills to 1 over dwellMs and fires once per visit.
 */
class DwellButtonTest {

    /** 100x100 button at (100,100). */
    private val btn = Rect(100f, 100f, 200f, 200f)

    private var fires = 0
    private val trace = StringBuilder()

    private fun control(dwell: Long = 150L) = DwellButton().apply {
        rect = btn
        dwellMs = dwell
        onFire = { fires++ }
        onTrace = { trace.append(it).append(';') }
    }

    /** One 16ms frame. Returns progress 0..1. */
    private fun DwellButton.f(x: Float, y: Float, still: Boolean = true, dt: Long = 16L) =
        step(x, y, still, dt)

    @Test fun `outside stays zero`() {
        val c = control()
        assertEquals(0f, c.f(50f, 50f))
        assertEquals(0f, c.progress)
    }

    @Test fun `still gaze fills and fires once`() {
        val c = control(dwell = 160L)
        repeat(9) { c.f(150f, 150f) }
        assertEquals(0, fires)
        assertTrue(c.f(150f, 150f) >= 1f)
        assertEquals(1, fires)
        // Holding the gaze fires nothing more.
        repeat(20) { c.f(150f, 150f) }
        assertEquals(1, fires)
    }

    @Test fun `moving drains without zeroing`() {
        val c = control(dwell = 160L)
        repeat(5) { c.f(150f, 150f) }
        val mid = c.progress
        assertTrue(mid > 0f)
        c.f(150f, 150f, still = false)
        assertTrue(c.progress < mid)
        assertEquals(0, fires)
    }

    @Test fun `leaving resets and re-arms`() {
        val c = control(dwell = 160L)
        repeat(10) { c.f(150f, 150f) }
        assertEquals(1, fires)
        assertEquals(0f, c.f(50f, 50f))
        assertEquals(0f, c.progress)
        repeat(10) { c.f(150f, 150f) }
        assertEquals(2, fires)
    }

    @Test fun `entry anywhere counts, not just sides`() {
        val c = control(dwell = 160L)
        // Top edge centre: refused as a sweep entry, fine as dwell.
        repeat(10) { c.f(150f, 105f) }
        assertEquals(1, fires)
    }

    @Test fun `empty rect is safe`() {
        val c = control()
        c.rect = Rect(0f, 0f, 0f, 0f)
        assertEquals(0f, c.f(150f, 150f))
    }
}
