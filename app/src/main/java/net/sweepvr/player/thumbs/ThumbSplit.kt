/* SweepVR Player — GPL-3.0-only.
 * See LICENSE in the repository root.
 */
package net.sweepvr.player.thumbs

/**
 * Which part of a frame the seek preview shows.
 *
 * A stereo frame is two eyes welded into one picture, and a 200-pixel preview
 * of both is two very squashed eyes — useless as a landmark and misleading
 * about what is on screen. So a 3D film previews as one eye: the left of a
 * side-by-side pair, the top of a top-bottom one. Mono previews whole.
 *
 * This also has to be part of the cache key: the same file previewed in 2D
 * and in 3D is two different sets of images.
 */
enum class ThumbSplit {
    /** Mono / 2D: the frame as it is. */
    FULL,

    /** Side-by-side: the left eye, i.e. the left half. */
    HALF_LEFT,

    /** Top-bottom: the top eye, i.e. the top half. */
    HALF_TOP;

    /** The split this stereo layout previews as. */
    companion object {
        fun of(sbs: Boolean, tb: Boolean): ThumbSplit =
            if (tb) HALF_TOP else if (sbs) HALF_LEFT else FULL
    }

    /**
     * The source rectangle to sample for this split, as left, top, right,
     * bottom. Halves are rounded so an odd dimension still yields a whole
     * number of pixels, and a degenerate frame (1 px wide, or a height too
     * small to halve) is passed through rather than emptied.
     */
    fun crop(w: Int, h: Int): IntArray {
        if (w <= 0 || h <= 0) return intArrayOf(0, 0, 0, 0)
        return when (this) {
            FULL -> intArrayOf(0, 0, w, h)
            HALF_LEFT -> intArrayOf(0, 0, maxOf(1, w / 2), h)
            HALF_TOP -> intArrayOf(0, 0, w, maxOf(1, h / 2))
        }
    }

    /** Short tag for cache keys and file names. */
    val tag: String
        get() = when (this) {
            FULL -> "f"
            HALF_LEFT -> "l"
            HALF_TOP -> "t"
        }
}