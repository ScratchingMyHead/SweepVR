/* SweepVR Player — GPL-3.0-only.
 * See LICENSE in the repository root.
 */
package net.sweepvr.player.sweep

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The engine is pure geometry, so every case that had to be discovered by
 * hand in a headset is testable here. The diagonal entry, the leeway hold and
 * the "passes straight over" case are all regressions: each one failed in the
 * field and none of them could be seen without a phone and a pair of hands.
 */
class SweepEngineTest {

    /** A small square button, like the bookmarks flyout icon. Entry is from
     *  the sides. Being square it does NOT exercise axis normalisation - see
     *  [normalisation matters most on a tall control] for that. */
    private val button = Rect(458f, 406f, 566f, 514f)
    private val buttonCfg = SweepConfig(
        entrySides = setOf(Side.Left, Side.Right),
        leeway = 24f,
        rearm = 40f,
        refuseCorners = true,
        cornerFraction = 0.14f,
        tieBreak = TieBreak.REFUSE
    )

    /** A narrow, tall thumb, like the scrollbar: enters from the ends. */
    private val thumb = Rect(0f, 100f, 64f, 800f)
    private val thumbCfg = SweepConfig(
        entrySides = setOf(Side.Top, Side.Bottom),
        leeway = 30f,
        rearm = 60f,
        refuseCorners = false,
        tieBreak = TieBreak.REFUSE
    )

    private fun ev(s: SweepEvent, kind: Class<*>): Boolean = kind.isInstance(s)

    // ---- entry ----

    @Test
    fun `enters from the left`() {
        val e = SweepEngine()
        e.step(Pt(300f, 460f), button, buttonCfg)
        val r = e.step(Pt(500f, 460f), button, buttonCfg)
        assertTrue("expected Entered, got $r", ev(r, SweepEvent.Entered::class.java))
        assertEquals(Side.Left, (r as SweepEvent.Entered).side)
    }

    @Test
    fun `enters from the right`() {
        val e = SweepEngine()
        e.step(Pt(800f, 460f), button, buttonCfg)
        val r = e.step(Pt(520f, 460f), button, buttonCfg)
        assertEquals(Side.Right, (r as SweepEvent.Entered).side)
    }

    @Test
    fun `refuses entry from the top`() {
        val e = SweepEngine()
        e.step(Pt(512f, 200f), button, buttonCfg)
        val r = e.step(Pt(512f, 450f), button, buttonCfg)
        assertTrue("top entry must be refused, got $r", ev(r, SweepEvent.Idle::class.java))
        assertTrue(!e.engaged)
    }

    @Test
    fun `refuses entry from the bottom`() {
        val e = SweepEngine()
        e.step(Pt(512f, 900f), button, buttonCfg)
        val r = e.step(Pt(512f, 480f), button, buttonCfg)
        assertTrue("bottom entry must be refused, got $r", ev(r, SweepEvent.Idle::class.java))
    }

    /**
     * The regression. A diagonal approach that lands on the MIDDLE of a side
     * must count as a side entry. Measured as absolute distance from a wide,
     * short button, the left edge is always nearer than the top, so this was
     * reported as an entry from the top and refused every time.
     */
    @Test
    fun `diagonal approach onto a side is a side entry`() {
        val e = SweepEngine()
        e.step(Pt(380f, 380f), button, buttonCfg)
        val r = e.step(Pt(470f, 450f), button, buttonCfg)
        assertTrue("diagonal onto a side must enter, got $r", ev(r, SweepEvent.Entered::class.java))
        assertEquals(Side.Left, (r as SweepEvent.Entered).side)
    }

    @Test
    fun `enters exactly once per approach`() {
        val e = SweepEngine()
        e.step(Pt(300f, 460f), button, buttonCfg)
        assertTrue(ev(e.step(Pt(500f, 460f), button, buttonCfg), SweepEvent.Entered::class.java))
        for (x in 500..540 step 2) {
            val r = e.step(Pt(x.toFloat(), 460f), button, buttonCfg)
            assertTrue("re-fired Entered at x=$x: $r", !ev(r, SweepEvent.Entered::class.java))
        }
    }

    /**
     * The one place the outside-to-inside requirement is actually load
     * bearing, and the reason it is not simply removed as redundant: the
     * `active` flag already suppresses re-entry while held, so the previous
     * position only matters for an entry that was REFUSED.
     *
     * Dropping onto the button from the top is refused, and the reticle ends
     * up resting inside it. Sliding along to the left edge is still inside,
     * so it has not entered from anywhere and must not fire - otherwise a
     * control could be entered by approach-then-slither, which is precisely
     * the incidental brushing the side-only entry exists to prevent.
     */
    @Test
    fun `a refused entry cannot become a side entry by sliding`() {
        val e = SweepEngine()
        e.step(Pt(512f, 200f), button, buttonCfg)
        assertTrue(ev(e.step(Pt(512f, 460f), button, buttonCfg), SweepEvent.Idle::class.java))
        for (x in 500 downTo 460 step 2) {
            val r = e.step(Pt(x.toFloat(), 460f), button, buttonCfg)
            assertTrue("fired after a refused entry at x=$x: $r",
                !ev(r, SweepEvent.Entered::class.java))
        }
    }

