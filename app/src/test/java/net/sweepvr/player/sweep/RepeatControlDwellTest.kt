/* SweepVR Player — GPL-3.0-only.
 * See LICENSE in the repository root.
 */
package net.sweepvr.player.sweep

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Dwell twin of the scrollbar arrow (sweep disabled): dwell the whole
 * button for the entry hit, hold past the pause to repeat, leave to stop.
 * The sweep path is untouched - every existing sweep test must keep passing
 * with dwellMode off.
 */
class RepeatControlDwellTest {

    /** 100x100 button at (100,100). */
    private val btn = Rect(100f, 100f, 200f, 200f)

    private var fires = 0
    private var starts = 0
    private var stops = 0

    private fun control() = RepeatControl(400L, 90L).apply {
        rect = btn
        reentryStandoffMs = 150L
        dwellMode = true
        dwellMs = 160L
        onFire = { fires++ }
        onRepeatStart = { starts++ }
        onRepeatStop = { stops++ }
    }

    /** One 16ms still frame. Returns whether the control owns the frame. */
    private fun RepeatControl.f(x: Float, y: Float, still: Boolean = true) =
        step(x, y, 16L, still)

    @Test fun `outside stays idle`() {
        val c = control()
        assertFalse(c.f(50f, 50f))
        assertEquals(0, fires)
        assertEquals(0f, c.dwellProgress)
    }

    @Test fun `dwell anywhere fires the entry hit`() {
        val c = control()
        // Top-centre: refused as a sweep entry, fine as dwell.
        repeat(9) { assertTrue(c.f(150f, 105f)) }
        assertEquals(0, fires)
        assertTrue(c.f(150f, 105f))
        assertEquals(1, fires)
        assertEquals(1, c.fireCount)
    }

    @Test fun `holding past the pause repeats then leaving stops`() {
        val c = control()
        repeat(10) { c.f(150f, 150f) }
        assertEquals(1, fires)
        assertEquals(0, starts)
        // Past the 400ms pause the repeat phase starts...
        repeat(30) { c.f(150f, 150f) }
        assertEquals(1, starts)
        assertTrue("held repeats must fire more than the entry hit", fires > 1)
        // ...and leaving stops it exactly once (after the tremor grace).
        assertTrue("inside the grace the hold is still owned", c.f(50f, 50f))
        repeat(20) { c.f(50f, 50f) }
        assertFalse(c.f(50f, 50f))
        assertEquals(1, stops)
        assertFalse(c.f(50f, 50f))
        assertEquals(1, stops)
    }

    @Test fun `moving drains the entry fill`() {
        val c = control()
        repeat(5) { c.f(150f, 150f) }
        val mid = c.dwellProgress
        assertTrue(mid > 0f)
        c.f(150f, 150f, still = false)
        assertTrue(c.dwellProgress < mid)
        assertEquals(0, fires)
    }

    @Test fun `leaving before the pause fires nothing`() {
        val c = control()
        repeat(5) { c.f(150f, 150f) }
        assertTrue("inside the grace the fill is still owned", c.f(50f, 50f))
        repeat(20) { c.f(50f, 50f) }
        assertFalse(c.f(50f, 50f))
        assertEquals(0, fires)
        assertEquals(0, starts)
        assertEquals(0, stops)
    }

    @Test fun `brief wobble out mid-fill rides through`() {
        val c = control()
        repeat(5) { c.f(150f, 150f) }
        val mid = c.dwellProgress
        assertTrue(mid > 0f)
        // 100ms outside: inside the 200ms grace, fill frozen, still owned.
        repeat(6) { assertTrue(c.f(50f, 50f)) }
        assertEquals(mid, c.dwellProgress)
        assertEquals(0, fires)
        // Past the grace: genuine exit, fill gone.
        repeat(20) { c.f(50f, 50f) }
        assertEquals(0f, c.dwellProgress)
    }

    @Test fun `wobble out mid-hold keeps repeating`() {
        val c = control()
        repeat(10) { c.f(150f, 150f) }
        repeat(30) { c.f(150f, 150f) }
        assertEquals(1, starts)
        val held = fires
        // 100ms outside: hold (and its repeat clock) ride through.
        repeat(6) { assertTrue(c.f(50f, 50f)) }
        assertEquals(0, stops)
        assertTrue("repeat clock must run through the grace", fires > held)
        // Past the grace: the hold genuinely ends, exactly once.
        repeat(20) { c.f(50f, 50f) }
        assertEquals(1, stops)
    }

    @Test fun `sweep path unaffected by dwell fields`() {
        // dwellMode off (default): classic side entry still fires at once.
        val c = control().apply { dwellMode = false }
        c.step(50f, 150f, 16L)
        c.step(150f, 150f, 16L)
        assertEquals(1, fires)
    }
}
