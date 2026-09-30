/* SweepVR Player — GPL-3.0-only.
 * See LICENSE in the repository root.
 */
package net.sweepvr.player.sweep

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The scrollbar arrows, as behaviour. Every case here is one that would be
 * invisible in a screenshot and obvious on a headset.
 */
class RepeatControlTest {

    /** 100x40 button at (100,100). Wide and short, like the arrow strips. */
    private val btn = Rect(100f, 100f, 200f, 140f)
    private val midY = 120f
    private val midX = 150f

    private var fires = 0
    private val trace = StringBuilder()

    private fun control(
        delay: Long = 400L,
        interval: Long = 90L,
        leeway: Float = 0f,
        rearm: Float = 0f,
        corner: Float = 0f
    ) = RepeatControl(delayMs = delay, intervalMs = interval).apply {
        rect = btn
        this.leeway = leeway
        this.rearm = rearm
        cornerFraction = corner
        onFire = { fires++ }
        onTrace = { trace.append(it).append(';') }
    }

    /** One frame at (x,y). Returns whether the control claims the frame. */
    private fun RepeatControl.f(x: Float, y: Float, dt: Long = 16L) = step(x, y, dt)

    // ---- entry: the dropdown's rule, unchanged ----

    @Test fun `enters from the left`() {
        val c = control()
        assertFalse(c.f(90f, midY))          // outside
        assertTrue(c.f(midX, midY))          // lands on it
        assertEquals(1, fires)
    }

    @Test fun `enters from the right`() {
        val c = control()
        assertFalse(c.f(210f, midY))
        assertTrue(c.f(midX, midY))
        assertEquals(1, fires)
    }

    /** THE RULE: no entry margin. Being near the button is not being on it. */
    @Test fun `does not fire from just outside the left edge`() {
        val c = control()
        c.f(midX, midY)                      // now sitting on it
        c.f(50f, midY)                       // retreat well clear
        fires = 0
        // Land 1px to the left of the button: outside, so no entry.
        assertFalse(c.f(btn.left - 1f, midY))
        assertEquals(0, fires)
    }

    @Test fun `does not fire from just above the top edge`() {
        val c = control()
        c.f(midX, midY); c.f(midX, 10f)
        fires = 0
        assertFalse(c.f(midX, btn.top - 1f))
        assertEquals(0, fires)
    }

    /** Entering top or bottom is not a nominated entry side. */
    @Test fun `does not enter from top or bottom`() {
        val c = control()
        c.f(midX, midY); c.f(midX, 10f)
        fires = 0
        assertFalse(c.f(midX, btn.top - 1f))
        assertFalse(c.f(midX, btn.bottom + 1f))
        assertEquals(0, fires)
    }

    @Test fun `resting on it does not re-fire`() {
        val c = control()
        c.f(90f, midY)
        repeat(40) { c.f(midX, midY, dt = 10L) }   // 400ms, still inside the delay
        assertEquals(1, fires)                     // entry hit only
    }

    @Test fun `leaving and returning fires again`() {
        val c = control()
        c.f(90f, midY); c.f(midX, midY)
        c.f(90f, midY)                            // out
        repeat(12) { c.f(90f, midY) }             // 192ms passes: standoff over
        c.f(midX, midY)                           // back on, deliberately
        assertEquals(2, fires)
    }

    /** The tremor case: out and straight back in within the standoff is
     *  swallowed, not fired. Without this every grab scrolled twice. */
    @Test fun `immediate re-entry inside the standoff does not fire`() {
        val c = control()
        c.f(90f, midY); c.f(midX, midY)
        assertEquals(1, fires)
        c.f(90f, midY)                            // out: a 1-frame wobble
        assertFalse(c.f(midX, midY))              // straight back: swallowed
        assertEquals(1, fires)
        assertFalse(c.active)                     // and no hold started either
    }

    @Test fun `re-entry after the standoff fires normally`() {
        val c = control()
        c.f(90f, midY); c.f(midX, midY)
        c.f(90f, midY)
        repeat(12) { c.f(90f, midY, dt = 16L) }    // 192ms > 150ms standoff
        assertTrue(c.f(midX, midY))
        assertEquals(2, fires)
    }

