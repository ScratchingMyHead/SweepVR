/* SweepVR Player — GPL-3.0-only.
 * See LICENSE in the repository root.
 */
package net.sweepvr.player.thumbs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** ThumbStrip — the two-pass plan: coarse groups first, then fine spans. */
class ThumbStripTest {
    /** 120 s of film: 12 fine spans of 10 s, 4 coarse groups of 3. */
    private fun strip() = ThumbStrip(120_000L)

    @Test
    fun countsComeFromTheDuration() {
        val s = ThumbStrip(120_000L)
        assertEquals(12, s.count)
        assertEquals(4, s.coarseCount)
        assertEquals(10_000L, s.bucketMs)
        assertEquals(3, s.coarseSpans)
        // one hour: 360 fine spans, 120 groups
        val hour = ThumbStrip(3_600_000L)
        assertEquals(360, hour.count)
        assertEquals(120, hour.coarseCount)
    }

    @Test
    fun unknownDurationStillHasOneSpan() {
        val s = ThumbStrip(0L)
        assertEquals(1, s.count)
        assertEquals(1, s.coarseCount)
        assertEquals(0, s.bucketAt(9_999L))
    }

    @Test
    fun spanTimesAreTheMiddleOfTheirSpan() {
        val s = strip()
        assertEquals(5_000L, s.timeMs(0))
        assertEquals(35_000L, s.timeMs(3))
        // a coarse group's frame sits in the middle of the group's middle span
        assertEquals(15_000L, s.coarseTimeMs(0))    // spans 0..2
        assertEquals(45_000L, s.coarseTimeMs(1))    // spans 3..5
        // out of range clamps rather than running off the end
        assertEquals(115_000L, s.timeMs(99))
        assertEquals(105_000L, s.coarseTimeMs(99))
    }

    @Test
    fun positionMapsToItsSpan() {
        val s = strip()
        assertEquals(0, s.bucketAt(-100))
        assertEquals(0, s.bucketAt(0))
        assertEquals(0, s.bucketAt(9_999))
        assertEquals(1, s.bucketAt(10_000))
        assertEquals(11, s.bucketAt(119_999))
        assertEquals(11, s.bucketAt(600_000))    // past the end sticks
        assertEquals(3, s.groupOf(9))
        assertEquals(0, s.groupOf(2))
    }

    @Test
    fun aCoarseFrameAnswersEverySpanInsideItsGroup() {
        val s = strip()
        assertFalse("nothing is answered to start with", s.answered(1))
        s.onCoarseBuilt(0)
        for (b in 0..2) {
            assertTrue("span $b should be answered by the group frame", s.answered(b))
            assertFalse("but has no preview of its own", s.hasOwn(b))
        }
        assertFalse("the next group is untouched", s.answered(3))
        // coverage is a fraction: 3 of 12 spans answerable
        assertEquals(0.25f, s.coverage(), 0.001f)
        assertEquals(0f, s.fineCoverage(), 0.001f)
    }

    @Test
    fun theFinePassOnlyTouchesGroupsThatAreAnswered() {
        val s = strip()
        // refining before the coarse pass has run would mean fetching a
        // frame for a span the sweep is about to answer anyway
        assertEquals(-1, s.nextWantingOwn(0))
        s.onCoarseBuilt(0)
        assertEquals(0, s.nextWantingOwn(0))
        s.onBuilt(0)
        assertEquals(1, s.nextWantingOwn(0))
        s.onBuilt(1)
        s.onBuilt(2)
        // group 0 is fully refined and group 1 has no coarse frame yet, so
        // there is nothing to refine
        assertEquals(-1, s.nextWantingOwn(0))
        s.onCoarseBuilt(1)
        assertEquals(3, s.nextWantingOwn(0))
    }

    @Test
    fun coarseSweepRunsAscendingThenWraps() {
        val s = strip()
        assertEquals(0, s.nextCoarseGroup(0))
        for (g in 0 until s.coarseCount) s.onCoarseBuilt(g)
        assertEquals(-1, s.nextCoarseGroup(0))
        // a pass that started late still picks up the gap behind it
        s.onCoarseFailed(1)
        // a failed group is not retried, so nothing is pending anywhere
        assertEquals(-1, s.nextCoarseGroup(0))
        val t = ThumbStrip(300_000L)               // 30 spans, 10 groups
        for (g in 1 until t.coarseCount) t.onCoarseBuilt(g)
        assertEquals(0, t.nextCoarseGroup(1))       // wrapped back to the gap
        t.onCoarseBuilt(0)
        assertEquals(-1, t.nextCoarseGroup(0))
    }

    @Test
    fun failuresAreWrittenOffSoOneBadRegionCannotStallAPass() {
        val s = strip()
        s.onCoarseBuilt(0)
        s.onFailed(1)
        assertEquals(ThumbStrip.FAILED, s.stateOf(1))
        s.onBuilt(0)
        // span 1 is skipped rather than retried, so the pass moves to 2
        assertEquals(2, s.nextWantingOwn(0))
        assertTrue(s.answered(1))                  // the group frame still answers it
    }

    @Test
    fun completeOnlyWhenBothPassesAreDone() {
        val s = strip()
        assertFalse(s.complete())
        for (g in 0 until s.coarseCount) s.onCoarseBuilt(g)
        assertFalse("the fine pass is still to come", s.complete())
        for (b in 0 until s.count) s.onBuilt(b)
        assertTrue(s.complete())
        assertEquals(1f, s.fineCoverage(), 0.001f)
        assertEquals(1f, s.coverage(), 0.001f)
    }

    @Test
    fun fineCoverageTracksTheSecondPassNotTheFirst() {
        val s = strip()
        for (g in 0 until s.coarseCount) s.onCoarseBuilt(g)
        assertEquals(1f, s.coverage(), 0.001f)      // everything answerable
        assertEquals(0f, s.fineCoverage(), 0.001f)  // but nothing of its own
        for (b in 0 until 6) s.onBuilt(b)
        assertEquals(0.5f, s.fineCoverage(), 0.001f)
    }
}