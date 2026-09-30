/* SweepVR Player — GPL-3.0-only.
 * See LICENSE in the repository root.
 */
package net.sweepvr.player.sweep

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The value-drag rules, including the one that changed: leaving past an end
 * cancels instead of jumping to that extreme. Every other case here was a bug
 * in the hand-written scrollbar.
 */
class ValueDragControlTest {

    /** A vertical track 100 wide by 700 tall, with a 240-tall thumb (0.34). */
    private val TRACK = Rect(0f, 100f, 100f, 800f)
    private val thumbF = 0.34f

    private fun control(value: Float = 0f) = ValueDragControl(
        axis = ValueDragControl.Axis.VERTICAL,
        thumbFraction = thumbF,
        leeway = 30f, rearm = 60f
    ).apply { track = TRACK; syncTo(value) }

    // ---- entry ----

    @Test
    fun `enters from the top of the thumb`() {
        val c = control(0f)
        val th = c.thumbRect()
        c.step(50f, th.top - 60f)
        val r = c.step(50f, th.top + 40f)
        assertTrue("must grab, got $r", r)
        assertTrue(c.engaged)
    }

    @Test
    fun `enters from the bottom of the thumb`() {
        val c = control(0f)
        val th = c.thumbRect()
        c.step(50f, th.bottom + 60f)
        c.step(50f, th.bottom - 40f)
        assertTrue("must grab from below", c.engaged)
    }

    @Test
    fun `refuses entry from the side`() {
        val c = control(0.5f)
        val th = c.thumbRect()
        c.step(th.right + 80f, (th.top + th.bottom) / 2f)
        val r = c.step(th.right - 20f, (th.top + th.bottom) / 2f)
        assertTrue("a sideways approach must not grab, got $r", !c.engaged)
    }

    // ---- dragging ----

    @Test
    fun `snaps to the reticle and follows it`() {
        val c = control(0f)
        var committed: Float? = null
        c.onCommit = { committed = it }
        val th = c.thumbRect()
        c.step(50f, th.top - 60f)
        c.step(50f, (th.top + th.bottom) / 2f)           // grab it
        assertTrue("grabbing should engage", c.engaged)
        // The reticle now sits at the thumb's centre, so moving it by half
        // the track's travel must move the value by half.
        val live = c.thumbRect()
        val mid = (live.top + live.bottom) / 2f
        val travel = 700f * (1f - thumbF)
        c.step(50f, mid + travel * 0.5f)                 // drag down half a track
        assertEquals("value should track the reticle", 0.5f, c.value, 0.02f)
        // The reticle is inside the thumb, not on its edge.
        val now = c.thumbRect()
        assertTrue("reticle should be within the thumb", mid + travel * 0.5f > now.top)
        c.step(300f, mid + travel * 0.5f)                // let go sideways
        assertEquals("should commit the dragged value", 0.5f, committed!!, 0.02f)
    }

    @Test
    fun `value stays within the track`() {
        val c = control(0f)
        c.step(50f, 100f)                               // approach the top
        c.step(50f, 150f)                               // grab
        c.step(50f, -500f)                              // drag far above
        assertTrue("value must not go below 0, was ${c.value}", c.value >= 0f)
    }

    // ---- release ----

    @Test
    fun `sideways release commits`() {
        val c = control(0.3f)
        var committed: Float? = null
        c.onCommit = { committed = it }
        val th = c.thumbRect()
        val mid = (th.top + th.bottom) / 2f             // grab dead centre, so
        c.step(50f, mid)                                 // the snap is a no-op
        c.step(50f, mid)
        c.step(300f, mid)                                // off to the right
        assertTrue("sideways must commit", committed != null)
        assertEquals("commits the value the thumb was left at", 0.3f, committed!!, 0.02f)
    }

    @Test
    fun `grabbing away from the thumb centre snaps it to the reticle`() {
        val c = control(0.3f)
        var committed: Float? = null
        c.onCommit = { committed = it }
        val th = c.thumbRect()
        c.step(50f, th.top - 60f)
        c.step(50f, th.bottom - 20f)                     // grab near its bottom
        c.step(300f, th.bottom - 20f)
        assertTrue(committed != null)
        assertTrue("snap should have moved it past the grab point, was ${committed!!}",
            committed!! > 0.3f)
    }