    // ---- corners ----

    @Test fun `refuses a corner entry`() {
        val c = control(corner = 0.14f)
        c.f(btn.left - 20f, midY)
        // Land just inside the top-left corner, having come from the left.
        assertFalse(c.f(btn.left + 2f, btn.top + 2f))
        assertEquals(0, fires)
    }

    @Test fun `a non-corner entry still works with corners refused`() {
        val c = control(corner = 0.14f)
        c.f(btn.left - 20f, midY)
        assertTrue(c.f(midX, midY))
        assertEquals(1, fires)
    }

    // ---- glide hooks: one start, one stop, never phantom ----

    @Test fun `repeat start fires once when the pause expires`() {
        val c = control(delay = 400L, interval = 90L)
        var starts = 0
        c.onRepeatStart = { starts++ }
        c.f(90f, midY); c.f(midX, midY)
        repeat(40) { c.f(midX, midY, dt = 10L) } // exactly 400ms
        assertEquals(0, starts)
        repeat(9) { c.f(midX, midY, dt = 10L) }  // 490ms: one interval past
        assertEquals(1, starts)
        repeat(30) { c.f(midX, midY, dt = 10L) } // much later: still one
        assertEquals(1, starts)
    }

    @Test fun `repeat stop fires on leave after repeating`() {
        val c = control(delay = 400L, interval = 90L)
        var stops = 0
        c.onRepeatStop = { stops++ }
        c.f(90f, midY); c.f(midX, midY)
        repeat(60) { c.f(midX, midY, dt = 10L) }
        c.f(90f, midY)
        assertEquals(1, stops)
    }

    @Test fun `no stop without a start`() {
        val c = control(delay = 400L, interval = 90L)
        var starts = 0; var stops = 0
        c.onRepeatStart = { starts++ }
        c.onRepeatStop = { stops++ }
        c.f(90f, midY); c.f(midX, midY)
        repeat(20) { c.f(midX, midY, dt = 10L) } // 200ms: never past pause
        c.f(90f, midY)
        assertEquals(0, starts)
        assertEquals(0, stops)
    }

    @Test fun `reset mid-glide stops it`() {
        val c = control(delay = 0L, interval = 100L)
        var stops = 0
        c.onRepeatStop = { stops++ }
        c.f(90f, midY); c.f(midX, midY)
        repeat(20) { c.f(midX, midY, dt = 10L) }
        c.reset()
        assertEquals(1, stops)
    }

    // ---- the repeat itself ----

    @Test fun `fires once on entry then waits`() {
        val c = control(delay = 400L, interval = 90L)
        c.f(90f, midY)
        c.f(midX, midY)
        assertEquals(1, fires)
        assertTrue(c.waiting)                    // held, not yet repeating
        repeat(20) { c.f(midX, midY, dt = 10L) } // 200ms: still in the pause
        assertEquals(1, fires)
    }

    @Test fun `begins repeating after the delay`() {
        val c = control(delay = 400L, interval = 90L)
        c.f(90f, midY); c.f(midX, midY)
        repeat(30) { c.f(midX, midY, dt = 10L) } // 300ms, still short
        assertEquals(1, fires)
        repeat(20) { c.f(midX, midY, dt = 10L) } // 200ms more: past 400+90
        assertTrue(fires > 1)
        assertFalse(c.waiting)
    }

    /** The pause banks no credit: reaching the delay fires nothing by
     *  itself. The old clock discharged the whole pause at once, so every
     *  hold opened with a burst. */
    @Test fun `the pause itself fires nothing`() {
        val c = control(delay = 400L, interval = 90L)
        c.f(90f, midY); c.f(midX, midY)
        repeat(40) { c.f(midX, midY, dt = 10L) } // exactly 400ms
        assertEquals(1, fires)
        c.f(midX, midY, dt = 10L)                // 410ms: clock barely started
        assertEquals(1, fires)
        repeat(8) { c.f(midX, midY, dt = 10L) }  // 490ms: one interval past
        assertEquals(2, fires)
    }

