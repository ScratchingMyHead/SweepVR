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
         *  before playing anyway. 40 × 60 ms is ~2.4 s, past any sane rebuffer. */
        const val LAND_POLL_MS = 60L
        const val LAND_POLL_TRIES = 40
        /** Frames are capped by their own area: beyond this the decoded
         *  plane buffer alone is tens of MB, and the heap belongs to playback. */
        const val MAX_FRAME_PIXELS = 12_000_000L
    }

    /** Where the strip stands, for the status line and the tests. */
    @Volatile var strip: ThumbStrip? = null
        private set

    @Volatile private var pressure = false
    @Volatile private var stopped = false
    @Volatile private var urgent = -1

    private var player: ExoPlayer? = null
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
        // Drop whatever was being fetched. The grip has moved on, so that
        // frame is for somewhere the user is no longer, and holding the
        // decoder on it means the bucket they ARE pointing at waits longer.
        if (pendingBucket >= 0 && pendingBucket != bucket) {
            pendingBucket = -1
            pendingCoarse = -1
            renderer.previewExpectBucket = -1
        }
        // Answerable already — by its own preview, or by the coarse frame that
        // covers it — then show it. Asking for a finer one mid-drag would
        // stall the card on a fetch the strip will get to anyway.
        if (!st.answered(bucket)) {
            urgent = bucket
            return
        }
        wq?.post {
            if (!stopped) showFromDisk(bucket)
        }
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
            // One frame's worth of buffer and no more. This player wants a
            // single keyframe, and the buffer it asks for before it will
            // render one IS what it reads: at 2 MB it pulled two to three
            // megabytes per preview to show a two-hundred-kilobyte frame, so a
            // strip of them read gigabytes and took minutes. These figures
            // ask for roughly one 512 KiB window, which is also the proxy's
            // own read granularity.
            val load = DefaultLoadControl.Builder()
                .setBufferDurationsMs(200, 600, 50, 100)
                .setTargetBufferBytes(384 * 1024)
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
                    strip = null      // no point sweeping for a strip we cannot read
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
            FileLog.w(TAG, "preview player failed: ${e.message}")
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