    /**
     * The change. Leaving past an end used to jump the page to the top or
     * the bottom - a large, hard-to-undo move caused by the easiest slip to
     * make, which is carrying the reticle too far while reaching for
     * something else. It now abandons the drag and leaves the value alone.
     */
    @Test
    fun `release past the top cancels and leaves the value alone`() {
        val c = control(0.5f)
        var committed: Float? = null
        var cancelled = false
        c.onCommit = { committed = it }
        c.onCancel = { cancelled = true }
        val th = c.thumbRect()
        c.step(50f, th.top - 60f)
        c.step(50f, th.top + 40f)                       // grab
        c.step(50f, TRACK.top - 100f)                    // carry it well off the top
        assertTrue("must cancel, not commit", cancelled)
        assertEquals("must not commit anything", null, committed)
        assertEquals("value must go back to where it was", 0.5f, c.value, 0.02f)
    }

    @Test
    fun `release past the bottom cancels and leaves the value alone`() {
        val c = control(0.5f)
        var committed: Float? = null
        var cancelled = false
        c.onCommit = { committed = it }
        c.onCancel = { cancelled = true }
        val th = c.thumbRect()
        c.step(50f, th.top - 60f)
        c.step(50f, th.top + 40f)
        c.step(50f, th.bottom + 300f)                   // off the bottom
        assertTrue(cancelled)
        assertEquals(null, committed)
        assertEquals(0.5f, c.value, 0.02f)
    }

    @Test
    fun `a cancel does not need a value change to take effect`() {
        // Grabbing and letting go immediately at the same spot, then overshooting
        // the end: still a cancel, and the value is untouched either way.
        val c = control(0.42f)
        var cancelled = false
        c.onCancel = { cancelled = true }
        val th = c.thumbRect()
        c.step(50f, th.top - 60f)
        c.step(50f, th.top + 40f)
        c.step(50f, TRACK.top - 100f)
        assertTrue(cancelled)
        assertEquals(0.42f, c.value, 0.02f)
    }

    @Test
    fun `a small overshoot past an end keeps dragging`() {
        val c = control(0.5f)
        var cancelled = false
        c.onCancel = { cancelled = true }
        val th = c.thumbRect()
        c.step(50f, th.top - 60f)
        c.step(50f, (th.top + th.bottom) / 2f)              // grab
        c.step(50f, TRACK.top - 20f)                        // 20px past the end, endLeeway 40
        assertTrue("a small carry-over must not cancel", !cancelled)
        assertTrue(c.engaged)
    }

    // ---- leeway ----

    @Test
    fun `drifting just past an end holds rather than cancelling`() {
        val c = control(0.5f)
        var cancelled = false
        c.onCancel = { cancelled = true }
        val th = c.thumbRect()
        c.step(50f, th.top - 60f)
        c.step(50f, th.top + 40f)                       // grab: the thumb SNAPS to
        val now = c.thumbRect()                         // the reticle, so measure
        c.step(50f, now.top - 20f)                      // the leeway from where it is NOW
        assertTrue("leeway must hold the drag", !cancelled)
        assertTrue(c.engaged)
    }

    // ---- geometry ----

    @Test
    fun `thumb is derived from the value so draw and hit test cannot drift`() {
        val c = control(0f)
        val t0 = c.thumbRect()
        assertEquals("thumb starts at the top of the track", TRACK.top, t0.top, 0.01f)
        assertEquals("thumb is the declared fraction of the track",
            TRACK.height * thumbF, t0.height, 0.01f)
    }

    @Test
    fun `empty track is inert`() {
        val c = control(0f)
        c.track = Rect(0f, 0f, 0f, 0f)
        assertTrue("must not claim the frame", !c.step(10f, 10f))
        assertTrue(c.thumbRect().isEmpty)
    }
}
