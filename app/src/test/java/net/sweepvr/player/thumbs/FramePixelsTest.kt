/* SweepVR Player — GPL-3.0-only.
 * See LICENSE in the repository root.
 */
package net.sweepvr.player.thumbs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** FramePixels — the GL readback to ARGB conversion: orientation, crop, order. */
class FramePixelsTest {
    /** A w*h RGBA readback whose colour encodes its own position, so a wrong
     *  flip or a wrong crop shows up as a wrong number rather than as a
     *  plausible-looking picture. Byte r = x, g = y, b = 0, a = 255. */
    private fun readback(w: Int, h: Int): ByteArray {
        val b = ByteArray(w * h * 4)
        // bottom row first: readback row 0 is image row h-1
        for (ry in 0 until h) {
            val y = h - 1 - ry
            for (x in 0 until w) {
                val i = (ry * w + x) * 4
                b[i] = x.toByte()
                b[i + 1] = y.toByte()
                b[i + 2] = 0
                b[i + 3] = 0xFF.toByte()
            }
        }
        return b
    }

    private fun r(p: Int) = (p ushr 16) and 0xFF
    private fun g(p: Int) = (p ushr 8) and 0xFF
    private fun a(p: Int) = (p ushr 24) and 0xFF

    @Test
    fun wholeFrameComesBackTheRightWayUp() {
        val w = 4; val h = 3
        val out = IntArray(w * h)
        assertTrue(FramePixels.toArgb(readback(w, h), w, h, intArrayOf(0, 0, w, h), out) != null)
        // image (0,0) is the top-left: red = x 0, green = y 0
        assertEquals(0, r(out[0])); assertEquals(0, g(out[0]))
        // last pixel is (x=3, y=2)
        assertEquals(3, r(out[w * h - 1])); assertEquals(2, g(out[w * h - 1]))
        // a middle row, to catch a transposed read
        val mid = out[1 * w + 2]
        assertEquals(2, r(mid)); assertEquals(1, g(mid))
        assertEquals(0xFF, a(out[0]))
    }

    @Test
    fun theFlipIsTheCallersChoiceAndItIsNotCosmetic() {
        // The orientation question belongs to whoever drew into the
        // framebuffer, so it is an argument rather than a guess buried in
        // here — and it is the difference between a preview and its
        // reflection. Both directions are pinned here.
        val w = 2; val h = 2
        val bytes = readback(w, h)          // row 0 carries image row h-1
        val flipped = IntArray(4)
        val asIs = IntArray(4)
        FramePixels.toArgb(bytes, w, h, intArrayOf(0, 0, w, h), flipped, flipRows = true)
        FramePixels.toArgb(bytes, w, h, intArrayOf(0, 0, w, h), asIs, flipRows = false)
        // flipping picks the readback's LAST row as the picture's first
        assertEquals(0, g(flipped[0]))
        assertEquals(1, g(asIs[0]))
        // and the other end follows
        assertEquals(1, g(flipped[3]))
        assertEquals(0, g(asIs[3]))
    }

    @Test
    fun cropTakesTheRequestedRegion() {
        val w = 8; val h = 4
        val out = IntArray((w / 2) * h)
        assertTrue(FramePixels.toArgb(readback(w, h), w, h, intArrayOf(w / 2, 0, w, h), out) != null)
        // every row starts at x = 4 now
        for (y in 0 until h) assertEquals(4, r(out[y * (w / 2)]))
    }

    @Test
    fun cropReadsRowsFromTheTopOfThePicture() {
        // top half of a top-bottom pair: image rows 0..1, so green is 0 then 1
        val w = 4; val h = 4
        val out = IntArray(w * 2)
        FramePixels.toArgb(readback(w, h), w, h, intArrayOf(0, 0, w, h / 2), out)
        assertEquals(0, g(out[0]))
        assertEquals(1, g(out[w]))
    }

    @Test
    fun rejectsSpansThatDoNotFit() {
        // not enough bytes for the claimed size
        assertNull(FramePixels.toArgb(ByteArray(4), 2, 2, intArrayOf(0, 0, 2, 2), IntArray(4)))
        // the output array is the wrong size for the crop
        assertNull(FramePixels.toArgb(readback(4, 4), 4, 4, intArrayOf(0, 0, 4, 4), IntArray(3)))
        // a matching one goes through
        assertTrue(FramePixels.toArgb(readback(4, 4), 4, 4, intArrayOf(0, 0, 4, 4), IntArray(16)) != null)
        // a crop outside the frame is clamped, not honoured
        val o2 = IntArray(4)
        assertTrue(FramePixels.toArgb(readback(2, 2), 2, 2, intArrayOf(0, 0, 99, 99), o2) != null)
        assertEquals(0, r(o2[0]))
    }

    @Test
    fun outputSizeHoldsHeightAndCapsWidth() {
        // a 16:9 crop: height is what matters, width follows
        assertTrue(FramePixels.fitWithin(1920, 1080, 128, 320).contentEquals(intArrayOf(228, 128)))
        // a square eye: width is the height
        assertTrue(FramePixels.fitWithin(1920, 1920, 128, 320).contentEquals(intArrayOf(128, 128)))
        // an extreme panorama must not exceed the width cap
        val wide = FramePixels.fitWithin(4000, 400, 128, 320)
        assertTrue("width capped, was ${wide[0]}", wide[0] <= 320)
        assertTrue(wide[1] > 0)
        // degenerate input
        assertTrue(FramePixels.fitWithin(0, 100, 128, 320).contentEquals(intArrayOf(0, 0)))
    }
}