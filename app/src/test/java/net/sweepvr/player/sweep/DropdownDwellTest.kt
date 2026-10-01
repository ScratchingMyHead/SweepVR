/* SweepVR Player — GPL-3.0-only.
 * See LICENSE in the repository root.
 */
package net.sweepvr.player.sweep

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Dwell twin of the sweep dropdown (sweep disabled): dwell the whole icon
 * rect to open, dwell a row to commit, leave to cancel. Two dwells: one to
 * open, one to select. The sweep path is untouched - every existing sweep
 * test must keep passing with dwellMode off.
 */
class DropdownDwellTest {

    private val icon = Rect(458f, 400f, 566f, 508f)     // 108 square

    private var committed: DropdownControl.Item? = null

    private fun control(n: Int = 6, dwell: Long = 160L) = DropdownControl(
        rowH = 64f, arrowH = 52f, gap = 8f, paneHalfW = 160f
    ).apply {
        texH = 1024f
        iconRect = icon
        items = (0 until n).map { DropdownControl.Item("item$it", "url$it") }
        leeway = 24f
        rearm = 40f
        dwellMode = true
        dwellMs = dwell
        onCommit = { committed = it }
    }

    /** One 16ms still frame. Returns whether the control owns the frame. */
    private fun DropdownControl.f(x: Float, y: Float, still: Boolean = true) =
        step(x, y, 16L, still)

    private fun dwellIcon(c: DropdownControl, n: Int = 10) {
        repeat(n) { c.f(512f, 454f) }
    }

    @Test fun `dwelling the icon opens, from any side`() {
        val c = control()
        // Top-centre: refused as a sweep entry, fine as dwell.
        dwellIcon(c)
        assertTrue("dwell must open the pane", c.open)
        assertTrue(c.pane != null)
    }

    @Test fun `off-icon stays shut`() {
        val c = control()
        repeat(20) { c.f(100f, 100f) }
        assertFalse(c.open)
        assertEquals(0f, c.dwellProgress)
    }

    @Test fun `moving drains the open fill`() {
        val c = control()
        repeat(5) { c.f(512f, 454f) }
        val mid = c.dwellProgress
        assertTrue(mid > 0f)
        c.f(512f, 454f, still = false)
        assertTrue(c.dwellProgress < mid)
        assertFalse(c.open)
    }

    @Test fun `dwell a row commits it`() {
        val c = control()
        dwellIcon(c)
        assertTrue(c.open)
        val rowY = c.pane!!.top + 32f   // first row centre
        repeat(10) { c.f(512f, rowY) }
        assertEquals("url0", committed?.value)
        assertFalse("commit closes the pane", c.open)
    }

    @Test fun `moving across rows restarts the fill`() {
        val c = control()
        dwellIcon(c)
        val top = c.pane!!.top
        repeat(8) { c.f(512f, top + 32f) }
        assertTrue(c.dwellProgress > 0f)
        c.f(512f, top + 96f)   // second row: fill restarts, no commit
        assertEquals(null, committed)
    }

    @Test fun `leaving the pane cancels`() {
        val c = control()
        dwellIcon(c)
        assertTrue(c.open)
        c.f(100f, 100f)
        assertFalse(c.open)
        assertEquals(null, committed)
    }

    @Test fun `sitting on the icon holds the pane open`() {
        val c = control()
        dwellIcon(c)
        assertTrue(c.open)
        // The icon sits a gap above the pane: resting on it must hold, not
        // cancel (this flashed the list up and back down).
        repeat(30) { c.f(512f, 454f) }
        assertTrue("icon rest must hold the pane", c.open)
        assertEquals(null, committed)
    }

    @Test fun `moving from icon into the pane commits after a dwell`() {
        val c = control()
        dwellIcon(c)
        assertTrue(c.open)
        val rowY = c.pane!!.top + 32f
        repeat(5) { c.f(512f, 454f) }   // hold on the icon: nothing fires
        assertEquals(null, committed)
        repeat(10) { c.f(512f, rowY) }  // dwell the first row: commits
        assertEquals("url0", committed?.value)
        assertFalse(c.open)
    }

    @Test fun `sweep path unaffected by dwell fields`() {
        // dwellMode off (default): classic side entry still opens.
        val c = control().apply { dwellMode = false }
        c.step(300f, 454f, 16L)
        c.step(500f, 454f, 16L)
        assertTrue(c.open)
    }
}
