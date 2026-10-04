/* SweepVR Player — GPL-3.0-only.
 * See LICENSE in the repository root.
 */
package net.sweepvr.player.thumbs

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Turns the GL readback of a preview frame into ARGB pixels, cropped to the
 * eye that should be shown.
 *
 * ## Why GL readback
 *
 * The obvious way to get a frame out of a video file is to ask the platform
 * for its pixels — `ImageReader` plus `Image.getPlanes`, which is what this
 * did first. On a hardware decoder that is a trap: the decoder hands the
 * reader an opaque, vendor-tiled buffer that claims to be YUV 4:2:0 and is
 * not, and the native plane maths underflows. It does not throw where it can
 * be caught — it aborts the process inside JNI, so there is no try/catch that
 * saves you and no log that explains it.
 *
 * So the frame is read where video already reads it safely: the decoder
 * writes to a [android.graphics.SurfaceTexture], the renderer samples that,
 * and `glReadPixels` hands back RGBA bytes at preview size — a few hundred
 * kilobytes, taken from a framebuffer we drew at that size on purpose.
 *
 * ## Orientation
 *
 * `glReadPixels` returns rows bottom-up (GL's origin is the lower left) and
 * the frame arrives upright in the framebuffer, so the last row it returns is
 * the picture's top. This flips as it copies. Getting that wrong is silent —
 * a preview that is simply upside down still looks like a preview — so the
 * flip is the first thing the tests pin.
 *
 * Pure arithmetic over byte arrays, so the parts that are easy to get
 * quietly wrong (the flip, the crop, the colour order) are testable without
 * a device.
 */
object FramePixels {

    /**
     * [rgba] is [w]x[h] RGBA bytes as `glReadPixels` wrote them, into [out]
     * as ARGB. [crop] is left, top, right, bottom in IMAGE coordinates, which
     * is what [ThumbSplit.crop] speaks.
     *
     * [flipRows] is the whole orientation question, and it belongs to the
     * caller: `glReadPixels` hands back rows in GL's bottom-up order, but
     * whether that means the picture is upside down depends on how the frame
     * was drawn into the framebuffer, which only the caller knows. The
     * preview pass draws with the video pipeline's own texcoord convention,
     * which already leaves the picture's top row in readback row 0 — so it
     * passes false. Guessing this inside here got a preview upside down.
     *
     * Returns [out], sized `cropW * cropH`, or null for a span that does not
     * fit what was read.
     */
    fun toArgb(rgba: ByteArray, w: Int, h: Int, crop: IntArray, out: IntArray,
               flipRows: Boolean = true): IntArray? {
        if (w <= 0 || h <= 0) return null
        val cl = crop[0].coerceIn(0, w)
        val ct = crop[1].coerceIn(0, h)
        val cr = crop[2].coerceIn(cl + 1, w)
        val cb = crop[3].coerceIn(ct + 1, h)
        val cw = cr - cl
        val ch = cb - ct
        if (cw <= 0 || ch <= 0) return null
        if (out.size != cw * ch) return null
        if (rgba.size < w * h * 4) return null
        var o = 0
        for (y in ct until cb) {
            // readback row for this image row
            val src = (if (flipRows) h - 1 - y else y) * w * 4
            for (x in cl until cr) {
                val i = src + x * 4
                val a = rgba[i + 3].toInt() and 0xFF
                val r = rgba[i].toInt() and 0xFF
                val g = rgba[i + 1].toInt() and 0xFF
                val b = rgba[i + 2].toInt() and 0xFF
                out[o++] = (a shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return out
    }

    /**
     * Output size for a crop of [srcW] x [srcH] at a given target HEIGHT,
     * capped in width. The preview is a fixed height on the panel, so height
     * is the dimension to hold and width follows the crop's shape.
     */
    fun fitWithin(cropW: Int, cropH: Int, targetH: Int, maxW: Int): IntArray {
        if (cropW <= 0 || cropH <= 0 || targetH <= 0) return intArrayOf(0, 0)
        val h = min(targetH, maxW)      // a tall-thin crop must not run away
        val w = min(maxW, max(1, (h.toFloat() * cropW / cropH).roundToInt()))
        return intArrayOf(w, min(h, max(1, (w.toFloat() * cropH / cropW).roundToInt())))
    }
}