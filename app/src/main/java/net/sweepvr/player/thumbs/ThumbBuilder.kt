/* SweepVR Player — GPL-3.0-only.
 * See LICENSE in the repository root.
 */
package net.sweepvr.player.thumbs

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import net.sweepvr.player.thumbs.FramePixels
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import net.sweepvr.player.FileLog
import net.sweepvr.player.VrRenderer
import java.io.File
import net.sweepvr.player.StreamProxy

/**
 * Builds the seek-preview strip: a frame every [ThumbStrip.DEFAULT_BUCKET_MS]
 * of the film, decoded ahead of the user so the slider always has something
 * to show.
 *
 * ## Why a second player
 *
 * The obvious tool, `MediaMetadataRetriever`, reads the file with the
 * platform's own demuxers — and those are exactly what cannot be relied on
 * for the formats this app is actually used with: MKV is the norm for VR
 * video, and platform MKV support is patchy to absent depending on the
 * device. A second ExoPlayer is the one reader in the app that is *proven*
 * to open every file we can play, because it is the one playing them.
 *
 * It is a preview player, not a second playback: no audio track selected at
 * all, a couple of tiny buffers, held paused, and driven one seek at a time.
 *
 * Frames arrive through the renderer's SurfaceTexture and are read back from
 * GL (see FramePixels for why not `ImageReader`); this class turns those
 * bytes into a bitmap, writes it to the strip, and hands it to the renderer.
 *
 * ## Threads
 *
 * ExoPlayer is built and driven on the main looper (its own rules — that is
 * where its events arrive), while frame conversion runs on a dedicated worker
 * thread so a 4K frame's worth of arithmetic never touches the UI thread or
 * the render thread. Nothing here touches the GL thread: the finished bitmap
 * is handed to the renderer, which uploads it.
 *
 * ## Bandwidth
 *
 * Every read this player makes is marked background (see [StreamProxy]), so
 * it runs on the proxy's own single thread, is paced to a ceiling the player
 * revises from the bandwidth it is actually achieving, waits its turn while
 * playback is starved, and — because the proxy caches what playback streams
 * — usually finds the bytes it needs already in memory, since a preview is
 * wanted near where the user is watching.
 */