    @Test
    fun `passes straight over without entering`() {
        val e = SweepEngine()
        e.step(Pt(512f, 200f), button, buttonCfg)
        var everEntered = false
        for (y in 200..900 step 4) {
            if (ev(e.step(Pt(512f, y.toFloat()), button, buttonCfg), SweepEvent.Entered::class.java))
                everEntered = true
        }
        assertTrue("sweeping down through the button must not enter", !everEntered)
    }

    @Test
    fun `refuses a corner`() {
        val e = SweepEngine()
        e.step(Pt(380f, 380f), button, buttonCfg)
        val r = e.step(Pt(462f, 410f), button, buttonCfg)
        assertTrue("corner must be refused, got $r", ev(r, SweepEvent.Idle::class.java))
    }

    /**
     * The one that cost the most time: the reticle arrives INSIDE the thumb
     * without any frame ever straddling its edge. The scrollbar rejected 112
     * real transitions of exactly this shape.
     */
    @Test
    fun `slow approach still registers even if no frame straddles the edge`() {
        val e = SweepEngine()
        var entered: SweepEvent.Entered? = null
        // Creep toward the thumb's top edge in sub-pixel steps.
        var y = 40f
        while (y < 140f && entered == null) {
            val r = e.step(Pt(32f, y), thumb, thumbCfg)
            if (ev(r, SweepEvent.Entered::class.java)) entered = r as SweepEvent.Entered
            y += 0.4f
        }
        assertTrue("a slow crossing must eventually enter", entered != null)
        assertEquals(Side.Top, entered!!.side)
    }

    // ---- hold and release ----

    @Test
    fun `stays engaged while inside`() {
        val e = SweepEngine()
        e.step(Pt(500f, 460f), button, buttonCfg)   // Entered
        assertTrue(ev(e.step(Pt(520f, 470f), button, buttonCfg), SweepEvent.Engaged::class.java))
        assertTrue(ev(e.step(Pt(530f, 480f), button, buttonCfg), SweepEvent.Engaged::class.java))
        assertTrue(e.engaged)
    }

    @Test
    fun `drifting just outside the control does not release`() {
        val e = SweepEngine()
        e.step(Pt(500f, 460f), button, buttonCfg)
        // 10px past the left edge, inside the 24px leeway.
        val r = e.step(Pt(444f, 460f), button, buttonCfg)
        assertTrue("leeway must hold, got $r", ev(r, SweepEvent.Engaged::class.java))
        assertTrue(e.engaged)
    }

    /**
     * Entry and exit are different questions. Entry is "which edge is this
     * point nearest" (it is inside, and you want the side it came through);
     * exit is "which way did it go" (it is outside, and the measure is how
     * far past each side it got). Answering exit the entry way misreported a
     * sideways release as a vertical one on any tall control, which turned
     * every commit into a cancel.
     */
    @Test
    fun `sideways release off a tall control is not reported as vertical`() {
        val tall = Rect(0f, 0f, 428f, 308f)
        val cfg = SweepConfig(setOf(Side.Left), leeway = 24f, rearm = 40f)
        val e = SweepEngine()
        e.step(Pt(200f, 150f), tall, cfg)                    // Entered
        // 82px left, 54px above centre: nearest normalised edge is the top,
        // but it plainly left sideways.
        val r = e.step(Pt(-82f, 54f), tall, cfg)
        assertEquals("sideways release must read as Left",
            Side.Left, (r as SweepEvent.Released).side)
    }

    @Test
    fun `vertical release off a tall control is still vertical`() {
        val tall = Rect(0f, 0f, 428f, 308f)
        val cfg = SweepConfig(setOf(Side.Left), leeway = 24f, rearm = 40f)
        val e = SweepEngine()
        e.step(Pt(200f, 150f), tall, cfg)
        val r = e.step(Pt(200f, -60f), tall, cfg)             // 60px straight up
        assertEquals(Side.Top, (r as SweepEvent.Released).side)
    }

    @Test
    fun `releasing sideways past the leeway`() {
        val e = SweepEngine()
        e.step(Pt(500f, 460f), button, buttonCfg)
        val r = e.step(Pt(400f, 460f), button, buttonCfg)   // 58px past the left edge
        assertTrue("must release, got $r", ev(r, SweepEvent.Released::class.java))
        assertEquals(Side.Left, (r as SweepEvent.Released).side)
        assertTrue(!e.engaged)
    }

    @Test
    fun `releasing past the top reports the top`() {
        val e = SweepEngine()
        e.step(Pt(32f, 200f), thumb, thumbCfg)            // Entered from the top
        val r = e.step(Pt(32f, 20f), thumb, thumbCfg)      // 80px above the thumb
        assertEquals(Side.Top, (r as SweepEvent.Released).side)
    }

