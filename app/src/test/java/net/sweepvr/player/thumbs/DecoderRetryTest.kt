/* SweepVR Player — GPL-3.0-only.
 * See LICENSE in the repository root.
 */
package net.sweepvr.player.thumbs

import androidx.media3.common.PlaybackException
import net.sweepvr.player.thumbs.ThumbBuilder.Companion.MAX_FRAME_PIXELS
import net.sweepvr.player.thumbs.ThumbBuilder.Companion.decoderRetryDelayMs
import net.sweepvr.player.thumbs.ThumbBuilder.Companion.isTransientErrorCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The preview decoder's two policies: how big a frame it will attempt, and
 * what it does about one that fails.
 *
 * Both are pure arithmetic worth pinning. Neither throws when wrong — a cap
 * that is too low just silently refuses films, and a retry policy that is too
 * eager quietly re-fetches a hopeless file — so nothing about them is visible
 * without a test or a log line.
 */
class DecoderRetryTest {
    @Test
    fun theCapIsAboveEveryResolutionSeenFailing() {
        // Previews are read back at PREVIEW_READ_H (288px) whatever the film
        // is, so the frames that are kept cost the same at any source size and
        // a lower cap buys nothing. The old 12 MP ceiling is exactly what
        // refused these with "frame too large for a preview, skipping".
        val seen = listOf(5760L * 2880L, 6144L * 3072L, 8000L * 4000L, 8192L * 4096L)
        for (px in seen) {
            assertTrue("${px / 1_000_000}MP must not be refused up front", px <= MAX_FRAME_PIXELS)
            assertTrue("${px / 1_000_000}MP WAS refused by the old 12MP ceiling",
                px > 12_000_000L)
        }
    }

    @Test
    fun aDecoderThatCouldNotBeCreatedIsRetried() {
        // The whole point of the retry: these arrive when the decoder could
        // not allocate because the heap was busy, which minutes later it can.
        assertTrue(isTransientErrorCode(PlaybackException.ERROR_CODE_DECODER_INIT_FAILED))
        assertTrue(isTransientErrorCode(PlaybackException.ERROR_CODE_DECODING_FAILED))
        assertTrue(isTransientErrorCode(
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES))
    }

    @Test
    fun aRuntimeErrorFromAWorkingDecoderIsRetried() {
        // Observed on 3840x1920 (below the cap, so not a size issue): the
        // decoder initialised, its surface was attached, ran for 17s, then
        // reported this — with no format complaint anywhere. A codec starved
        // mid-flight, not an undecodable file. Treating it as permanent cost
        // that film its strip for the whole session, on an attempt that
        // another attempt of the same file builds fine.
        assertTrue(isTransientErrorCode(
            PlaybackException.ERROR_CODE_FAILED_RUNTIME_CHECK))
    }

    @Test
    fun anUnreadableContainerIsNotRetried() {
        // Retrying this re-downloads the same bytes to fail again.
        assertTrue(!isTransientErrorCode(
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED))
        assertTrue(!isTransientErrorCode(
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED))
        assertTrue(!isTransientErrorCode(
            PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE))
    }

    @Test
    fun anUnrecognisedErrorIsNotRetried() {
        // Unknown means PERMANENT on purpose: the other way round turns one
        // hopeless file into a three-minute bandwidth leak.
        assertTrue(!isTransientErrorCode(0x1234))
        assertTrue(!isTransientErrorCode(PlaybackException.ERROR_CODE_UNSPECIFIED))
    }

    @Test
    fun theRetryBacksOffRatherThanSpinning() {
        // Each retry is a whole new player that re-reads the container, off
        // SMB. Playback comes first.
        assertTrue(decoderRetryDelayMs(0) < decoderRetryDelayMs(1))
        assertTrue(decoderRetryDelayMs(1) < decoderRetryDelayMs(4))
        assertTrue(decoderRetryDelayMs(99) <= ThumbBuilder.DECODER_RETRY_MAX_MS)
        assertEquals(decoderRetryDelayMs(0), decoderRetryDelayMs(-5))
    }
}