@UnstableApi
class ThumbBuilder(
    private val context: Context,
    /** Owns the capture surface and the GL readback this builder receives its
     *  pixels through. */
    private val renderer: VrRenderer,
    private val store: ThumbMemory,
    /** Called on the worker thread with a finished preview. Ownership passes
     *  to the receiver, which must recycle the previous one it was given. */
    private val onThumb: (bucket: Int, bmp: Bitmap) -> Unit,
    /** Called on the worker once every bucket that is going to be built has
     *  been — so the strip having finished can be announced rather than
     *  inferred from silence. */
    private val onStripDone: (() -> Unit)? = null
) {
    companion object {
        private const val TAG = "SweepVR-thumb"
        /** Preview height in pixels. Big enough for the card at panel distance,
         *  small enough that 1440 of them fit on disk in a sensible size. */
        const val TARGET_H = 128
        const val TARGET_MAX_W = 288
        /** Gap between two prebuild fetches, on top of the proxy's byte cap. */
        const val PREBUILD_GAP_MS = 250L
        /** How often the prebuild loop ticks. It polls rather than handing
         *  work on, so this is the loop's heartbeat, not a latency budget. */
        const val POLL_MS = 250L
        /** A fetch that has been waiting this long for its frame is given up
         *  on, but NOT written off as failed: a slow link, or a player still
         *  warming up, is not a region that cannot be decoded. The bucket
         *  stays wanted and the loop comes back to it. */
        const val STALL_MS = 20000L
        /** How often to re-check while the player is asking for the link. */
        const val PRESSURE_POLL_MS = 400L
        /** How often to re-check while the decoder is still coming up. */
        const val WARMUP_POLL_MS = 500L
        /** How often to ask whether a seek has landed, and how many times
         *  before playing anyway.
         *
         *  55 × 60 ms is ~3.3 s, deliberately inside ExoPlayer's own 4 s
         *  stuck-playback watchdog so this wait can never outlast the player
         *  tolerating the state.
         *
         *  Giving up early is not a harmless timeout, which is why it is worth
         *  being long: the capture then takes whatever frame is on screen,
         *  which is from BEFORE the seek, and files it against the new span —
         *  a preview of the wrong moment. */
        const val LAND_POLL_MS = 60L
        const val LAND_POLL_TRIES = 55
        /** An upper bound on frame area, not a limit on what the app will try.
         *
         *  This was 12 MP, justified as "the decoded plane buffer alone is tens
         *  of MB" — but the preview readback is scaled to PREVIEW_READ_H
         *  (288px) regardless of the film, so the frames that are kept are a
         *  few hundred kilobytes whatever the source is. At 12 MP it refused
         *  5760x2880 and larger outright with "frame too large for a preview,
         *  skipping", which is most of a real library, and it did so BEFORE
         *  anything was attempted.
         *
         *  It stays as a bound because there has to be a line somewhere: a
         *  frame this large cannot be decoded at all on most hardware, and a
         *  check that can refuse is better than an OOM. 40 MP is above every
         *  resolution seen failing in practice (8192x4096 is 33.6 MP), so it
         *  no longer refuses anything real. */
        const val MAX_FRAME_PIXELS = 40_000_000L

        /** A failed preview decoder: how long to wait before trying again, and
         *  when to stop trying for good.
         *
         *  This used to not exist, and the absence was the bug: one
         *  DECODER_INIT_FAILED cleared `strip` and ended prebuilding for the
         *  rest of the session, so a film whose decoder could not allocate
         *  because the heap was momentarily full never got a strip at all, and
         *  a restart was the only way out. Those failures are usually
         *  transient — the heap a minute later is a different heap. */
        const val DECODER_RETRY_MS = 15_000L
        const val DECODER_GIVE_UP_MS = 180_000L
        /** Ceiling on the backoff's doubling. Must stay under
         *  [DECODER_GIVE_UP_MS], or a film that could have recovered is never
         *  given the chance. */
        const val DECODER_RETRY_MAX_MS = 240_000L

        /**
         * The wait before retrying a failed decoder, given how many attempts
         * have already failed. Doubling, capped.
         *
         * Each retry is a whole new player that re-reads the container, and on
         * a big remote file that is bandwidth the prebuild is not allowed to
         * spend — playback comes first. Retrying flat spends it every 15s for
         * three minutes on a decoder that is never going to appear.
         */
        fun decoderRetryDelayMs(attempts: Int): Long =
            (DECODER_RETRY_MS shl attempts.coerceIn(0, 4))
                .coerceAtMost(DECODER_RETRY_MAX_MS)

        /**
         * Which decoder error codes are worth retrying, by code so the policy
         * can be pinned in a test (DecoderRetryTest).
         *
         * Allocation-adjacent codes only: a decoder that could not be created
         * usually means the allocation did not fit at that moment rather than
         * that the format is undecodable. A container the demuxer cannot read
         * is not that, and retrying it just re-downloads the same bytes to
         * fail again.
         *
         * Anything unrecognised is treated as PERMANENT, deliberately. The
         * failure mode of guessing wrong in the other direction is a
         * hopeless file re-fetching itself for three minutes; the failure mode
         * here is one film with no strip, which is visible and cheap.
         */
        fun isTransientErrorCode(code: Int): Boolean = when (code) {
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
            // FAILED_RUNTIME_CHECK is the codec reporting a problem from
            // INSIDE a decoder that had already started — observed on a
            // 3840x1920 file whose preview surface was attached and working
            // for 17 s before the error, with no format complaint anywhere.
            // That is a decoder being torn down or starved while playback also
            // wants the hardware instance, not an undecodable file, and it
            // resolves by trying again on a fresh player.
            //
            // It matters that this is listed: without it the "unknown means
            // permanent" rule below applied, and a file that builds fine on
            // other attempts lost its strip for the rest of the session on the
            // one attempt that hit this — which is worse than the behaviour
            // the retry was added to fix.
            PlaybackException.ERROR_CODE_FAILED_RUNTIME_CHECK -> true
            else -> false
        }
    }

    /** Where the strip stands, for the status line and the tests. */
    @Volatile var strip: ThumbStrip? = null
        private set

    @Volatile private var pressure = false
    @Volatile private var stopped = false
    @Volatile private var urgent = -1

    private var player: ExoPlayer? = null
    /** What the player was built from, so a failed one can be rebuilt. */
    @Volatile private var sourceUrl: String? = null
    /** Set when the preview decoder reported an error; cleared by a retry. */
    @Volatile private var decoderFailed = false
    /** When it gave up, so the retry can wait — and eventually stop waiting.
     *  Set at the moment of failure: leaving it 0 made the elapsed time read
     *  as "now", which is instantly over the give-up budget and abandoned the
     *  strip on the first error. That is the permanence this exists to fix. */
    @Volatile private var decoderFailedAt = 0L
    /** Consecutive failures, for the backoff between retries. */
    @Volatile private var decoderAttempts = 0
    /** The renderer's capture surface, and the GL generation it belongs to. */
    private var attachedSurface: android.view.Surface? = null
    private var attachedGen = -1
    private var attachedSize: Pair<Int, Int>? = null
    private var worker: HandlerThread? = null
    private var wq: Handler? = null
    private val mq = Handler(Looper.getMainLooper())
    private var mqReady = false

    /** The bucket being fetched right now. The renderer stamps it on the
     *  frame it reads back, which is how a frame is matched to the seek that
     *  asked for it: ExoPlayer renders the seeked frame and *then* reports
     *  READY, so gating on playback state drops precisely the frame wanted,
     *  and a paused player renders nothing further to replace it. */
    private var pendingBucket = -1
    private var cursor = 0
    private var coarseCursor = 0
    private var finePass = false
    /** True when the fetch in flight is a coarse one, and which group. */
    private var pendingCoarse = -1
    /** Where the player actually landed on the last seek, for checking a
     *  preview against the moment it was asked for. */
    @Volatile
    private var landedMs = -1L
    /** True while waiting for a seek to be reflected in the player's
     *  position; then exactly one frame is taken and we pause again. */
    @Volatile
    private var awaitLanded = false
    private var landingTries = 0
    /**
     * Stored key -> the film time that frame was captured from. Diagnostic
     * only, and the thing that settles "is the card showing the wrong frame"
     * without guessing: for every frame handed to the card, this says what
     * moment it was actually taken from.
     */
    private val capturedAt = java.util.HashMap<Int, Long>()

    /** Film time the fetch in flight asked for; recorded with the capture. */
    private var askedFor = 0L

    /**
     * Has the seek landed? Main thread (it reads the player). When it has,
     * start playing so the renderer emits the frame at the new position — and
     * only then, so no frame from the old position can be mistaken for it.
     * Gives up after a bounded number of tries and plays anyway: a preview a
     * moment out is still worth having, and the stall watchdog above still
     * owns the case where nothing arrives at all.
     */
    private fun checkLanded(p: ExoPlayer, targetMs: Long, spanMs: Long) {
        if (stopped || !awaitLanded || pendingBucket < 0) return
        val at = try { p.currentPosition } catch (_: Exception) { -1L }
        val near = at >= 0 && kotlin.math.abs(at - targetMs) <= spanMs / 2 + 250L
        if (!near && landingTries-- > 0) {
            mq.postDelayed({ checkLanded(p, targetMs, spanMs) }, LAND_POLL_MS)
            return
        }
        awaitLanded = false
        renderer.previewSeekArmed = true
        try { p.play() } catch (_: Exception) {}
    }
    private var built = 0
    private var fetched = 0
    /** One-shot diagnostics, so a stalled prebuild says why once rather than
     *  once a poll. */
    private var loggedComplete = false
    private var waitingLogged = false
    private var noSizeLogged = false
    /** One attach in flight, so a 500 ms poll does not queue the same one
     *  over and over while the main thread is busy. */
    private var reattachPosted = false
    private var pressureLogged = false
    private var dropLogged = false
    /** When the in-flight fetch began, for the stall watchdog in the loop. */
    private var fetchStartedAt = 0L
    private var pendingBucketForStall = -1

    // ---------- lifecycle ----------

    /**
     * Start prebuilding [url]'s strip. Any strip already on disk for the same
     * film and split is adopted rather than rebuilt, so a resumed film is
     * instantly complete.
     */
    fun start(url: String, durationMs: Long, split: ThumbSplit) {
        stop()
        stopped = false
        pressure = false
        currentSplit = split
        urgent = -1
        built = 0
        fetched = 0
        pendingBucket = -1
        cursor = 0
        loggedComplete = false
        waitingLogged = false
        noSizeLogged = false
        decoderFailed = false
        decoderFailedAt = 0L
        decoderAttempts = 0
        sourceUrl = url
        val st = ThumbStrip(durationMs)
        strip = st
        // Memory-only: the strip starts empty and is built again for this
        // session, so nothing carries over between plays and nothing on disk
        // can be stale. Clear out the directory an earlier version kept, once,
        // so it cannot sit there looking like it is still in use.
        try {
            File(context.cacheDir, "thumbs").takeIf { it.isDirectory }?.deleteRecursively()
        } catch (_: Exception) {}
        store.open(split)
        for (k in store.keys()) {
            if (k >= 0) st.onBuilt(k) else st.onCoarseBuilt(-1 - k)
        }
        // From the beginning of the film, not from where playback happens to
        // be: the sweep is a job, not a response to the user, and starting it
        // at the playhead means the strip is half-built in whatever order the
        // film happens to be watched in.
        cursor = 0
        coarseCursor = 0
        finePass = false
        FileLog.i(TAG, "start ${st.count} spans of ${st.bucketMs}ms in ${st.coarseCount} " +
            "coarse groups, split=$split")

        renderer.previewSink = { b, bytes, w, h -> onPreviewPixels(b, bytes, w, h) }
        worker = HandlerThread("sweepvr-thumb").apply { start() }
        wq = Handler(worker!!.looper)
        mqReady = true
        mq.post { buildPlayer(url) }
        wq!!.post { sweep() }
    }

    /**
     * Throw away the failed preview player and build another.
     *
     * MAIN THREAD, like every other player operation — hence the post from the
     * worker tick that calls this. Returns false when there is nothing to
     * rebuild from, which leaves the strip as it is rather than pretending.
     */
    private fun rebuildPreviewPlayer(): Boolean {
        val url = sourceUrl ?: return false
        val p = player
        if (p != null) {
            try { p.release() } catch (_: Exception) {}
            player = null
        }
        // The failed surface must be forgotten, or attachSurface sees the same
        // one it already has and skips handing it over — leaving the new
        // player rendering into a released surface.
        attachedSurface = null
        attachedSize = null
        renderer.previewExpectBucket = -1
        renderer.previewSeekArmed = false
        pendingBucket = -1
        FileLog.i(TAG, "retrying the preview decoder (attempt ${decoderAttempts + 1})")
        mq.post { if (!stopped) buildPlayer(url) }
        return true
    }

    /** Stop prebuilding and give back the decoder, the reader and the thread. */
    fun stop() {
        if (stopped) return
        stopped = true
        urgent = -1
        val p = player
        player = null
        attachedSurface = null
        attachedGen = -1
        attachedSize = null
        renderer.previewSink = null
        renderer.previewExpectBucket = -1
        renderer.previewSeekArmed = false
        if (mqReady && p != null) mq.post { try { p.setVideoSurface(null); p.release() } catch (_: Exception) {} }
        worker?.quitSafely()
        worker = null
        wq = null
        strip = null
    }

    /**
     * The player is buffering or has just seeked: the link belongs to it
     * until it says otherwise.
     */
    fun setPressure(on: Boolean) {
        pressure = on
    }

    /**
     * The user is pointing at [bucket]. Shows it at once if it exists,
     * otherwise jumps the queue: the prebuild sweep is a background nicety
     * and this is the thing actually being looked at.
     */
    fun request(bucket: Int) {
        val st = strip ?: return
        // A fetch is out and the grip has moved on. It used to abandon the fetch
        // here (pendingBucket = -1), which meant the very next sweep tick
        // issued another seek while the previous one was still executing on
        // the player. During a drag that is a seek every POLL_MS with the
        // buffer budget below it — bufferForPlaybackAfterRebuffer is 100ms —
        // and it is what produced ERROR_CODE_FAILED_RUNTIME_CHECK on a film
        // that previews fine when nobody is dragging it.
        //
        // Letting it finish is both cheaper and more useful: the frame lands
        // where it was asked for, and is a perfectly good preview for THAT
        // span, so the drag costs nothing and the strip gains one. The grip's
        // current position is recorded below and picked up on the next tick,
        // which is what bounds the rate to one seek per POLL_MS.
        //
        // Answerable already — by its own preview, or by the coarse frame that
        // covers it — then show it. Asking for a finer one mid-drag would
        // stall the card on a fetch the strip will get to anyway.
        if (st.answered(bucket)) {
            wq?.post {
                if (!stopped) showFromDisk(bucket)
            }
            return
        }
        urgent = bucket
    }

    // ---------- the preview player ----------

    private fun buildPlayer(url: String) {
        if (stopped) return
        try {
            val http = DefaultHttpDataSource.Factory()
                .setAllowCrossProtocolRedirects(false)
                .setConnectTimeoutMs(6000)
                .setReadTimeoutMs(10_000)
                // Marks every read as background: the proxy gives it its own
                // thread, paces it, and holds it off while playback is
                // starved. Without this it would queue behind the player in
                // the same eight-thread pool and compete for SMB on equal
                // terms — the exact failure this feature must not cause.
                .setDefaultRequestProperties(mapOf("X-SweepVR-Bg" to "1"))
            val ds = DefaultDataSource.Factory(context, http)
            val renderers = DefaultRenderersFactory(context)
            // Keep LOADING, and render early.
            //
            // These figures were once (200, 600, 50, 100) with a 384 KB byte
            // target and bytes taking priority. That is what caused
            // ERROR_CODE_FAILED_RUNTIME_CHECK on high-bitrate files, and the
            // cause was not contention:
            //
            // ExoPlayerImplInternal throws IllegalStateException("Playback
            // stuck buffering and not loading") once it has spent 4 s with
            // UNDER 500 ms buffered while its load control says loading is no
            // longer possible. DefaultLoadControl says exactly that — in its
            // own words, logged every poll — when the buffer target is reached
            // before 500 ms of media has arrived:
            //
            //   W/DefaultLoadControl: Target buffer size reached with less
            //     than 500ms of buffered media data.
            //
            // 384 KB is under 500 ms on anything above ~6 MB/s, so the player
            // stopped loading just below the watchdog's threshold and was
            // then declared stuck. Whether a fetch landed above or below
            // 500 ms was a coin toss, which is why one file worked on some
            // attempts and died ~5 s in on others while a slower one never
            // tripped it at all. (These films run at up to 14 MB/s, so 500 ms
            // of video is ~7 MB.)
            //
            // So the byte budget is raised well past anything we could load
            // before we pause, and maxBufferMs likewise. The load control
            // therefore never reaches its target while a fetch is in flight,
            // so it never claims "no more loading is possible", and the
            // watchdog is never armed. Meanwhile bufferForPlaybackMs stays
            // tiny so the frame we came for is rendered as soon as it exists.
            //
            // The cost is that the loader may pull a little further ahead
            // while it waits than a hard byte cap would allow — bounded, in
            // practice, by how long the first frame takes. It is all marked
            // background: paced, on its own thread, and held off entirely
            // while playback is starved.
            val load = DefaultLoadControl.Builder()
                // minBufferMs (first) must be >= bufferForPlaybackAfterRebufferMs
                // (last) or the builder throws; the earlier (100, 10_000, 100,
                // 250) violated that and the player never got built at all.
                // Hence 300 for both, which is still small enough to render
                // the frame we came for promptly.
                .setBufferDurationsMs(300, 10_000, 150, 300)
                .setTargetBufferBytes(16 * 1024 * 1024)
                .setPrioritizeTimeOverSizeThresholds(false)
                .build()
            val exo = ExoPlayer.Builder(context, renderers)
                .setLoadControl(load)
                .setMediaSourceFactory(androidx.media3.exoplayer.source.DefaultMediaSourceFactory(ds))
                .build()
            // It has to be PLAYING to render a frame at all: paused, the
            // renderer is never enabled, so after a seek ExoPlayer sits READY
            // and produces nothing — and before we attach our surface it
            // renders that first frame into a dummy surface instead. It runs
            // with no audio track selected, so "playing" means decoding and
            // rendering, nothing audible, and it is paused again the moment
            // the frame is taken.
            exo.playWhenReady = false
            exo.repeatMode = Player.REPEAT_MODE_OFF
            // Where to land is set per fetch, in seekToFetch(): a span's frame
            // must come from INSIDE that span, not from the keyframe before
            // it.
            // Video only. A preview has no sound, and an audio track left
            // selectable would be loaded off the network and decoded for
            // nobody's ears — on a preview player's tiny buffers that is a
            // real share of both the link and the CPU the film is using.
            exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_AUDIO, true)
                .build()
            exo.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_READY) landedMs = exo.currentPosition
                    FileLog.i(TAG, "preview state ${stateName(state)} pos=${exo.currentPosition / 1000}s")
                    // READY is also the first moment the video track's shape is
                    // knowable, which is what the reader needs (see
                    // attachSurface); it has nothing to do with frame timing.
                    if (state == Player.STATE_READY) attachSurface(exo)
                }

                override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                    FileLog.i(TAG, "preview videoSize ${videoSize.width}x${videoSize.height}")
                    if (videoSize.width <= 0 || videoSize.height <= 0) return
                    val px = videoSize.width.toLong() * videoSize.height.toLong()
                    if (px > MAX_FRAME_PIXELS) {
                        FileLog.w(TAG, "frame too large for a preview (${videoSize.width}x${videoSize.height}), skipping")
                        return
                    }
                    attachSurface(exo, videoSize.width, videoSize.height)
                }

                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                    FileLog.w(TAG, "preview player error: ${error.errorCodeName} ${error.message}")
                    // Not fatal to the strip. It used to clear `strip` here,
                    // which ended prebuilding for the rest of the session on
                    // the first hiccup — and the errors that land here are
                    // often transient: a decoder that could not allocate
                    // because the heap was busy succeeds minutes later, once
                    // playback has settled. So: stand down, keep the strip,
                    // and let the sweep retry below.
                    decoderFailed = true
                    decoderAttempts = 0
                    decoderFailedAt = android.os.SystemClock.elapsedRealtime()
                    if (!isTransientErrorCode(error.errorCode)) {
                        strip = null
                        FileLog.w(TAG, "preview player: unrecoverable, giving up on the strip")
                    } else {
                        FileLog.i(TAG, "preview player: transient, will retry")
                    }
                }
            })
            player = exo
            // The preview player gets the same engine-verbose treatment as
            // playback, into logcat: this is the half of the app that must not
            // be guessed at, since a stalled prebuild is otherwise invisible.
            exo.addAnalyticsListener(androidx.media3.exoplayer.util.EventLogger())
            exo.setMediaItem(MediaItem.fromUri(url))
            exo.prepare()
            FileLog.i(TAG, "preview player prepared: $url")
        } catch (e: Exception) {
            // Loud, and retried. This used to log one line and give up, which
            // made a construction-time mistake look exactly like "this film
            // has no previews" — the one failure mode this whole path is
            // supposed to be diagnosable about. A thrown IllegalArgumentException
            // here is our own configuration, not the film, and it is
            // deterministic, so it is re-thrown to the sweep as a decoder
            // failure rather than silently ending the session with nothing.
            FileLog.e(TAG, "preview player failed to build: ${e.message}")
            decoderFailed = true
            decoderAttempts = 0
            decoderFailedAt = android.os.SystemClock.elapsedRealtime()
        }
    }

    /**
     * The size of the SELECTED video track, from the track format rather than
     * from [ExoPlayer.getVideoSize].
     *
     * Those are not the same thing, and only one of them can be had first:
     * the video size is reported when the decoder reports its output format,
     * and the decoder will not initialise without a surface to decode into —
     * which is the very surface whose size we are trying to learn. So asking
     * the player for its video size here waits for a frame that can never
     * arrive. The selected track's format is known at prepare, before any
     * decoder exists, so it can be read.
     */
    private fun videoSizeOf(p: ExoPlayer): Pair<Int, Int>? {
        try {
            for (g in p.currentTracks.groups) {
                if (!g.isSelected) continue
                val f = g.getTrackFormat(0)
                if (f.sampleMimeType?.startsWith("video/") != true) continue
                if (f.width > 0 && f.height > 0) return f.width to f.height
            }
        } catch (_: Exception) {}
        return null
    }

    /**
     * Hand the decoder the renderer's SurfaceTexture, and tell the renderer
     * how big the film is so its readback framebuffer can be sized.
     *
     * There is no size handshake to wait for here — the renderer owns the
     * surface and the framebuffer, so attaching is just handing over a
     * Surface it already made.
     *
     * MAIN THREAD ONLY: this reads the player's selected tracks and sets its
     * video surface, and ExoPlayer throws if either arrives from elsewhere.
     * It is called from the player's own listener and, for a surface that has
     * gone stale, posted from the sweep.
     */
    private fun attachSurface(exo: ExoPlayer, w: Int = 0, h: Int = 0) {
        val fromFormat = videoSizeOf(exo)
        val width = if (w > 0) w else fromFormat?.first ?: exo.videoSize.width
        val height = if (h > 0) h else fromFormat?.second ?: exo.videoSize.height
        if (width <= 0 || height <= 0) {
            if (!noSizeLogged) {
                noSizeLogged = true
                FileLog.w(TAG, "preview: ready but the video track's size is unknown — nothing can be captured")
            }
            return
        }
        val surf = renderer.previewSurface()
        // Only when something has actually changed. ExoPlayer reports a video
        // size on every SEEK, and handing it the same Surface again is not a
        // no-op: it takes it as a new output and rebuilds the codec around
        // it. Done unconditionally that is dozens of decoder rebuilds a
        // second, which is a freeze, not a preview.
        if (surf != null && surf === attachedSurface &&
            attachedGen == renderer.previewGen && attachedSize == (width to height)) return
        if (surf == null) {
            if (!noSizeLogged) {
                noSizeLogged = true
                FileLog.w(TAG, "preview: renderer has no capture surface (GL not ready)")
            }
            return
        }
        renderer.setPreviewSourceSize(width, height)
        attachedSurface = surf
        attachedGen = renderer.previewGen
        attachedSize = width to height
        try {
            exo.setVideoSurface(surf)
            FileLog.i(TAG, "preview surface (re)attached ${width}x$height gen=${renderer.previewGen}")
        } catch (e: Exception) {
            FileLog.w(TAG, "preview surface rejected: ${e.message}")
        }
    }

    // ---------- the sweep ----------

    /**
     * One tick of the prebuild loop, which re-arms itself every time.
     *
     * It used to be a chain — sweep, fetch, frame, sweep — where each step
     * handed the next one on. That is fragile in a way that hides itself: if
     * any hand-off is missed the whole strip stops, with nothing in the log
     * but silence, and the thread still looks healthy. Polling instead means
     * the loop cannot fall off: whatever happens to a fetch, the next tick
     * sees the state and does the right thing.
     */
    private fun sweep() {
        if (stopped) return
        val st = strip ?: return
        try {
            sweepOnce(st)
        } catch (e: Exception) {
            // A throw here would otherwise end the loop for good.
            FileLog.w(TAG, "sweep tick failed: ${e.message}")
        }
        wq?.postDelayed({ sweep() }, POLL_MS)
    }

    private fun sweepOnce(st: ThumbStrip) {
        // The preview decoder gave up. Transient failures are worth another
        // go — an allocation that could not be met while playback was busy
        // often succeeds once things settle — but not immediately, and not
        // forever.
        if (decoderFailed) {
            val waited = android.os.SystemClock.elapsedRealtime() - decoderFailedAt
            if (waited >= DECODER_GIVE_UP_MS) {
                strip = null
                FileLog.w(TAG, "preview decoder still failing after ${waited}ms — giving up")
                return
            }
            if (waited < decoderRetryDelayMs(decoderAttempts)) return
            if (!rebuildPreviewPlayer()) return
            decoderFailed = false
            decoderFailedAt = 0L
            decoderAttempts++
            return      // the new player needs a tick of its own to get going
        }
        // Playback wants the link: nothing else matters until it says.
        if (pressure) {
            if (!pressureLogged) {
                pressureLogged = true
                FileLog.i(TAG, "prebuild standing aside: playback wants the link")
            }
            return
        }
        pressureLogged = false
        // A fetch is out: the next frame belongs to it. Watchdogged below, so
        // one that never gets its frame cannot wedge the strip.
        if (pendingBucket >= 0) {
            val waited = android.os.SystemClock.elapsedRealtime() - fetchStartedAt
            if (waited > STALL_MS) {
                FileLog.w(TAG, "bucket $pendingBucket: no frame in ${waited}ms — moving on")
                pendingBucket = -1
                renderer.previewExpectBucket = -1
                cursor = pendingBucketForStall
            }
            return
        }
        // What to fetch next: the coarse pass first — one frame per group,
        // which answers every span inside it and therefore covers the film in
        // a fraction of the work — then the fine pass, one frame per span.
        var coarseGroup = -1
        val bucket: Int
        if (!finePass) {
            val g = st.nextCoarseGroup(coarseCursor)
            if (g >= 0) {
                coarseGroup = g
                bucket = (g * st.coarseSpans).coerceAtMost(st.count - 1)
            } else {
                // Coarse pass done: the whole film is answerable, now give
                // each span its own moment.
                finePass = true
                val f = st.nextWantingOwn(cursor)
                if (f < 0) { stripComplete(st); return }
                bucket = f
            }
        } else {
            val f = st.nextWantingOwn(cursor)
            if (f < 0) { stripComplete(st); return }
            bucket = f
        }
        // The grip is on a span nothing answers yet: that one goes first,
        // whatever pass is running. (request() only leaves an urgent span
        // unanswered, so there is nothing to double-check here.)
        val urgentBucket = urgent
        urgent = -1
        if (urgentBucket >= 0) {
            coarseGroup = -1
            fetch(st, urgentBucket, -1)
            return
        }
        // Wait for the decoder and its capture surface, WITHOUT advancing the
        // cursor: walking the pass forward while the player is still coming up
        // would spend the whole strip on empty retries.
        if (player == null) {
            if (!waitingLogged) {
                waitingLogged = true
                FileLog.i(TAG, "waiting for the preview player")
            }
            return
        }
        // A GL context rebuild makes the capture surface stale, and a player
        // still holding the dead one decodes into nothing. Re-attach before
        // asking for a frame rather than discovering it as a timeout. Posted,
        // because attaching reads the player's tracks and sets its surface,
        // which is main-thread-only.
        if (attachedSurface == null || attachedGen != renderer.previewGen) {
            val p = player ?: return
            if (!reattachPosted) {
                reattachPosted = true
                mq.post {
                    reattachPosted = false
                    if (!stopped) attachSurface(p)
                }
            }
            if (!waitingLogged) {
                waitingLogged = true
                FileLog.i(TAG, "waiting for the capture surface")
            }
            return
        }
        waitingLogged = false
        urgent = -1
        fetch(st, bucket, coarseGroup)
    }

    private fun stripComplete(st: ThumbStrip) {
        if (loggedComplete) return
        loggedComplete = true
        FileLog.i(TAG, "strip complete: $built spans, $fetched fetched this run, " +
            "${st.coverage() * 100}% answerable")
        onStripDone?.invoke()
    }

    /**
     * Ask for one preview. [coarseGroup] >= 0 means this is the first pass:
     * the frame stands for every span in that group and is stored once, under
     * the group's key, so the card can find it from any of them.
     */
    private fun fetch(st: ThumbStrip, bucket: Int, coarseGroup: Int) {
        val p = player ?: return
        pendingBucket = bucket
        pendingCoarse = coarseGroup
        // Nothing the decoder produces from here until the player is actually
        // at the target is worth keeping.
        renderer.previewSeekArmed = false
        pendingBucketForStall = bucket
        fetchStartedAt = android.os.SystemClock.elapsedRealtime()
        renderer.previewExpectBucket = bucket
        // MILLISECONDS: Player.seekTo takes ms. Multiplying here sent every
        // preview to the wrong part of the film entirely.
        val spanMs = if (coarseGroup >= 0) st.bucketMs * st.coarseSpans else st.bucketMs
        val tMs = if (coarseGroup >= 0) st.coarseTimeMs(coarseGroup) else st.timeMs(bucket)
        askedFor = tMs
        mq.post {
            if (stopped) return@post
            try {
                // Tolerance FORWARD only, and no more than half the span.
                //
                // Left at CLOSEST_SYNC — the obvious choice — every preview is
                // the nearest keyframe at or BEFORE the span's middle, so every
                // preview is up to one GOP early: five seconds at the fine
                // spacing, fifteen at the coarse one. That reads as a strip
                // showing things happening before the video does, worst while
                // the coarse pass is still the only answer. Forward-only
                // tolerance picks the first keyframe at or after the middle,
                // which is inside the span whenever one exists there — the
                // right moment rather than a moment just before it. If a span
                // has no keyframe in its second half, the earlier frame is
                // still the best answer there is.
                p.setSeekParameters(SeekParameters(0L, spanMs / 2 * 1000L))
                p.seekTo(tMs)
                // NOT played here. seekTo is asynchronous, so playing straight
                // after it resumes at the old position and hands us that frame
                // as if it were the new one. Wait until the player reports
                // being at the target, then play for exactly one frame — the
                // pause comes the moment the readback lands.
                awaitLanded = true
                landingTries = LAND_POLL_TRIES
                mq.postDelayed({ checkLanded(p, tMs, spanMs) }, LAND_POLL_MS)
            } catch (e: Exception) {
                FileLog.w(TAG, "seek failed: ${e.message}")
            }
        }
    }

    /**
     * The renderer read a preview frame back from GL and handed us the bytes.
     * Runs on the GL thread, so everything expensive — the crop, the colour
     * shuffle, building the bitmap, writing the jpeg — is handed straight to
     * the worker rather than done here.
     */
    private fun onPreviewPixels(bucket: Int, rgba: ByteArray, w: Int, h: Int) {
        if (stopped || bucket < 0 || bucket != pendingBucket) {
            if (!stopped && !dropLogged) {
                dropLogged = true
                FileLog.i(TAG, "frame ignored: bucket=$bucket pending=$pendingBucket")
            }
            return
        }
        pendingBucket = -1
        renderer.previewExpectBucket = -1
        // One frame was all this fetch was for; stop decoding until the next
        // one is asked for. Playback continues from a paused player without
        // re-reading anything.
        val p = player
        if (p != null) mq.post { if (!stopped) try { p.pause() } catch (_: Exception) {} }
        val group = pendingCoarse
        wq?.post {
            if (stopped) return@post
            val split = currentSplit
            val crop = split.crop(w, h)
            val out = IntArray((crop[2] - crop[0]) * (crop[3] - crop[1]))
            // flipRows = true: glReadPixels hands back rows bottom-up, and
            // the frame this pass draws has not reversed that. Verified
            // against a real capture, which came out upside down without it.
            val argb = FramePixels.toArgb(rgba, w, h, crop, out, flipRows = true)
            if (argb == null) {
                strip?.onFailed(bucket)
                FileLog.w(TAG, "bucket $bucket: readback ${w}x$h could not be cropped")
                sweep()
                return@post
            }
            val cw = crop[2] - crop[0]
            val chh = crop[3] - crop[1]
            val bmp = Bitmap.createBitmap(argb, cw, chh, Bitmap.Config.ARGB_8888)
            fetched++; built++
            val key = ThumbMemory.keyFor(bucket, group)
            if (group >= 0) strip?.onCoarseBuilt(group) else strip?.onBuilt(bucket)
            if (group >= 0) coarseCursor = (group + 1).coerceAtMost(strip?.coarseCount ?: 0)
            else cursor = (bucket + 1).coerceAtMost(strip?.count ?: 0)
            capturedAt[key] = askedFor
            val saved = store.save(key, bmp)
            // Progress, not a firehose: 577 buckets would be 577 lines.
            if (fetched == 1 || fetched % 25 == 0 || group >= 0) {
                val asked = if (group >= 0) strip?.coarseTimeMs(group) else strip?.timeMs(bucket)
                FileLog.i(TAG, "previews ${(strip?.coverage() ?: 0f) * 100}%, $built/" +
                    "${strip?.count} held, ${store.size() / 1024}KB, bucket $bucket" +
                    (if (group >= 0) " coarse$group" else "") +
                    " asked=${asked}ms landed=${landedMs}ms, ${w}x$h -> ${cw}x$chh, saved=$saved")
            }
            // Ownership goes to the renderer from here; it recycles the last.
            onThumb(bucket, bmp)
            wq?.postDelayed({ sweep() }, PREBUILD_GAP_MS)
        }
    }

    /**
     * A preview that is already held: read it and hand it over. Looks for the
     * span's own preview first, then the coarse frame covering it — which is
     * what makes the first pass useful instead of merely fast.
     */
    private fun showFromDisk(bucket: Int) {
        val st = strip ?: return
        // The span's own preview if the fine pass has reached it, otherwise
        // the coarse frame that covers it — one frame every 30 s until the
        // refinement catches up, which is a better answer than none.
        val own = ThumbMemory.keyFor(bucket, -1)
        val coarse = ThumbMemory.keyFor(-1, st.groupOf(bucket))
        val key = if (store.has(own)) own else coarse
        val bmp = store.load(key) ?: return
        val want = st.timeMs(bucket)
        val got = capturedAt[key] ?: -1L
        FileLog.i(TAG, "card span $bucket wants ${want}ms, showing frame from " +
            "${got}ms (key $key${if (key == coarse) " coarse" else ""}, off by " +
            "${got - want}ms)")
        onThumb(bucket, bmp)
    }

    private fun stateName(state: Int): String = when (state) {
        Player.STATE_IDLE -> "idle"
        Player.STATE_BUFFERING -> "buffering"
        Player.STATE_READY -> "ready"
        Player.STATE_ENDED -> "ended"
        else -> "?$state"
    }

    /** Kept beside the strip so the crop rule always matches what was built. */
    @Volatile var currentSplit: ThumbSplit = ThumbSplit.FULL
        private set
}