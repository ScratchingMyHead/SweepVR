/* SweepVR Player — GPL-3.0-only.
 * See LICENSE in the repository root.
 */
package net.sweepvr.player

import org.junit.Assert.assertEquals
import org.junit.Test

/** BgPacer — the arithmetic that keeps background reads off the link. */
class BgPacerTest {
    @Test
    fun uncappedNeverWaits() {
        val p = BgPacer()
        p.bytesPerSec = 0
        assertEquals(0L, p.delayMs(10_000_000L, 0))
        assertEquals(0L, p.delayMs(10_000_000L, 1))
    }

    @Test
    fun waitsOutTheRemainderOfTheAllowance() {
        val p = BgPacer()
        p.bytesPerSec = 100_000      // 100 KB/s: 512 KiB should take ~5 s
        // 10 KB is due after 100 ms, and 40 ms has passed -> 60 ms to go
        assertEquals(60L, p.delayMs(10_000L, 40L))
        // behind schedule -> no wait at all
        assertEquals(0L, p.delayMs(10_000L, 500L))
        // exactly on time
        assertEquals(0L, p.delayMs(10_000L, 100L))
    }

    @Test
    fun delayNeverExceedsTheStallCap() {
        val p = BgPacer()
        p.bytesPerSec = 1            // a byte per second: absurd on purpose
        // a clock jump or a long GC must not park the thread for minutes
        assertEquals(2000L, p.delayMs(1_000_000L, 0L))
    }

    @Test
    fun raisedCapClearsOwedTimeImmediately() {
        // The player revises the ceiling from the bandwidth it is achieving,
        // so the next window must not be charged for the old one's debt.
        val p = BgPacer()
        p.bytesPerSec = 100_000
        assertEquals(90L, p.delayMs(10_000L, 10L))
        p.bytesPerSec = 10_000_000   // link turns out to be fast
        assertEquals(0L, p.delayMs(10_000L, 10L))
    }

    @Test
    fun pressureHoldsBackgroundOff() {
        val p = BgPacer()
        assertEquals(0L, p.holdMs(false))
        assertEquals(250L, p.holdMs(true))
    }
}