    @Test
    fun `releasing past the bottom reports the bottom`() {
        val e = SweepEngine()
        e.step(Pt(32f, 700f), thumb, thumbCfg)
        val r = e.step(Pt(32f, 900f), thumb, thumbCfg)
        assertEquals(Side.Bottom, (r as SweepEvent.Released).side)
    }

    // ---- re-entry ----

    @Test
    fun `can enter again after leaving properly`() {
        val e = SweepEngine()
        e.step(Pt(500f, 460f), button, buttonCfg)
        e.step(Pt(300f, 460f), button, buttonCfg)          // released
        e.step(Pt(200f, 460f), button, buttonCfg)          // clear, clears the rearm
        val r = e.step(Pt(500f, 460f), button, buttonCfg)  // approach again
        assertTrue("must be able to re-enter, got $r", ev(r, SweepEvent.Entered::class.java))
    }

    @Test
    fun `does not immediately re-enter after a release`() {
        val e = SweepEngine()
        e.step(Pt(500f, 460f), button, buttonCfg)
        e.step(Pt(400f, 460f), button, buttonCfg)          // released, rearm on
        // Still within the rearm distance: must not fire again.
        val r = e.step(Pt(450f, 460f), button, buttonCfg)
        assertTrue("must not re-enter inside the rearm zone, got $r",
            ev(r, SweepEvent.Idle::class.java))
    }

    // ---- geometry ----

    @Test
    fun `rect may grow while held`() {
        // The bookmarks flyout: while the pane is open the control is the
        // button AND the pane. Before the union existed, the button read as
        // outside the pane's hit area and every move cancelled it.
        val e = SweepEngine()
        val pane = Rect(400f, 522f, 624f, 900f)
        e.step(Pt(500f, 460f), button, buttonCfg)                 // Entered on the button
        assertTrue(ev(e.step(Pt(500f, 460f), button, buttonCfg), SweepEvent.Engaged::class.java))

        val both = button.union(pane)
        // Moving down onto the pane, both rects: still held.
        assertTrue(ev(e.step(Pt(500f, 600f), both, buttonCfg), SweepEvent.Engaged::class.java))
        assertTrue("union must cover the button", both.contains(Pt(500f, 460f)))
        assertTrue("union must cover the pane", both.contains(Pt(500f, 600f)))
    }

    @Test
    fun `nearest side normalises by axis length`() {
        // Square: absolute and normalised agree, so this only pins behaviour.
        assertEquals(Side.Left, button.nearestSide(Pt(458f, 460f)))
        assertEquals(Side.Top, thumb.nearestSide(Pt(32f, 100f)))
    }

    /**
     * The case axis normalisation actually exists for, and the one that
     * breaks if the distances are measured in absolute units.
     *
     * On the tall thumb this point is 10px from the left edge and 20px from
     * the top. Absolute distance says Left, which is a release side and would
     * refuse the entry. As fractions of each axis - 10/64 versus 20/700 - it
     * is far closer to the top proportionally, and Top is the entry edge.
     *
     * Verified by mutation: reverting [Rect.sideDistances] to absolute
     * distances fails `releasing past the top` and `releasing past the
     * bottom`, and this one.
     */
    @Test
    fun `normalisation matters most on a tall control`() {
        // Just inside the top-left region of the thumb: nearer the left edge
        // in absolute terms, much nearer the top proportionally.
        assertEquals(Side.Top, thumb.nearestSide(Pt(10f, 120f)))
    }

    /**
     * A genuinely wide, short control - wider than it is tall by a lot, like
     * a full-width row. A diagonal onto the middle of its left side must be a
     * left entry, and must not be misread as an entry from the top.
     */
    @Test
    fun `diagonal onto a wide control is a side entry`() {
        val wide = Rect(0f, 400f, 400f, 460f)          // 400 wide, 60 tall
        val cfg = SweepConfig(
            entrySides = setOf(Side.Left, Side.Right),
            leeway = 20f, rearm = 40f,
            refuseCorners = true, cornerFraction = 0.14f
        )
        val e = SweepEngine()
        e.step(Pt(-200f, 300f), wide, cfg)
        val r = e.step(Pt(6f, 425f), wide, cfg)
        assertTrue("diagonal onto a wide control's side must enter, got $r",
            ev(r, SweepEvent.Entered::class.java))
        assertEquals(Side.Left, (r as SweepEvent.Entered).side)
    }

    @Test
    fun `empty rect releases rather than throwing`() {
        val e = SweepEngine()
        e.step(Pt(500f, 460f), button, buttonCfg)
        val r = e.step(Pt(500f, 460f), Rect(0f, 0f, 0f, 0f), buttonCfg)
        assertTrue("an empty rect must not throw, got $r", ev(r, SweepEvent.Released::class.java))
    }

    @Test
    fun `reset clears engagement`() {
        val e = SweepEngine()
        e.step(Pt(500f, 460f), button, buttonCfg)
        assertTrue(e.engaged)
        e.reset()
        assertTrue(!e.engaged)
    }
}
