/* SweepVR Player — GPL-3.0-only.
 * See LICENSE in the repository root.
 */
package net.sweepvr.player.thumbs

/**
 * Which previews of a film exist, and which are still wanted.
 *
 * ## Two passes, because the first useful answer should not wait for the
 * ## whole film
 *
 * A preview every ten seconds of a long film is hundreds of seeks, and at a
 * second apiece that is minutes before the slider is useful anywhere. So the
 * strip is built in two passes over the same timeline: a **coarse** pass at
 * one preview per [COARSE_SPANS] fine buckets (30 s at the default spacing),
 * which covers the whole film in a sixth of the work, and then a **fine**
 * pass that gives each ten-second span its own. The coarse pass is what makes
 * the card show something sensible straight away; the fine pass is what makes
 * it right, and it can run to completion in the background.
 *
 * A coarse preview stands for every span inside its group, so a group whose
 * fine previews are not built yet is still "answered" — the card shows the
 * nearest frame it actually has, and the time printed above it is always the
 * exact position the drop will seek to.
 *
 * ## What this class is not
 *
 * No bitmaps, no clock, no player: this is the bookkeeping both passes ask,
 * so the rules (what is still wanted, where to resume, what counts as
 * covered) are testable without a device.
 */
class ThumbStrip(
    /** Media duration in ms. */
    val durationMs: Long,
    /** Width of one fine span in ms. */
    val bucketMs: Long = DEFAULT_BUCKET_MS,
    /** Fine spans covered by one coarse preview. */
    val coarseSpans: Int = COARSE_SPANS
) {
    private val state: ByteArray
    private val coarseState: ByteArray

    init {
        require(bucketMs > 0) { "bucketMs must be positive, was $bucketMs" }
        require(coarseSpans >= 1) { "coarseSpans must be at least 1, was $coarseSpans" }
        val n = bucketCount(durationMs, bucketMs)
        state = ByteArray(n)
        coarseState = ByteArray(bucketCount(durationMs, bucketMs * coarseSpans))
    }

    /** Fine spans in the film — always at least 1, so an unknown duration
     *  still has somewhere to put a preview. */
    val count: Int get() = state.size

    /** Coarse groups in the film. */
    val coarseCount: Int get() = coarseState.size

    /** The fine span a playhead position falls in. */
    fun bucketAt(ms: Long): Int = clampBucket(ms, bucketMs, count)

    /** The film time a fine span's preview shows: its middle, so the frame is
     *  representative of the span rather than of its first instant. */
    fun timeMs(bucket: Int): Long {
        val b = bucket.coerceIn(0, count - 1)
        return b * bucketMs + bucketMs / 2
    }

    /** The film time a coarse group's preview shows: the middle of the
     *  group's middle span. */
    fun coarseTimeMs(group: Int): Long {
        val g = group.coerceIn(0, coarseCount - 1)
        return g * bucketMs * coarseSpans + (bucketMs * coarseSpans) / 2
    }

    /** The coarse group a fine span belongs to. */
    fun groupOf(bucket: Int): Int =
        (bucket.coerceIn(0, count - 1) / coarseSpans).coerceIn(0, coarseCount - 1)

    /** True when this span has a preview of its own. */
    fun hasOwn(bucket: Int): Boolean = stateOf(bucket) == READY

    /** True when this span can be answered at all — its own preview, or the
     *  coarse one covering it. */
    fun answered(bucket: Int): Boolean =
        hasOwn(bucket) || coarseState[groupOf(bucket)] == READY.toByte()

    /** True when this span still wants its own preview. */
    fun wantsOwn(bucket: Int): Boolean =
        stateOf(bucket) != READY && stateOf(bucket) != FAILED &&
            coarseState[groupOf(bucket)] == READY.toByte()

    fun stateOf(bucket: Int): Int =
        if (bucket in state.indices) state[bucket].toInt() else PENDING

    fun coarseStateOf(group: Int): Int =
        if (group in coarseState.indices) coarseState[group].toInt() else PENDING

    /** A fine span's preview has been captured. */
    fun onBuilt(bucket: Int) {
        if (bucket in state.indices) state[bucket] = READY.toByte()
    }

    /**
     * A coarse group's preview has been captured: it answers every fine span
     * inside it, which is the point of the coarse pass.
     */
    fun onCoarseBuilt(group: Int) {
        if (group !in coarseState.indices) return
        coarseState[group] = READY.toByte()
    }

    /**
     * A span could not be captured. Written off rather than retried: a span
     * that fails once (an unreadable region, a codec that gave up) fails
     * identically on a second attempt, and retrying it would let it monopolise
     * a pass. The coarse group's own failure answers nothing, so its spans are
     * left PENDING and the group is only marked so the pass moves on.
     */
    fun onFailed(bucket: Int) {
        if (bucket in state.indices) state[bucket] = FAILED.toByte()
    }

    fun onCoarseFailed(group: Int) {
        if (group in coarseState.indices) coarseState[group] = FAILED.toByte()
    }

    /**
     * The next coarse group still wanting a preview, ascending from [from]
     * and wrapping, so a pass that started late still picks up the gap behind
     * it. -1 when the coarse pass is done.
     */
    fun nextCoarseGroup(from: Int = 0): Int {
        for (i in from.coerceAtLeast(0) until coarseState.size) {
            if (coarseState[i] == PENDING.toByte()) return i
        }
        for (i in 0 until from.coerceIn(0, coarseState.size)) {
            if (coarseState[i] == PENDING.toByte()) return i
        }
        return -1
    }

    /**
     * The next fine span still wanting its own preview — that is, one whose
     * coarse group has been captured, so refining it replaces a frame that
     * already exists with one from the right moment. Ascending, wrapping.
     * -1 when the fine pass is done.
     */
    fun nextWantingOwn(from: Int = 0): Int {
        for (i in from.coerceAtLeast(0) until state.size) if (wantsOwn(i)) return i
        for (i in 0 until from.coerceIn(0, state.size)) if (wantsOwn(i)) return i
        return -1
    }

    /** True when nothing more will be built. */
    fun complete(): Boolean = nextCoarseGroup(0) < 0 && nextWantingOwn(0) < 0

    /** How much of the film can be answered, 0..1: coarse coverage counts,
     *  because a frame every 30 s does answer the slider. */
    fun coverage(): Float {
        if (state.isEmpty()) return 1f
        var n = 0
        for (b in state.indices) if (answered(b)) n++
        return n.toFloat() / state.size
    }

    /** How much of the film has its own preview, 0..1 — the second pass. */
    fun fineCoverage(): Float {
        if (state.isEmpty()) return 1f
        var n = 0
        for (s in state) if (s == READY.toByte()) n++
        return n.toFloat() / state.size
    }

    companion object {
        /** Fine spacing: one preview per ten seconds of film. */
        const val DEFAULT_BUCKET_MS = 10_000L

        /** Fine spans per coarse preview: 3, so the first pass is a third of
         *  the work rather than a sixth — coarse frames land close enough
         *  together to be useful and the whole film answers quickly. */
        const val COARSE_SPANS = 3

        const val PENDING = 0
        const val READY = 1
        const val FAILED = 2

        /** Which span a position falls in, for callers holding no strip. */
        fun bucketOf(ms: Long, bucketMs: Long = DEFAULT_BUCKET_MS): Int =
            clampBucket(ms, bucketMs, Int.MAX_VALUE)

        private fun clampBucket(ms: Long, bucketMs: Long, count: Int): Int {
            if (ms <= 0L || count <= 0) return 0
            val b = (ms / bucketMs).toInt()
            return if (b >= count) count - 1 else b
        }

        fun bucketCount(durationMs: Long, bucketMs: Long = DEFAULT_BUCKET_MS): Int =
            if (durationMs <= 0L) 1
            else (((durationMs + bucketMs - 1) / bucketMs).toInt()).coerceAtLeast(1)
    }
}