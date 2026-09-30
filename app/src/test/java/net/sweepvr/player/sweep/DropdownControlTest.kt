/* SweepVR Player — GPL-3.0-only.
 * See LICENSE in the repository root.
 */
package net.sweepvr.player.sweep

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every rule here was a bug in the hand-written version, found by hand, in a
 * headset. If one of these is ever uncomfortable to write, that is a sign the
 * behaviour is being described rather than pinned.
 */
class DropdownControlTest {

    private val icon = Rect(458f, 400f, 566f, 508f)     // 108 square, side-entry

    private fun control(n: Int = 6, halfW: Float = 160f) = DropdownControl(
        rowH = 64f, arrowH = 52f, gap = 8f, paneHalfW = halfW
    ).apply {
        texH = 1024f
        iconRect = icon
        items = (0 until n).map { DropdownControl.Item("item$it", "url$it") }
        leeway = 24f
        rearm = 40f
    }

    /** Sweep in from the left and stop on the button. */
    private fun DropdownControl.enterFromLeft() {
        step(300f, 454f, 16L)
        step(500f, 454f, 16L)
    }

    // ---- opening ----

    @Test
    fun `opens when swept into from the left`() {
        val c = control()
        c.enterFromLeft()
        assertTrue("pane should be open", c.open)
        assertTrue("pane rect should exist", c.pane != null)
    }

    @Test
    fun `opens when swept into from the right`() {
        val c = control()
        c.step(800f, 454f, 16L)
        c.step(520f, 454f, 16L)
        assertTrue(c.open)
    }

    @Test
    fun `does not open from above`() {
        val c = control()
        c.step(512f, 200f, 16L)
        c.step(512f, 450f, 16L)
        assertTrue("dropping from above must not open it", !c.open)
    }

    @Test
    fun `does not open from below`() {
        val c = control()
        c.step(512f, 800f, 16L)
        c.step(512f, 470f, 16L)
        assertTrue("sweeping up from below must not open it", !c.open)
    }

    @Test
    fun `pane sits below the button`() {
        val c = control()
        c.enterFromLeft()
        val p = c.pane!!
        assertTrue("pane must start below the button, was $p", p.top >= icon.bottom)
        assertEquals("pane and button share a centre line", icon.left + icon.width / 2, p.left + p.width / 2, 0.5f)
    }

    @Test
    fun `pane flips above when there is no room below`() {
        val c = control(n = 20)
        val low = Rect(458f, 860f, 566f, 968f)             // near the bottom
        c.iconRect = low
        c.step(300f, 914f, 16L)                            // approach the RELOCATED
        c.step(500f, 914f, 16L)                            // button, not the old one
        assertTrue("pane should be open", c.open)
        val p = c.pane!!
        assertTrue("pane must flip above, was $p", p.bottom <= low.top)
    }

    @Test
    fun `pane is only as wide as it was told`() {
        val c = control(n = 4, halfW = 90f)
        c.enterFromLeft()
        assertEquals(90f, c.pane!!.width / 2, 0.5f)
    }

    @Test
    fun `no items means nothing to open`() {
        val c = control(n = 0)
        c.enterFromLeft()
        assertTrue(!c.open)
        assertNull(c.pane)
    }

    // ---- cursor ----

    @Test
    fun `cursor follows the reticle down the pane`() {
        val c = control()
        c.enterFromLeft()
        val top = c.pane!!.top
        c.step(500f, top + 20f, 16L)                       // row 0
        assertEquals(0, c.cursor)
        c.step(500f, top + 84f, 16L)                       // row 1
        assertEquals(1, c.cursor)
        c.step(500f, top + 148f, 16L)                      // row 2
        assertEquals(2, c.cursor)
    }

    @Test
    fun `cursor cannot leave the list`() {
        val c = control(n = 3)
        c.enterFromLeft()
        val p = c.pane!!
        // Walk down to the very bottom edge of the pane, still inside it.
        for (y in (p.top + 8).toInt() until p.bottom.toInt() step 6) {
            c.step(500f, y.toFloat(), 16L)
            assertTrue("cursor left the list at y=$y: ${c.cursor}", c.cursor in 0..2)
        }
        assertEquals("should settle on the last row", 2, c.cursor)
    }

    // ---- scroll arrows ----

    @Test
    fun `arrows appear only when the list overflows`() {
        val small = control(n = 4)
        small.enterFromLeft()
        assertEquals("no arrows for a short list", 0, small.arrow)

        val big = control(n = 40)
        big.enterFromLeft()
        val p = big.pane!!
        big.step(500f, p.top + 10f, 16L)                    // on the up arrow
        assertEquals("arrow shown for a long list", -1, big.arrow)
    }

    @Test
    fun `hovering an arrow scrolls the list`() {
        val c = control(n = 40)
        c.enterFromLeft()
        val p = c.pane!!
        c.step(500f, p.bottom - 10f, 16L)                   // down arrow
        assertEquals(1, c.arrow)
        c.step(500f, p.bottom - 10f, 1000L)                 // hold it
        assertTrue("holding the down arrow should scroll, scroll was ${c.scroll}", c.scroll > 0)
    }

