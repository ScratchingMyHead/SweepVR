/* SweepVR Player — GPL-3.0-only.
 * See LICENSE in the repository root.
 */
package net.sweepvr.player

/**
 * Pacing for background reads in [StreamProxy], as a pure function of
 * "bytes sent so far" and "wall time so far" so the arithmetic can be tested
 * without a clock.
 *
 * A background stream (the seek-preview decoder) must never take the link
 * away from playback. Serialising it onto its own thread stops it from
 * queueing behind the player, and this stops it from *outrunning* the player:
 * after each read window it asks how long that many bytes should have taken
 * at the cap and sleeps off the difference. Playback reads are never paced.
 *
 * The cap is in bytes per second and may be raised at any time (the player
 * revises it from the bandwidth it is actually achieving), so the delay is
 * recomputed from the totals every window rather than accumulating per-window
 * debt — a cap that is raised mid-stream must not punish the next window for
 * the old one.
 */
class BgPacer {
    /** Bytes per second ceiling for background traffic; 0 = uncapped. */
    @Volatile var bytesPerSec: Long = 0

    /**
     * Milliseconds to sleep after [bytesSent] have gone out and [elapsedMs]
     * have passed. Never negative; never more than [MAX_STALL] so a clock
     * jump or a long GC cannot park the background thread for minutes.
     */
    fun delayMs(bytesSent: Long, elapsedMs: Long): Long {
        val bps = bytesPerSec
        if (bps <= 0L || bytesSent <= 0L) return 0L
        val owed = bytesSent * 1000L / bps - elapsedMs
        return owed.coerceIn(0L, MAX_STALL)
    }

    /**
     * Whether a background read should hold off entirely because playback is
     * starved — the player sets this while it is buffering or has just seeked,
     * which is exactly when the link must belong to it alone.
     */
    fun holdMs(playbackPressure: Boolean): Long = if (playbackPressure) PRESSURE_HOLD else 0L

    private companion object {
        const val MAX_STALL = 2000L
        const val PRESSURE_HOLD = 250L
    }
}