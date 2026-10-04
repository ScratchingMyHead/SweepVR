/* SweepVR Player — GPL-3.0-only.
 * See LICENSE in the repository root.
 */
package net.sweepvr.player.thumbs

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import net.sweepvr.player.FileLog
import java.io.ByteArrayOutputStream

/**
 * The seek previews, held in memory as compressed bytes.
 *
 * ## Why compressed and not decoded
 *
 * A preview is one frame every five seconds, cropped to a single eye: a
 * 48-minute film is 577 of them. Decoded at the size they are captured
 * (288x288) that is about 190 MB — not a cache but most of the heap, on a
 * device whose heap is 256 MB and whose player is already allowed 90 MB of
 * buffer. As JPEG at preview quality the same 577 previews are roughly 5 MB,
 * so the whole strip fits comfortably and stays complete, and the one decode
 * a drag needs is about a millisecond.
 *
 * ## Why nothing is written to disk
 *
 * Because a preview is only worth having while its film is playing, and a
 * strip that outlives the session has to be rebuilt or trusted. Keeping it in
 * memory means: no directory to manage, no cache to invalidate when the
 * capture path changes, nothing to delete, and no risk of an old strip (built
 * by an older pipeline, or upside down) being picked up as if it were current.
 * The cost is that a film played again builds its strip again, in the
 * background, at the rate the link allows.
 *
 * Least-recently-used by total bytes, so a very long film loses its oldest
 * previews rather than the heap.
 */
class ThumbMemory(
    /** Byte budget for the whole strip. A long film is bounded by this. */
    private val maxBytes: Long = DEFAULT_MAX_BYTES
) {
    /**
     * Preview -> JPEG bytes, keyed by [fineKey] or [coarseKey]. Access-ordered,
     * so the least recently used goes first.
     */
    private val map = java.util.LinkedHashMap<Int, ByteArray>(256, 0.75f, true)
    private var bytes = 0L
    private var splits: ThumbSplit? = null

    /** Bytes currently held. */
    fun size(): Long = bytes

    fun count(): Int = map.size

    /** Point the store at one film's strip, cropped [split]. Nothing to open:
     *  the strip lives in this object and starts empty. */
    fun open(split: ThumbSplit) {
        map.clear()
        bytes = 0
        splits = split
    }

    fun isOpen(): Boolean = splits != null

    /** True when this preview is already held — checked before every fetch,
     *  so a strip that is mostly built is not rebuilt. */
    fun has(key: Int): Boolean = map.containsKey(key)

    /**
     * Keep a preview. Compressing here rather than holding the bitmap means
     * the caller can hand the frame straight on to the renderer and forget
     * it. True when it was stored.
     */
    fun save(key: Int, bmp: Bitmap): Boolean {
        if (splits == null) return false
        return try {
            val bos = ByteArrayOutputStream(bmp.width * bmp.height / 8)
            if (!bmp.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, bos)) return false
            val bytesOut = bos.toByteArray()
            val old = map.put(key, bytesOut)
            if (old != null) bytes -= old.size
            bytes += bytesOut.size
            while (bytes > maxBytes) {
                val it = map.entries.iterator()
                if (!it.hasNext()) break
                val e = it.next()
                if (e.key == key) continue        // never evict the one just added
                bytes -= e.value.size
                it.remove()
            }
            true
        } catch (e: Exception) {
            FileLog.w("SweepVR-thumb", "preview store failed: ${e.message}")
            false
        }
    }

    /** Decode a preview, or null when it is not held or will not decode. */
    fun load(key: Int): Bitmap? {
        val bytes = map[key] ?: return null
        return try {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (_: Exception) {
            null
        }
    }

    /** Every key held, ascending (so coarse keys come first). */
    fun keys(): List<Int> {
        val out = ArrayList(map.keys)
        out.sort()
        return out
    }

    fun clear() {
        map.clear()
        bytes = 0
    }

    companion object {
        private const val JPEG_QUALITY = 80

        /**
         * Key for a fine span's own preview. Fine spans are numbered from
         * zero, so this is the span number itself.
         */
        fun fineKey(span: Int): Int = span

        /**
         * Key for the coarse preview covering a group.
         *
         * NEGATIVE, and that is the whole point: a coarse group and a fine
         * span are both counted from the film's start, so sharing one key
         * space has group 5 and span 5 as the same entry — and those are
         * 150 s and 50 s in. A card then shows a frame from minutes away with
         * nothing to say so, which is exactly how it looked: right near the
         * middle (where a span usually has its own preview and hits first) and
         * minutes out near the ends.
         */
        fun coarseKey(group: Int): Int = -1 - group

        /**
         * The one place a captured frame's key is decided.
         *
         * Every save and every load has to agree on this. When they don't,
         * nothing throws: a coarse frame saved under its bare group number
         * lands on the same entry as the fine span numbered the same, and the
         * card shows whichever pass wrote last — frames from the wrong part of
         * the film, self-healing as each pass catches up.
         */
        fun keyFor(bucket: Int, coarseGroup: Int): Int =
            if (coarseGroup >= 0) coarseKey(coarseGroup) else fineKey(bucket)

        /** Whole-strip budget. A 48-minute film needs ~5 MB; this leaves room
         *  for several hours of film before anything is dropped. */
        const val DEFAULT_MAX_BYTES = 64L * 1024 * 1024
    }
}