    @Test
    fun `the up arrow does nothing at the top of the list`() {
        val c = control(n = 40)
        c.enterFromLeft()
        val p = c.pane!!
        c.step(500f, p.top + 10f, 16L)                      // up arrow
        assertEquals(-1, c.arrow)
        c.step(500f, p.top + 10f, 2000L)                    // hold it at the top
        assertEquals("already at the top, must not go negative", 0, c.scroll)
    }

    @Test
    fun `scrolling cannot run past the end`() {
        val c = control(n = 40)
        c.enterFromLeft()
        val p = c.pane!!
        c.step(500f, p.bottom - 10f, 16L)                   // down arrow
        c.step(500f, p.bottom - 10f, 20000L)
        assertTrue("scroll clamped, was ${c.scroll}", c.scroll >= 0)
    }

    // ---- release ----

    @Test
    fun `sideways release on the pane commits the cursor row`() {
        val c = control()
        var committed: DropdownControl.Item? = null
        c.onCommit = { committed = it }
        c.enterFromLeft()
        val top = c.pane!!.top
        c.step(500f, top + 84f, 16L)                        // row 1
        assertEquals(1, c.cursor)
        c.step(300f, top + 84f, 16L)                        // out to the left
        assertEquals("url1", committed?.value)
        assertTrue("pane must close", !c.open)
    }

    @Test
    fun `release upward cancels`() {
        val c = control()
        var committed: DropdownControl.Item? = null
        c.onCommit = { committed = it }
        c.enterFromLeft()
        c.step(500f, c.pane!!.top + 84f, 16L)
        c.step(500f, 200f, 16L)                             // up and out
        assertNull("up must cancel", committed)
        assertTrue(!c.open)
    }

    @Test
    fun `release downward cancels`() {
        val c = control()
        var committed: DropdownControl.Item? = null
        c.onCommit = { committed = it }
        c.enterFromLeft()
        c.step(500f, c.pane!!.top + 84f, 16L)
        c.step(500f, 1000f, 16L)                            // down and out
        assertNull("down must cancel", committed)
    }

    /**
     * The regression. Sweeping across the button on the way somewhere else -
     * opening the pane, then carrying straight on sideways - must NOT commit
     * whatever row the cursor happened to be holding. The list has to be
     * reached first.
     */
    @Test
    fun `sideways release without reaching the pane does not commit`() {
        val c = control()
        var committed: DropdownControl.Item? = null
        c.onCommit = { committed = it }
        c.enterFromLeft()                                    // opens, cursor still -1
        c.step(300f, 454f, 16L)                             // straight back out the side
        assertNull("must not commit without reaching the list", committed)
        assertTrue(!c.open)
    }

    @Test
    fun `drifting just outside does not close it`() {
        val c = control()
        c.enterFromLeft()
        val p = c.pane!!
        // 10px past the button's left edge, inside the 24px leeway.
        c.step(448f, 454f, 16L)
        assertTrue("leeway must hold the pane open", c.open)
    }

    // ---- the button boundary-trace bug ----

    /**
     * The regression. The button sits ABOVE the pane, and the exit test used
     * to use the pane's own hit area, so every move down off the button read
     * as an upward exit: the pane cancelled the instant it opened and the
     * reticle had to be walked around the button's boundary to reach the list.
     * The button and pane must behave as one target.
     */
    @Test
    fun `moves from the button onto the pane stay open`() {
        val c = control()
        var committed: DropdownControl.Item? = null
        c.onCommit = { committed = it }
        c.enterFromLeft()
        assertTrue(c.open)
        val top = c.pane!!.top
        // Straight down the middle, through the gap between button and pane.
        for (y in 454 until (top + 40).toInt() step 4) {
            c.step(512f, y.toFloat(), 16L)
            assertTrue("pane closed while moving down at y=$y", c.open)
        }
        assertNull("must not have committed", committed)
    }

    // ---- re-entry ----

    @Test
    fun `can open again after cancelling`() {
        val c = control()
        c.enterFromLeft()
        c.step(500f, 200f, 16L)                             // cancel upward
        assertTrue(!c.open)
        c.step(300f, 454f, 16L)                             // clear of the rearm zone
        c.step(500f, 454f, 16L)                             // and in from the left
        assertTrue("must be able to open again", c.open)
    }

    @Test
    fun `does not reopen immediately after cancelling near the button`() {
        val c = control()
        c.enterFromLeft()
        // The pane is 320 wide, so its leeway starts at 328: step well clear
        // of that to actually let go.
        c.step(300f, 454f, 16L)                             // out past the leeway: cancels
        assertTrue(!c.open)
        // Still within the rearm zone of the button: must stay shut rather
        // than springing open again under a reticle that never left.
        c.step(430f, 454f, 16L)
        assertTrue("must not spring straight back open", !c.open)
    }
}
