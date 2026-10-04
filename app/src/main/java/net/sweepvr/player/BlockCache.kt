/* SweepVR Player — GPL-3.0-only.
 * See LICENSE in the repository root.
 */
package net.sweepvr.player

/**
 * A bounded cache of file blocks, keyed by stream token + block index, for
 * [StreamProxy].
 *
 * ## Why
 *
 * The seek-bar grip shows a preview of the position it will drop at, and the
 * preview is a frame decoded from the file itself. Almost every one of those
 * frames is wanted *near where the user is already watching* — the drag is
 * a small excursion from the playhead — and the bytes for that neighbourhood
 * were just read by playback. Caching what playback streams turns those
 * thumbnail reads into memory hits, which is what keeps the preview feature
 * off the network almost entirely rather than merely slow.
 *
 * Blocks are the proxy's own read window (512 KiB), so a cached window is
 * returned without touching SMB at all. Entries remember the FILE OFFSET they
 * start at rather than assuming block alignment: range requests arrive
 * unaligned (a player's first read can start at any byte), so a block may be
 * partially covered. An entry is only used when it fully contains the wanted
 * span, and a partially-filled block is simply not stored.
 *
 * Eviction is least-recently-used over entries, bounded by total bytes. Only
 * the foreground stores: a throttled background read gains nothing from
 * filling the cache it is itself about to be served from, and copying every
 * window it does read would double its memory traffic for nothing.
 */
class BlockCache(
    val maxBytes: Long,
    /** Below this a range read is a container probe rather than a stream of
     *  media, and caching it would evict something useful for nothing.
     *  A parameter so the rule can be exercised without allocating 64 KiB. */
    private val minEntryBytes: Long = MIN_ENTRY
) {
    /** One cached span: the file offset it begins at, and its bytes. */
    class Entry(val start: Long, val bytes: ByteArray)

    private val key = java.util.LinkedHashMap<Key, Entry>(128, 0.75f, /* accessOrder */ true)
    private var bytes = 0L
    private var hits = 0L
    private var misses = 0L

    /** Identity of a cached block: which file, and which window of it. A
     *  data class because callers hold their own instance — identity equality
     *  would silently turn every store into a miss and every read into a
     *  trip to SMB. */
    data class Key(val token: String, val block: Long)

    /** Bytes currently held. */
    @Synchronized fun size(): Long = bytes

    /** Cumulative hit/miss counters, for the log. */
    @Synchronized fun stats(): String = "$hits/$misses ${bytes / 1024}KB"

    /** Wipe everything — called when the playing item changes. */
    @Synchronized fun clear() {
        key.clear()
        bytes = 0
    }

    /**
     * [len] bytes at file offset [offset], or null unless a stored entry
     * covers the whole span. Copies out, so the caller's buffer is free to
     * scribble on.
     */
    @Synchronized fun read(k: Key, offset: Long, len: Int): ByteArray? {
        val e = key[k] ?: run { misses++; return null }
        val start = e.start
        val end = start + e.bytes.size
        if (offset < start || offset + len > end) { misses++; return null }
        val out = ByteArray(len)
        System.arraycopy(e.bytes, (offset - start).toInt(), out, 0, len)
        hits++
        return out
    }

    /**
     * Store the [len] bytes of [buf] starting at its index 0, which the
     * caller read from file offset [offset]. Skipped when the span is too
     * small to be worth an entry, when the block already holds something, or
     * when the entry could not fit whatever else went — everything else makes
     * room by dropping the least recently used blocks, because a cache that
     * refuses to accept once full is a cache that stays stale forever.
     */
    @Synchronized fun store(k: Key, offset: Long, buf: ByteArray, len: Int) {
        if (len.toLong() < minEntryBytes || len > maxBytes) return
        if (key.containsKey(k)) return
        key[k] = Entry(offset, buf.copyOf(len))
        bytes += len
        while (bytes > maxBytes) {
            val it = key.entries.iterator()
            if (!it.hasNext()) break
            bytes -= it.next().value.bytes.size
            it.remove()
        }
    }

    private companion object {
        /** Below this a range read is a container probe, not a stream of
         *  media; caching it evicts something useful for nothing. */
        const val MIN_ENTRY = 64L * 1024
    }
}