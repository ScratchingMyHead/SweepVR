/* SweepVR Player — GPL-3.0-only.
 * See LICENSE in the repository root.
 */
package net.sweepvr.player.thumbs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** ThumbSplit — which eye the preview shows, and the crop it comes from. */
class ThumbSplitTest {
    @Test
    fun layoutPicksTheSplit() {
        assertEquals(ThumbSplit.FULL, ThumbSplit.of(sbs = false, tb = false))
        assertEquals(ThumbSplit.HALF_LEFT, ThumbSplit.of(sbs = true, tb = false))
        assertEquals(ThumbSplit.HALF_TOP, ThumbSplit.of(sbs = false, tb = true))
        // top-bottom wins if a caller somehow says both
        assertEquals(ThumbSplit.HALF_TOP, ThumbSplit.of(sbs = true, tb = true))
    }

    @Test
    fun monoKeepsTheWholeFrame() {
        assertTrue(ThumbSplit.FULL.crop(3840, 1920).contentEquals(intArrayOf(0, 0, 3840, 1920)))
    }

    @Test
    fun stereoTakesOneEye() {
        // a 180 3D pair is two square eyes
        assertTrue(ThumbSplit.HALF_LEFT.crop(3840, 1920).contentEquals(intArrayOf(0, 0, 1920, 1920)))
        assertTrue(ThumbSplit.HALF_TOP.crop(1920, 3840).contentEquals(intArrayOf(0, 0, 1920, 1920)))
    }

    @Test
    fun oddDimensionsHalveToWholePixels() {
        // 1921 px wide cannot halve exactly: the eye is 960 px, not 960.5
        assertTrue(ThumbSplit.HALF_LEFT.crop(1921, 1080).contentEquals(intArrayOf(0, 0, 960, 1080)))
        assertTrue(ThumbSplit.HALF_TOP.crop(1080, 1921).contentEquals(intArrayOf(0, 0, 1080, 960)))
    }

    @Test
    fun degenerateFramesAreNotEmptied() {
        // 1 px wide would halve to nothing, and an empty crop has no meaning
        assertTrue(ThumbSplit.HALF_LEFT.crop(1, 8).contentEquals(intArrayOf(0, 0, 1, 8)))
        assertTrue(ThumbSplit.HALF_TOP.crop(8, 1).contentEquals(intArrayOf(0, 0, 8, 1)))
        assertTrue(ThumbSplit.FULL.crop(0, 0).contentEquals(intArrayOf(0, 0, 0, 0)))
    }

    @Test
    fun tagsSeparateTheCaches() {
        val tags = ThumbSplit.values().map { it.tag }
        assertEquals(3, tags.toSet().size)
    }
}