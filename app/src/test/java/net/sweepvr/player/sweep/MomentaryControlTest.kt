/* SweepVR Player — GPL-3.0-only.
 * See LICENSE in the repository root.
 */
package net.sweepvr.player.sweep

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Toolbar buttons, as behaviour: arm on side-entry (blue, silent), commit
 * on leaving up or down, cancel on leaving left or right. Every case here
 * is one that would be invisible in a screenshot and obvious on a headset.
 */
class MomentaryControlTest {

    /** 100x100 button at (100,100). */
    private val btn = Rect(100f, 100f, 200f, 200f)
    private val midY = 150f
    private val midX = 150f

    private var fires = 0
    private var lastSide: Side? = null
    private val trace = StringBuilder()

    private fun control(
        standoff: Long = 150L,
        corner: Float = 0f
    ) = MomentaryControl().apply {
        rect = btn
        reentryStandoffMs = standoff
        cornerFraction = corner
        onFire = { lastSide = it; fires++ }
        onTrace = { trace.append(it).append(';') }
    }

    /** One frame at (x,y). Returns whether the control claims the frame. */
    private fun MomentaryControl.f(x: Float, y: Float, dt: Long = 16L) = step(x, y, dt)

    @Test fun `entry arms without firing`() {
        val c = control()
        assertFalse(c.f(90f, midY))
        assertTrue(c.f(midX, midY))   // entry: claims, goes blue
        assertEquals(0, fires)
        assertTrue(c.active)
    }

    @Test fun `exit up commits`() {
        val c = control()
        c.f(90f, midY); c.f(midX, midY)
        assertTrue(c.f(midX, btn.top - 10f))
        assertEquals(1, fires)
        assertEquals(Side.Top, lastSide)
        assertFalse(c.active)
    }

    @Test fun `exit down commits`() {
        val c = control()
        c.f(90f, midY); c.f(midX, midY)
        assertTrue(c.f(midX, btn.bottom + 10f))
        assertEquals(1, fires)
        assertEquals(Side.Bottom, lastSide)
    }

    /** Sliding straight through left-to-right fires nothing. This is the
     *  whole point: reaching a far button must not trigger every button
     *  en route. */
    @Test fun `straight pass-through left to right fires nothing`() {
        val c = control()
        c.f(90f, midY)
        assertTrue(c.f(midX, midY))   // armed...
        assertFalse(c.f(210f, midY))  // ...out the far side: cancelled
        assertEquals(0, fires)
        assertFalse(c.active)
    }

    @Test fun `pass-through right to left fires nothing`() {
        val c = control()
        c.f(210f, midY)
        assertTrue(c.f(midX, midY))
        assertFalse(c.f(90f, midY))
        assertEquals(0, fires)
    }

    @Test fun `holding fires nothing`() {
        val c = control()
        c.f(90f, midY); c.f(midX, midY)
        repeat(60) { assertTrue(c.f(midX, midY, dt = 16L)) }
        assertEquals(0, fires)
        assertTrue(c.active)
    }

    @Test fun `frame claimed while armed only`() {
        val c = control()
        assertFalse(c.f(90f, midY))
        assertTrue(c.f(midX, midY))
        assertTrue(c.f(midX, midY))
        c.f(midX, btn.top - 10f)      // commit
        assertFalse(c.f(midX, btn.top - 20f))
    }

    @Test fun `does not arm from top or bottom`() {
        val c = control()
        c.f(midX, midY); c.f(midX, 10f)
        fires = 0
        assertFalse(c.f(midX, btn.top - 1f))
        assertFalse(c.f(midX, btn.bottom + 1f))
        assertEquals(0, fires)
        assertFalse(c.active)
    }

    @Test fun `does not arm from just outside an edge`() {
        val c = control()
        c.f(midX, midY); c.f(10f, midY)
        fires = 0
        assertFalse(c.f(btn.left - 1f, midY))
        assertFalse(c.f(btn.right + 1f, midY))
        assertEquals(0, fires)
    }

    @Test fun `refuses a corner entry`() {
        val c = control(corner = 0.14f)
        c.f(btn.left - 20f, midY)
        assertFalse(c.f(btn.left + 2f, btn.top + 2f))
        assertEquals(0, fires)
        assertFalse(c.active)
    }

    /** The tremor case: commit, out-and-back inside the standoff, exit up
     *  again - the second commit is swallowed, not fired. */
    @Test fun `immediate re-commit inside the standoff does not fire`() {
        val c = control()
        c.f(90f, midY); c.f(midX, midY); c.f(midX, btn.top - 10f)
        assertEquals(1, fires)
        c.f(midX, midY)               // straight back: swallowed
        assertFalse(c.active)
        c.f(midX, btn.top - 10f)      // out the top again: still standoff
        assertEquals(1, fires)
    }

    @Test fun `commit after the standoff fires again`() {
        val c = control()
        c.f(90f, midY); c.f(midX, midY); c.f(midX, btn.top - 10f)
        assertEquals(1, fires)
        // Back out to the side and wait: re-entry must come through a
        // nominated side, so coming straight back down the middle would
        // resolve to Top and refuse - swing out left first, like a real
        // re-aim.
        c.f(90f, midY)
        repeat(12) { c.f(90f, midY, dt = 16L) }  // 192ms, outside
        c.f(midX, midY)               // re-enter from the left, genuinely
        c.f(midX, btn.top - 10f)
        assertEquals(2, fires)
        assertEquals(Side.Top, lastSide)
    }

    @Test fun `reset never fires and never starts a standoff`() {
        val c = control()
        c.f(90f, midY); c.f(midX, midY)  // armed
        c.reset()
        assertFalse(c.active)
        assertTrue(c.f(midX, midY))      // first genuine entry still arms
        assertEquals(0, fires)
    }

    @Test fun `an empty rect never fires`() {
        val c = control().apply { rect = Rect(0f, 0f, 0f, 0f) }
        assertFalse(c.f(midX, midY))
        assertEquals(0, fires)
    }

    @Test fun `traces arm and commit with sides`() {
        val c = control()
        c.f(90f, midY); c.f(midX, midY); c.f(midX, btn.bottom + 10f)
        val t = trace.toString()
        assertTrue(t, t.contains("arm left"))
        assertTrue(t, t.contains("commit exit bottom"))
    }
}
