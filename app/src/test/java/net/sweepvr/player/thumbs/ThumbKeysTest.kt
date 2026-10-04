/* SweepVR Player — GPL-3.0-only.
 * See LICENSE in the repository root.
 */
package net.sweepvr.player.thumbs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The key scheme the preview store is addressed by. Pure arithmetic, so it is
 * worth pinning: getting it wrong does not throw, it shows a frame from the
 * wrong part of the film.
 */
class ThumbKeysTest {
    @Test
    fun coarseAndFineKeysCannotCollide() {
        // 48 minutes at ten-second spans: 289 fine spans, 97 coarse groups.
        val fine = 0 until 289
        val coarse = 0 until 97
        for (f in fine) {
            for (c in coarse) {
                assertTrue(
                    "span $f and group $c share key ${ThumbMemory.fineKey(f)}",
                    ThumbMemory.fineKey(f) != ThumbMemory.coarseKey(c)
                )
            }
        }
    }

    @Test
    fun theTwoSpacesAreIdentifiableBySign() {
        assertTrue("fine keys are non-negative", ThumbMemory.fineKey(0) >= 0)
        assertTrue("coarse keys are negative", ThumbMemory.coarseKey(0) < 0)
        assertEquals(0, ThumbMemory.fineKey(0))
        assertEquals(-1, ThumbMemory.coarseKey(0))
        assertEquals(-98, ThumbMemory.coarseKey(97))
    }

    @Test
    fun keyForIsWhatTheStoreIsActuallyAddressedBy() {
        // The bug this pins: saves addressed the store by the bare bucket
        // number while lookups used fineKey/coarseKey, so a coarse group's
        // frame and a fine span's frame shared an entry and the card showed
        // whichever pass wrote last. Every save and lookup now goes through
        // keyFor, so agreeing here is what keeps them agreeing.
        val span = 20
        val group = 6
        assertEquals(20, ThumbMemory.keyFor(span, -1))
        assertEquals(-7, ThumbMemory.keyFor(span, group))
        assertTrue(ThumbMemory.keyFor(span, -1) != ThumbMemory.keyFor(span, group))

        // Every span and every group in a 48-minute film stay distinct.
        val keys = HashSet<Int>()
        for (f in 0 until 289) keys.add(ThumbMemory.keyFor(f, -1))
        for (c in 0 until 97) keys.add(ThumbMemory.keyFor(0, c))
        assertEquals(289 + 97, keys.size)
    }

    @Test
    fun theCollisionThisAvoidsIsARealMisalignment() {
        // The bug this scheme exists to prevent: group 5 and span 5 are the
        // same key if both spaces are counted from zero, and they are 100
        // seconds apart in the film.
        val group = 5
        val span = 5
        val groupStartSec = group * ThumbStrip.DEFAULT_BUCKET_MS / 1000 *
            ThumbStrip.COARSE_SPANS
        val spanStartSec = span * ThumbStrip.DEFAULT_BUCKET_MS / 1000
        assertEquals(100L, groupStartSec - spanStartSec)
        assertTrue(ThumbMemory.fineKey(span) != ThumbMemory.coarseKey(group))
    }
}