    /** One fire max per frame, however long the frame. Backlog is dropped,
     *  not repaid - under lag the repeat slows, it never bursts. */
    @Test fun `a single long frame fires at most once`() {
        val c = control(delay = 0L, interval = 100L)
        c.f(90f, midY); c.f(midX, midY)          // entry
        assertEquals(1, fires)
        c.f(midX, midY, dt = 1000L)              // one 1s frame
        assertEquals(2, fires)
        c.f(midX, midY, dt = 1000L)              // another: one more, not ten
        assertEquals(3, fires)
    }

    /** Steady cadence never emits two fires in one step, whatever the dts. */
    @Test fun `never two fires in one frame`() {
        val c = control(delay = 0L, interval = 100L)
        c.f(90f, midY); c.f(midX, midY)
        val dts = longArrayOf(16L, 33L, 16L, 500L, 8L, 250L, 16L, 100L, 400L, 16L)
        for (dt in dts) {
            val before = fires
            c.f(midX, midY, dt = dt)
            assertTrue(fires - before <= 1)
        }
    }

    @Test fun `repeat rate matches the interval`() {
        val c = control(delay = 0L, interval = 100L)
        c.f(90f, midY); c.f(midX, midY)          // entry hit
        repeat(100) { c.f(midX, midY, dt = 10L) } // 1000ms
        // entry + one per 100ms -> 11, give or take the partial frame
        assertEquals(11, fires)
    }



    @Test fun `stops when the reticle leaves`() {
        val c = control(delay = 0L, interval = 100L)
        c.f(90f, midY); c.f(midX, midY)
        repeat(20) { c.f(midX, midY, dt = 10L) }
        val before = fires
        c.f(90f, midY)                            // out
        repeat(20) { c.f(90f, midY, dt = 10L) }
        assertEquals(before, fires)               // no further hits
        assertFalse(c.active)
    }

    // ---- leeway ----

    /** The whole point of a small leeway: one pixel out means stop. */
    @Test fun `zero leeway stops on the first pixel outside`() {
        val c = control(delay = 0L, interval = 100L, leeway = 0f)
        c.f(90f, midY); c.f(midX, midY)
        repeat(20) { c.f(midX, midY, dt = 10L) }
        val before = fires
        val justOut = btn.bottom + 0.5f            // half a pixel below
        c.f(midX, justOut)
        assertFalse(c.active)
        repeat(20) { c.f(midX, justOut, dt = 10L) }
        assertEquals(before, fires)
    }

    @Test fun `a small leeway tolerates a pixel of jitter`() {
        val c = control(delay = 0L, interval = 100L, leeway = 3f)
        c.f(90f, midY); c.f(midX, midY)
        repeat(20) { c.f(midX, midY, dt = 10L) }
        c.f(midX, btn.bottom + 2f)                 // 2px out, leeway 3: still held
        assertTrue(c.active)
        val before = fires
        repeat(10) { c.f(midX, btn.bottom + 2f, dt = 10L) }
        assertTrue(fires > before)                 // still repeating
    }

    @Test fun `a small leeway still lets go when clearly outside`() {
        val c = control(delay = 0L, interval = 100L, leeway = 3f)
        c.f(90f, midY); c.f(midX, midY)
        repeat(20) { c.f(midX, midY, dt = 10L) }
        c.f(midX, btn.bottom + 10f)                // 10px out, well past leeway 3
        assertFalse(c.active)
    }

    /** Exit leeway must not become an entry margin. */
    @Test fun `leeway does not let it fire from outside`() {
        val c = control(leeway = 40f)
        c.f(50f, midY)                             // clear of a 40px leeway
        c.f(midX, midY)                            // enter
        assertEquals(1, fires)
        c.f(50f, midY)                             // leave
        fires = 0
        // Land outside the rect but inside the leeway: still no entry.
        assertFalse(c.f(btn.left - 5f, midY))
        assertEquals(0, fires)
    }

