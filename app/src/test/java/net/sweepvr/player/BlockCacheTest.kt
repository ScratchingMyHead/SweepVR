/* SweepVR Player — GPL-3.0-only.
 * See LICENSE in the repository root.
 */
package net.sweepvr.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** BlockCache — the proxy's playback-fills / background-drains cache. */
class BlockCacheTest {
    private fun key(t: String = "tok", b: Long = 0) = BlockCache.Key(t, b)

    @Test
    fun storesAndReturnsTheExactSpan() {
        val c = BlockCache(1_000_000, minEntryBytes = 0)
        val buf = ByteArray(100) { it.toByte() }
        c.store(key(), offset = 5_000, buf = buf, len = 100)
        val out = c.read(key(), offset = 5_000, len = 100)
        assertTrue(out != null)
        assertEquals(100, out!!.size)
        assertEquals(0L, out[0].toLong())            // buf[0] is byte 0
        assertEquals(94L, out[94].toLong())
    }

    @Test
    fun spanMustBeContainedByTheEntry() {
        val c = BlockCache(1_000_000, minEntryBytes = 0)
        c.store(key(), offset = 1_000, buf = ByteArray(100), len = 100)
        // starts before it
        assertNull(c.read(key(), offset = 999, len = 10))
        // runs past the end
        assertNull(c.read(key(), offset = 1_050, len = 100))
        // a suffix that fits is fine
        assertEquals(50, c.read(key(), offset = 1_050, len = 50)!!.size)
    }

    @Test
    fun tokensAndBlocksAreDistinctEntries() {
        val c = BlockCache(1_000_000, minEntryBytes = 0)
        c.store(key("a", 0), offset = 0, buf = ByteArray(100) { 1 }, len = 100)
        c.store(key("b", 0), offset = 0, buf = ByteArray(100) { 2 }, len = 100)
        c.store(key("a", 1), offset = 100, buf = ByteArray(100) { 3 }, len = 100)
        assertEquals(1L, c.read(key("a", 0), 0, 10)!![0].toLong())
        assertEquals(2L, c.read(key("b", 0), 0, 10)!![0].toLong())
        assertEquals(3L, c.read(key("a", 1), 100, 10)!![0].toLong())
    }

    @Test
    fun smallReadsAreNotWorthAnEntry() {
        // the default floor: a container probe, not a stream of media, so
        // storing it would evict something useful for nothing
        val c = BlockCache(1_000_000)
        c.store(key(), offset = 0, buf = ByteArray(16), len = 16)
        assertNull(c.read(key(), 0, 16))
        assertEquals(0L, c.size())
    }

    @Test
    fun storeEvictsToMakeRoomRatherThanRefusing() {
        // A cache that stops accepting once it is full goes stale forever, so
        // a full cache drops its oldest instead: three 100-byte entries in a
        // 300-byte cache, then a fourth pushes the least recent one out.
        val c = BlockCache(300, minEntryBytes = 0)
        repeat(3) {
            c.store(key("t", it.toLong()), offset = it * 100L,
                buf = ByteArray(100) { it.toByte() }, len = 100)
        }
        assertEquals(300L, c.size())
        // touch entry 0 so it becomes the most recent
        assertTrue(c.read(key("t", 0), 0, 10) != null)
        c.store(key("t", 3), offset = 300, buf = ByteArray(100) { 9 }, len = 100)
        assertEquals(300L, c.size())
        assertTrue("the touched entry should survive", c.read(key("t", 0), 0, 10) != null)
        assertNull("the oldest should go", c.read(key("t", 1), 100, 10))
    }

    @Test
    fun anEntryBiggerThanTheWholeCacheIsRefused() {
        val c = BlockCache(150, minEntryBytes = 0)
        c.store(key("t", 0), offset = 0, buf = ByteArray(100), len = 100)
        c.store(key("t", 1), offset = 100, buf = ByteArray(200), len = 200)
        assertEquals(100L, c.size())   // the first stays; the second never fits
        assertTrue(c.read(key("t", 0), 0, 10) != null)
    }

    @Test
    fun firstWriterWinsAndClearEmpties() {
        val c = BlockCache(1_000_000, minEntryBytes = 0)
        c.store(key(), offset = 0, buf = ByteArray(100) { 1 }, len = 100)
        c.store(key(), offset = 0, buf = ByteArray(100) { 7 }, len = 100)
        assertEquals(1L, c.read(key(), 0, 10)!![0].toLong())
        c.clear()
        assertNull(c.read(key(), 0, 10))
        assertEquals(0L, c.size())
    }
}