    // ---- housekeeping ----

    @Test fun `an empty rect never fires`() {
        val c = control().apply { rect = Rect(0f, 0f, 0f, 0f) }
        assertFalse(c.f(midX, midY))
        repeat(20) { c.f(midX, midY, dt = 10L) }
        assertEquals(0, fires)
    }

    @Test fun `shrinking the rect under the reticle stops it`() {
        val c = control(delay = 0L, interval = 100L)
        c.f(90f, midY); c.f(midX, midY)
        assertTrue(c.active)
        c.rect = Rect(0f, 0f, 10f, 10f)          // surface changed
        assertFalse(c.f(midX, midY))
        assertFalse(c.active)
    }

    @Test fun `reset clears everything`() {
        val c = control()
        c.f(90f, midY); c.f(midX, midY)
        c.reset()
        assertFalse(c.active)
        assertEquals(0, c.fireCount)
    }

    @Test fun `fireCount counts the entry hit`() {
        val c = control(delay = 1000L, interval = 90L)
        c.f(90f, midY); c.f(midX, midY)
        assertEquals(1, c.fireCount)
        c.f(90f, midY)
        assertEquals(0, c.fireCount)
    }

    // ---- commitOnExit: arm on entry, tap on vertical exit ----

    private fun tapControl() = control().apply { commitOnExit = true }

    @Test fun `commitOnExit entry arms without firing`() {
        val c = tapControl()
        assertFalse(c.f(90f, midY))
        assertTrue(c.f(midX, midY))
        assertEquals(0, fires)
        assertEquals(0, c.fireCount)
        assertTrue(c.active)
    }

    @Test fun `commitOnExit exit up fires the single hit`() {
        val c = tapControl()
        var countAtFire = -1
        c.onFire = { countAtFire = c.fireCount; fires++ }
        c.f(90f, midY); c.f(midX, midY)
        c.f(midX, btn.top - 10f)
        assertEquals(1, fires)
        // The owner's nudge check reads fireCount DURING the invoke...
        assertEquals(1, countAtFire)
        // ...and afterwards nothing is outstanding: disarmed, count clear.
        assertEquals(0, c.fireCount)
        assertFalse(c.active)
    }

    @Test fun `commitOnExit sideways pass-through fires nothing`() {
        val c = tapControl()
        c.f(90f, midY)
        assertTrue(c.f(midX, midY))
        assertFalse(c.f(btn.right + 10f, midY))
        assertEquals(0, fires)
        assertFalse(c.active)
    }

    @Test fun `commitOnExit hold still glides`() {
        val c = tapControl()
        var starts = 0
        c.onRepeatStart = { starts++ }
        c.f(90f, midY); c.f(midX, midY)
        assertEquals(0, fires)             // armed, silent
        repeat(60) { c.f(midX, midY, dt = 10L) }
        assertEquals(1, starts)            // pause expired: gliding
        assertTrue(fires > 0)              // repeats fire while held
    }

    @Test fun `commitOnExit exit after gliding adds no tap`() {
        val c = tapControl()
        c.f(90f, midY); c.f(midX, midY)
        repeat(60) { c.f(midX, midY, dt = 10L) }
        val before = fires
        c.f(midX, btn.top - 10f)           // leave up mid-glide: stop, no tap
        assertEquals(before, fires)
        assertFalse(c.active)
    }

    @Test fun `default mode still fires on entry`() {
        val c = control()                  // commitOnExit false
        c.f(90f, midY); c.f(midX, midY)
        assertEquals(1, fires)
    }

    @Test fun `traces entry and exit`() {
        val c = control(delay = 0L, interval = 50L)
        c.f(90f, midY); c.f(midX, midY)
        repeat(10) { c.f(midX, midY, dt = 10L) }
        c.f(90f, midY)
        val t = trace.toString()
        assertTrue(t, t.contains("enter"))
        assertTrue(t, t.contains("repeat"))
        assertTrue(t, t.contains("leave"))
    }
}
