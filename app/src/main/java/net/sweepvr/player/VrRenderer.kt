/* SweepVR Player — GPL-3.0-only.
 * See LICENSE in the repository root.
 */
package net.sweepvr.player

/* ===========================================================================
 * THE RULE for every sweep control in this file. Read it before changing
 * barEntryEdge, bookEntryEdge, or anything else that decides whether a
 * control has been grabbed. The same rule is stated in full, with the
 * reasoning, at the top of sweep/SweepTypes.kt.
 * ===========================================================================
 *
 * LEEWAY IS AN EXIT TOLERANCE AND NOTHING ELSE.
 *
 *   - Entry has NO margin. Not a slop, not a pad, not a tolerance. The reticle
 *     must be ON the control.
 *   - Entry only through a nominated side. For the scrollbar that is top and
 *     bottom; for the bookmarks button, left and right.
 *   - The angle of attack is irrelevant and must NOT be read. Do not decide
 *     the entry side from the previous position, and do not compare the
 *     direction of travel. Only the position AT WHICH the reticle enters
 *     decides.
 *
 * Why this is written down: every one of those three has been got wrong here,
 * repeatedly, and each wrong version felt plausible while making the control
 * fire when the user was NEXT TO it rather than on it. A margin on entry does
 * not make a control forgiving, it makes it lie about where it is.
 *
 * If a grab feels unreliable, the fix is the entry SIDE - a wider target, a
 * padded hit rect or a "helpful" tolerance are all ways of re-breaking this.
 * =========================================================================== */

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import kotlin.math.roundToInt
import java.util.Locale
import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLUtils
import android.opengl.Matrix
import com.google.vr.sdk.base.Eye
import com.google.vr.sdk.base.GvrView
import com.google.vr.sdk.base.HeadTransform
import com.google.vr.sdk.base.Viewport
import net.sweepvr.player.sweep.Rect
import net.sweepvr.player.thumbs.FramePixels
import net.sweepvr.player.thumbs.ThumbStrip
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig

/**
 * GVR/Cardboard stereo renderer (see A-method-to-render-VR-videos…md).
 *
 * VIDEO: ExoPlayer frame (OES texture) on plane / dome sphere, drawn with
 * the per-eye projection from Eye.getPerspective() and the eye view
 * (head tracking + IPD) from Eye.getEyeView(); lens distortion is the
 * Cardboard pass inside the SDK.
 * BROWSER: Canvas-rendered file/settings list on a world-locked panel.
 * Play menu: world-locked button panel (doc §7) with the app's own
 * leaky-integrator dwell, head-locked reticle / tooltip / toast (§8).
 *
 * Gaze is a real head-forward ray intersected with the panel plane, so the
 * head-locked reticle actually hovers rows. Staring [dwellMs] activates.
 * All optics (FOV scale, IPD, swap, zoom, panel distance) are live fields
 * fed from SettingsStore by the activity.
 */
class VrRenderer(
    private val onBrowserActivate: (Int, Float?) -> Unit,
    private val onMenuEvent: (MenuEvent) -> Unit = {},
    private val onWebEvent: (WebEvent) -> Unit = {}
) : GvrView.StereoRenderer, SurfaceTexture.OnFrameAvailableListener {

    enum class Mode { BROWSER, VIDEO, WEB }

    /** Web-mode events out to the activity (all marshalled to the UI thread
     *  there). HitTest is a question (answer with [onWebHit]), the rest are
     *  commands. */
    sealed class WebEvent {
        /** Ask the page what lives at page pixel (px, py). */
        data class HitTest(val token: Int, val px: Float, val py: Float) : WebEvent()
        /** Dwell finished on a link/control: activate it. */
        data class Click(val px: Float, val py: Float) : WebEvent()
        /** Scrollbar arrow dwell: -1 = page up, +1 = page down. */
        data class Scroll(val dir: Int) : WebEvent()
        /** Toolbar momentary buttons. */
        object GoBack : WebEvent()
        object GoForward : WebEvent()
        object ReloadPage : WebEvent()
        object GoHome : WebEvent()
        object OpenMenu : WebEvent()
        /** Web panel zoom stepper: -1 = text smaller, +1 = text bigger. */
        data class TextZoom(val dir: Int) : WebEvent()
        /** Scrollbar arrow entry nudge: one small relative step, no native
         *  key dispatch (which would jump a full page and defeat it). */
        data class Nudge(val dir: Int) : WebEvent()
        /** Scrollbar arrow hold past its pause: glide smoothly until stop. */
        data class GlideStart(val dir: Int) : WebEvent()
        /** Scrollbar arrow released (or surface gone): halt any glide. */
        object GlideStop : WebEvent()
        /** Dwell on inert page: open (true) or close (false) the web panel. */
        data class Panel(val open: Boolean) : WebEvent()
        /** PgUp/PgDn button on the web panel: -1 up, +1 down. */
        data class PageKey(val dir: Int) : WebEvent()
        /** Bookmarks flyout commit: load this url and close the panel. */
        data class Navigate(val url: String) : WebEvent()
        /** Scrollbar drag released: put the page at this fraction of its
         *  scrollable range, 0 = top, 1 = bottom. */
        data class ScrollTo(val frac: Float) : WebEvent()
    }

    /** The GvrView we render into, set by the activity. Only used for
     *  recentering (GL thread reads, activity may swap it any time). */
    @Volatile var gvrView: GvrView? = null

    /** Play-menu actions. Press(idx) index map (menuButtons ids):
     *  0 settings, 1 shape, 2 files, 3 prev, 4 rew, 5 play, 6 ff, 7 next,
     *  8 zoom+, 9 zoom-, 10 vol+, 11 vol-, 12 flip, 13 recenter,
     *  14 fov-, 15 fov+, 18 web, 19 cue toggle (repeat <-> autocue).
     *  16 (A/V sync) and 17 (screenpos) have no button
     *  any more, so Press(16)/Press(17) never fire.
     *  Seek(frac): jump to fraction. */
    sealed class MenuEvent {
        data class Press(val idx: Int) : MenuEvent()
        data class Seek(val frac: Float) : MenuEvent()
    }

    @Volatile var mode: Mode = Mode.BROWSER
    @Volatile var projection: Projection = Projection.DEG180
    @Volatile var stereo: Stereo = Stereo.SBS
    /** Scales the Cardboard eye field of view (0.5..1.5, 1 = profile FOV). */
    @Volatile var fovScale: Float = 1f
    /** Screen size multiplier (0.5..10, 1 = default). The video picture's
     *  own size; the browser has its own field below. */
    @Volatile var screenSize: Float = 1f
    /** Web page size multiplier (0.5..10, 1 = default), read only while
     *  mode == WEB. Separate from screenSize so Size +/- on the browser
     *  panel never moves the video. */
    @Volatile var webScreenSize: Float = 1f
    /** Flat-screen curvature (0 = plane, 1 = full cap bent around the
     *  viewer). Lives only in the FLAT projection; 0 keeps the old path. */
    @Volatile var screenCurve: Float = 0f
    /** Allow Cardboard lens distortion in the SDK (off = raw stereo). */
    @Volatile var disableDist: Boolean = false
    /** Show the gaze tooltip pill above the reticle. */
    @Volatile var enableTooltip: Boolean = true
    /** Dome mesh density: standard 30x7, high 60x15 (method doc §5.1:
     *  latitude is longitude / 4 exactly). */
    @Volatile var panoQuality: String = "vertex"
    @Volatile var swapEyes: Boolean = false
    @Volatile var zoom: Float = 1f
    @Volatile var panelDistM: Float = 2.4f
    @Volatile var dwellMs: Long = 1500L
    /** Viewer IPD in metres (GVR viewer params, default 64 mm). */
    @Volatile var ipdM: Float = 0.064f
    /** Pin video dead-ahead (screen lock); browser always look-around. */
    @Volatile var pinVideo: Boolean = false
    /**
     * Diagnostics: ignore the sensors and drive tracking with a scripted
     * sweep (yaw ±35°/10s + pitch ±12°/7s). If the image pans level in sweep
     * mode but rotates on your real head, the sensors (not the math) lie.
     */
    @Volatile var testSweep: Boolean = false
    private var lastTestSweep = false
    private var sweepT0 = 0L

    var videoTextureId: Int = -1
    /** Bumped every onSurfaceCreated. If ExoPlayer is still targeting an
     *  older surface (EGL context loss recreates it silently), its frames
     *  go nowhere: frozen picture, advancing position, zero errors. The
     *  activity watches this generation and re-attaches on change. */
    @Volatile var surfaceGen = 0
    /** Frames completed (GL thread). Watchdog reads it: advancing = GL
     *  alive; frozen + frozen video = GL stuck (GPU hang/surface stall). */
    @Volatile var frameCount = 0L
        private set
    /** Video frames actually consumed from the decoder (updateTexImage ran).
     *  The discriminator: glfps high + consumed frozen = decoder stopped
     *  delivering (input starvation/track end); both frozen = GL stalled. */
    @Volatile var consumedFrames = 0L
        private set
    /** onFrameAvailable firings (binder thread). arrivals frozen + renderer
     *  counters climbing = queue/listener stopped delivering despite output;
     *  arrivals flowing + consumed frozen = consumption broken. */
    @Volatile var arrivedFrames = 0L
        private set
    var surfaceTexture: SurfaceTexture? = null
        private set
    var surface: android.view.Surface? = null
        private set
    // Written by the BufferQueue binder thread (onFrameAvailable), read by the
    // GL thread every frame. MUST be volatile: without it the GL thread may
    // stop seeing new frames after JIT recompiles the read (seconds in) —
    // frozen video, healthy audio/position/buffers, zero errors. This exact
    // failure froze every video at varying 5-15s until found.
    @Volatile private var frameAvailable = false

    // ---------------- web (WebView -> SurfaceTexture -> OES texture) ----------------
    // The page is hosted by a WebView inside a TextureView in the activity;
    // its SurfaceTexture becomes an external GL texture sampled by the SAME
    // flat-screen path the video uses (same size, curve and zoom), so WEB
    // borrows drawVideo() wholesale rather than growing a second pipeline.
    /** Web page size in pixels (the WebView's viewport). */
    @Volatile var webPageW: Int = 1280
    @Volatile var webPageH: Int = 720
    /** Page frames uploaded (GL thread). 0 = nothing has landed: the page
     *  never rendered, or the copy failed. */
    @Volatile var webConsumedFrames: Long = 0L
        private set
    /** Hand a freshly captured page bitmap to the GL thread (UI thread).
     *  The bitmap is reused by the caller, so it is uploaded on the very
     *  next frame — the copy must not be mutated before then. */
    fun submitWebFrame(bmp: Bitmap) { webBmpPending = bmp }
    /** Answer to a [WebEvent.HitTest]: is there something clickable at the
     *  page pixel that token asked about? */
    fun onWebHit(token: Int, interactive: Boolean) {
        if (token == webHitToken) webHitInteractive = interactive
    }
    /** Page scroll state from the activity (thumb position + scrollable). */
    @Volatile var webScrollFrac: Float = 0f
    /** Visible fraction of the page (0..1) — sets the thumb's size. */
    @Volatile var webViewFrac: Float = 1f
    @Volatile var webScrollable: Boolean = false
    /** Current page URL, for the toolbar address field. Pushed every state
     *  poll alongside the scroll fractions above. */
    @Volatile var webBarUrl: String = ""
    /** Back/forward availability, for dimming the toolbar buttons. Pushed on
     *  page finish/start; stale for at most one navigation either way. */
    @Volatile var webCanGoBack: Boolean = false
    @Volatile var webCanGoForward: Boolean = false
    /** The web control panel is open (gaze drives the panel, not the page). */
    @Volatile var webPanelOpen: Boolean = false

    @Volatile private var webBmpPending: Bitmap? = null
    private var webTexId: Int = -1
    private var webBarTexId: Int = -1
    private var webToolbarTexId: Int = -1
    private var webBarBitmap: Bitmap? = null
    private var webBarMesh: Mesh? = null
    private var webBarMeshKey = ""
    @Volatile private var webBmpConsumed = 0L
    private var webHitToken = 0
    @Volatile private var webHitInteractive = false
    private var webHitAskedAt = 0L
    private var webAskPx = -1f
    private var webAskPy = -1f
    /** Page pixels of head wobble still considered "the same place", so a
     *  slightly stale hit answer is trusted rather than flickering. */
    private val WEB_HIT_SLOP = 70f
    private var webLastClickAt = 0L
    // dwell state for the page itself
    private var webProgF = 0f
    private var webProgT = 0L
    private var webTarget = ""      // "" = inert page, else "px,py"
    private var webFiredFor = "\u0000none"
    private var webPanelHold = 0f
    private var webPanelT0 = 0L
    private var webPanelLockUntil = 0L
    @Volatile private var webPanelPinned = false
    /** Sweep-entry tracing. Off unless -Ddebug.book=1 is set at launch, so
     *  it can be turned on and off without rebuilding the APK. */
    @Volatile var bookDbg: Boolean =
        true // TEMP: trace on
    /** TEMP DEBUG: where the gaze samples, for the on-page crosshair. */
    @Volatile var webDebugPoint: FloatArray? = null
    private var webDbgT0 = 0L
    @Volatile var webDbgOn: Boolean = false // TEMP DEBUG
    private var webScrollDir = 0
    private var webScrollFiredFor = 0
    private val webStill = MotionStillness(400L, 5f)

    @Volatile var browserTitle: String = "/"
    @Volatile var browserRows: List<BrowserRow> = emptyList()

    // ---- bookmarks flyout (web panel only) ----
    /** Bookmarks to show in the flyout, label to url, in order. Separate
     *  from browserRows because the icon is one row and the pane is drawn
     *  over the rows beneath it. */
    @Volatile var webBookList: List<Pair<String, String>> = emptyList()
    /** Which row opens the flyout when entered horizontally, or -1. */
    @Volatile var webBookIconRow: Int = -1
    @Volatile private var bookOpen = false
    @Volatile private var bookCursor = -1
    /** True once the flyout has swallowed a frame: while set, normal row
     *  dwell is suspended and the panel cannot close under the gesture. */
    @Volatile private var bookActive = false
    /** How far past the pane edge (in reticle widths) before an exit counts.
     *  Clamped at runtime to the panel's own margin - see bookTolPx(). */
    private var bookTolRet = 5f
    /** First visible row when the list overflows. */
    private var bookScroll = 0
    /** The shared sweep control. Owns all the dropdown behaviour; the
     *  renderer only supplies geometry and draws the result. */
    private val bookControl = net.sweepvr.player.sweep.DropdownControl()
    /** Text-zoom steppers flanking the bookmarks icon: zoom-out (minus) on
     *  the left, zoom-in (plus) on the right. RepeatControls with the same
     *  side-entry rule as the icon itself, repeating TextZoom steps like
     *  the scrollbar arrows repeat scrolls. */
    private val zoomOutCtl = net.sweepvr.player.sweep.RepeatControl(400L, 300L)
    private val zoomInCtl = net.sweepvr.player.sweep.RepeatControl(400L, 300L)
    /** Last panel point fed to the control, used only to synthesise an
     *  off-plane exit when the gaze turns away from the panel entirely. */
    private var bookLastX = TEX * 0.5f
    private var bookLastY = TEX * 0.5f
    private val bookMeasurePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    /** Which scroll arrow the reticle is on, 0 none, -1 up, +1 down. */
    private var bookArrow = 0
    /** A row can carry a gaze slider: dwelling at horizontal fraction u
     *  sets value = min + u·(max-min). One dwell reaches any value. */
    /** Display format for a gaze slider's live tooltip: display value =
     *  raw * scale + offset, snapped to the snap grid (0 = no snap),
     *  rendered with decimals places plus suffix. Mirrors handleSlide. */
    data class SlideFormat(
        val suffix: String = "",
        val decimals: Int = 0,
        val scale: Float = 1f,
        val offset: Float = 0f,
        val snap: Float = 0f
    )
    data class BrowserRow(
        val label: String, val meta: String, val kind: Int,
        val slideKey: String? = null,
        val slideMin: Float = 0f,
        val slideMax: Float = 1f,
        val slideVal: Float = 0f,
        val slideFmt: SlideFormat? = null,
        val segLabels: List<String> = emptyList(),
        val segActions: List<String> = emptyList(),
        val segSelected: Int = -1,
        val previewMags: FloatArray? = null, // shaping preview: per-point 0..1
        val previewHull: IntArray? = null, // shaping preview: convex-hull indices
        val previewN: Int = 9, // shaping preview grid size
        val previewPos: FloatArray? = null, // shaping preview: absolute [x,y] per point, [0,1], y down
        val dead: Boolean = false // rest zone: hover drains, never accumulates or fires
    ) {
        companion object {
            const val FOLDER = 0; const val VIDEO = 1; const val FILE = 2; const val ACTION = 3
        }
    }

    // highlight index into FULL rows list
    private var highlight = -1
    /** One-shot row to select on the next listing (consumed by the rows-
     *  replacement reset below): opening the file browser over a video
     *  highlights the playing file and ensureVisible scrolls it into view.
     *  Selection only - dwell still starts from zero, so it never fires. */
    @Volatile var revealHighlight = -1
    /** Rows list the dwell state was last computed against. A new object
     *  means a new listing (entered a directory, opened the panel): all
     *  gaze accumulators reset so nothing fires on arrival. */
    private var lastDwellRows: List<BrowserRow>? = null
    /** Top edge of the rows window, in row units. File pages glide it
     *  fractionally while a scroll strip is engaged; settings pages snap
     *  it to integers via ensureVisible. Counts from the first SCROLLING
     *  row (after the pinned top rows). */
    private var scrollPos = 0f
    /** Pinned top rows: file pages pin home + up = 2 (scroll strips on),
     *  settings/shaping/sensor pages 0 (plain window, no strips). Set by
     *  the activity per page in pushRows. */
    @Volatile var pinTopRows = 0
    /** PgUp/PgDn buttons, right side of the title bar. Web panel only: the
     *  file and SMB lists have no page keys to send. */
    @Volatile var webSideBtns = false
    private var sideBtnDir = 0
    private var sideBtnProg = 0f
    private var sideBtnFired = false
    /** Scroll-strip state (GL thread): 0 idle, -1 scrolling up, +1 down. */
    private var scrollEngage = 0
    /** Strip currently earning trigger progress (same sign convention). */
    private var scrollTrigDir = 0
    /** Trigger progress 0..1: short still-gaze on a strip engages it. */
    private var scrollTrigF = 0f
    private var dwellStart = 0L
    private var dwellFiredFor = -2
    // X close button (title bar, top right): own dwell state, fires sentinel -10.
    // Hit zone in panel TEX coords: x > 920, y < TITLE_Y1 (title bar).
    private var inXZone = false
    private var xProgF = 0f
    private var xDwellFired = false
    // Diagnostic: log once per latch episode when a completed dwell is
    // suppressed by the fired latch (tells stuck-latch from no-dwell).
    private var fireBlockedLogged = false
    fun tapSelect() { val h = highlight; if (h in browserRows.indices) onBrowserActivate(h, null) }
    fun moveHighlight(d: Int) {
        val n = browserRows.size
        if (n == 0) return
        highlight = ((if (highlight < 0) n / 2 else highlight) + d).coerceIn(0, n - 1)
        ensureVisible()
        dwellStart = now(); dwellFiredFor = -2
    }

    // Head pose from GVR (doc §3): headView = world→head (HeadTransform),
    // headWorld = headView · N where N is the constant §3 basis shift that
    // puts the viewer 0.01 m in front of the world origin. invHeadWorld
    // turns the head-forward ray into a world ray for gaze. The monitor is
    // headViewM: stillness gates and copies lock on it.
    private val headViewM = FloatArray(16).also { Matrix.setIdentityM(it, 0) }
    private val headWorldM = FloatArray(16).also { Matrix.setIdentityM(it, 0) }
    private val invHeadWorldM = FloatArray(16).also { Matrix.setIdentityM(it, 0) }
    /** N (doc §3): translate the viewer 0.01 m along -Z of world space.
     *  Frozen — the live basis below is re-folded against it on recenter. */
    private val basisShiftN = FloatArray(16).also {
        Matrix.setLookAtM(it, 0, 0f, 0f, 0.01f, 0f, 0f, 0f, 0f, 1f, 0f)
    }
    /** appWorld→trackerWorld, initially just N. Recenter overwrites it with
     *  headView⁻¹·N so the CURRENT head pose — yaw, pitch AND roll — becomes
     *  the app world (see the recenterPending block in frameTick). */
    private val basisShiftM = FloatArray(16).also {
        System.arraycopy(basisShiftN, 0, it, 0, 16)
    }
    private val tmpA = FloatArray(16)
    private val tmpB = FloatArray(16)
    /** Rendered camera-forward, refreshed every frame (for the trace recorder). */
    @Volatile var lastEffFwd = floatArrayOf(0f, 0f, -1f)
    /** Rendered head-up, refreshed every frame. Drives the menu trigger. */
    @Volatile var lastEffUp = floatArrayOf(0f, 1f, 0f)
    /** Cause tag for the next snap (entry/files/video/tap/auto). Audit trail. */
    @Volatile private var snapTag = "auto"
    /** Successful basis snaps since creation (debug overlay). */
    @Volatile var snapCount = 0
        private set
    /** Kept frames: no longer derivable with GVR tracking (stays 0). */
    @Volatile var keptFrames = 0
        private set

    /** Head orientation in world space (debug overlay / sensor page): the
     *  head→world basis (N baked in), so forward/up read off it directly. */
    fun effCopy(): FloatArray = synchronized(headViewM) { invHeadWorldM.clone() }

    /** Consume the request on the GL thread (GVR recenter is a native
     *  call, safe there) so taps and the aim dwell snap immediately. */
    @Volatile private var recenterPending = false

    /** Snap the world to the current head pose — full 3DOF (yaw, pitch and
     *  roll), consumed on the GL thread in frameTick. */
    fun recenter(why: String = "auto"): Boolean {
        if (mode == Mode.WEB) webPanelLockUntil = now() + (PANEL_TOGGLE_MS * 4).toLong()
        android.util.Log.d("SweepVR-basis", "recenter ($why)")
        try { FileLog.d("SweepVR-basis", "recenter ($why)") } catch (_: Throwable) {}
        snapTag = "auto"
        recenterPending = true
        inputGraceUntil = now() + 800
        return true
    }

    /** Re-center on the next frame (enter VR / play). */
    fun resetBasis(tag: String = "auto") {
        snapTag = tag
        recenterPending = true
        inputGraceUntil = now() + 2500
    }

    @Volatile private var inputGraceUntil = 0L

    private var progOes = 0; private var prog2d = 0; private var progWeb = 0
    /** Method-doc video program (§§3-7): homogeneous texcoords, projective
     *  divide in-shader (vertical crop uploads 0), stereo halves in
     *  texcoord buffers, zoom as a Z translate in the zoomPan matrix.
     *  Replaces the old per-eye-half shader. */
    private var progVideo = 0
    private var aPositionVid = 0; private var aTexCoordVid = 0
    private var uMvpVid = 0; private var uVideoTexVid = 0; private var uCropVid = 0
    private var uTexMatVid = 0; private var uTexTransVid = 0
    // Fisheye circle-sampling path (§8): same vertex shader, dedicated frag.
    private var progFish = 0
    private var aPosFish = 0; private var aTexFish = 0; private var uMvpFish = 0
    private var aShapeFish = -1
    private var uTexFish = 0; private var uStereoFish = 0; private var uEyeFish = 0
    private var uTexMatFish = 0; private var uZoomOutFish = 0
    private var uFishC = 0; private var uFishR = 0; private var uFishMirror = 0
    private var aPos2d = 0; private var aTex2d = 0; private var uMvp2d = 0; private var uTex2d = 0
    private var uAlpha2d = 0
    private var aPosWeb = 0; private var aTexWeb = 0
    private var uMvpWeb = 0; private var uTexWeb = 0; private var uZoomWeb = 0

    /** Video geometry set (method doc §§5-6): strip positions plus five
     *  baked texcoord buffers each (2-D, SBS eye 1/2, TB eye 1/2). Rebuilt
     *  when density, curve dims or shaping change - all small, all rare. */
    private var videoGeom: VideoGeom? = null
    private var videoGeomKey = ""
    /** Web screen mesh: today's plain indexed grid (unshaped), shared by
     *  drawWeb and the gaze mesh lookup. Separate object from the video
     *  geometry above, so neither path can disturb the other. */
    private var webMesh: Mesh? = null
    private var webMeshKey = ""
    private var browserTexId = -1
    private var browserBitmap: Bitmap? = null
    private var lastPanelHash = 0
    private var reticleTexId = -1
    /** Blue twin of the dwell reticle, for the recenter aim pointer. */
    private var aimTexId = -1

    /** Per-eye projections from Eye.getPerspective() plus the convergence
     *  trim, and the per-eye view O = eye.getEyeView() · N (doc §3). ov is
     *  the MVP base for every panel, the reticle and the FLAT screen;
     *  domeOv is that same view under projDomeM — the FOV-scaled panoramic
     *  projection — and feeds the dome/fisheye video only (§6.3). */
    private val projM = FloatArray(16)
    private val eyeViewNM = FloatArray(16)
    /** Head-based view O = headView·N for the dome (§4.3: the sphere
     *  surrounds the viewer, so the eye offset must NOT apply - it tears
     *  the image across the seam and shifts the poles per eye. Stereo on
     *  the dome comes solely from the asymmetric per-eye projection). */
    private val headViewNM = FloatArray(16)
    private val ovM = FloatArray(16)
    private val ptrTmp3 = FloatArray(3)
    private val projDomeM = FloatArray(16)
    private val domeOvM = FloatArray(16)
    /** Eye translation in head space (eyeView · headView⁻¹), pinVideo only. */
    private val eyeShiftM = FloatArray(16)
    private val modelM = FloatArray(16)
    /** Convergence trim: uniform clip-space offset per eye. An m[8]
     *  addition shifts the image by minus that amount in NDC x, so the
     *  left eye takes -ct: positive trim converges the halves (§10).
     *  Baseline -0.040; live-tunable after. */
    @Volatile var convTrimNdc = -0.04f
    /** Play-menu trigger tilts: up opens the top menu, down the bottom menu. */
    @Volatile var menuAngleUp = 40f
    @Volatile var menuAngleDown = -40f
    /** Which side the play menu lives on; flipped by its ⇅ button (persisted). */
    @Volatile var menuSideUp = true
    @Volatile private var menuAnimFrom = 52f
    @Volatile private var menuAnimT0 = 0L
    private val menuAnimMs = 350L
    /** Flip top<->bottom with a quick visible sweep through the middle. */
    fun menuToggleSide() {
        menuAnimFrom = menuElevCurrent()
        menuSideUp = !menuSideUp
        menuAnimT0 = now()
    }
    /** Browser panel elevation (deg): 0 = centered at horizon (file
     *  browsing), halfway to the play menu when floating over video. */
    @Volatile var browserElevDeg: Float = 0f
    /** Elevation for browser panels floating over live video: halfway
     *  between center (horizon) and the play-menu panel. */
    fun overlayElevDeg(): Float = menuElevDeg() / 2f
    /** True while the play menu is shown (VIDEO mode only). */
    @Volatile var menuOpen = false
    /** Transient in-VR message on the menu panel (Toasts are invisible
     *  in the headset). Activity sets text; visible ~2s. */
    @Volatile var menuFlash = ""
    @Volatile var menuFlashUntil = 0L
    fun flashMenu(msg: String, ms: Long = 2000L) {
        menuFlash = msg
        menuFlashUntil = System.currentTimeMillis() + ms
        showToast(msg, ms)
    }
    /** Center-screen 3D toast (Android Toasts are unreadable in-headset):
     *  head-locked quad in the vertical middle, auto-expiring. */
    @Volatile var toastText = ""
    @Volatile var toastUntil = 0L
    fun showToast(msg: String, ms: Long = 2500L) {
        toastText = msg
        toastUntil = System.currentTimeMillis() + ms
    }
    private var toastTexId = 0
    private var tipTexId = 0

    // ---------------- on-screen keyboard ----------------
    //
    // A floating window, head-locked below whatever panel is up, so one
    // keyboard serves the web page and the file-manager panel without either
    // having to own it. Head-locked rather than anchored to a field for the
    // first cut: it needs no coordinate conversion from either surface, and
    // anchoring is a small change once the gesture itself is proven.
    //
    // It is deliberately NOT subject to menuUsable()'s "nothing dwells when
    // sweep is on". The keyboard has no dwell to lose - every key is swept -
    // and the rule exists to stop a dwell firing a control the user is
    // mid-gesture on. Here the whole surface is gesture.
    private val kbd = net.sweepvr.player.sweep.KeyboardControl()

    /** The dwell keyboard, used when sweep controls are off.
     *
     *  A separate instance and a separate draw path, not a mode of [kbd]: the
     *  drum's whole shape - a tumbler of rows with one live row, entry sides
     *  on every key, a band you traverse - IS the sweep gesture, and none of
     *  it survives without it.
     *
     *  Everything around the keyboard is shared: the placement is renderer
     *  state, the texture is one texture, and the text plumbing and the
     *  page-field write path are the same callbacks. */
    private val kbdw = net.sweepvr.player.sweep.DwellKeyboardControl()

    /** The address field opens the keyboard when it is swept through the same
     *  way every key is: in through its top or bottom, out through the far
     *  one. It is deliberately NOT a dwell - under sweep, nothing on a panel
     *  dwells, and the keyboard is the one surface where that rule has to be
     *  suspended because there is no dwell to lose. */
    private val addrOpen = net.sweepvr.player.sweep.SweepEngine()
    /** The address field as a dwell target, for when sweep is off.
     *
     *  Without this the keyboard is UNREACHABLE with sweep disabled: the field
     *  is the only thing that opens it, and its only gesture was a sweep. A
     *  setting that turns sweep off must not also remove the only way in. */
    /** The address field as a dwell target.
     *
     *  NOT stepped in [stepAddrField]: with sweep off, toolbarStep returns into
     *  toolbarDwell and never reaches the sweep address logic at all, so a
     *  dwell implementation placed there is dead code. It belongs with the
     *  other dwell buttons, and its absence there is why the field opened the
     *  keyboard with no visible charge. */
    private val addrDwell = net.sweepvr.player.sweep.DwellButton()
    private var addrArmed = false
    private var kbTexId = 0
    private var kbBitmap: Bitmap? = null
    private var kbKey = ""
    private var kbOpen = false
    private var kbAlpha = 0f
    private var kbAlphaWant = false
    /** Gaze in the keyboard's own pixels while it owns the frame, else null. */
    private var kbHit: FloatArray? = null

    // ---- hover labels ----------------------------------------------------
    //
    // A plain dwell on the key, with NO relation to the sweep rules: it fires
    // whichever side you came from, including sides the key does not admit.
    // That is the point. The entry dips only show the sides that work, so a
    // key reached from any other angle looks completely inert, and naming it
    // is the only way to find out what it is before trying the right edge.
    private var kbTipKey: String? = null
    private var kbTipAt = 0L
    private var kbTipShown: String? = null

    /** How long a key must be hovered before it is named. Long enough not to
     *  flash on a sweep that merely crosses a key on its way somewhere else. */
    private val KBD_TIP_DELAY = 480L

    /**
     * Angular half-height of the keyboard, as a fraction of its DISTANCE.
     *
     *  A world size is the wrong thing to hold constant here. The keyboard used
     *  to live on a head-locked plane at panelDistM while the address field
     *  sits on the web screen at FLAT_DIST - 2.4 m against 12 m - so the two
     *  were at completely different depths and the eye had to re-focus
     *  between them on every glance. Fixing the DEPTH fixes that; keeping a
     *  fixed world size would then shrink the keyboard to nothing, so its size
     *  is held constant in ANGLE instead and the world size follows the depth.
     */
    private val kbAngleH: Float get() = KBD_HALF_H / panelDistM

    /** Half-height in metres at the depth the keyboard is actually at. */
    private var kbWorldHalfH = KBD_HALF_H
        private set
    private var kbWorldHalfW = KBD_HALF_H * (KBD_TEX_W.toFloat() / KBD_TEX_H)

    /** Where the keyboard sits in the world, and the basis it faces on. */
    private val kbPoint = FloatArray(3)
    private val kbRight = FloatArray(3)
    private val kbUp = FloatArray(3)
    private val kbNormal = FloatArray(3)
    private var kbPlaced = false
    private var kbAliveT = 0L

    /**
     * Put the keyboard just under the text field, ON THE FIELD'S OWN SURFACE.
     *
     * The web screen, when it is up: the point below the toolbar, pushed down
     * the screen's own vertical axis so it follows the curve rather than
     * dropping straight to world-floor level. The browser panel otherwise,
     * which is the panel plane.
     */
    private fun placeKeyboard() {
        val useWeb = mode == Mode.WEB && webPagePointOk && panelDistM > 0.01f
        if (!useWeb) {
            // No web surface behind it (the file-manager panel): sit on the
            // PANEL's own plane, so the depth and the facing both match the
            // surface it belongs to.
            if (panelDistM <= 0.01f) { kbPlaced = false; return }
            val d = panelDistM
            val el = Math.toRadians(browserElevDeg.toDouble()).toFloat()
            val ce = kotlin.math.cos(el); val se = kotlin.math.sin(el)
            val cy = se * d; val cz = -ce * d
            kbWorldHalfH = kbAngleH * d
            kbWorldHalfW = kbWorldHalfH * (KBD_TEX_W.toFloat() / KBD_TEX_H)
            val halfW = panelHalfW()
            val hh = panelHalfH() * panelHc() / TEX
            val sx = halfW * 0.86f
            kbPoint[0] = sx
            kbPoint[1] = cy - hh - kbWorldHalfH - KBD_TOP_GAP
            kbPoint[2] = cz
            // The panel basis, as drawBrowser builds it: right = (1,0,0),
            // up = n x right, n = the inward normal.
            kbRight[0] = 1f; kbRight[1] = 0f; kbRight[2] = 0f
            kbUp[0] = 0f; kbUp[1] = ce; kbUp[2] = se
            kbNormal[0] = 0f; kbNormal[1] = se; kbNormal[2] = ce
            kbPlaced = true
            return
        }
        if (useWeb) {
            // v = TB_V0 is the toolbar's LOWER edge: the boundary the keyboard
            // hangs from.
            // The address bar keeps its own placement: the toolbar's lower
            // edge, dead centre. Only a field sets the anchor, and TB_V0 is
            // declared further down the file so it cannot be used to
            // initialise a property up here.
            val fieldEd = kbTarget == KeyboardTarget.PAGE_FIELD
            webPointAt(if (fieldEd) kbAnchorU else 0.5f,
                       if (fieldEd) kbAnchorV else TB_V0, kbPoint)
            val dTop = kotlin.math.sqrt(
                (kbPoint[0] - invHeadWorldM[12]).let { it * it } +
                    (kbPoint[1] - invHeadWorldM[13]).let { it * it } +
                    (kbPoint[2] - invHeadWorldM[14]).let { it * it })
            if (dTop < 0.2f) { kbPlaced = false; return }
            // The screen's downward direction, read off the surface itself so
            // it follows the curve. v = 1 is the TOP of the page and v = 0 the
            // bottom, so "down" is bottom MINUS top. It was written the other
            // way round and labelled downward, which hung the keyboard ABOVE
            // the field it belongs to.
            val ty = FloatArray(3); val by = FloatArray(3)
            webPointAt(if (fieldEd) kbAnchorU else 0.5f, 1f, ty)
            webPointAt(if (fieldEd) kbAnchorU else 0.5f, 0f, by)
            var dx = by[0] - ty[0]; var dy = by[1] - ty[1]; var dz = by[2] - ty[2]
            val dl = kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
            if (dl > 1e-4f) { dx /= dl; dy /= dl; dz /= dl } else { dy = -1f; dz = 0f; dx = 0f }
            val depth = dTop
            kbWorldHalfH = kbAngleH * depth
            kbWorldHalfW = kbWorldHalfH * (KBD_TEX_W.toFloat() / KBD_TEX_H)
            val off = kbWorldHalfH + KBD_TOP_GAP * (depth / panelDistM)
            kbPoint[0] += dx * off; kbPoint[1] += dy * off; kbPoint[2] += dz * off

            // Face like the SCREEN, not like the head. Billboarding it turned
            // the keyboard to follow every head movement, which reads as it
            // spinning about its own centre; anchored to the browser it is
            // part of the surface instead.
            //
            // The basis is read off the surface at four points, so it matches
            // the drawn screen exactly - including the curve - rather than
            // approximating its tilt.
            val lf = FloatArray(3); val rt = FloatArray(3)
            webPointAt(0f, 0.5f, lf)
            webPointAt(1f, 0.5f, rt)
            var rx = rt[0] - lf[0]; var ry = rt[1] - lf[1]; var rz = rt[2] - lf[2]
            val rl = kotlin.math.sqrt(rx * rx + ry * ry + rz * rz)
            if (rl < 1e-4f) { kbPlaced = false; return }
            rx /= rl; ry /= rl; rz /= rl
            // Screen up is the negated down vector already computed.
            val ux = -dx; val uy = -dy; val uz = -dz
            // normal = right x up, which points at the viewer.
            var nx = ry * uz - rz * uy
            var ny = rz * ux - rx * uz
            var nz = rx * uy - ry * ux
            val nl = kotlin.math.sqrt(nx * nx + ny * ny + nz * nz)
            if (nl < 1e-4f) { kbPlaced = false; return }
            nx /= nl; ny /= nl; nz /= nl
            kbRight[0] = rx; kbRight[1] = ry; kbRight[2] = rz
            kbUp[0] = ux; kbUp[1] = uy; kbUp[2] = uz
            kbNormal[0] = nx; kbNormal[1] = ny; kbNormal[2] = nz
            kbPlaced = true
            return
        }
    }

    private var lastToastText = ""
    private var lastToastActive = false
    private fun maybeUploadToast() {
        val active = toastText.isNotEmpty() && now() < toastUntil
        if (active == lastToastActive && toastText == lastToastText && toastBitmap != null) return
        lastToastActive = active
        lastToastText = toastText
        val W = 512; val H = 112
        val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.TRANSPARENT)
        if (active) {
            val p = Paint(Paint.ANTI_ALIAS_FLAG)
            p.color = Color.argb(220, 10, 14, 22)
            c.drawRoundRect(4f, 4f, (W - 4).toFloat(), (H - 4).toFloat(), 24f, 24f, p)
            p.color = Color.WHITE; p.textSize = 40f; p.textAlign = Paint.Align.CENTER
            c.drawText(toastText.take(34), W / 2f, 70f, p)
            p.textAlign = Paint.Align.LEFT
        }
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, toastTexId)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
        toastBitmap?.recycle()
        toastBitmap = bmp
    }
    private var toastBitmap: Bitmap? = null
    /** Toast pill, head-locked in 3D so it is a STEREO pair: the same
     *  head-space quad path as the reticle/tooltip (ov through invHeadWorld),
     *  centred on the gaze ray at panel depth. The old path drew an NDC quad
     *  through the identity matrix — the identical image in both eye
     *  viewports, zero disparity, which fuses into ONE flat picture glued to
     *  the screen. */
    private fun drawToast() {
        maybeUploadToast()
        if (toastText.isEmpty() || now() >= toastUntil) return
        val k = menuScale()
        putQuad(ptrVerts, ptrTex, -TOAST_HALF_W * k, -TOAST_HALF_H * k, TOAST_HALF_W * k, TOAST_HALF_H * k)
        headLockedAt(tmpA, 0f, 0f, -panelDistM)
        Matrix.multiplyMM(mvpM, 0, ovM, 0, tmpB, 0)
        drawQuadTex(toastTexId, mvpM)
    }

    /** Playback position/duration/state for the menu progress bar. */
    @Volatile var menuPosMs = 0L
    @Volatile var menuDurMs = 0L
    @Volatile var menuPlaying = true
    /** File name shown across the top of the play menu. */
    @Volatile var menuTitle = ""
    /** Rewind/fast-forward jump, seconds (2D setting). */
    @Volatile var skipSecs = 10
    /** End-of-media mode behind the cue toggle (id [MENU_CUE_ID]): false =
     *  repeat the same video, true = autocue the next one in the queue.
     *  Activity-owned: it reads settings and applies it to the player. */
    @Volatile var autoCue = false
    // menu gaze state (GL thread)
    private var menuHighlight = -2 // -1 = seek bar, 0..19 buttons, -2 = decorative
    private var menuDwellFiredFor = -3
    private var menuHitValid = false
    /** Where a seek from the bar would land: under the reticle, or the value
     *  in hand while the grip is dragged. The one anchor a preview thumbnail
     *  would hang off later — the position being pointed at, whoever is
     *  pointing. -1 = none. */
    private var menuSeekHoverU = -1f
    /** True for the whole grab→drop episode of the grip — entry to release
     *  inclusive (the release frame returns owned even though engaged has
     *  already cleared). The seek pill rides the thumb while this is set
     *  instead of sitting head-locked, because what it reads is the position
     *  the drop commits to. See updateTooltip. */
    private var seekTipOnThumb = false

    /** ---------- seek preview card (the thumbnail under the slider) ----------
     *
     *  The grip drags a small card under the slider: the time it will seek
     *  to on top, the frame from that moment below. It replaces the floating
     *  pill on the seek bar while a drag is held (a bare hover still gets the
     *  pill), because the whole point here is that the pill's number and the
     *  picture beside it must be read together, and the pill has nowhere to
     *  put a picture.
     *
     *  Frames are decoded in the background (see the thumbs package) and
     *  handed over the same way a web page capture is: a volatile pending
     *  reference, uploaded on the GL thread. Only one frame is ever live.
     */

    /**
     * The card is composited into one of two textures and shown from the
     * other, so the texture being sampled is never the one being written.
     *
     * Uploading into the live texture is undefined behaviour in GL — the
     * driver may apply the new pixels to a quad whose fetch already used the
     * old ones — and on this panel the card is re-uploaded on every span
     * crossing during a drag, which is exactly when it showed. Alternating
     * makes each card whole before the frame that shows it, which is the
     * texture-side half of not flickering; holding the previous card while
     * the next is built is the other half.
     */
    private var thumbTexIds = IntArray(2) { -1 }
    private var thumbTexShown = 0

    private fun thumbTex(): Int = thumbTexIds[thumbTexShown]
    private var thumbBitmap: Bitmap? = null

    /**
     * Waiting for the GL thread. Atomic, and that is not tidiness: the
     * extractor hands frames over from its own thread while this one uploads,
     * and a check-then-recycle between them recycles the very bitmap being
     * uploaded — which does not throw, it segfaults inside texImage2D when the
     * driver asks the recycled bitmap for its size. Whoever takes the value
     * out owns it; whoever puts one in owns whatever it displaced.
     */
    private val thumbBmpPending = java.util.concurrent.atomic.AtomicReference<Bitmap?>(null)

    /** The five-second span the live preview stands for, and its shape. */
    private var thumbBucket = -1
    private var thumbAspect = 16f / 9f

    /** The composited card: frame, time and picture in one image, so it
     *  moves as a unit and cannot flicker against itself. */
    private var cardBitmap: Bitmap? = null
    /** Set by clearThumb from any thread; acted on by the GL thread. */
    @Volatile
    private var thumbDrop = false
    private var cardAspect = 1f
    private var thumbCardKey = ""

    /**
     * Counts previews the GL thread has taken up. The card rebuild compares
     * this against the count it was last built at, so "a new picture arrived"
     * is detectable without comparing bitmaps.
     */
    private var thumbFrameSeq = 0L
    private var thumbCardSeq = -1L

    @Volatile
    private var thumbBucketIn = -1

    @Volatile
    private var thumbAspectIn = 16f / 9f

    /** Bucket last asked of the extractor, so a drag crossing the film asks
     *  once per span rather than once per frame. */
    private var thumbAskedBucket = -1

    /** Set by the activity: hand a bucket to the background builder. */
    @Volatile
    var thumbSink: ((Int) -> Unit)? = null

    /** The span the grip is over: what the card is for, and the only thing
     *  it may show. -1 when no drag is held. */
    @Volatile
    var cardWantedSpan = -1

    /** Where the card is centred: the grip, in design panel space. */
    private fun seekThumbCentreU(): Float {
        val g = seekDrag.thumbRect()      // engine space, y down
        return (g.left + g.right) * 0.5f
    }

    /**
     * The card: time above, picture below, as ONE composited image drawn as
     * one quad.
     *
     * It used to be three things — a frame rectangle and a time label drawn
     * into the menu bitmap, the picture drawn as a quad on top — and that
     * split is what made it flicker: the menu bitmap is rebuilt every frame
     * of a drag and its edges land on whole texels, while the quad's edges
     * land wherever the grip is, so a sub-texel sliver of frame showed and
     * hid along the picture's edge at 60 Hz. One image cannot disagree with
     * itself, so the whole card is composited once per preview and moved as
     * a unit.
     *
     * Returns left, bottom, right, top in design units.
     */
    private fun thumbCard(): FloatArray {
        val imgH = THUMB_IMG_H
        val imgW = minOf(THUMB_IMG_MAX_W, imgH * thumbAspect)
        val cardW = imgW + THUMB_CARD_PAD * 2f
        val cardH = THUMB_TIME_H + imgH + THUMB_CARD_PAD * 2f
        val top = menuBar.y - menuBar.hh - THUMB_CARD_GAP
        var cu = seekThumbCentreU()
        val lim = MENU_X1 - cardW * 0.5f - 0.06f
        cu = cu.coerceIn(-lim, lim)
        return floatArrayOf(cu - cardW * 0.5f, top - cardH, cu + cardW * 0.5f, top)
    }

    /**
     * Compose the card bitmap if anything about it has changed: a new preview,
     * or a new second on the clock. The pixels are derived from the design
     * size, so the picture on it is exactly the shape the quad is.
     */
    private fun maybeBuildThumbCard(timeText: String) {
        val img = thumbBitmap ?: return
        if (thumbTex() < 0) return
        val key = "$thumbBucket|$timeText|${(thumbAspect * 1000f).toInt()}"
        if (key == thumbCardKey && cardBitmap != null) return
        thumbCardKey = key
        thumbCardSeq = thumbFrameSeq
        // Picture height in card texels; the card's pixel size follows from
        // the design size so the two can never drift apart.
        val imgHpx = CARD_IMG_H_TEX
        val imgWpx = (imgHpx * img.width.toFloat() / img.height)
            .roundToInt().coerceIn(CARD_IMG_H_TEX / 2, CARD_IMG_MAX_W_TEX)
        val padPx = (THUMB_CARD_PAD * PIXELS_PER_DESIGN_UNIT).roundToInt()
        val timePx = (THUMB_TIME_H * PIXELS_PER_DESIGN_UNIT).roundToInt()
        val W = imgWpx + padPx * 2
        val H = imgHpx + padPx * 2 + timePx
        cardAspect = W.toFloat() / H
        val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.TRANSPARENT)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        // The card body, matching the panel's own faces.
        val rad = padPx * 1.6f
        p.style = Paint.Style.FILL
        p.color = Color.argb(235, 10, 14, 22)
        c.drawRoundRect(1f, 1f, (W - 1).toFloat(), (H - 1).toFloat(), rad, rad, p)
        // A DARK edge, and a thin one.
        //
        // This was a bright ~7px stroke, which on a texture this size lands on
        // a handful of texels and is then magnified across the panel: the
        // stroke's own edge is the card's highest-contrast feature, so it is
        // where the magnification shows, and it crawled as the card moved.
        // Anti-aliasing only softens that edge, it does not stop a hard
        // boundary being resampled at a moving position — the crawl comes
        // from the contrast, not the jaggies. Against the panel's own dark
        // faces a dark rim separates just as well and has almost no contrast
        // left to shimmer with.
        p.color = Color.argb(255, 46, 56, 74)
        p.style = Paint.Style.STROKE
        p.strokeWidth = maxOf(1.5f, padPx * 0.13f)
        val sw = p.strokeWidth * 0.5f
        c.drawRoundRect(sw, sw, W - sw, H - sw, rad, rad, p)
        p.style = Paint.Style.FILL
        // Time, in its own row above the picture — inside the card, so it
        // cannot collide with anything outside it.
        p.color = Color.WHITE
        p.textSize = timePx * 0.74f
        p.textAlign = Paint.Align.CENTER
        c.drawText(timeText, W / 2f, timePx * 0.80f, p)
        p.textAlign = Paint.Align.LEFT
        c.drawBitmap(img, null, RectF(padPx.toFloat(), (timePx + padPx).toFloat(),
            (W - padPx).toFloat(), (H - padPx).toFloat()), p)
        // Into the texture that is NOT on screen, then show it: the swap is the
        // assignment, with no frame in which a half-built card is sampled.
        val back = 1 - thumbTexShown
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, thumbTexIds[back])
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
        thumbTexShown = back
        cardBitmap?.recycle()
        cardBitmap = bmp
    }

    /** The five-second span the position in hand falls in: what the grip is
     *  being asked for, whether or not that frame has arrived yet. */
    private fun thumbBucketInHand(): Int {
        val ms = (menuSeekHoverU.coerceIn(0f, 1f) * menuDurMs).toLong()
        return ThumbStrip.bucketOf(ms, ThumbStrip.DEFAULT_BUCKET_MS)
    }

    /**
     * Ask the background builder for the preview of the span in hand — once
     * per span, not once per frame: a drag can cross the whole film in a
     * second, and each request is a seek the builder has to give up its
     * current work for. The builder decides what is free to build.
     */
    private fun requestSeekThumb() {
        val sink = thumbSink ?: return
        if (!sweepEnabled || menuDurMs <= 0L) return
        val b = thumbBucketInHand()
        if (b == thumbAskedBucket) return
        thumbAskedBucket = b
        cardWantedSpan = b
        sink.invoke(b)
    }

    /**
     * True while a hand is on the grip. The activity's bandwidth gate reads
     *  it, and the readback below stands aside for it.
     */
    val seekDragHeld: Boolean get() = seekDrag.engaged

    /**
     * Whether the card should be on screen: a drag is held and there is a
     * preview to show.
     *
     * Deliberately NOT "and the preview is the exact bucket under the grip".
     * Requiring that made the card blink out for the frame or two between
     * buckets while the next one was fetched — a flicker at every span
     * boundary, which is what a drag spends all its time crossing. submitThumb
     * still refuses any frame that is not the span under the grip, so what is
     * held is always a frame of somewhere the grip has been; it simply stays
     * up, with the time it was taken at, until the frame for the span in hand
     * replaces it.
     */
    private fun thumbCardReady(): Boolean =
        seekTipOnThumb && sweepEnabled && menuDurMs > 0L && thumbBucket >= 0

    /**
     * A preview, from the background builder. Ownership passes here: the
     * previous frame is recycled once the new one is on the GPU.
     */
    fun submitThumb(bucket: Int, bmp: Bitmap) {
        // While the grip is held, the card shows the frame for the span under
        // it and nothing else. The prebuild's captures arrive here too — they
        // are the newest frames in the system — so without this the card
        // displays whatever the sweep last grabbed (minutes away from where
        // the user is pointing) under a label saying where the drop will
        // land. That looked like a strip that was wildly out of time, and
        // sorted itself out only once the strip was complete, because by then
        // a drag was fetching the right frame itself.
        if (seekTipOnThumb && bucket != cardWantedSpan) {
            bmp.recycle()
            return
        }
        thumbBucketIn = bucket
        thumbAspectIn = bmp.width.toFloat() / bmp.height.coerceAtLeast(1)
        // A newer frame displaces an older waiting one rather than queueing
        // behind it: a preview that arrives late is a preview of somewhere
        // the grip has already left. The displaced one has provably not been
        // taken by the GL thread yet (it took its own value out already), so
        // freeing it here is safe.
        thumbBmpPending.getAndSet(bmp)?.recycle()
    }

    /** Called by the activity when the strip is closed: drop the preview so a
     *  preview from the last film cannot sit under the next one's slider. */
    fun clearThumb() {
        // Callable from any thread, so it frees NOTHING: the two bitmaps the GL
        // thread owns are released there, and the waiting one is simply
        // dropped (getAndSet claims it atomically, so it cannot be recycled
        // out from under an upload).
        thumbBmpPending.getAndSet(null)?.recycle()
        thumbDrop = true
        thumbBucket = -1
        thumbBucketIn = -1
        thumbAskedBucket = -1
        cardWantedSpan = -1
        thumbAspect = 16f / 9f
        thumbAspectIn = thumbAspect
        thumbCardKey = ""
        thumbCardSeq = -1L
        thumbFrameSeq++
    }

    // ---------- seek-preview capture ----------
    //
    // The preview player decodes into [previewSt], and the frame is read back
    // through a framebuffer sized for the picture rather than for the film:
    // a 1920x960 frame read at full size would be 7 MB per thumbnail and a
    // visible stall on the render thread, while 288x144 is ~170 KB and
    // sub-millisecond. Everything here runs on the GL thread; only the
    // finished bytes are handed off (see [previewSink]).
    private var previewOesId = 0
    private var previewSt: SurfaceTexture? = null
    private var previewSurface: android.view.Surface? = null
    private var previewFbo = 0
    private var previewFbTex = 0
    private var previewW = 0
    private var previewH = 0
    /** The film the builder told us about, and the eye-shaped crop the
     *  readback is sized for (written from the main thread, read here). */
    @Volatile
    private var previewSrcW = 0

    @Volatile
    private var previewSrcH = 0
    /** The SOURCE size the framebuffer was last built for. Compared against
     *  the source size asked for — comparing it against the OUTPUT size
     *  (144) instead can never match (960), and that mismatch rebuilds the
     *  texture and a fresh readback buffer every frame, which is a freeze. */
    private var previewBuiltSrcW = 0
    private var previewBuiltSrcH = 0
    private var previewPixels: ByteBuffer? = null
    private val previewTexMat = FloatArray(16)
    private val previewPosBuf: FloatBuffer = fb(floatArrayOf(
        -1f, 1f, 0f, 1f, -1f, -1f, 0f, 1f, 1f, 1f, 0f, 1f, 1f, -1f, 0f, 1f))
    private val previewTexBuf: FloatBuffer = fb(floatArrayOf(
        0f, 1f, 0f, 1f, 0f, 0f, 0f, 1f, 1f, 1f, 0f, 1f, 1f, 0f, 0f, 1f))
    private val previewIdentity = floatArrayOf(
        1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)

    /** Frames the preview SurfaceTexture has received (any thread). */
    @Volatile
    private var previewFrameSeq = 0
    private var previewDrainedSeq = 0
    private var shortReadLogged = false

    /** Bumped whenever the GL context is rebuilt, so the preview player can
     *  tell its Surface is stale. */
    @Volatile
    var previewGen = 0
        private set

    /** The span being fetched, stamped onto the frame we hand over. */
    @Volatile
    var previewExpectBucket = -1

    /**
     * Whether a frame arriving now belongs to the fetch in progress.
     *
     * Set false when a seek is issued and true only once the player reports
     * being AT the target. Without the gap, the frame that matters is easy to
     * lose: `seekTo` is asynchronous, so calling `play()` straight after it
     * resumes playback at the OLD position for a moment, and that frame gets
     * filed against the new span — a preview of somewhere the film has not
     * reached yet, which is worse than no preview.
     */
    @Volatile
    var previewSeekArmed = false

    /** Receives (bucket, RGBA bytes, width, height) on the GL thread. The
     *  receiver owns the array. */
    @Volatile
    var previewSink: ((Int, ByteArray, Int, Int) -> Unit)? = null

    /**
     * The Surface the preview player should decode into. Valid between GL
     * context rebuilds; re-read it (and compare [previewGen]) whenever the
     * player is (re)started, since a context loss invalidates the old one.
     */
    fun previewSurface(): android.view.Surface? = previewSurface

    /**
     * The film is [w] x [h]: size the readback framebuffer to that shape, at
     * preview height. Nothing is captured until this is called, because the
     * framebuffer cannot be built without knowing what it is reading.
     */
    fun setPreviewSourceSize(w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        // Recorded, not acted on: this is called from the main thread (which
        // owns the player) and GL objects may only be touched on the GL
        // thread. drainPreviewFrame does the actual work.
        previewSrcW = w
        previewSrcH = h
    }

    /** Build (or rebuild) the readback framebuffer for the film we now know
     *  the size of. GL thread; the size is chosen so the picture lands at
     *  preview height, cropped by the stereo layout on the CPU afterwards. */
    private fun ensurePreviewFbo() {
        val w = previewSrcW
        val h = previewSrcH
        if (w <= 0 || h <= 0) return
        if (previewFbo != 0 && previewBuiltSrcW == w && previewBuiltSrcH == h) return
        // The WHOLE frame, not one eye: which eye the preview shows is the
        // builder's decision, and sizing the readback for the eye as well
        // meant the crop was taken twice — the preview showed a quarter of
        // the picture, which is not a preview of anything.
        val size = FramePixels.fitWithin(w, h, PREVIEW_READ_H, PREVIEW_READ_MAX_W)
        val nw = size[0]
        val nh = size[1]
        if (nw <= 0 || nh <= 0) return
        previewW = nw; previewH = nh
        previewBuiltSrcW = w; previewBuiltSrcH = h
        previewPixels = ByteBuffer.allocateDirect(nw * nh * 4).order(ByteOrder.nativeOrder())
        if (previewFbo == 0) {
            val t = IntArray(1)
            GLES20.glGenTextures(1, t, 0)
            previewFbTex = t[0]
            GLES20.glGenFramebuffers(1, t, 0)
            previewFbo = t[0]
        }
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, previewFbTex)
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, nw, nh, 0,
            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, previewFbo)
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
            GLES20.GL_TEXTURE_2D, previewFbTex, 0)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        FileLog.i("SweepVR-GL", "preview readback ${nw}x$nh for ${w}x$h (${stereo})")
    }

    /**
     * Take the newest preview frame if one has arrived. GL thread, once per
     * frame: sample the SurfaceTexture, draw it into the readback
     * framebuffer at the size we want rather than the size it arrived, and
     * read that back — so the cost of a preview is a few hundred kilobytes
     * and never a full-resolution copy.
     */
    private fun drainPreviewFrame() {
        val sink = previewSink ?: return
        val st = previewSt ?: return
        ensurePreviewFbo()
        if (previewDrainedSeq >= previewFrameSeq) return
        // One frame per drain: the builder asks for one at a time, and a
        // second frame would be somewhere the grip has already left.
        previewDrainedSeq = previewFrameSeq
        val bucket = previewExpectBucket
        // Not armed: a seek is in flight and any frame is from the position
        // before it. Latch it (below) but keep nothing.
        if (!previewSeekArmed) {
            try { st.updateTexImage() } catch (e: Throwable) {
                FileLog.w("SweepVR-GL", "preview latch failed: ${e.message}")
            }
            return
        }
        // A frame we cannot use STILL has to be latched. updateTexImage is
        // what releases the buffer back to the producer, and a SurfaceTexture
        // has a small fixed pool: a frame that arrives and is never latched
        // fills it, and the decoder then blocks for good. Skipping this
        // because "nobody wanted that frame" is what leaves the prebuild
        // waiting forever on a player that has already stopped producing.
        if (bucket < 0 || seekTipOnThumb || previewFbo == 0 || previewW <= 0 || previewH <= 0) {
            try { st.updateTexImage() } catch (e: Throwable) {
                FileLog.w("SweepVR-GL", "preview latch failed: ${e.message}")
            }
            return
        }
        // Not while the grip is held (checked above): reading a frame back is
        // a pipeline synchronisation point on the thread that draws both eyes,
        // and a drag is exactly when a hitch reads as the world juddering.
        // The strip is built ahead of time, so the drag itself reads previews
        // from disk and needs nothing from here.
        val px = previewPixels ?: return
        try {
            // Latch FIRST, then ask for the transform.
            //
            // getTransformMatrix() reports the transform of the buffer that
            // updateTexImage() most recently latched, so reading it first
            // describes the PREVIOUS frame. For every capture after the first
            // that is harmless — a steady film has the same matrix twice — but
            // on the first capture of a session there is no previous buffer,
            // so it returns the identity and the frame is drawn with the
            // wrong vertical orientation. That is the upside-down first frame
            // in the strip, and the ordering here is the whole of it.
            st.updateTexImage()
            st.getTransformMatrix(previewTexMat)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, previewFbo)
            GLES20.glViewport(0, 0, previewW, previewH)
            // Scissor OFF, and this is not tidiness: drawEye leaves the
            // scissor set to the last eye's half of the window, and
            // glReadPixels obeys the scissor. Reading a 144-wide framebuffer
            // through a box starting at x=1163 clips the whole read away and
            // hands back nothing at all, silently.
            GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
            GLES20.glDisable(GLES20.GL_DEPTH_TEST)
            GLES20.glDisable(GLES20.GL_BLEND)
            GLES20.glUseProgram(progVideo)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, previewOesId)
            GLES20.glUniform1i(uVideoTexVid, 0)
            GLES20.glUniform1f(uCropVid, 0f)   // no zoom: this is the frame as it is
            GLES20.glUniformMatrix4fv(uTexMatVid, 1, false, previewTexMat, 0)
            GLES20.glUniformMatrix4fv(uTexTransVid, 1, false, previewTexMat, 0)
            GLES20.glUniformMatrix4fv(uMvpVid, 1, false, previewIdentity, 0)
            GLES20.glEnableVertexAttribArray(aPositionVid)
            GLES20.glVertexAttribPointer(aPositionVid, 4, GLES20.GL_FLOAT, false, 0, previewPosBuf)
            GLES20.glEnableVertexAttribArray(aTexCoordVid)
            GLES20.glVertexAttribPointer(aTexCoordVid, 4, GLES20.GL_FLOAT, false, 0, previewTexBuf)
            previewPosBuf.position(0); previewTexBuf.position(0)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GLES20.glDisableVertexAttribArray(aPositionVid)
            GLES20.glDisableVertexAttribArray(aTexCoordVid)
            px.clear()
            GLES20.glReadPixels(0, 0, previewW, previewH, GLES20.GL_RGBA,
                GLES20.GL_UNSIGNED_BYTE, px)
            // The Java binding writes at the buffer's position and does NOT
            // advance it, unlike the C API. Reading the length back out of
            // position() therefore yields zero every time, and the frame comes
            // out as an empty array — which is how a working readback ends up
            // looking like an impossible crop.
            px.position(0)
            px.limit(previewW * previewH * 4)
        } catch (e: Throwable) {
            FileLog.w("SweepVR-GL", "preview readback failed: ${e.message}")
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            return
        }
        // Leave GL exactly as we found it: this runs mid-frame, and the eyes
        // are about to draw.
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        val bytes = ByteArray(px.remaining())
        px.get(bytes)
        val err = GLES20.glGetError()
        if (err != GLES20.GL_NO_ERROR) {
            // Say so once: a failed read here is invisible from the builder,
            // which sees only an empty array and an impossible crop.
            if (!shortReadLogged) {
                shortReadLogged = true
                FileLog.w("SweepVR-GL", "preview readback glErr=$err bytes=${bytes.size}")
            }
        }
        sink.invoke(bucket, bytes, previewW, previewH)
    }
    /** Last ray/plane hit in design panel space (menuHitTest). */
    private var menuHitU = 0f
    private var menuHitV = 0f
    private var menuHitId = -2
    /** Head-locked gaze tooltip pill (updated by updateTooltip). */
    private var tooltipVisible = false
    private var tooltipText = ""
    private var lastTipText: String? = null
    private var tipBitmap: Bitmap? = null
    private var menuTexId = 0
    private var menuBitmap: Bitmap? = null
    private var lastMenuHash = 0
    /** When the gaze first dropped below the close threshold (0 = above).
     *  Closing needs 400ms continuously below: sensor noise and transient
     *  tilt dips while operating the end buttons must not strobe the menu
     *  (and reset every dwell). */
    private var menuBelowSince = 0L
    private var menuWasOpen = false
    private var menuProgFresh = true
    /** Cardboard lens distortion coefficients (0..1, standard Cardboard).
     *  The activity pushes them into the SDK's Distortion object; the
     *  actual lens pass runs inside the GVR native renderer. */
    @Volatile var lensK1 = 0.34f
    @Volatile var lensK2 = 0.55f
    private val mvpM = FloatArray(16)
    private val tmpM = FloatArray(16)

    /** Decoded-frame aspect (width/height) for fisheye circle calibration.
     *  Refreshed by the activity from the video track when known. */
    @Volatile var videoAspect = 16f / 9f
    /** Fisheye circle calibration (§8): radius multiplier (1 = default),
     *  center offsets in frame UV, per-eye horizontal-mirror flags. */
    @Volatile var fisheyeRadiusScale = 1f
    @Volatile var fisheyeCxOff = 0f
    @Volatile var fisheyeCyOff = 0f
    @Volatile var fisheyeMirrorL = false
    @Volatile var fisheyeMirrorR = false
    /** Decoder texture transform (SurfaceTexture.getTransformMatrix),
     *  refreshed every frame on the GL thread and honored by both video
     *  sampling paths (§4). Identity until the first frame arrives. */
    private val texMat = FloatArray(16).also { Matrix.setIdentityM(it, 0) }

    @Volatile var lastWidth = 1
    @Volatile var lastHeight = 1

    companion object {
        /** Keyboard texture size and fade. The texture is wider than tall in
         *  the same proportion the quad is drawn at, so nothing is stretched. */
        const val KBD_TEX_W = 1024
        const val KBD_TEX_H = 400
        const val KBD_FADE_MS = 140f
        /** Gap under the text field, in metres. */
        const val KBD_TOP_GAP = 0.035f
        /** Half-height in metres at panelDistM; converted to an ANGLE so the
         *  keyboard keeps its size wherever it is anchored. */
        const val KBD_HALF_H = 0.42f

        /** Play-menu panel world geometry (doc §7): a button is 0.6 m
         *  across at pitch 0.75 m on a panel of radius panelDistM. */
        const val MENU_BTN_HALF = 0.3f
        const val MENU_PITCH = 0.75f
        /** The left column — recenter (top), settings, cue toggle (bottom)
         *  — is a stack of small squares: 0.5 design units across, not the
         *  0.6 default, all sharing one x. */
        const val MENU_COL_X = -4.50f
        const val MENU_COL_HALF = 0.25f
        /** Cue toggle (repeat / autocue), the bottom of that column. */
        const val MENU_CUE_ID = 19
        const val MENU_CUE_Y = -0.70f
        /** Drawn corner radius as a FRACTION of the face — and the sweep's
         *  corner refusal, so the refused corners are the drawn rounding
         *  rather than a second, disagreeing number. Every button face on
         *  the panel draws with it now, not just the left column. */
        const val MENU_CORNER_FRAC = 0.10f
        /** Icon size for the transport row, files / web and the ± columns,
         *  as a fraction of what they used to draw at: at full size the
         *  glyphs came too close to the button edges. The faces themselves
         *  are unchanged — only the icons inside them shrink. */
        const val MENU_ICON_FRAC = 0.9f
        /** The seek bar's grip: the handle the sweep latches onto, in
         *  design units. The track it drags on is the bar widened by half a
         *  handle either side (so value 0 and 1 sit on the bar's ENDS) and
         *  set to [SEEK_HANDLE_H] tall; see [seekDrag]. */
        const val SEEK_HANDLE_W = 0.42f
        /** The grip's height: 0.468 against the bar's own 0.6, so more of
         *  the bar clears the grip top and bottom. The grip must not
         *  swallow the line it rides on — with the bar hidden behind it the
         *  position has no visible context, and there is nothing to aim the
         *  entry at but the grip alone. It is also the drag's vertical band
         *  (a drop is a move past the grip's edge), so a shorter grip makes
         *  the drop a shorter gesture — now about the travel of dipping one
         *  of the buttons. */
        const val SEEK_HANDLE_H = 0.468f

        /**
         * The grip's border while it is held: a medium blue, and the same
         * blue as the line down its middle (SEEK_GRIP_MARK_RGB).
         *
         * Dark enough to read against the seek bar's own blue historical
         * fill rather than vanishing into it, which is what a border lighter
         * than the fill did — and light enough to still be a rim against the
         * grip's dark face at arm's length.
         */
        const val SEEK_GRIP_ARMED_RGB = 0x4A8FD0.toInt()
        /** The grip's centre line: the same blue, a touch lighter so it
         *  reads as a mark on the face rather than a seam. */
        const val SEEK_GRIP_MARK_RGB = 0x6FB0E8.toInt()
        /** The seek preview card under the slider: image height, and the
         *  width it may grow to before the picture's own shape decides. */
        const val THUMB_IMG_H = 1.72f
        const val THUMB_IMG_MAX_W = 3.00f
        /** Row of text above the picture, and the card's own padding/gap.
         *  The card hangs from just under the bar to the panel's bottom edge
         *  (design y -2.0), and that gap is all the room there is: padding +
         *  time + picture is sized to land on the edge, not past it. */
        const val THUMB_TIME_H = 0.34f
        const val THUMB_CARD_PAD = 0.08f
        const val THUMB_CARD_GAP = 0.05f
        /** The composited card's picture, in card texels, and its width cap.
         *  264 keeps the card's upload around 0.3 MB, once per preview. */
        const val CARD_IMG_H_TEX = 480
        const val CARD_IMG_MAX_W_TEX = 960
        /** Card texels per design unit, so the composite and the quad it is
         *  drawn as are the same size by construction rather than by two sets
         *  of numbers agreeing. */
        val PIXELS_PER_DESIGN_UNIT: Float =
            (CARD_IMG_H_TEX + 16f) / (THUMB_IMG_H + THUMB_CARD_PAD * 2f)
        /** Preview readback size: height in pixels, and the width cap. The
         *  card shows the picture about 0.54 design units tall, which is some
         *  50 texels of the panel bitmap — but a readback at that size has
         *  nothing left to show once it is cropped and squashed into a 4:3-ish
         *  box, so this reads the frame a few times over and lets it be
         *  scaled down to the panel. */
        const val PREVIEW_READ_H = 288
        const val PREVIEW_READ_MAX_W = 576
        /** How far past either end of that track the reticle may travel
         *  before the drag counts as abandoned rather than an overshoot.
         *  Small on purpose: the track already runs half a handle past the
         *  bar, and the − of the right-hand columns starts at x = 2.85 — a
         *  longer tolerance would let a runaway drag walk the reticle into
         *  zoom− sideways and arm it on the way out. */
        const val SEEK_END_LEEWAY = 0.06f
        /** How long a committed seek shows as the grip's position before
         *  falling back to the reported playback position. The commit is
         *  posted to the UI thread and so is the optimistic position that
         *  goes with it, so for a frame or two the player still reports
         *  where the video WAS — the grip would jump back and then
         *  forward again. It drops out early the moment playback reaches
         *  the committed position. */
        const val SEEK_STICKY_MS = 3000L
        /** The transport tile's blue, crown to foot, as the colour emoji
         *  font on this device paints it: near-white cyan at the top
         *  settling to a mid blue at the bottom. */
        val TRACK_BLUE = intArrayOf(
            0xFF9CE9FA.toInt(), 0xFF8AE0F8.toInt(), 0xFF68CFF1.toInt(),
            0xFF48B3E3.toInt(), 0xFF2A98D4.toInt()
        )
        val TRACK_BLUE_POS = floatArrayOf(0f, 0.45f, 0.65f, 0.85f, 1f)
        /** Panel texture covers x ∈ [MENU_X0, MENU_X1], y ∈ [MENU_Y0, MENU_Y1]
         *  in panel space (y up), 1024 texels wide.
         *
         *  The bottom edge sits well below anything drawn on the panel: it is
         *  the room the seek-preview card occupies while a drag is held. The
         *  texture height follows the design span at the same ~96.5 texels a
         *  unit is wide, so extending the panel does not stretch it. */
        const val MENU_X0 = -5.3f
        const val MENU_X1 = 5.3f
        const val MENU_Y0 = -3.50f
        const val MENU_Y1 = 1.4f
        const val MENU_TEX_W = 1024
        const val MENU_TEX_H = 474
        /** Viewing distance the panel rect was authored for (the flat screen
         *  sits at −11.95 too); the live rect scales with panelDistM. */
        const val MENU_DESIGN_R = 11.95f
        /** Viewing distance of the flat screen plane (and the cap's blend
         *  start): buildVideoModel's z translate, screenCapMesh's flat leg. */
        const val FLAT_DIST = 11.95f
        /** Tightest cap radius at curve = 1: the picture bends toward the
         *  viewer but never closer than this (metres). */
        const val SCREEN_R_MIN = 4.0f
        /** Wrap clamp: the bent screen never sweeps past this total angle,
         *  so its edges stay in front of the ears (not a whole sphere). */
        const val SCREEN_ARC_MAX_DEG = 150f
        /** Rect half-extents: x ±5.3, y −2.0..1.4 (centre −0.3, half 1.7). */
        const val MENU_RECT_HW = 5.3f
        const val MENU_RECT_HH = 1.7f
        const val MENU_RECT_VOFF = -0.3f
        /** Near-edge extent past the panel centre, per side (bottom 1.8,
         *  top 1.05 — title top) for the elevation formula. */
        const val MENU_EXT_BOT = 1.8f
        const val MENU_EXT_TOP = 1.05f
        /** Degrees the near edge keeps beyond the trigger angle. */
        const val MENU_MARGIN_DEG = 6.7f
        /** Reticle diameter angle (rad): world half-size = sin(0.03)·R. */
        private const val RETICLE_ANG = 0.03f
        /** Gaze tooltip pill, half-extents at panel depth (design units) —
         *  aspect-matched to the 512×96 texture, small enough to float over
         *  a button without burying the row behind it. */
        const val TIP_HALF_W = 0.96f
        const val TIP_HALF_H = 0.18f
        /** Clear air between a control's top-right corner and the pill's. */
        const val TIP_GAP = 0.1f
        /** Toast pill at panel depth (design units), aspect-matched to the
         *  512×112 texture; ~62%×14% of the view, like the old NDC quad. */
        const val TOAST_HALF_W = 4.3f
        const val TOAST_HALF_H = 0.94f
        /** Screen-position calibration sweep, milliseconds. */
        const val SCREEN_SWEEP_MS = 4000f
        const val TEX = 1024
        const val ROW_H = 64
        // ---- browser panel layout (TEX coords, y down) ----
        // Settings pages: title bar, then a plain 13-row window.
        // File pages (pin 2): title bar, pinned home + up rows, scroll-up
        // strip, scrolling window, scroll-down strip. The strips are pinned
        // chrome: always visible whenever the list overflows the window.
        // The window holds 12 rows total shared between pinned and
        // scrolling rows, so the down strip always lands at the same y.
        const val TITLE_Y1 = 110
        const val ROWS_Y0 = 150
        const val VISIBLE_ROWS = 13
        const val PIN_Y0 = 114
        const val STRIP_H = 56
        // PgUp/PgDn buttons, top-right of the panel (inside the title bar,
        // which the short web title leaves free).
        const val SIDE_BTN_X0 = 856f
        const val SIDE_BTN_X1 = 1004f
        const val SIDE_BTN_H = 46f
        const val SIDE_BTN_UP_Y0 = 12f
        const val SIDE_BTN_DN_Y0 = 62f
        /** Rows visible in the scrolling window (12 minus pinned rows). */
        fun winRows(pin: Int): Int = 12 - pin.coerceIn(0, 2)
        /** Top y of the scroll-up strip. */
        fun upStripY0(pin: Int): Float = (PIN_Y0 + pin.coerceIn(0, 2) * ROW_H).toFloat()
        /** Top y of the scrolling rows window. */
        fun rowsY0(pin: Int): Float = upStripY0(pin) + STRIP_H
        /** Top y of the scroll-down strip (same for pin 0..2 by design). */
        fun downStripY0(pin: Int): Float = rowsY0(pin) + winRows(pin) * ROW_H
        // Scroll-strip feel: still-gaze time to engage, then rows/second.
        const val STRIP_TRIG_MS = 350f
        const val STRIP_ROWS_PER_SEC = 10f
        // ---- bookmarks flyout ----
        /** Icon button: a small square, sized just to carry the glyph. */
        const val BOOK_BTN = 108f
        /** Icon position on the open web panel: top-right, centred under the
         *  PgUp/PgDn buttons, clear of every row. Elsewhere (stale web flags
         *  in other modes) the icon keeps its legacy row-homed rect, so those
         *  paths are untouched. */
        const val BOOK_ICON_X0 = 876f // (SIDE_BTN_X0 + SIDE_BTN_X1 - BOOK_BTN) / 2
        const val BOOK_ICON_Y0 = 124f // SIDE_BTN_DN_Y0 + SIDE_BTN_H + 16
        /** Corner rounding, and the triangular dips in the entry edges.
         *  DIP_H is the half-height of the notch opening, and is wider than
         *  it is deep, so the mouth of the entry reads as an opening rather
         *  than a jag. */
        const val BOOK_BTN_R = 15f
        const val BOOK_BTN_DIP = 8f
        const val BOOK_BTN_DIP_H = 26f
        /** Pane: width is measured from the longest label (see bookPaneW),
         *  so there is no dead whitespace either side. */
        const val BOOK_PANE_PAD_X = 28f
        const val BOOK_PANE_ROW_H = 64f
        const val BOOK_PANE_GAP = 8f
        /** Scroll arrow rows, present only when the list overflows. */
        const val BOOK_ARROW_H = 52f
        /** Rows: a min-height pane slot for the cursor over an arrow. */
        const val BOOK_PANE_TEXT = 30f
        /** Leeway outside the pane, as a fraction of the pane's own margin.
         *  The band has to leave runway beyond it, so this is well under 1. */
        const val BOOK_TOL_FRAC = 0.125f
        /** How far clear of the button the reticle must get before a new
         *  entry is judged on its own terms. Generous, so a reticle resting
         *  on the edge after passing over does not immediately re-arm. */
        const val BOOK_REARM_SLOP = 40f
        /** Side-entry gate: approach samples kept, and the ratio by which the
         *  horizontal must beat the vertical to count as a side entry. */
        const val BOOK_APPROACH_S = 0.15f
        // Panel open/close fade: fast smoothstep, both directions.
        const val PANEL_FADE_MS = 180f
    /** How long the gaze must rest on the web panel to toggle it. */
    const val PANEL_TOGGLE_MS = 350f
    /** Hysteresis band for the tilt trigger, degrees. The panel opens at
     *  its lower edge and closes this far back below it. */
    const val PANEL_HYST_DEG = 3f

        private const val VERT = """
attribute vec4 aPos; attribute vec2 aTex; attribute vec2 aShape; varying vec2 vTex; varying vec3 vDir; varying vec2 vShape; uniform mat4 uMvp;
uniform float uWarpOn; uniform float uWarpCx; uniform float uWarpK1; uniform float uWarpK2; uniform float uWarpAspect;
void main(){
  vTex = aTex;
  vShape = aShape;
  vDir = aPos.xyz;
  vec4 p = uMvp * aPos;
  float w = p.w;
  if (uWarpOn > 0.5) {
    if (w > 0.0) {
      float nx = p.x / w;
      float ny = p.y / w;
      float dx = (nx - uWarpCx) * uWarpAspect;
      float r2 = dx * dx + ny * ny;
      float s = 1.0 / (1.0 + uWarpK1 * r2 + uWarpK2 * r2 * r2);
      nx = uWarpCx + dx * s / uWarpAspect;
      ny = ny * s;
      p.x = nx * w;
      p.y = ny * w;
    }
  }
  gl_Position = p;
}
"""
        // highp UVs when available: mediump quantizes texture coordinates
        // to ~1024 steps, i.e. 8-texel blocks on 8K video ("lego").
        // Video program (method doc §§3-7): homogeneous texcoords with the
        // projective divide in-shader (§6.3). Stereo halves live in texcoord
        // buffers (§6.1) - the fragment shader selects nothing per eye.
        // Zoom is a model-space Z translate in the zoomPan matrix, so on
        // this shader u_verticalCrop is always 0 (it is kept as a uniform
        // only so the fisheye path can share the crop plumbing, and the
        // fisheye shader never reads it).
        private const val VERT_VIDEO = """
attribute vec4 a_position;
attribute vec4 a_texCoord;
uniform mat4 u_mvpMatrix;
uniform mat4 u_textureTransform;
uniform mat4 u_texMat;
varying vec4 v_texCoord;
void main() {
    gl_Position = u_mvpMatrix * a_position;
    v_texCoord = a_texCoord;
}
"""
        // u_textureTransform is declared and uploaded per §7.3 but
        // deliberately never sampled. u_texMat carries the live decoder
        // flip/rotation instead (§7.3 sanctions applying the matrix when the
        // source needs it - dropping it flips every clip, which the old
        // path's identical handling proves ours do). It applies in the
        // fragment AFTER the projective divide: the decoder matrix carries
        // a w-term translation, which would corrupt v_texCoord.y if folded
        // in up here (only x is divided back out).
        private const val FRAG_VIDEO = """
#extension GL_OES_EGL_image_external : require
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif
varying vec4 v_texCoord;
uniform samplerExternalOES u_video;
uniform float u_verticalCrop;
uniform mat4 u_texMat;
void main() {
    vec2 uv = vec2(
        v_texCoord.x / v_texCoord.w,
        v_texCoord.y * (1.0 - u_verticalCrop)
                       + u_verticalCrop * 0.5
    );
    vec4 st = u_texMat * vec4(uv, 0.0, 1.0);
    gl_FragColor = texture2D(u_video, st.xy);
}
"""
        // Fisheye circle sampling (§8): each pixel's view direction is
        // recovered from the interpolated mesh position (the dome is
        // centered on the origin, so normalize(aPos) is the view ray).
        // Angle from the forward axis (-Z) over a hemisphere gives the
        // equidistant radius; the bearing gives the direction in the
        // circle. Outside the active circle (corners included) is black.
        // Mesh, basis, projection, zoom and lens pass are identical to the
        // equirectangular path.
        private const val FRAG_OES_FISH = """
#extension GL_OES_EGL_image_external : require
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif
varying vec2 vTex;
varying vec2 vShape;
varying vec3 vDir;
uniform samplerExternalOES uTex; uniform int uStereo; uniform int uEye;
uniform mat4 uTexMat; uniform float uZoomOut;
uniform vec2 uFishC; uniform vec2 uFishR; uniform float uFishMirror;
void main(){
  vec3 d = normalize(vDir);
  float cosA = clamp(-d.z, -1.0, 1.0);
  float ang = acos(cosA);
  float maxAng = 1.5707963;
  if (ang > maxAng) { gl_FragColor = vec4(0.0, 0.0, 0.0, 1.0); return; }
  float s = sin(ang);
  vec2 bearing = (s > 1e-4) ? (d.xy / s) : vec2(0.0, 0.0);
  float rr = ang / maxAng;
  vec2 off = bearing * rr;
  if (uFishMirror > 0.5) { off.x = -off.x; }
  if (dot(off, off) > 1.0) { gl_FragColor = vec4(0.0, 0.0, 0.0, 1.0); return; }
  vec2 t = uFishC + vec2(off.x * uFishR.x, off.y * uFishR.y);
  // Shaping (dome correction) lives in the mesh UVs, which this path never
  // samples - so the shaped delta rides in as a varying instead, scaled
  // from per-half mesh space to full-frame halves. Unshaped meshes feed
  // (0,0) and this is a no-op.
  vec2 sh = vShape;
  if (uStereo == 1) { sh = vec2(sh.x * 0.5, sh.y); }
  else if (uStereo == 2) { sh = vec2(sh.x, sh.y * 0.5); }
  t += sh;
  // Zoom-out minifies in texture space about the half-image center (§5),
  // same as the equirect path: the frustum never widens.
  vec2 zc = vec2(0.5);
  if (uStereo == 1) { zc = vec2(float(uEye) * 0.5 + 0.25, 0.5); }
  else if (uStereo == 2) { zc = vec2(0.5, float(uEye) * 0.5 + 0.25); }
  t = zc + (t - zc) / uZoomOut;
  bool oob = false;
  if (uStereo == 1) {
    float halfMin = float(uEye) * 0.5;
    float halfMax = halfMin + 0.5;
    oob = (t.x < halfMin || t.x > halfMax || t.y < 0.0 || t.y > 1.0);
  } else if (uStereo == 2) {
    float halfMin = float(uEye) * 0.5;
    float halfMax = halfMin + 0.5;
    oob = (t.y < halfMin || t.y > halfMax || t.x < 0.0 || t.x > 1.0);
  } else {
    oob = (t.x < 0.0 || t.x > 1.0 || t.y < 0.0 || t.y > 1.0);
  }
  if (oob) { gl_FragColor = vec4(0.0, 0.0, 0.0, 1.0); return; }
  vec4 st = uTexMat * vec4(t, 0.0, 1.0);
  gl_FragColor = texture2D(uTex, st.xy);
}
"""
        private const val FRAG_2D = """
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif
varying vec2 vTex; varying vec3 vDir; uniform sampler2D uTex; uniform float uAlpha;
void main(){ gl_FragColor = texture2D(uTex, vTex) * uAlpha; }
"""
        /** Web page: the panel shader plus the flat video's centre zoom, so
         *  the browser magnifies over the same 0.1..20x range. */
        private const val FRAG_WEB = """
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif
varying vec2 vTex; varying vec3 vDir; uniform sampler2D uTex; uniform float uZoom;
void main(){
  vec2 c = vec2(0.5);
  vec2 t = c + (vTex - c) / uZoom;
  // The flat-screen mesh is wound for the video's external textures (v=0 is
  // the BOTTOM row); a bitmap capture is the other way up, so flip it here
  // rather than disturbing the shared mesh.
  t.y = 1.0 - t.y;
  if (t.x < 0.0 || t.x > 1.0 || t.y < 0.0 || t.y > 1.0) { gl_FragColor = vec4(0.0,0.0,0.0,1.0); return; }
  gl_FragColor = texture2D(uTex, t);
}
"""
    }

    override fun onSurfaceCreated(config: EGLConfig?) {
        // Black: the warped meshes cover less than the viewport, and the
        // lens boundary must read as darkness, like real VR software.
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        // Alpha blending for the pointer ring (transparent bitmap
        // background). Opaque content (video, panels) has alpha 1, so this
        // is a no-op for everything except the pointer.
        // PREMULTIPLIED alpha: every 2-D texture is an Android bitmap (which
        // stores color already multiplied by coverage), so the shader writes
        // (src·a) and the blend must consume it as-is: ONE for src,
        // (1−src.a) for dst. Straight-alpha blending here would double-dark
        // every faded panel (browser fades, menu dwell dimming).
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glDisable(GLES20.GL_CULL_FACE)
        progVideo = buildProgram(VERT_VIDEO, FRAG_VIDEO)
        aPositionVid = GLES20.glGetAttribLocation(progVideo, "a_position")
        aTexCoordVid = GLES20.glGetAttribLocation(progVideo, "a_texCoord")
        uMvpVid = GLES20.glGetUniformLocation(progVideo, "u_mvpMatrix")
        uVideoTexVid = GLES20.glGetUniformLocation(progVideo, "u_video")
        uCropVid = GLES20.glGetUniformLocation(progVideo, "u_verticalCrop")
        uTexMatVid = GLES20.glGetUniformLocation(progVideo, "u_texMat")
        uTexTransVid = GLES20.glGetUniformLocation(progVideo, "u_textureTransform")
        progFish = buildProgram(VERT, FRAG_OES_FISH)
        aPosFish = GLES20.glGetAttribLocation(progFish, "aPos")
        aTexFish = GLES20.glGetAttribLocation(progFish, "aTex")
        aShapeFish = GLES20.glGetAttribLocation(progFish, "aShape")
        uMvpFish = GLES20.glGetUniformLocation(progFish, "uMvp")
        uTexFish = GLES20.glGetUniformLocation(progFish, "uTex")
        uStereoFish = GLES20.glGetUniformLocation(progFish, "uStereo")
        uEyeFish = GLES20.glGetUniformLocation(progFish, "uEye")
        uTexMatFish = GLES20.glGetUniformLocation(progFish, "uTexMat")
        uZoomOutFish = GLES20.glGetUniformLocation(progFish, "uZoomOut")
        uFishC = GLES20.glGetUniformLocation(progFish, "uFishC")
        uFishR = GLES20.glGetUniformLocation(progFish, "uFishR")
        uFishMirror = GLES20.glGetUniformLocation(progFish, "uFishMirror")
        prog2d = buildProgram(VERT, FRAG_2D)
        aPos2d = GLES20.glGetAttribLocation(prog2d, "aPos")
        aTex2d = GLES20.glGetAttribLocation(prog2d, "aTex")
        uMvp2d = GLES20.glGetUniformLocation(prog2d, "uMvp")
        uTex2d = GLES20.glGetUniformLocation(prog2d, "uTex")
        uAlpha2d = GLES20.glGetUniformLocation(prog2d, "uAlpha")
        progWeb = buildProgram(VERT, FRAG_WEB)
        aPosWeb = GLES20.glGetAttribLocation(progWeb, "aPos")
        aTexWeb = GLES20.glGetAttribLocation(progWeb, "aTex")
        uMvpWeb = GLES20.glGetUniformLocation(progWeb, "uMvp")
        uTexWeb = GLES20.glGetUniformLocation(progWeb, "uTex")
        uZoomWeb = GLES20.glGetUniformLocation(progWeb, "uZoom")

        val tex = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)
        videoTextureId = tex[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, videoTextureId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        surfaceTexture = SurfaceTexture(videoTextureId)
        surfaceTexture?.setOnFrameAvailableListener(this)
        surface = android.view.Surface(surfaceTexture)
        surfaceGen++

        // ---- seek-preview capture ----
        // A second SurfaceTexture for the background preview player, plus a
        // small framebuffer to read it back through. Both die with this GL
        // context; the preview player is handed the new Surface afterwards.
        try { previewSurface?.release(); previewSt?.release() } catch (_: Exception) {}
        previewSt = null; previewSurface = null; previewFbo = 0; previewFbTex = 0
        GLES20.glGenTextures(1, tex, 0)
        previewOesId = tex[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, previewOesId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        val pst = SurfaceTexture(previewOesId)
        pst.setOnFrameAvailableListener { previewFrameSeq++ }
        previewSt = pst
        previewSurface = android.view.Surface(pst)
        previewW = 0; previewH = 0; previewSrcW = 0; previewSrcH = 0
        previewBuiltSrcW = 0; previewBuiltSrcH = 0
        // Both counters, together. updateTexImage() BLOCKS until a frame is
        // queued, so "have I seen more frames than I've taken" has to be true
        // or the render thread hangs — and after a context rebuild the old
        // frame count means nothing against the new SurfaceTexture.
        previewDrainedSeq = 0
        previewFrameSeq = 0
        previewGen++

        GLES20.glGenTextures(1, tex, 0)
        browserTexId = tex[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, browserTexId)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)

        // Web page texture: a plain 2D texture fed by PixelCopy captures of
        // the WebView, drawn by drawWeb() on the flat screen.
        GLES20.glGenTextures(1, tex, 0)
        webTexId = tex[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, webTexId)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        GLES20.glGenTextures(1, tex, 0)
        webBarTexId = tex[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, webBarTexId)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)

        GLES20.glGenTextures(1, tex, 0)
        webToolbarTexId = tex[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, webToolbarTexId)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)

        reticleTexId = makeReticle(Color.RED)
        aimTexId = makeReticle(Color.rgb(96, 165, 250))

        GLES20.glGenTextures(1, tex, 0)
        menuTexId = tex[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, menuTexId)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)

        GLES20.glGenTextures(1, tex, 0)
        toastTexId = tex[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, toastTexId)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)

        GLES20.glGenTextures(1, tex, 0)
        tipTexId = tex[0]

        GLES20.glGenTextures(1, tex, 0)
        kbTexId = tex[0]
        // The keyboard lays itself out in its own pixel space, so the drawn
        // keys and the hit rects are the same numbers whatever the panel or
        // page is doing. Ratio matches KBD_TEX_W:KBD_TEX_H so nothing is
        // stretched when the quad is placed.
        kbd.window = Rect(0f, 0f, KBD_TEX_W.toFloat(), KBD_TEX_H.toFloat())
        kbdw.window = Rect(0f, 0f, KBD_TEX_W.toFloat(), KBD_TEX_H.toFloat())
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, kbTexId)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tipTexId)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)

        // Two, so a card is always composited into the one not on screen.
        // Its own array: `tex` is the single-name scratch the calls above use,
        // and asking for two names in it overruns it.
        val cardTex = IntArray(2)
        GLES20.glGenTextures(2, cardTex, 0)
        thumbTexIds[0] = cardTex[0]
        thumbTexIds[1] = cardTex[1]
        thumbTexShown = 0
        // Both, not just the one shown first: whichever is the back buffer at
        // the first swap is still on default filtering until it is bound here,
        // and it is the one every card after the first is composited into.
        for (id in thumbTexIds) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, id)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        }
        // A new GL context means new textures and no bitmaps behind them: the
        // pending hand-off from the extractor may be long gone by then.
        thumbBmpPending.set(null)
        thumbDrop = false

        videoGeomKey = ""; webMeshKey = "" // meshes survive, but rebuild against the new session
        browserGrid = null
        menuGrid = null
        webBarMesh = null
        webBarMeshKey = ""
        lastPanelHash = 0
        lastMenuHash = 0
        lastTipText = null
    }

    override fun onSurfaceChanged(w: Int, h: Int) {
        lastWidth = w.coerceAtLeast(1)
        lastHeight = h.coerceAtLeast(1)
    }

    /** Once per frame, before either eye: consume the decoded frame, fold
     *  the head pose into the §3 matrices, run gaze/menu dwell. */
    override fun onNewFrame(head: HeadTransform) {
        try {
            frameTick(head)
            frameCount++
        } catch (e: Throwable) {
            // An uncaught exception here kills the GL thread SILENTLY:
            // frozen picture, advancing position, zero errors. Never again.
            android.util.Log.e("SweepVR-GL", "onNewFrame failed", e)
            FileLog.e("SweepVR-GL", "onNewFrame failed", e)
        }
    }

    override fun onDrawEye(eye: Eye) {
        try {
            drawEye(eye)
        } catch (e: Throwable) {
            android.util.Log.e("SweepVR-GL", "onDrawEye failed", e)
            FileLog.e("SweepVR-GL", "onDrawEye failed", e)
        }
    }

    override fun onFinishFrame(viewport: Viewport?) = Unit

    override fun onRendererShutdown() = Unit

    private var texFailCount = 0

    private fun frameTick(head: HeadTransform) {
        // Consume UNCONDITIONALLY once any frame has ever arrived (the queue
        // warms within ~1s of start; before that stay flag-guarded so an
        // empty queue never throws). Gating consumption on the flag
        // deadlocks permanently: if the producer ever gets a frame ahead
        // (ordinary jitter), its overflow replaces the queued frame SILENTLY
        // (no onFrameAvailable), the flag stays false forever, we never
        // consume, the queue never drains — frozen video, healthy audio,
        // zero errors, both decoders, varying 5-25s. Latching the same frame
        // an extra time is harmless; a stale slot is fatal. Never again.
        // NOTE: no mode check here — panels float over LIVE video in
        // BROWSER mode now, so the queue must keep draining there too.
        surfaceTexture?.let { st ->
            if (frameAvailable || arrivedFrames > 0) {
                drainPreviewFrame()
                try { st.updateTexImage(); if (frameAvailable) consumedFrames++ } catch (e: Throwable) { texFailCount++; if (texFailCount <= 3 || texFailCount % 300 == 0) { android.util.Log.e("SweepVR-GL", "updateTexImage failed #$texFailCount", e); FileLog.e("SweepVR-GL", "updateTexImage failed #$texFailCount", e) } }
                frameAvailable = false
            }
            // Decoder orientation (§4): honor the transform every frame so the
            // sampled image matches what the decoder produced.
            try { st.getTransformMatrix(texMat) } catch (_: Throwable) {}
        }

        head.getHeadView(headViewM, 0)

        if (testSweep != lastTestSweep) {
            lastTestSweep = testSweep
            if (testSweep) sweepT0 = android.os.SystemClock.elapsedRealtime()
            // The sweep composes a synthetic headView on top of the basis, so
            // the basis has to be the canonical one or the sweep axes come out
            // rotated by whatever pose the last recenter froze.
            System.arraycopy(basisShiftN, 0, basisShiftM, 0, 16)
        }
        if (testSweep) {
            val t = (android.os.SystemClock.elapsedRealtime() - sweepT0) / 1000f
            // yaw (about Y) ±12°/10s + pitch (about X) ±8°/7s + roll (about
            // view axis Z) ±15°/13s. Small on purpose: the panel stays in
            // frame so screenshots (or eyes) can judge each axis. Roll must
            // spin the panel IN PLACE (centered, tilted); yaw/pitch pan it.
            val yaw = 12f * kotlin.math.sin(2 * Math.PI.toFloat() * t / 10f)
            val pitch = 8f * kotlin.math.sin(2 * Math.PI.toFloat() * t / 7f + 1.3f)
            val roll = 15f * kotlin.math.sin(2 * Math.PI.toFloat() * t / 13f + 2.1f)
            // The sweep builds the device→world rotation; the view matrix
            // (world→head) is its transpose.
            Matrix.setIdentityM(tmpA, 0)
            Matrix.rotateM(tmpA, 0, yaw, 0f, 1f, 0f)
            Matrix.rotateM(tmpA, 0, pitch, 1f, 0f, 0f)
            Matrix.rotateM(tmpA, 0, roll, 0f, 0f, 1f)
            Matrix.transposeM(headViewM, 0, tmpA, 0)
        }

        if (recenterPending) {
            recenterPending = false
            // The test sweep drives a fixed canonical basis — never anchor it.
            if (!testSweep) {
                // Full app-side recenter. GVR's own call (gvr_recenter_tracking)
                // "resets the yaw to zero, leaving pitch and roll unmodified" —
                // that only ever fixes left/right, so the vertical placement
                // stayed glued to whatever it was. Fold the WHOLE current head
                // pose into the basis instead: app-forward/app-up become
                // wherever the head is now — yaw, pitch and roll (the same full
                // 3DOF snap the pre-GVR renderer did in computeEffLocked).
                val preFwd = lastEffFwd
                if (Matrix.invertM(tmpA, 0, headViewM, 0)) {
                    Matrix.multiplyMM(basisShiftM, 0, tmpA, 0, basisShiftN, 0)
                }
                snapCount++
                val p = Math.toDegrees(kotlin.math.asin(preFwd[1].coerceIn(-1f, 1f).toDouble()))
                val y = Math.toDegrees(kotlin.math.atan2(preFwd[0].toDouble(), -preFwd[2].toDouble()))
                FileLog.i("SweepVR-basis", "recenter applied (snap=$snapCount tag=$snapTag " +
                    "yaw=${"%.1f".format(y)}° pitch=${"%.1f".format(p)}°)")
            }
        }

        synchronized(headViewM) {
            Matrix.multiplyMM(headWorldM, 0, headViewM, 0, basisShiftM, 0)
            if (!Matrix.invertM(invHeadWorldM, 0, headWorldM, 0)) Matrix.setIdentityM(invHeadWorldM, 0)
            // Gaze/tilt vectors live in WORLD space, so they come from the
            // INVERSE (head→world): forward = R·(0,0,-1), up = R·(0,1,0).
            val ihw = invHeadWorldM
            lastEffFwd = floatArrayOf(-ihw[8], -ihw[9], -ihw[10])
            lastEffUp = floatArrayOf(ihw[4], ihw[5], ihw[6])
        }

        // Screen-position sweep: run the window, then recenter — the app
        // basis absorbs the pose on the next frame (both axes).
        if (sweepRequest) {
            sweepRequest = false
            screenSweep = true
            screenSweepT0 = now()
            FileLog.i("SweepVR-menu", "screenpos sweep start")
        }
        if (screenSweep && now() - screenSweepT0 >= SCREEN_SWEEP_MS) {
            screenSweep = false
            recenter("screenpos")
            FileLog.i("SweepVR-menu", "screenpos sweep done -> recentered")
        }

        val cur = mode
        // Seek preview: take the newest decoded frame (if any) before anyone
        // samples it. Same hand-off as the web page capture — the extractor
        // thread cannot touch GL, and this is the only place that can.
        val tb = thumbBmpPending.getAndSet(null)
        if (tb != null && thumbTex() >= 0) {
            thumbBucket = thumbBucketIn
            thumbAspect = thumbAspectIn
            thumbFrameSeq++
            // Taken ownership of, NOT uploaded: the only thing ever drawn from
            // this texture is the finished card, composite below. Uploading
            // the bare frame here as well put an unbordered, unlabelled frame
            // on screen for the width of a texImage2D whenever the composite
            // did not immediately cover it.
            thumbBitmap?.recycle()
            thumbBitmap = tb
        }
        // A clear from another thread is finished here, where the bitmaps it
        // refers to are safe to release.
        if (thumbDrop) {
            thumbDrop = false
            cardBitmap?.recycle()
            cardBitmap = null
            thumbBitmap?.recycle()
            thumbBitmap = null
        }
        // The preview card is a composite of the picture and the time it
        // would drop at, so it is rebuilt when either changes — and only
        // then, so a drag does not re-upload it sixty times a second.
        //
        // A frame lands, or the clock moves while the picture on the card is
        // the frame for the span in hand. While the grip has run on to the
        // next span and its frame is still being fetched, neither happens and
        // the card stays as it was — picture and the time it was taken at,
        // agreeing with each other, rather than a fresh time over an old
        // picture. That is the whole of the flicker fix: the card never shows
        // a half-swapped state, it holds the last whole one until the next
        // whole one is ready.
        if (cur == Mode.VIDEO && menuOpen && thumbCardReady() &&
            (thumbFrameSeq != thumbCardSeq || thumbBucket == cardWantedSpan)) {
            maybeBuildThumbCard(fmtTime((menuSeekHoverU.coerceIn(0f, 1f) * menuDurMs).toLong()))
        }
        // Web page: upload the latest capture (if the page changed) before
        // anyone samples it.
        val wb = webBmpPending
        if (wb != null && webTexId >= 0) {
            webBmpPending = null
            try {
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, webTexId)
                GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, wb, 0)
                webConsumedFrames = ++webBmpConsumed
            } catch (_: Throwable) {
            }
        }
        // Dwell is suspended for the whole calibration sweep.
        if (!screenSweep) {
            when (cur) {
                Mode.BROWSER -> updateGaze()
                Mode.WEB -> {
                    // Every frame, panel open or not: the reticle falls back
                    // to this point when the gaze leaves the panel, and it
                    // was frozen while the panel was open because only the
                    // debug-gated path updated it.
                    updateWebPagePoint()
                    updateWebDebugPoint() // TEMP DEBUG: crosshair keeps tracking
                    // The tilt/hold update must run even while the panel is
                    // OPEN: it owns the open AND close transitions. Calling
                    // only updateGaze() when the panel was up meant the hold
                    // timer never decayed, so the close branch was
                    // unreachable - the panel stayed up until the X row was
                    // used, the dwell was never re-armed for the page, and
                    // the reticle had no live page point to fall back on.
                    updateWebPanelTilt()
                    if (webPanelOpen) updateGaze() else updateWebGaze()
                }
                Mode.VIDEO -> updateMenu()
            }
            if (cur != Mode.VIDEO) { menuOpen = false; menuHitValid = false }
        }
        // Panel fade (fast smoothstep both ways): browser fades with mode,
        // menu with menuOpen. Alphas gate the draw calls so a closing panel
        // keeps rendering until fully transparent.
        browAlpha = fadeAlpha(cur == Mode.BROWSER || (cur == Mode.WEB && webPanelOpen), true)
        menuAlpha = fadeAlpha(cur == Mode.VIDEO && menuOpen, false)

        // Video geometry: the web screen gets its own plain mesh, video gets
        // the method-doc set (§§5-6). Rebuilds are rare (density / curve /
        // layout / shaping changes only).
        ensureMeshes()
    }

    private var browAlpha = 0f
    private var menuAlpha = 0f
    private val tmpFov = com.google.vr.sdk.base.FieldOfView()
    /** One-shot log of GVR's per-eye off-axis term (convergence diagnostics). */
    private val projLogged = booleanArrayOf(false, false)

    /** Per-eye matrices (§3): proj from GVR with the convergence trim baked
     *  into proj[8]; O = eyeView·N; ov = proj·O. pinVideo drops the head
     *  rotation (FLAT keeps the parallel eye shift, domes are head-locked).
     *
     *  Two projections, never mixed: projM is the UNSCALED eye frustum and
     *  drives every panel, the reticle/tooltip and the FLAT screen — fov+/-
     *  must not resize the UI. projDomeM is the panoramic projection and
     *  carries the FOV scale (§6.3); domeOvM = projDomeM·(headView·N) feeds
     *  the dome and fisheye video only, with the same convergence trim so
     *  video and UI still fuse at one disparity. */
    private fun buildEyeMatrices(eye: Eye, physEye: Int) {
        System.arraycopy(eye.getPerspective(0.1f, 100f), 0, projM, 0, 16)
        // Convergence trim (§10): uniform clip-space offset per eye; polarity
        // matches the old physical frustum (left eye gets -ct). ADDED to
        // whatever GVR's off-axis frustum already carries — overwriting it
        // would throw away the viewer profile's physical convergence and
        // leave only the trim, which pushes the halves apart.
        val ct = convTrimNdc.coerceIn(-0.15f, 0.15f)
        val base8 = projM[8]
        val trim = if (physEye == 0) -ct else ct
        projM[8] = base8 + trim
        if (!projLogged[physEye]) {
            projLogged[physEye] = true
            val f = eye.fov
            FileLog.i("SweepVR-GL", "proj eye=$physEye base8=${"%.4f".format(base8)} " +
                "trim=$ct fovL=${"%.1f".format(f.left)} fovR=${"%.1f".format(f.right)}")
        }
        // Panoramic projection: identical to projM at fovScale 1, otherwise
        // the eye FOV angles scale — trim re-applied so disparity matches.
        val fs = fovScale.coerceIn(0.5f, 1.5f)
        if (fs == 1f) {
            System.arraycopy(projM, 0, projDomeM, 0, 16)
        } else {
            val f = eye.fov
            tmpFov.setAngles(f.left * fs, f.right * fs, f.bottom * fs, f.top * fs)
            tmpFov.toPerspectiveMatrix(0.1f, 100f, projDomeM, 0)
            projDomeM[8] = projDomeM[8] + trim
        }

        Matrix.multiplyMM(eyeViewNM, 0, eye.eyeView, 0, basisShiftM, 0)
        Matrix.multiplyMM(headViewNM, 0, headViewM, 0, basisShiftM, 0)
        if (pinVideo && mode == Mode.VIDEO) {
            if (projection == Projection.FLAT) {
                // Pinned flat screen keeps only the parallel eye shift:
                // eyeShift = eyeView · headView⁻¹ (a pure translation once
                // the head rotation cancels), then the basis shift.
                Matrix.invertM(tmpA, 0, headViewM, 0)
                Matrix.multiplyMM(tmpB, 0, eye.eyeView, 0, tmpA, 0)
                Matrix.setIdentityM(eyeShiftM, 0)
                eyeShiftM[12] = tmpB[12]; eyeShiftM[13] = tmpB[13]; eyeShiftM[14] = tmpB[14]
                Matrix.multiplyMM(tmpA, 0, eyeShiftM, 0, basisShiftM, 0)
                Matrix.multiplyMM(ovM, 0, projM, 0, tmpA, 0)
                Matrix.multiplyMM(domeOvM, 0, projDomeM, 0, tmpA, 0)
            } else {
                Matrix.multiplyMM(ovM, 0, projM, 0, basisShiftM, 0)
                Matrix.multiplyMM(domeOvM, 0, projDomeM, 0, basisShiftM, 0)
            }
        } else {
            Matrix.multiplyMM(ovM, 0, projM, 0, eyeViewNM, 0)
            // Dome (and fisheye) ride headView, never eyeView (§4.3 above):
            // the eye offset on a surrounding sphere shifts the seam and the
            // poles per eye, which reads as perspective swim when the head
            // turns. Flat keeps the eye offset (real parallaxy panel).
            Matrix.multiplyMM(domeOvM, 0, projDomeM, 0, headViewNM, 0)
        }
    }

    /** One eye: set this eye's viewport/scissor, clear, then the §3.3 draw
     *  order — video with depth on, panels/reticle/tooltip/toast with depth
     *  off, toast last. */
    private fun drawEye(eye: Eye) {
        val vp = eye.viewport
        vp.setGLViewport()
        vp.setGLScissor()
        GLES20.glEnable(GLES20.GL_SCISSOR_TEST)
        // GVR's own passes (clear/distortion) leave GL state as THEY like
        // it; premultiplied blend must be re-asserted every eye or the
        // panels and reticle paint their transparent texels opaque.
        blendAtEntry = GLES20.glIsEnabled(GLES20.GL_BLEND)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

        val physEye = if (eye.type == Eye.Type.RIGHT) 1 else 0
        // swapEyes: which texture half this eye samples (texcoords only).
        val uEye = if (swapEyes) 1 - physEye else physEye
        buildEyeMatrices(eye, physEye)

        val cur = mode
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        // Panels float over LIVE video in BROWSER mode too, so the queue
        // (and the draw) keep running there once frames have arrived.
        if (cur == Mode.WEB) { drawWeb(uEye); drawWebBar(); drawToolbar() }
        else if (cur == Mode.VIDEO || arrivedFrames > 0) drawVideo(uEye)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        if (cur == Mode.VIDEO) { if (menuAlpha > 0f) drawMenuPanel(menuAlpha) }
        else if (browAlpha > 0f) drawBrowser(browAlpha)
        // The preview picture sits on the panel it belongs to, after it (the
        // panel bitmap carries the card behind it) and before the head-locked
        // reticle and pills, which belong to the gaze rather than the control.
        if (cur == Mode.VIDEO && menuAlpha > 0f) drawSeekThumb(menuAlpha)
        // The keyboard is a surface, so it draws over the panels and under the
        // reticle - the reticle belongs to the gaze, the keyboard to the
        // control.
        drawKeyboard()
        drawHeadLocked()
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
    }

    /**
     * The seek preview's picture, in the card the panel bitmap drew behind
     * it: the image area of the card, at the card's own shape (never
     * stretched — a preview that is not the frame's shape is a lie about
     * what is on screen). One textured quad in the panel's plane, like the
     * anchored pill, so it tracks the grip and never needs clipping: the
     * picture is generated at exactly the aspect of the rect it fills.
     */

    // ---------------- keyboard: drawing ----------------

    /**
     * Rebuild the keyboard bitmap if the picture changed. Hash-guarded like
     *  every other panel, so a drag does not re-upload it sixty times a
     *  second. The rects come from the control, so what is drawn and what is
     *  hit are the same numbers.
     *
     *  The hash MUST include where the rows actually ARE, not just which row
     *  is live. Those are not the same on a row change: the switch redraws on
     *  the frame the button fires, which is while the drum is still at the
     *  FULL slide offset, and then the hash never changes again as it settles.
     *  So the drawn keys stayed one row off the rects the control tests, for
     *  ever, and the user had to aim a row above the letters they could see to
     *  type them. The gaze-to-key mapping was correct the whole time - the
     *  log showed every armed sample inside the band, at the right index.
     */
    /** True when the dwell keyboard is the live one. Sweep off means sweep
     *  gestures are rejected wholesale, so a sweep keyboard would be dead
     *  weight on screen. */
    private val dwellKeyboard get() = !sweepEnabled

    private fun maybeUploadDwellKeyboard() {
        if (kbTexId <= 0) return
        val w = kbdw.window
        if (w.isEmpty) return
        val active = kbdw.activeKey
        // The charged key's progress, quantised: it climbs every frame while a
        // key fills, and without this in the key the fill would never be drawn.
        val prog = ((active?.dwell?.progress ?: 0f) * 24f).toInt()
        val key = "D|" + kbdw.capsLabel() + "|" + kbdw.text + "|" +
            (active?.spec?.label ?: "") + "|" + prog + "|" + kbTipShown
        if (key == kbKey) return
        kbKey = key

        val bmp = kbBitmap ?: Bitmap.createBitmap(KBD_TEX_W, KBD_TEX_H, Bitmap.Config.ARGB_8888)
            .also { kbBitmap = it }
        val c = Canvas(bmp)
        c.drawColor(Color.TRANSPARENT)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val sx = KBD_TEX_W / w.width
        val sy = KBD_TEX_H / w.height
        fun tx(x: Float) = (x - w.left) * sx
        fun ty(y: Float) = (y - w.top) * sy

        p.style = Paint.Style.FILL
        p.color = Color.argb(238, 10, 14, 22)
        c.drawRoundRect(RectF(0f, 0f, KBD_TEX_W.toFloat(), KBD_TEX_H.toFloat()), 10f, 10f, p)

        for (k in kbdw.currentKeys) {
            val r = k.rect
            if (r.isEmpty) continue
            val x0 = tx(r.left); val y0 = ty(r.top)
            val x1 = tx(r.right); val y1 = ty(r.bottom)
            val on = active === k
            val progF = if (on) (k.dwell.progress.coerceIn(0f, 1f)) else 0f
            p.style = Paint.Style.FILL
            p.color = Color.argb(255, 26, 36, 52)
            c.drawRoundRect(x0, y0, x1, y1, 6f, 6f, p)
            // Charge is shown by the SHRINKING RETICLE, not by filling the
            // key: that is the indicator used everywhere else in the app, and
            // a second one here would mean a second rule to learn.
            if (progF >= 1f) {
                p.color = Color.argb(255, 56, 189, 248)
                c.drawRoundRect(x0, y0, x1, y1, 6f, 6f, p)
            }
            p.style = Paint.Style.STROKE
            p.strokeWidth = 1.9f
            p.color = if (on) Color.argb(255, 240, 246, 252) else Color.argb(255, 126, 148, 176)
            c.drawRoundRect(x0, y0, x1, y1, 6f, 6f, p)
            p.style = Paint.Style.FILL

            val cx = (x0 + x1) * 0.5f
            val cy = (y0 + y1) * 0.5f
            val t = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG)
            t.textAlign = Paint.Align.CENTER
            t.color = Color.argb(255, 226, 232, 240)
            when (k.spec.kind) {
                net.sweepvr.player.sweep.DwellKeyboardControl.Kind.SHIFT -> {
                    // An up arrow, and it fills when caps is engaged - the
                    // state is the only thing that key really communicates.
                    val u = kotlin.math.min(x1 - x0, y1 - y0) * 0.26f
                    val g = android.graphics.Path()
                    g.moveTo(cx, cy - u); g.lineTo(cx + u, cy + u * 0.7f)
                    g.lineTo(cx - u, cy + u * 0.7f); g.close()
                    p.color = if (kbdw.capsMode !=
                        net.sweepvr.player.sweep.DwellKeyboardControl.CapsMode.LOWER)
                        Color.argb(255, 56, 189, 248) else Color.argb(255, 226, 232, 240)
                    p.style = Paint.Style.FILL
                    c.drawPath(g, p)
                    p.style = Paint.Style.STROKE
                    p.strokeWidth = 3f
                    p.strokeCap = Paint.Cap.ROUND
                    p.strokeJoin = Paint.Join.ROUND
                    c.drawPath(g, p)
                    p.strokeCap = Paint.Cap.BUTT
                    p.style = Paint.Style.FILL
                }
                net.sweepvr.player.sweep.DwellKeyboardControl.Kind.BKSP -> {
                    val u = kotlin.math.min(x1 - x0, y1 - y0) * 0.22f
                    val g = android.graphics.Path()
                    g.moveTo(cx + u, cy - u * 0.7f); g.lineTo(cx + u, cy + u * 0.7f)
                    g.lineTo(cx - u * 0.2f, cy + u * 0.7f)
                    g.lineTo(cx - u, cy); g.lineTo(cx - u * 0.2f, cy - u * 0.7f)
                    g.close()
                    p.color = Color.argb(255, 226, 232, 240)
                    p.style = Paint.Style.FILL
                    c.drawPath(g, p)
                    p.style = Paint.Style.STROKE
                    p.strokeWidth = 3f
                    p.strokeCap = Paint.Cap.ROUND
                    p.strokeJoin = Paint.Join.ROUND
                    c.drawPath(g, p)
                    p.strokeCap = Paint.Cap.BUTT
                    p.style = Paint.Style.FILL
                }
                net.sweepvr.player.sweep.DwellKeyboardControl.Kind.ENTER -> {
                    // A return arrow as ONE path: across the top, down the
                    // right, then diagonally away to the lower left with the
                    // arrowhead ON that end of the stroke. Drawn as loose
                    // pieces it read as two unrelated angled lines.
                    // A RIGHT ANGLE, not a diagonal: stem down the right side,
                    // turn 90 degrees into a horizontal, chevron pointing left.
                    // Every earlier attempt drew a diagonal into the arrowhead
                    // and it read as a thin "7" - the shape was wrong, not just
                    // the weight.
                    val u = kotlin.math.min(x1 - x0, y1 - y0) * 0.30f
                    val stemX = cx + u * 0.62f
                    val stemTop = cy - u * 0.95f
                    val tipX = cx - u * 0.72f
                    val tipY = cy + u * 0.42f
                    val g = android.graphics.Path()
                    g.moveTo(stemX, stemTop)
                    g.lineTo(stemX, tipY)
                    g.lineTo(tipX, tipY)
                    // Barbs symmetric about the direction of travel. Written
                    // as two literal offsets they came out at different
                    // angles - one at 45 degrees, one vertical - which is what
                    // made the head look broken rather than hand-drawn.
                    // Symmetric about the horizontal, opening to the right.
                    val bl = u * 0.66f
                    for (sgn in intArrayOf(1, -1)) {
                        val a = sgn * 0.62
                        g.moveTo(tipX, tipY)
                        g.lineTo(tipX + (bl * kotlin.math.cos(a)).toFloat(),
                                 tipY + (bl * kotlin.math.sin(a)).toFloat())
                    }
                    p.color = Color.argb(255, 226, 232, 240)
                    p.style = Paint.Style.STROKE
                    p.strokeWidth = 4f
                    p.strokeCap = Paint.Cap.ROUND
                    p.strokeJoin = Paint.Join.ROUND
                    c.drawPath(g, p)
                    p.strokeCap = Paint.Cap.BUTT
                    p.style = Paint.Style.FILL
                }
                net.sweepvr.player.sweep.DwellKeyboardControl.Kind.DISMISS -> {
                    // A cross, drawn as a cross rather than the letter X so it
                    // matches the sweep keyboard's dismiss.
                    val u = kotlin.math.min(x1 - x0, y1 - y0) * 0.20f
                    p.color = Color.argb(255, 226, 232, 240)
                    p.style = Paint.Style.STROKE
                    p.strokeWidth = 3f
                    p.strokeCap = Paint.Cap.ROUND
                    c.drawLine(cx - u, cy - u, cx + u, cy + u, p)
                    c.drawLine(cx + u, cy - u, cx - u, cy + u, p)
                    p.strokeCap = Paint.Cap.BUTT
                    p.style = Paint.Style.FILL
                }
                else -> {
                    t.textSize = kotlin.math.min(x1 - x0, y1 - y0) * 0.42f
                    // Show the letters as they will be TYPED. The shift key
                    // lights when caps is up, which tells you the mode; it
                    // does not tell you what the row will produce. The sweep
                    // keyboard did the same.
                    val show = if (kbdw.capsMode ==
                        net.sweepvr.player.sweep.DwellKeyboardControl.CapsMode.LOWER)
                        k.spec.label else k.spec.label.uppercase()
                    c.drawText(show, cx, cy - (t.descent() + t.ascent()) * 0.5f, t)
                }
            }
        }

        val tip = kbTipShown
        if (tip != null) {
            val hk = kbdw.keyLabelAt(kbHitX(), kbHitY())
            if (hk != null) {
                val r = hk.rect
                val tp = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG)
                tp.color = Color.argb(255, 126, 148, 176)
                tp.textSize = 21f
                tp.textAlign = Paint.Align.CENTER
                val tw = tp.measureText(tip)
                val cx = (r.left + r.right) * 0.5f
                // Above the key, unless it is in the top row where that would
                // be off the top of the keyboard.
                val cy = if (r.top < w.height * 0.25f) r.bottom + 19f else r.top - 19f
                val halfW = tw * 0.5f + 9f
                val pill = RectF(tx(cx - halfW), ty(cy - 14f), tx(cx + halfW), ty(cy + 14f))
                p.style = Paint.Style.FILL
                p.color = Color.argb(238, 15, 23, 34)
                c.drawRoundRect(pill, 7f, 7f, p)
                p.style = Paint.Style.STROKE
                p.strokeWidth = 1.4f
                p.color = Color.argb(255, 126, 148, 176)
                c.drawRoundRect(pill, 7f, 7f, p)
                p.style = Paint.Style.FILL
                c.drawText(tip, tx(cx), ty(cy) - (tp.descent() + tp.ascent()) * 0.5f, tp)
                p.strokeWidth = 1.9f
            }
        }

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, kbTexId)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
    }

    private fun kbHitX(): Float = kbHit?.get(0) ?: -9999f
    private fun kbHitY(): Float = kbHit?.get(1) ?: -9999f

    private fun maybeUploadKeyboard() {
        if (kbTexId <= 0) return
        val w = kbd.window
        if (w.isEmpty) return
        val row = kbd.activeRowName
        val caps = kbd.capsLabel()
        val txt = kbd.text
        val held = kbd.heldLabel
        // The live row's top, quantised: it moves through every value while
        // the drum slides, so this redraws the slide and the settled frame
        // after it.
        val rowTop = kbd.activeRowBand.top.toInt()
        // The hover label belongs in the redraw key. Nothing else about it
        // reaches the bitmap, so without this the tooltip is computed every
        // frame and then never painted.
        val tip = kbTipShown
        val key = "$row|$caps|$txt|$held|${w.width.toInt()}|$rowTop|$tip"
        if (key == kbKey) return
        kbKey = key

        val bmp = kbBitmap ?: Bitmap.createBitmap(KBD_TEX_W, KBD_TEX_H, Bitmap.Config.ARGB_8888)
            .also { kbBitmap = it }
        val c = Canvas(bmp)
        c.drawColor(Color.TRANSPARENT)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val sx = KBD_TEX_W / w.width
        val sy = KBD_TEX_H / w.height
        fun tx(x: Float) = (x - w.left) * sx
        fun ty(y: Float) = (y - w.top) * sy

        p.style = Paint.Style.FILL
        p.color = Color.argb(238, 10, 14, 22)
        c.drawRoundRect(RectF(0f, 0f, KBD_TEX_W.toFloat(), KBD_TEX_H.toFloat()), 10f, 10f, p)

        /** The space key: a face, and the space BAR drawn on it.
         *
         *  A word was wrong twice over - it was five letters shrunk into a key,
         *  and then "space" is a label for the key rather than a picture of
         *  what it does. This is the symbol: a bar with a stem standing on it,
         *  which is what a spacebar looks like from the front. */
        fun spaceKey(r: Rect, live: Boolean, arm: Boolean,
                     entry: Set<net.sweepvr.player.sweep.Side> = emptySet()) {
            val x0 = tx(r.left); val y0 = ty(r.top); val x1 = tx(r.right); val y1 = ty(r.bottom)
            val rad = 6f
            p.style = Paint.Style.FILL
            p.color = if (arm) Color.argb(255, 56, 189, 248)
                      else if (live) Color.argb(255, 26, 36, 52)
                      else Color.argb(140, 15, 21, 31)
            val path = dipKeyPath(x0, y0, x1, y1, entry, rad, KBD_DIP, KBD_DIP_H)
            c.drawPath(path, p)
            p.style = Paint.Style.STROKE
            p.strokeWidth = 1.9f
            p.color = when {
                arm -> KBD_ARMED_EDGE
                live -> KBD_EDGE_LIVE
                else -> KBD_EDGE_IDLE
            }
            c.drawPath(path, p)

            val w = x1 - x0
            val h = y1 - y0
            val cx = (x0 + x1) * 0.5f
            val base = y1 - h * 0.30f
            val halfW = w * 0.30f
            val stem = h * 0.20f
            p.strokeWidth = (h * 0.07f).coerceAtLeast(2f)
            p.strokeCap = Paint.Cap.ROUND
            p.color = if (live || arm) Color.WHITE else Color.argb(80, 200, 210, 225)
            val g = Path()
            g.moveTo(cx - halfW, base)
            g.lineTo(cx + halfW, base)
            // Stems at the ENDS, not the middle. A stem in the centre reads as
            // a single object standing on the bar - a divider, or a plinth -
            // rather than as the bar itself being the thing you press. Two
            // marks at the ends describe a surface with a left and a right,
            // which is what a spacebar has.
            g.moveTo(cx - halfW, base)
            g.lineTo(cx - halfW, base - stem)
            g.moveTo(cx + halfW, base)
            g.lineTo(cx + halfW, base - stem)
            c.drawPath(g, p)
            p.strokeCap = Paint.Cap.BUTT
        }

        /** An icon key: the face, then a glyph drawn as a path.
         *
         *  Words are the wrong thing on these. "DEL" and "BKSP" on a key are
         *  four or five letters shrunk to fit, which reads as noise and, at
         *  this size, as more letters than the key can hold. The glyph carries
         *  one idea in one shape and stays legible from across the room. */
        fun iconKey(r: Rect, kind: String, live: Boolean, arm: Boolean,
                    entry: Set<net.sweepvr.player.sweep.Side> = emptySet()) {
            val x0 = tx(r.left); val y0 = ty(r.top); val x1 = tx(r.right); val y1 = ty(r.bottom)
            val rad = 6f
            p.style = Paint.Style.FILL
            p.color = if (arm) Color.argb(255, 56, 189, 248)
                      else if (live) Color.argb(255, 26, 36, 52)
                      else Color.argb(140, 15, 21, 31)
            val path = dipKeyPath(x0, y0, x1, y1, entry, rad, KBD_DIP, KBD_DIP_H)
            c.drawPath(path, p)
            p.style = Paint.Style.STROKE
            p.strokeWidth = 1.9f
            p.color = if (arm) KBD_ARMED_EDGE else KBD_EDGE_LIVE
            c.drawPath(path, p)

            val w = x1 - x0
            val h = y1 - y0
            val cx = (x0 + x1) * 0.5f
            val cy = (y0 + y1) * 0.5f
            val stroke = (h * 0.055f).coerceAtLeast(2f)
            p.strokeWidth = stroke
            p.strokeCap = Paint.Cap.ROUND
            p.strokeJoin = Paint.Join.ROUND
            p.color = if (live || arm) Color.WHITE else Color.argb(80, 200, 210, 225)
            val g = Path()
            when (kind) {
                // CLR had no case and fell through to `else`, which draws an X.
                // So the clear button on the right was rendered as a second
                // dismiss, and the keyboard appeared to have an X over there.
                "CLR" -> {
                    val t = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG)
                    t.color = if (live || arm) Color.WHITE else Color.argb(80, 200, 210, 225)
                    t.textSize = h * 0.34f
                    t.textAlign = Paint.Align.CENTER
                    c.drawText("CLR", cx, cy - (t.descent() + t.ascent()) * 0.5f, t)
                }
                // Return: up the right side, then away to the left.
                // Return: up the right side, then away to the left. Sized off
                // the SHORTER axis, because the edge columns are narrow and
                // tall - sizing off the height ran the glyph out of the box.
                "ENT" -> {
                    val u = kotlin.math.min(w, h)
                    val right = cx + u * 0.20f
                    val left = cx - u * 0.22f
                    val top = cy - u * 0.22f
                    val bot = cy + u * 0.24f
                    val head = u * 0.15f
                    g.moveTo(right, bot); g.lineTo(right, top)
                    g.lineTo(left, top)
                    g.moveTo(left + head, top - head)
                    g.lineTo(left, top)
                    g.lineTo(left + head, top + head)
                }
                // Backspace: a point on the left with a cross inside it.
                // Backspace: a point on the left with a cross inside it. Again
                // off the shorter axis, and inset from the face so the point
                // does not touch the rounded corner.
                "DEL" -> {
                    val u = kotlin.math.min(w, h)
                    val inset = u * 0.16f
                    val tip = cx - u * 0.34f
                    val shoulder = cx - u * 0.06f
                    val back = cx + u * 0.36f
                    val halfH = u * 0.22f
                    g.moveTo(shoulder, cy - halfH)
                    g.lineTo(back, cy - halfH)
                    g.lineTo(back, cy + halfH)
                    g.lineTo(shoulder, cy + halfH)
                    g.lineTo(tip, cy)
                    g.close()
                    val q = u * 0.13f
                    val mx = cx + u * 0.12f
                    g.moveTo(mx - q, cy - q); g.lineTo(mx + q, cy + q)
                    g.moveTo(mx + q, cy - q); g.lineTo(mx - q, cy + q)
                }
                // Cancel: a cross.
                else -> {
                    val s = kotlin.math.min(w, h) * 0.22f
                    g.moveTo(cx - s, cy - s); g.lineTo(cx + s, cy + s)
                    g.moveTo(cx + s, cy - s); g.lineTo(cx - s, cy + s)
                }
            }
            c.drawPath(g, p)
            p.strokeCap = Paint.Cap.BUTT
        }

        fun key(r: Rect, label: String, live: Boolean, arm: Boolean,
                entry: Set<net.sweepvr.player.sweep.Side> = emptySet()) {
            val x0 = tx(r.left); val y0 = ty(r.top); val x1 = tx(r.right); val y1 = ty(r.bottom)
            val rad = 6f
            p.style = Paint.Style.FILL
            p.color = when {
                arm -> Color.argb(255, 56, 189, 248)
                live -> Color.argb(255, 26, 36, 52)
                else -> Color.argb(140, 15, 21, 31)
            }
            val path = dipKeyPath(x0, y0, x1, y1, entry, rad, KBD_DIP, KBD_DIP_H)
            c.drawPath(path, p)
            p.style = Paint.Style.STROKE
            p.strokeWidth = 1.9f
            p.color = when {
                arm -> KBD_ARMED_EDGE
                live -> KBD_EDGE_LIVE
                else -> KBD_EDGE_IDLE
            }
            c.drawPath(path, p)
            val t = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG)
            t.color = if (live) Color.WHITE else Color.argb(80, 200, 210, 225)
            t.textSize = (y1 - y0) * 0.56f
            t.textAlign = Paint.Align.CENTER
            c.drawText(label, (x0 + x1) * 0.5f,
                (y0 + y1) * 0.5f - (t.descent() + t.ascent()) * 0.5f, t)
        }

        val barLabels = kbd.barLabels
        for (i in barLabels.indices) {
            val lbl = if (barLabels[i] == "Aa") caps else barLabels[i]
            val active = when (lbl) {
                "123" -> row == "123"
                "abc" -> row == "abc"
                "Sym2" -> row == "Sym2"
                else -> false
            }
            val r0 = kbd.barKeyRect(kbd.topBar, i, barLabels.size)
            val r1 = kbd.barKeyRect(kbd.bottomBar, i, barLabels.size)
            // Highlight on the key's OWN label, not on `lbl`. `lbl` is what
            // gets DRAWN, and for Aa that is the live caps state - aaa, Aaa or
            // AAA - while the control reports the key as "Aa". Comparing the
            // drawn text against the control's label meant Aa never matched
            // and never lit, so it was the one bar key with no feedback on its
            // one non-idempotent action. Every other key draws and tests the
            // same string, which is why only this one looked broken.
            val arm = kbd.isBarKeyHeld(barLabels[i])
            if (barLabels[i] == "BKSP") {
                iconKey(r0, "DEL", true, arm, kbd.topBarEntry(i))
                iconKey(r1, "DEL", true, arm, kbd.bottomBarEntry(i))
            } else {
                key(r0, lbl, true, arm, kbd.topBarEntry(i))
                key(r1, lbl, true, arm, kbd.bottomBarEntry(i))
            }
        }
        fun ec(e: net.sweepvr.player.sweep.KeyboardControl.EdgeKey) = kbd.edgeEntry(e)
        iconKey(kbd.enterTop, "ENT", true, held == "ENT", ec(net.sweepvr.player.sweep.KeyboardControl.EdgeKey.ENTER_TOP))
        iconKey(kbd.enterBottom, "ENT", true, held == "ENT", ec(net.sweepvr.player.sweep.KeyboardControl.EdgeKey.ENTER_BOTTOM))
        iconKey(kbd.clearTop, "CLR", true, held == "CLR", ec(net.sweepvr.player.sweep.KeyboardControl.EdgeKey.CLEAR_TOP))
        iconKey(kbd.clearKey, "CLR", true, held == "CLR", ec(net.sweepvr.player.sweep.KeyboardControl.EdgeKey.CLEAR_BOTTOM))
        iconKey(kbd.cancelTop, "X", true, held == "X", ec(net.sweepvr.player.sweep.KeyboardControl.EdgeKey.DISMISS_TOP))
        iconKey(kbd.cancelBottom, "X", true, held == "X", ec(net.sweepvr.player.sweep.KeyboardControl.EdgeKey.DISMISS_BOTTOM))
        iconKey(kbd.delWordTop, "DEL", true, held == "DEL", ec(net.sweepvr.player.sweep.KeyboardControl.EdgeKey.DELWORD_TOP))
        iconKey(kbd.delWordBottom, "DEL", true, held == "DEL", ec(net.sweepvr.player.sweep.KeyboardControl.EdgeKey.DELWORD_BOTTOM))

        for (r in 0..2) {
            val live = r == kbd.activeRowIndex
            val chars = kbd.rowKeys(r)
            for (i in chars.indices) {
                // Upper case whenever caps is up at all - SINGLE as well as
                // LOCK. It used to lower-case anything but LOCK, so Aaa drew
                // the letters exactly as they would be typed after the first
                // character, which is the one moment the row should be
                // announcing that caps is engaged. TYPING resets SINGLE to
                // LOWER, so the row drops back on its own after one
                // character, which is the point of the middle mode.
                val shown = if (live && chars[i].length == 1 && chars[i][0].isLetter() &&
                    kbd.capsMode != net.sweepvr.player.sweep.KeyboardControl.CapsMode.LOWER)
                    chars[i] else chars[i].lowercase()
                val ent = kbd.charEntry()
                if (chars[i] == " ")
                    spaceKey(kbd.charRect(r, i), live, live && held == " ", ent)
                else
                    key(kbd.charRect(r, i), shown, live,
                        live && i == kbd.heldCharIndex, ent)
            }
        }

        if (tip != null) {
            // Placed on the drum side of the key: below it for the top bar,
            // which has nothing above it but the edge of the keyboard, and
            // above it for everything else.
            val hk = kbd.hoveredKey()
            if (hk != null) {
                val r = hk.first
                val tp = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG)
                // The light border colour, matching the pill's own outline.
                // It was the pill's fill - rgb(15,23,34) on rgb(15,23,34) -
                // so the label was dark on dark and simply invisible.
                tp.color = Color.argb(255, 126, 148, 176)
                tp.textSize = 21f
                tp.textAlign = Paint.Align.CENTER
                val tw = tp.measureText(tip)
                val below = r.top <= kbd.topBar.bottom
                val cx = (r.left + r.right) * 0.5f
                val cy = if (below) r.bottom + 19f else r.top - 19f
                val halfW = tw * 0.5f + 9f
                val pill = RectF(tx(cx - halfW), ty(cy - 14f),
                    tx(cx + halfW), ty(cy + 14f))
                p.style = Paint.Style.FILL
                p.color = Color.argb(238, 15, 23, 34)
                c.drawRoundRect(pill, 7f, 7f, p)
                p.style = Paint.Style.STROKE
                p.strokeWidth = 1.4f
                p.color = Color.argb(255, 126, 148, 176)
                c.drawRoundRect(pill, 7f, 7f, p)
                p.style = Paint.Style.FILL
                c.drawText(tip, tx(cx), ty(cy) - (tp.descent() + tp.ascent()) * 0.5f, tp)
                p.strokeWidth = 1.9f
            }
        }

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, kbTexId)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
    }

        /**
     * Name whichever non-character key the gaze is resting on, after a delay.
     *
     * Independent of every sweep rule on purpose - see kbTipKey. `gaze` is the
     * keyboard-pixel reading, and null clears it, so a label cannot outlive
     * the gaze leaving the keyboard.
     */
    private fun stepKeyTip(gaze: FloatArray?) {
        val label = if (gaze == null) null
            else if (dwellKeyboard) kbdw.labelAt(gaze[0], gaze[1])
            else kbd.labelAt(gaze[0], gaze[1])
        if (label == null) {
            kbTipKey = null
            kbTipShown = null
            return
        }
        if (label != kbTipKey) {
            kbTipKey = label
            kbTipAt = now()
            kbTipShown = null
            return
        }
        if (kbTipShown == null && now() - kbTipAt >= KBD_TIP_DELAY)
            kbTipShown = label
    }

/**
     * Gaze -> keyboard pixels, and whether it is over the keyboard.
     *
     * The keyboard is head-locked on the plane z = -panelDistM, dropped by
     * kbDropY, and this is the ray against exactly that plane - so the pixel
     * under the reticle and the pixel the control is stepped with are the same
     * number by construction.
     *
     * The point is returned even when the gaze is OFF the keyboard, clamped to
     * a band one window wide beyond it, and that is deliberate. It used to
     * return null instead and the caller stepped a magic far-away point, which
     * sits to the upper LEFT of every key - so a key released that way always
     * read as a LEFT exit. DELWORD commits through its RIGHT edge, so it could
     * never fire, while ENTER, which commits left, fired on any release at all.
     * That is the whole of "the del word key doesn't work, or is that enter?".
     *
     * Real geometry preserves the exit side; a constant cannot.
     */
    private fun keyboardGaze(): FloatArray? {
        val w = kbd.window
        if (w.isEmpty || !kbPlaced) return null
        val f = lastEffFwd
        // Ray against the keyboard's own world plane: origin at the head,
        // normal the basis it faces on. The drawn quad and the hit-tested
        // plane are then the same plane by construction, at whatever depth the
        // surface behind it is.
        val hx = invHeadWorldM[12] - kbPoint[0]
        val hy = invHeadWorldM[13] - kbPoint[1]
        val hz = invHeadWorldM[14] - kbPoint[2]
        // The normal points from the surface AT the eye, so it opposes the
        // gaze direction: n.f is negative for any ray heading at the plane.
        // Dividing the positive distance by it gave a negative t every frame,
        // the guard below rejected every frame, and the keyboard silently
        // stopped engaging - AND stopped claiming the frame, so the page behind
        // it stayed live and its links fired through. The denominator is
        // negated so t is the distance ALONG THE GAZE.
        val ndotf = kbNormal[0] * f[0] + kbNormal[1] * f[1] + kbNormal[2] * f[2]
        if (ndotf > -1e-4f) return null
        val t = (hx * kbNormal[0] + hy * kbNormal[1] + hz * kbNormal[2]) / -ndotf
        if (t <= 0f || t.isNaN()) return null
        // hx/hy/hz are ALREADY the head relative to kbPoint, so this is
        // hit - kbPoint and nothing more. Subtracting kbPoint a second time
        // gave hit - 2*kbPoint, which is invisible on the horizontal axis
        // (kbPoint.x is 0) and shifts the vertical axis by the full height of
        // the anchor: 1.67m, or 1.56x the key band. Every vertical coordinate
        // was wrong by that factor, so a key armed only when the reticle was
        // far from it. The eye X was exact, which is what kept this hidden.
        val px = hx + f[0] * t
        val py = hy + f[1] * t
        val pz = hz + f[2] * t
        val u = ((px * kbRight[0] + py * kbRight[1] + pz * kbRight[2]) / kbWorldHalfW) * 0.5f + 0.5f
        val v = 0.5f - ((px * kbUp[0] + py * kbUp[1] + pz * kbUp[2]) / kbWorldHalfH) * 0.5f
        // Unclamped in u, clamped in v: a gaze far above the keyboard must
        // not turn into a wild sideways reading that releases a bar key.
        val x = (w.left + u * w.width).coerceIn(w.left - w.width, w.right + w.width)
        val y = (w.top + v * w.height).coerceIn(w.top - w.height, w.bottom + w.height)
        return floatArrayOf(x, y, if (u in 0f..1f && v in 0f..1f) 1f else 0f)
    }

    /** Step the keyboard for this frame. True when it owns the gaze. */
    /** [still] is the gaze-is-holding-still answer, needed by the dwell
     *  keyboard (a moving reticle must never charge a key). The sweep
     *  keyboard ignores it. Defaults true so the panel path need not know. */
    private fun stepKeyboard(dtMs: Long, still: Boolean = true): Boolean {
        // Placement has to happen here, every frame: it reads the surface
        // behind the keyboard, which MOVES with the head and with the page.
        // Without it kbPlaced stayed false for ever, and a false kbPlaced makes
        // keyboardGaze() return null and drawKeyboard() bail out - the
        // keyboard silently never appeared at all.
        placeKeyboard()
        if (now() - kbAliveT > 1000L) {
            kbAliveT = now()
            FileLog.i("SweepVR-kbd", "tick open=$kbOpen placed=$kbPlaced alpha=$kbAlpha")
        }
        // Just kbOpen. The `|| kbAlpha > 0.01f` was meant to soften the close,
        // but it is self-sustaining: once closed with kbAlpha at 1 the term is
        // true, so the next line clamps kbAlpha back to 1 and it can never
        // reach the 0.01 that clears the condition. The keyboard was logically
        // closed - kbOpen false, controls reset, page live again - while still
        // drawn at full opacity, so it looked present but swallowed every
        // gesture. Exactly "it thinks it's hidden but it's still showing".
        kbAlphaWant = kbOpen
        kbAlpha = if (kbAlphaWant) minOf(1f, kbAlpha + dtMs / KBD_FADE_MS)
                  else maxOf(0f, kbAlpha - dtMs / KBD_FADE_MS)
        if (!kbOpen) {
            kbd.reset(); kbdw.reset(); kbHit = null
            kbTipKey = null; kbTipShown = null
            return false
        }
        if (kbTexId <= 0) return false
        val g = keyboardGaze()
        if (g == null) { kbHit = null; kbd.reset(); kbdw.reset(); return false }
        val over = g[2] > 0.5f
        // Stepped either way: leaving the keyboard has to reach the controls so
        // a key in hand can resolve, and the geometry decides how.
        val owned = if (dwellKeyboard) kbdw.step(g[0], g[1], dtMs, still)
                    else kbd.step(g[0], g[1], dtMs)
        kbdDwellProg = if (dwellKeyboard) (kbdw.activeKey?.dwell?.progress ?: 0f) else 0f
        // What the control was actually handed, against what it believes the
        // live row is. Two rounds of reasoning about this mapping were both
        // wrong, so it is measured rather than deduced.
        //
        // ONE format string, whole. Written as a concatenation it silently
        // formatted only the second half - `.format` binds tighter than `+` -
        // so the line that exists to be read came out as literal `%.0f`
        // placeholders and was no use at all.
        kbHit = if (over) floatArrayOf(g[0], g[1]) else null
        stepKeyTip(if (over) g else null)
        // The WHOLE surface claims, not just the bits with a key on them.
        // Requiring `owned` left the gaps live - between bar keys, the space
        // bar's blank margins, the margins either side of it - and a link
        // behind the keyboard could be dwelled through one of them. The
        // keyboard is opaque artwork; nothing behind it is reachable.
        if (over) return true
        return owned
    }

    /** Sweep the address field to open the keyboard. True on the frame it
     *  fires, so the toolbar's own buttons do not also fire underneath. */
    private fun stepAddrField(u: Float, v: Float, dtMs: Long, still: Boolean = true): Boolean {
        if (kbOpen) {
            addrArmed = false
            addrOpen.reset()
            addrDwell.reset()
            return false
        }
        // Bar space is y-up and v-up; the control is y-down, so flip.
        val r = net.sweepvr.player.sweep.Rect(TB_ADDR_U0, 1f - TB_V1, TB_ADDR_U1, 1f - TB_V0)
        if (r.isEmpty) return false
        val cfg = net.sweepvr.player.sweep.SweepConfig(
            entrySides = setOf(net.sweepvr.player.sweep.Side.Top,
                net.sweepvr.player.sweep.Side.Bottom),
            leeway = 0f, rearm = 0f,
            refuseCorners = true, cornerFraction = 0.18f,
            tieBreak = net.sweepvr.player.sweep.TieBreak.REFUSE)
        return when (val ev = addrOpen.step(net.sweepvr.player.sweep.Pt(u, 1f - v), r, cfg)) {
            is net.sweepvr.player.sweep.SweepEvent.Entered -> {
                addrArmed = true
                toolbarAddrFocused = true
                true
            }
            is net.sweepvr.player.sweep.SweepEvent.Engaged -> true
            is net.sweepvr.player.sweep.SweepEvent.Released -> {
                val fire = addrArmed &&
                    ev.side != net.sweepvr.player.sweep.Side.Left &&
                    ev.side != net.sweepvr.player.sweep.Side.Right
                addrArmed = false
                if (fire) openKeyboard(webBarUrl)
                fire
            }
            net.sweepvr.player.sweep.SweepEvent.Idle -> { addrArmed = false; false }
        }
    }

    private fun drawKeyboard() {
        if (kbAlpha <= 0.01f || kbTexId <= 0) return
        // Everything below is control-agnostic - it draws whatever is in
        // kbTexId on the basis the hit test uses - so only the upload differs.
        if (dwellKeyboard) maybeUploadDwellKeyboard() else maybeUploadKeyboard()
        if (!kbPlaced) return
        // Built from the same orthonormal basis the hit test uses, so the drawn
        // quad and the tested plane cannot drift apart. A yaw-only rotation
        // would have been wrong here: the screen is tilted, so square-on means
        // pitched as well.
        val hw = kbWorldHalfW
        val hh = kbWorldHalfH
        tmpA[0] = kbRight[0] * hw; tmpA[1] = kbRight[1] * hw
        tmpA[2] = kbRight[2] * hw; tmpA[3] = 0f
        tmpA[4] = kbUp[0] * hh; tmpA[5] = kbUp[1] * hh
        tmpA[6] = kbUp[2] * hh; tmpA[7] = 0f
        tmpA[8] = kbNormal[0]; tmpA[9] = kbNormal[1]
        tmpA[10] = kbNormal[2]; tmpA[11] = 0f
        tmpA[12] = kbPoint[0]; tmpA[13] = kbPoint[1]
        tmpA[14] = kbPoint[2]; tmpA[15] = 1f
        Matrix.multiplyMM(mvpM, 0, ovM, 0, tmpA, 0)
        putQuad(ptrVerts, ptrTex, -1f, -1f, 1f, 1f)
        drawQuadTex(kbTexId, mvpM, kbAlpha)
    }

    // ---------------- keyboard: open and close ----------------

    /** True while the keyboard owns an edit. */
    val keyboardOpen: Boolean get() = kbOpen

    /** Open the keyboard on [value], which the caller then shows live. */

    /**
     * What the keyboard is editing. It changes what a commit MEANS, so it has
     * to be part of opening the keyboard rather than something inferred later
     * from whether the address bar happens to be showing: ENTER on an address
     * navigates, ENTER on a page field writes the value back into the page,
     * and those two share every key, every gesture and every line of
     * rendering.
     */
    enum class KeyboardTarget { ADDRESS, PAGE_FIELD }

    private var kbTarget = KeyboardTarget.ADDRESS

    /** Where the keyboard hangs, in page UV. Defaults to the top centre,
     *  which is right for the address bar and wrong for everything else: a
     *  keyboard editing a field in the middle of the page appeared below the
     *  address bar, nowhere near what it was editing. Set from the field's
     *  own rectangle when one is being edited. */
    private var kbAnchorU = 0.5f
    private var kbAnchorV = 0f

    /** Hang the keyboard under this point of the page. [v] is page UV, so
     *  pass the BOTTOM of the field: placeKeyboard drops the keyboard by its
     *  own half-height from here, which puts it just below. */
    fun setKeyboardAnchor(u: Float, v: Float) {
        kbAnchorU = u.coerceIn(0f, 1f)
        kbAnchorV = v.coerceIn(0f, 1f)
    }

    /** True when the keyboard is editing a field inside the page. */
    val keyboardIsField: Boolean get() = kbTarget == KeyboardTarget.PAGE_FIELD

    fun openKeyboard(value: String, target: KeyboardTarget = KeyboardTarget.ADDRESS) {
        kbTarget = target
        kbOpen = true
        onKeyboardOpened?.invoke()
        if (dwellKeyboard) {
            val w = kbdw
            w.beginEdit(value)
            w.changed = { onKeyboardText?.invoke(w.text) }
            w.committed = { closeKeyboard(true, it) }
            w.cancelled = { closeKeyboard(false, w.text) }
            w.onTrace = { FileLog.i("SweepVR-kbd", "dwell $it") }
            onKeyboardText?.invoke(w.text)
            return
        }
        kbd.beginEdit(value)
        kbd.changed = {
            onKeyboardText?.invoke(kbd.text)
            val g = kbHit
            if (g != null) kbd.prime(g[0], g[1])
        }
        // Every entry, fire and cancel, into the app log. Guessing at why a
        // key does nothing has been the wrong tool repeatedly in this session;
        // the log answers it directly.
        kbd.onTrace = { FileLog.i("SweepVR-kbd", it) }
        kbd.committed = { closeKeyboard(true, it) }
        FileLog.i("SweepVR-kbd", "opened: placed=$kbPlaced " +
            "point=%.2f,%.2f,%.2f half=%.2fx%.2f normal=%.2f,%.2f,%.2f".format(
                kbPoint[0], kbPoint[1], kbPoint[2], kbWorldHalfW, kbWorldHalfH,
                kbNormal[0], kbNormal[1], kbNormal[2]))
        kbd.cancelled = { closeKeyboard(false, kbd.text) }
        onKeyboardText?.invoke(kbd.text)
        val g = kbHit
        if (g != null) kbd.prime(g[0], g[1])
    }

    /** Text changed: the owner redraws whatever shows it. */
    var onKeyboardText: ((String) -> Unit)? = null
    /** The keyboard took an edit. */
    var onKeyboardOpened: (() -> Unit)? = null

    /** ENTER committed ([navigate] true) or X abandoned the edit. */
    var onKeyboardClose: ((Boolean, String) -> Unit)? = null

    fun closeKeyboard(navigate: Boolean, text: String) {
        kbOpen = false
        kbd.reset()
        kbdw.reset()
        kbHit = null
        kbTipKey = null
        kbTipShown = null
        onKeyboardClose?.invoke(navigate, text)
    }


    private fun drawSeekThumb(alpha: Float) {
        if (!thumbCardReady() || thumbTex() < 0 || cardBitmap == null) return
        val card = thumbCard()
        panelQuad(card[0], card[1], card[2], card[3])
        drawQuadTex(thumbTex(), ovM, alpha)
    }

    /** Dwell progress 0..1 for the browser pointer (full ring → point).
     *  Rows, the X close button and the scroll strips each keep their OWN
     *  accumulator and drain the others while hovered, so the pointer has to
     *  read whichever one is live — checking browProgF alone left the ring at
     *  full size over the close buttons (and strips) even while they fired. */
    private fun browserDwellProg(): Float =
        maxOf(browProgF, xProgF, scrollTrigF).coerceIn(0f, 1f)

    /** Sweep progress 0..1 (reticle shrinks across the window). */
    private fun screenSweepProg(): Float =
        if (!screenSweep) 0f
        else ((now() - screenSweepT0).toFloat() / SCREEN_SWEEP_MS).coerceIn(0f, 1f)

    /** Dwell progress 0..1 for the menu pointer. */
    private fun menuDwellProg(): Float =
        if (menuHighlight != -2) menuProg[menuSlot(menuHighlight)].coerceIn(0f, 1f) else 0f

    private val clipV = FloatArray(4)

    /** Reticle + tooltip + toast: head-locked world quads drawn with ov. Any
     *  point on the head ray projects to the same screen point, so the
     *  reticle lands on the hovered hit no matter how deep it sits. */
    private fun drawHeadLocked() {
        val cur = mode
        // With the web panel up, the gaze is on its rows, so the reticle
        // animates with the PANEL's dwell (it was reading the page's, which
        // is always idle then, so rows never shrank).
        val panelGaze = cur == Mode.WEB && webPanelOpen
        // While the flyout owns the gaze there is no dwell, so progress is
        // pinned to 0: the reticle would otherwise shrink as if arming, which
        // reads as a broken affordance rather than a cursor.
        val bookGaze = panelGaze && webBookIconRow >= 0 && bookActive
        // Dwell fills shrink the reticle (icon fill before open, row fill
        // after); sweep keeps the pinned 0 while the flyout owns the gaze.
        val flyoutProg =
            if (panelGaze && webBookIconRow >= 0 && !sweepEnabled) bookControl.dwellProgress else 0f
        val prog = if (bookGaze && sweepEnabled) 0f else if (screenSweep) screenSweepProg() else
            if (panelGaze) maxOf(browserDwellProg(), flyoutProg) else
            if (cur == Mode.WEB) maxOf(webDwellProg(), toolbarDwellProg, barDwellProg, kbdDwellProg) else
            if (cur == Mode.BROWSER) browserDwellProg() else menuDwellProg()
        // The reticle is ALWAYS up in web mode: the page is the pointer.
        val show = testSweep || screenSweep || cur == Mode.WEB || cur == Mode.BROWSER ||
            (cur == Mode.VIDEO && menuOpen && (menuHitValid || prog > 0f))
        if (show) drawPointer(prog, reticleTexId, 1f)
        if (cur == Mode.VIDEO && menuOpen) reticleDiag(show, prog)
        if (cur == Mode.VIDEO && aimArmed) drawPointer(aimProg, aimTexId, 2f)
        // The pill belongs to the menu: follow its fade, and never outlive
        // it (tooltipVisible only clears when the gaze LEAVES the panel, so
        // gating on the flag alone left it hanging after the menu closed).
        if (cur == Mode.VIDEO && menuOpen && menuAlpha > 0f && tooltipVisible) drawTooltip()
        drawToast()
    }

    private var reticleDiagT = 0L
    /** Blend state GVR handed us at the top of the last eye draw. */
    private var blendAtEntry = true
    /** Throttled reticle health dump: is it drawn, where does it land,
     *  is the texture real. */
    private fun reticleDiag(show: Boolean, prog: Float) {
        if (now() - reticleDiagT < 500L) return
        reticleDiagT = now()
        val d = panelDistM
        headLockedAt(tmpA, 0f, 0f, -d)
        Matrix.multiplyMM(mvpM, 0, ovM, 0, tmpB, 0)
        Matrix.multiplyMV(clipV, 0, mvpM, 0, floatArrayOf(0f, 0f, -d, 1f), 0)
        val w = clipV[3]
        val s = kotlin.math.sin(RETICLE_ANG) * d * (1f - 0.85f * prog.coerceIn(0f, 1f))
        FileLog.i("SweepVR-GL", "ret show=$show prog=$prog hl=$menuHighlight hit=$menuHitValid " +
            "s=${"%.4f".format(s)} d=$d tex=$reticleTexId isTex=${GLES20.glIsTexture(reticleTexId)} " +
            "ndc=${"%.3f".format(clipV[0] / w)},${"%.3f".format(clipV[1] / w)} w=${"%.2f".format(w)} " +
            "alpha=${"%.2f".format(menuAlpha)} blendIn=$blendAtEntry")
    }

    /** Head-locked ring at panel depth, shrinking (1 - 0.85·prog) to a point.
     *  Placed in HEAD space (invHeadWorldM⁻¹·ov) so it rides the gaze ray:
     *  ov alone would pin it to a fixed world point, which lands off-screen
     *  as soon as the head tilts to the menu. */
    private fun drawPointer(prog: Float, texId: Int, sizeMul: Float) {
        // Over the page the reticle belongs at the page's depth, not the
        // panel's: a near reticle over a far screen is offset per eye.
        // Until the first page hit (or after the gaze leaves it) fall back to
        // the screen centre's distance, so the reticle never hops between the
        // page depth and the panel depth.
        if (mode == Mode.WEB && !webPanelOpen && webPagePointOk) {
            // On the page: sit exactly on the sampled surface point, so the
            // reticle and the page converge at the same distance in each
            // eye. Placing it "d metres along the ray" left the stereo pair
            // slightly wider than the page it was pointing at.
            // ON the page point where the gaze ray lands. That point is
            // world-anchored, which is correct for a cursor on a flat
            // screen: it moves as the head moves because the ray genuinely
            // sweeps across the page. Deriving the position from the ray
            // instead (head-locked at a fixed distance) put the reticle on
            // a rigid stalk that slid the opposite way.
            webReticleAt(webPagePoint)
            putQuad(ptrVerts, ptrTex, -webRetS(sizeMul, prog), -webRetS(sizeMul, prog),
                webRetS(sizeMul, prog), webRetS(sizeMul, prog))
            Matrix.multiplyMM(mvpM, 0, ovM, 0, tmpA, 0)
            drawQuadTex(texId, mvpM)
            return
        }
        // The panel floats at browserElevDeg ABOVE eye level, so the reticle
        // has to sit on the same point of the sphere - placing it dead ahead
        // at eye level put it below the panel, which is where the gaze lands
        // when you look up at the menu. (It is drawn over the panel whether or
        // not the panel is open, so this also covers the web-panel case.)
        val d = panelDistM
        // Off the panel: fall back to the page if the gaze is still on it,
        // otherwise hold the last known point rather than snapping to a
        // centre that is nowhere near what the user is looking at.
        // Order matters. While the panel is open the gaze belongs to the
        // panel, so the panel wins; only when the ray misses the panel does
        // the reticle fall back to the page. Putting the panel's own hit
        // ahead of the page point (both are panel data while it is open)
        // meant the reticle could never leave the panel at all.
        // In the gap (above the panel, or off its edge) the ray still has to
        // drive the reticle. Holding the last position instead froze it at a
        // fixed spot - the same spot every time, and whichever spot the gaze
        // last crossed - which is what it did above the menu. Ray-following
        // is continuous through the gap, so it slides on and off the panel.
        val onPanel = webPanelPoint(ptrTmp3)
        if (!onPanel && !webPagePointOk) {
            webReticleRay(d)
            val sr = webRetS(sizeMul, prog)
            putQuad(ptrVerts, ptrTex, -sr, -sr, sr, sr)
            Matrix.multiplyMM(mvpM, 0, ovM, 0, tmpA, 0)
            drawQuadTex(texId, mvpM)
            return
        }
        val tgt = when {
            onPanel -> ptrTmp3
            webPagePointOk -> webPagePoint
            else -> webLastPoint
        }
        webReticleAt(tgt)
        val sr = webRetS(sizeMul, prog)
        putQuad(ptrVerts, ptrTex, -sr, -sr, sr, sr)
        Matrix.multiplyMM(mvpM, 0, ovM, 0, tmpA, 0)
        drawQuadTex(texId, mvpM)
    }

    /** Head-to-screen-centre distance, the reticle's depth before the gaze
     *  has landed on the page. 0 if the screen is not there. */
    private fun webScreenCentreDist(): Float {
        webPointAt(0.5f, 0.5f, wpTmp)
        val dx = wpTmp[0] - invHeadWorldM[12]
        val dy = wpTmp[1] - invHeadWorldM[13]
        val dz = wpTmp[2] - invHeadWorldM[14]
        return kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
    }

    /** tmpB = invHeadWorldM · T(x,y,z): a head-space offset in world coords,
     *  ready to premultiply by ovM. */
    private fun headLockedAt(t: FloatArray, x: Float, y: Float, z: Float) {
        Matrix.setIdentityM(t, 0)
        Matrix.translateM(t, 0, x, y, z)
        // ovM is world->clip, so the point handed to it must be in WORLD
        // space: headWorldM (head->world) maps the head-local offset out to
        // the world. Composing invHeadWorldM (world->head) here applied the
        // head pose twice, which is a no-op only while the pose is identity
        // and a wrong depth/rotation everywhere else - so head-locked items
        // sat at the right screen spot but the wrong distance in each eye,
        // and recenter (which writes a real rotation into the basis) is
        // exactly when it started to show.
        Matrix.multiplyMM(tmpB, 0, headWorldM, 0, t, 0)
    }


    /** The pill's centre in PANEL design space when it is anchored to a
     *  control (above and to the right of it); otherwise the old head-locked
     *  spot under the reticle. Set with the text, read by the draw. */
    private var tipAnchored = false
    private var tipU = 0f
    private var tipV = 0f

    /** Gaze tooltip pill. A button's pill floats in the panel's own plane,
     *  just above and to the right of the control being held, so it sits
     *  WITH the control instead of riding the gaze — looking at the control
     *  and finding the pill somewhere else below it is the whole complaint.
     *  The seek bar does the same while the grip is dragged (the pill then
     *  reads the position the drop commits to), and keeps the head-locked
     *  spot under the reticle on a bare hover, where it stands in for the
     *  time readout and has to follow the scrub point.
     *  Half-extents are design units × menuScale(), so the pill keeps its
     *  apparent size at every panel distance (same rule as the menu rect). */
    private fun drawTooltip() {
        maybeUploadTooltip()
        if (!tooltipVisible) return
        val k = menuScale()
        if (tipAnchored) {
            panelQuad(tipU - TIP_HALF_W, tipV - TIP_HALF_H,
                      tipU + TIP_HALF_W, tipV + TIP_HALF_H)
            drawQuadTex(tipTexId, ovM, menuAlpha)
            return
        }
        putQuad(ptrVerts, ptrTex, -TIP_HALF_W * k, -TIP_HALF_H * k, TIP_HALF_W * k, TIP_HALF_H * k)
        headLockedAt(tmpA, 0f, -1.0f * k, -panelDistM)
        Matrix.multiplyMM(mvpM, 0, ovM, 0, tmpB, 0)
        drawQuadTex(tipTexId, mvpM, menuAlpha)
    }

    /** One vertex of a design-space rect into ptrVerts: the panel's own
     *  centre + (right·u + up·v)·menuScale(). The panel's right axis is
     *  (1,0,0), so its contribution is just u·k. Member (not local) so the
     *  per-frame path allocates nothing. Vertex and texel order match
     *  [putQuad]. */
    private fun panelVert(u: Float, v: Float, cy: Float, cz: Float,
                          uy: Float, uz: Float, k: Float) {
        ptrVerts.put(u * k)
        ptrVerts.put(cy + uy * v * k)
        ptrVerts.put(cz + uz * v * k)
    }

    /** Fill ptrVerts/ptrTex with the design-space rect [u0,u1]×[v0,v1]
     *  lying in the panel's plane — the same centre/normal/up the menu grid
     *  is built from in drawMenuPanel — so the quad shares the panel's
     *  orientation exactly. */
    private fun panelQuad(u0: Float, v0: Float, u1: Float, v1: Float) {
        val d = panelDistM
        val el = Math.toRadians(menuElevCurrent().toDouble()).toFloat()
        val k = menuScale()
        val cx = 0f
        val cy = kotlin.math.sin(el) * d
        val cz = -kotlin.math.cos(el) * d
        var nx = -cx; var ny = -cy; var nz = -cz
        val nl = kotlin.math.sqrt(nx * nx + ny * ny + nz * nz).coerceAtLeast(1e-6f)
        ny /= nl; nz /= nl
        // up = n × (1,0,0) = (0, nz, -ny)
        val uy = nz; val uz = -ny
        ptrVerts.clear()
        panelVert(u0, v1, cy, cz, uy, uz, k)   // top-left
        panelVert(u0, v0, cy, cz, uy, uz, k)   // bottom-left
        panelVert(u1, v1, cy, cz, uy, uz, k)   // top-right
        panelVert(u1, v0, cy, cz, uy, uz, k)   // bottom-right
        ptrVerts.position(0)
        ptrTex.clear()
        ptrTex.put(0f); ptrTex.put(0f)
        ptrTex.put(0f); ptrTex.put(1f)
        ptrTex.put(1f); ptrTex.put(0f)
        ptrTex.put(1f); ptrTex.put(1f)
        ptrTex.position(0)
    }

    /** 512×96 pill bearing the current tooltip text; re-uploaded only when
     *  the text changes (cache hit per frame otherwise). */
    private fun maybeUploadTooltip() {
        val txt = tooltipText
        if (txt == lastTipText && tipBitmap != null) return
        lastTipText = txt
        val W = 512; val H = 96
        val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.TRANSPARENT)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = Color.argb(224, 10, 14, 22)
        c.drawRoundRect(2f, 2f, (W - 2).toFloat(), (H - 2).toFloat(), 20f, 20f, p)
        p.color = Color.rgb(125, 211, 252)
        p.style = Paint.Style.STROKE; p.strokeWidth = 3f
        c.drawRoundRect(2f, 2f, (W - 2).toFloat(), (H - 2).toFloat(), 20f, 20f, p)
        p.style = Paint.Style.FILL
        p.color = Color.WHITE; p.textSize = 56f; p.textAlign = Paint.Align.CENTER
        val t = txt.take(28)
        var ts = 56f
        while (ts > 16f && p.measureText(t) > W - 40f) { ts -= 2f; p.textSize = ts }
        c.drawText(t, W / 2f, H / 2f + ts * 0.35f, p)
        p.textAlign = Paint.Align.LEFT
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tipTexId)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
        tipBitmap?.recycle()
        tipBitmap = bmp
    }

    /** One textured quad (ptrVerts/ptrTex, world coords) through prog2d. */
    private fun drawQuadTex(texId: Int, mvp: FloatArray, alpha: Float = 1f) {
        GLES20.glUseProgram(prog2d)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId)
        GLES20.glUniform1i(uTex2d, 0)
        GLES20.glUniform1f(uAlpha2d, alpha.coerceIn(0f, 1f))
        GLES20.glUniformMatrix4fv(uMvp2d, 1, false, mvp, 0)
        GLES20.glEnableVertexAttribArray(aPos2d)
        GLES20.glVertexAttribPointer(aPos2d, 3, GLES20.GL_FLOAT, false, 0, ptrVerts)
        GLES20.glEnableVertexAttribArray(aTex2d)
        GLES20.glVertexAttribPointer(aTex2d, 2, GLES20.GL_FLOAT, false, 0, ptrTex)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(aPos2d)
        GLES20.glDisableVertexAttribArray(aTex2d)
    }

    // scratch buffers: zero per-frame allocation on the GL thread (direct
    // ByteBuffer churn caused GC strobes that read as pointer flicker)
    private val v4 = FloatArray(4)
    private val ptrVerts: FloatBuffer = ByteBuffer.allocateDirect(12 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
    private val ptrTex: FloatBuffer = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
    private fun putQuad(vb: FloatBuffer, tb: FloatBuffer, x0: Float, y0: Float, x1: Float, y1: Float) {
        vb.clear()
        vb.put(x0); vb.put(y1); vb.put(0f)
        vb.put(x0); vb.put(y0); vb.put(0f)
        vb.put(x1); vb.put(y1); vb.put(0f)
        vb.put(x1); vb.put(y0); vb.put(0f)
        vb.position(0)
        tb.clear()
        tb.put(0f); tb.put(0f)
        tb.put(0f); tb.put(1f)
        tb.put(1f); tb.put(0f)
        tb.put(1f); tb.put(1f)
        tb.position(0)
    }

    /** Zoom number z for the finite FLAT quad: texture magnification about
     *  the half-image center (the flat quad keeps texture zoom, §5). Domes
     *  slide their sphere toward the viewer instead and upload 1 here. */

    /** Texture zoom for the web page: always 1.
     *
     *  The page capture is 16:9 and screenDims() builds a 16:9 screen for
     *  it, so the texture covers the surface exactly - there is never
     *  anything to crop, and magnifying the page is what webScreenSize
     *  does: the capture is stretched across the screen, so a larger
     *  screen shows the SAME whole page, bigger. Cropping is therefore
     *  redundant for the page. The page is sized by its own preference,
     *  never by the video's (settings.screenSize / settings.videoZoom) -
     *  the web panel used to write the video's control and changed the
     *  picture behind the user's back. */
    private fun webZoomF(): Float = 1f

    // ---------- gaze ----------
    // Panel size grows sub-linearly with distance (sqrt): distance changes
    // stay clearly visible (nearer = bigger) while extremes stay comfortable.
    // A linear width (= constant on-screen size) hid the control entirely.
    // Matches stock 0.96 half-width at the 2.4 m default.
    private fun panelHalfW(): Float = 0.62f * kotlin.math.sqrt(panelDistM)
    private fun panelHalfH(): Float = panelHalfW() * 0.62f

    // last ray↔panel hit in panel-world coords (for the at-depth cursor)
    private var hitX = 0f
    private var hitY = 0f
    private var hitZ = -2.4f
    private var hitValid = false
    // hovered slider fraction (panel-wide u) for the live tooltip; -1 = none
    private var sliderHoverU = -1f
    // gaze u where the last slider dwell fired; sliding the gaze along the
    // bar re-arms shrink + fire without leaving the row (NaN = disarmed)
    private var lastFiredU = Float.NaN
    // head-forward at the last slider fire: re-arm requires the HEAD to have
    // moved, so a panel resize shifting the mapping under a steady gaze
    // can't trigger a spurious feedback-loop refire
    private var lastFiredFwd = floatArrayOf(0f, 0f, -1f)
    // stillness gate state: dwell must NEVER fire while the head is moving,
    // or slow test turns sweep the gaze across rows and trigger accidental
    // navigation + recentering mid-turn (reads as rotation/pan glitches).
    private val dwellPrevM = FloatArray(16).also { Matrix.setIdentityM(it, 0) }
    private var dwellPrevT = 0L
    private var dwellPrevInit = false

    /** Dead band in panel px: the requested tolerance in reticle widths,
     *  clamped to what the panel can actually give. Past this far outside
     *  the pane an exit counts; inside it, nothing happens. */
    private fun bookIconRect(): FloatArray {
        if (webBookIconRow < 0) return floatArrayOf(0f, 0f, 0f, 0f)
        if (mode == Mode.WEB && webSideBtns) {
            // Open web panel: fixed top-right slot under PgUp/PgDn, in no
            // row. webBookIconRow is only a present-flag here.
            return floatArrayOf(BOOK_ICON_X0, BOOK_ICON_Y0,
                BOOK_ICON_X0 + BOOK_BTN, BOOK_ICON_Y0 + BOOK_BTN)
        }
        // Anywhere else: legacy row-homed rect, exactly as before.
        val i = webBookIconRow
        // Sits BELOW its row, not centred on it: at 2x the row height a
        // centred button would spill up over the row above and hide it.
        val y0 = (ROWS_Y0 + i * ROW_H).toFloat()
        return floatArrayOf(TEX * 0.5f - BOOK_BTN * 0.5f, y0,
            TEX * 0.5f + BOOK_BTN * 0.5f, y0 + BOOK_BTN)
    }

    /** Space from the pane's edge to the panel's edge, in px. That is the
     *  only room there is to travel before an exit can register. */
    /** Pane rect, or null if it does not fit below the icon - then it goes
     *  above instead, so a long list stays reachable and the bottom-cancel
     *  exit always exists. */
    private fun bookClose() {
        bookControl.close()
        bookOpen = false; bookCursor = -1; bookActive = false
        bookScroll = 0; bookArrow = 0
    }

    /** The bookmarks flyout. Returns true if it handled this frame, in
     *  which case the normal row/dwell logic must not run.
     *
     *  Enter the icon from the side and the pane opens; enter from above or
     *  below and the reticle passes straight over, inert - no pane and no
     *  dwell, so resting on it vertically does nothing. Inside the pane the
     *  highlight follows the reticle with no timer. Leave it sideways to
     *  commit the row under the reticle, or up/down to cancel. A dead band
     *  around the pane absorbs overshoot so only a deliberate exit counts. */
    /**
     * The bookmarks dropdown, delegated to the shared sweep engine.
     *
     * Everything that used to live here - which edges open it, the leeway, the
     * pane layout, the cursor, the scroll arrows, when a release is a choice
     * rather than an accident - now lives in DropdownControl, where it is
     * covered by tests that run without a phone. This only supplies the
     * things that genuinely need a Canvas: the label measurement that sets
     * the pane width, and the tracing.
     */
    private fun bookFlyout(bx: Float, by: Float, onPanel: Boolean, dtMs: Long, still: Boolean = true): Boolean {
        val icon = bookIconRect()
        if (icon[2] <= icon[0]) return false
        val c = bookControl
        c.texH = TEX.toFloat()
        c.iconRect = net.sweepvr.player.sweep.Rect(icon[0], icon[1], icon[2], icon[3])
        // The pane hugs its longest label, so measure it here rather than
        // teaching the control about text metrics.
        val mp = bookMeasurePaint
        mp.textSize = BOOK_PANE_TEXT
        var widest = 0f
        for (it in webBookList) widest = maxOf(widest, mp.measureText(it.first))
        c.paneHalfW = widest * 0.5f + BOOK_PANE_PAD_X
        c.rowH = BOOK_PANE_ROW_H
        c.arrowH = BOOK_ARROW_H
        c.gap = BOOK_PANE_GAP
        c.cornerFraction = BOOK_BTN_R / maxOf(1f, icon[2] - icon[0])
        c.leeway = bookLeewayPx(c.paneHalfW)
        c.rearm = BOOK_REARM_SLOP
        c.items = webBookList.map { net.sweepvr.player.sweep.DropdownControl.Item(it.first, it.second) }
        if (bookDbg) c.onTrace = { FileLog.i("SweepVR-book", it) } else c.onTrace = null
        c.onCommit = { onWebEvent(WebEvent.Navigate(it.value)) }
        bookLastX = bx; bookLastY = by
        bookOpen = c.open
        bookCursor = c.cursor
        bookScroll = c.scroll
        bookArrow = c.arrow
        bookActive = c.open
        c.dwellMode = !sweepEnabled
        c.dwellMs = dwellMs
        return c.step(bx, by, dtMs, still).also { bookOpen = c.open; bookActive = c.open }
    }

    /**
     * Leeway outside the control before a release counts. The request is in
     * reticle widths, but it is clamped to the pane's own margin: honoured
     * literally the band would reach the panel edge, leaving nowhere to
     * travel to commit and no gesture that could ever complete.
     */
    private fun bookLeewayPx(paneHalfW: Float): Float {
        val retPx = TEX * kotlin.math.sin(RETICLE_ANG) * panelDistM / panelHalfW()
        val margin = maxOf(0f, TEX * 0.5f - paneHalfW)
        return minOf(bookTolRet * retPx, minOf(margin * BOOK_TOL_FRAC, maxOf(0f, margin - retPx)))
    }

    private fun updateGaze() {
        val rows = browserRows
        if (rows.isEmpty()) { highlight = -1; hitValid = false; inXZone = false; xDwellFired = false;
            sideBtnDir = 0; sideBtnFired = false; sideBtnProg = 0f; scrollTrigF = 0f; sliderHoverU = -1f; lastFiredU = Float.NaN; return }
        // New listing (entered a directory): the rows under a motionless
        // reticle are different rows now, so no retained progress, latch or
        // highlight may survive - otherwise the entry that lands under the
        // gaze inherits a full charge and fires as if stared at. Scrolling
        // keeps the same list object and is unaffected.
        if (rows !== lastDwellRows) {
            lastDwellRows = rows
            highlight = revealHighlight
            // Scroll the reveal into view HERE, not via the highlight: gaze
            // landing anywhere overwrites highlight later this same frame,
            // before ensureVisible ever sees it - so a reveal with the gaze
            // on-panel scrolled nowhere, and only an off-panel gaze worked.
            // Positioning scrollPos directly survives any later hover.
            if (revealHighlight in rows.indices) {
                // Centre the reveal in the window (context either side),
                // clamped at the ends where that is impossible. Always
                // applied, not just when off-screen: opening Files lands on
                // the playing file mid-list, not at its top or bottom edge.
                val pin = pinTopRows.coerceIn(0, 2)
                val win = if (pin > 0 || rows.size > VISIBLE_ROWS) winRows(pin) else VISIBLE_ROWS
                val maxS = maxOf(0, rows.size - pin - win).toFloat()
                FileLog.i("SweepVR-reveal", "reset reveal=$revealHighlight win=$win scrollPos=$scrollPos")
                scrollPos = (revealHighlight - pin - win / 2f).coerceIn(0f, maxS)
                FileLog.i("SweepVR-reveal", "scrolled to scrollPos=$scrollPos")
            }
            revealHighlight = -1
            dwellFiredFor = -2; browProgF = 0f
            inXZone = false; xDwellFired = false; xProgF = 0f
            sideBtnDir = 0; sideBtnFired = false; sideBtnProg = 0f
            scrollEngage = 0; scrollTrigDir = 0; scrollTrigF = 0f
            sliderHoverU = -1f; lastFiredU = Float.NaN; fireBlockedLogged = false
        }
        if (now() < inputGraceUntil) { dwellStart = now(); sliderHoverU = -1f; return }
        // The flyout swallows the whole panel while it is up: rows must not
        // highlight or dwell underneath it. It is deliberately NOT an early
        // return - the gesture resolves by carrying the gaze off the panel
        // edge, so bookFlyout() below still has to see every frame. Returning
        // here starved it of the frames where the exit is detected, and the
        // pane simply never closed or selected.
        if (webBookIconRow >= 0 && bookActive) {
            inXZone = false
            sideBtnDir = 0; sideBtnFired = false
            sideBtnProg = 0f
            sliderHoverU = -1f
            highlight = if (bookOpen) -1 else webBookIconRow
            dwellFiredFor = -2
            browProgF = 0f
        }
        // Windowed stillness + leaky dwell (same as the play menu): jitter
        // and row churn only dent progress instead of zeroing the timer.
        val nowMs = now()
        val still = synchronized(headViewM) { browserStill.update(headViewM, nowMs) }
        val dtMs = (nowMs - browProgT).coerceIn(0L, 500L)
        browProgT = nowMs
        if (!still) browProgF = maxOf(0f, browProgF - dtMs / 600f)
        // head-forward ray in world (panel floats at browserElevDeg,
        // facing the viewer; elevation 0 = centered at z=-D, identical math).
        // Uses the recentered orientation, so the reticle and the hover agree.
        val o = invHeadWorldM        // head position in world
        val fwd = lastEffFwd
        val fx = fwd[0]; val fy = fwd[1]; val fz = fwd[2]
        val d = panelDistM
        val el = Math.toRadians(browserElevDeg.toDouble()).toFloat()
        val cx = 0f; val cy = (kotlin.math.sin(el) * d); val cz = (-kotlin.math.cos(el) * d)
        var pnx = -cx; var pny = -cy; var pnz = -cz
        val pnl = kotlin.math.sqrt(pnx * pnx + pny * pny + pnz * pnz).coerceAtLeast(1e-6f)
        pnx /= pnl; pny /= pnl; pnz /= pnl
        val denom = fx * pnx + fy * pny + fz * pnz
        if (webBookIconRow >= 0 && bookActive && denom >= -0.05f) {
            // The gaze has turned away from the panel plane entirely, so the
            // intersection below cannot run. Feed the flyout a point far
            // outside the pane, in the direction it was last heading, so the
            // gesture can still resolve instead of hanging open forever.
            val lx = bookLastX; val ly = bookLastY
            val away = if (lx < TEX * 0.5f) -TEX * 2f else TEX * 2f
            if (bookFlyout(away, ly, false, dtMs)) return
        }
        if (denom < -0.05f) {
            val t = ((cx - o[12]) * pnx + (cy - o[13]) * pny + (cz - o[14]) * pnz) / denom
            val hx = o[12] + fx * t; val hy = o[13] + fy * t; val hz = o[14] + fz * t
            // panel coords: right = (1,0,0); up = n × right = (0, nz, -ny)
            val upx = 0f; val upy = pnz; val upz = -pny
            val upl = kotlin.math.sqrt(upy * upy + upz * upz).coerceAtLeast(1e-6f)
            val hw = panelHalfW(); val hh = panelHalfH()
            val alongRight = hx - cx
            val alongUp = ((hx - cx) * upx + (hy - cy) * (upy / upl) + (hz - cz) * (upz / upl))
            // Panel-relative px, valid even when the point is OFF the panel:
            // the flyout's sideways exit happens past the edge, so clamping
            // here first meant the exit could never be seen.
            // Compact mapping: on the open web panel rows 0..hc are painted
            // top-aligned, so the gaze follows the shrunken surface; anywhere
            // else hc is TEX and this is the old full-panel mapping exactly.
            val hhc = hh * panelHc() / TEX
            val bxAll = ((alongRight + hw) / (2 * hw) * TEX)
            val byAll = ((hh - alongUp) / (2 * hhc) * panelHc())
            val onPanelRect = alongRight >= -hw && alongRight <= hw &&
                alongUp >= hh - 2 * hhc && alongUp <= hh
            if (webBookIconRow >= 0) {
                // The flyout owns the frame while it is up, on or off panel.
                if (bookFlyout(bxAll, byAll, onPanelRect, dtMs, still)) return
                // The icon owns its own rect even when the flyout does not
                // engage: on the open web panel it overlaps live rows, so a
                // rest on it - or a refused top/bottom approach - must go
                // inert here rather than fall through and dwell the row
                // underneath.
                val icon = bookIconRect()
                if (bxAll >= icon[0] && bxAll <= icon[2] &&
                    byAll >= icon[1] && byAll <= icon[3]) {
                    highlight = -1; dwellFiredFor = -2
                    sliderHoverU = -1f
                    browProgF = maxOf(0f, browProgF - dtMs / 600f)
                    return
                }
            }
            // The keyboard owns the frame while it is up, over the panel as
            // well as over the page: the same "a gesture surface cannot have a
            // dwell fire underneath it" rule as barSweep on the web side.
            if (stepKeyboard(dtMs)) { hitValid = false; return }

            if (onPanelRect) {
                hitX = hx; hitY = hy; hitZ = hz; hitValid = true
                val u = (alongRight + hw) / (2 * hw)
                val v = byAll / TEX
                // PgUp / PgDn, web panel only. Checked before the rows so a
                // gaze on a button never selects the row underneath it.
                if (webSideBtns) {
                    val bx = u * TEX
                    val by = v * TEX
                    val onUp = bx >= SIDE_BTN_X0 && bx <= SIDE_BTN_X1 &&
                        by >= SIDE_BTN_UP_Y0 && by < SIDE_BTN_UP_Y0 + SIDE_BTN_H
                    val onDn = bx >= SIDE_BTN_X0 && bx <= SIDE_BTN_X1 &&
                        by >= SIDE_BTN_DN_Y0 && by < SIDE_BTN_DN_Y0 + SIDE_BTN_H
                    if (onUp || onDn) {
                        val dir = if (onUp) -1 else 1
                        sliderHoverU = -1f
                        lastFiredU = Float.NaN
                        fireBlockedLogged = false
                        highlight = -1; dwellFiredFor = -2
                        browProgF = maxOf(0f, browProgF - dtMs / 600f)
                        scrollEngage = 0; scrollTrigDir = 0; scrollTrigF = 0f
                        if (sideBtnDir != dir) { sideBtnDir = dir; sideBtnProg = 0f; sideBtnFired = false }
                        if (!sideBtnFired) {
                            if (still) sideBtnProg += dtMs / dwellMs.toFloat()
                            else sideBtnProg = maxOf(0f, sideBtnProg - dtMs / 600f)
                        }
                        if (still && !sideBtnFired && sideBtnProg >= 1f) {
                            sideBtnFired = true
                            sideBtnProg = 1f
                            FileLog.i("SweepVR-web", "FIRE ${if (dir < 0) "PageUp" else "PageDown"}")
                            onWebEvent(WebEvent.PageKey(dir))
                        }
                        return
                    }
                    sideBtnDir = 0; sideBtnFired = false
                    sideBtnProg = maxOf(0f, sideBtnProg - dtMs / 600f)
                }
                // X close button, top right of the title bar - everywhere
                // EXCEPT the open web panel. There it was removed on purpose:
                // closing it left the gaze inside the panel's visibility
                // range, so the hold timer sprang it open again (it closes on
                // gaze return instead). File/settings/shaping/sensor panels
                // have no such auto-open, so without this they cannot close.
                if (!webPanelOpen && u > 0.90f && v * TEX < TITLE_Y1) {
                    inXZone = true
                    sliderHoverU = -1f
                    lastFiredU = Float.NaN
                    fireBlockedLogged = false
                    highlight = -1; dwellFiredFor = -2
                    browProgF = maxOf(0f, browProgF - dtMs / 600f)
                    if (!xDwellFired) {
                        if (still) xProgF += dtMs / dwellMs.toFloat()
                        else xProgF = maxOf(0f, xProgF - dtMs / 600f)
                    }
                    if (still && !xDwellFired && xProgF >= 1f) {
                        xDwellFired = true
                        xProgF = 1f
                        FileLog.i("SweepVR-browser", "FIRE X close")
                        onBrowserActivate(-10, null)
                        return
                    }
                    return
                }
                inXZone = false
                xDwellFired = false
                xProgF = maxOf(0f, xProgF - dtMs / 600f)
                val pin = pinTopRows.coerceIn(0, 2)
                var idx = -1
                // Strips mode on file pages always, and on settings pages
                // while testing once the list overflows the plain window
                // (no pinned rows there).
                val stripsOn = pin > 0 || rows.size > VISIBLE_ROWS
                if (stripsOn) {
                    // File pages: strips + pinned rows + fractional window.
                    // Hit only rows that are actually drawn: the window spans
                    // `win` rows regardless, so below a short list the empty
                    // area used to coerce onto the bottom file and dwell it.
                    // Off-row areas fall through to the drain below.
                    val win = winRows(pin)
                    val ypx = v * TEX
                    val upY0 = upStripY0(pin); val rY0 = rowsY0(pin); val dnY0 = downStripY0(pin)
                    val scrollable = rows.size > pin + win
                    val inBarX = u * TEX >= 20f && u * TEX <= 1004f
                    if (scrollable && inBarX && ypx >= upY0 && ypx < upY0 + STRIP_H) {
                        stripGaze(-1, dtMs, still, hx, hy, hz)
                        return
                    }
                    if (scrollable && inBarX && ypx >= dnY0 && ypx < dnY0 + STRIP_H) {
                        stripGaze(1, dtMs, still, hx, hy, hz)
                        return
                    }
                    if (scrollEngage != 0 || scrollTrigDir != 0) {
                        scrollEngage = 0; scrollTrigDir = 0; scrollTrigF = 0f
                    }
                    // Same window the upload draws (fractional row included).
                    val base = scrollPos.toInt()
                    val winStart = (pin + base).coerceAtMost(rows.size)
                    val winEnd = (winStart + win + 1).coerceAtMost(rows.size)
                    val drawnPin = pin.coerceAtMost(rows.size)
                    if (drawnPin > 0 && ypx >= PIN_Y0 && ypx < PIN_Y0 + drawnPin * ROW_H) {
                        idx = ((ypx - PIN_Y0) / ROW_H).toInt().coerceIn(0, drawnPin - 1)
                    } else if (winEnd > winStart && ypx >= rY0 && ypx < rY0 + (winEnd - winStart) * ROW_H) {
                        val f = pin + scrollPos + (ypx - rY0) / ROW_H
                        idx = f.toInt().coerceIn(winStart, winEnd - 1)
                    }
                } else {
                    // Settings pages: plain fixed window, no strips.
                    val vi = ((v * TEX - ROWS_Y0) / ROW_H).toInt()
                    // Only rows that actually exist. Coercing to rows.size-1
                    // made the blank area below a short list read as the last
                    // row, so the gaze rested on a live row and the reticle
                    // shrank to a point and stayed there.
                    if (vi in 0 until minOf(VISIBLE_ROWS, rows.size)) {
                        idx = (scrollPos.toInt() + vi).coerceIn(0, rows.size - 1)
                    }
                }
                if (idx in rows.indices) {
                    // dead rows are gaze rest zones: drain, never accumulate or fire
                    if (rows[idx].dead) {
                        if (idx != highlight) { highlight = idx; dwellFiredFor = -2 }
                        sliderHoverU = -1f
                        browProgF = maxOf(0f, browProgF - dtMs / 600f)
                        return
                    }
                    sliderHoverU = if (rows[idx].slideKey != null || rows[idx].segActions.isNotEmpty()) u else -1f
                    if (idx != highlight) {
                        highlight = idx; dwellFiredFor = -2
                        lastFiredU = Float.NaN
                        fireBlockedLogged = false
                        // small credit on row change (replaces the old
                        // 150ms-style stability delay): keeps flips cheap
                        browProgF = minOf(browProgF, 0.25f)
                    }
                    // Slider re-arm: sliding the gaze along the bar after a
                    // fire restarts shrink + fire without leaving the row.
                    // Requires real head motion: a panel resize shifting the
                    // mapping under a steady gaze must not refire by itself.
                    // (Gaze is head-driven — no eye tracking — so any genuine
                    // slide moves the head; jitter stays far below both gates.)
                    if (highlight == dwellFiredFor && rows[idx].slideKey != null &&
                        !lastFiredU.isNaN() && kotlin.math.abs(u - lastFiredU) > 0.05f &&
                        angleDeg(lastFiredFwd, lastEffFwd) > 2f) {
                        dwellFiredFor = -2
                        browProgF = 0f
                        lastFiredU = Float.NaN
                    }
                    // Accumulate only until fired for this hover; after firing
                    // hold the shrunken state (no second shrink animation).
                    // Re-entry (off-panel resets dwellFiredFor) restarts it.
                    if (highlight != dwellFiredFor) {
                        if (still) browProgF += dtMs / dwellMs.toFloat()
                        else browProgF = maxOf(0f, browProgF - dtMs / 600f)
                    }
                    if (still && highlight != dwellFiredFor && browProgF >= 1f) {
                        dwellFiredFor = highlight
                        browProgF = 1f
                        // slider rows pass the bar-mapped fraction so one dwell
                        // sets any value; segmented rows pass panel-wide u for
                        // segment picking; plain rows null
                        val frac = if (rows[idx].slideKey != null) barFrac(u)
                            else if (rows[idx].segActions.isNotEmpty()) u else null
                        if (rows[idx].slideKey != null) {
                            lastFiredU = u
                            lastFiredFwd = lastEffFwd.clone()
                        }
                        FileLog.i("SweepVR-browser", "FIRE row=$idx frac=$frac")
                        onBrowserActivate(highlight, frac)
                        return
                    } else if (still && highlight == dwellFiredFor && browProgF >= 1f && !fireBlockedLogged) {
                        fireBlockedLogged = true
                        FileLog.i("SweepVR-browser", "BLOCKED repeat dwell row=$highlight prog=$browProgF")
                    }
                    return
                }
            }
        }
        // not hovering the panel: hide cursor, drain progress (no zeroing).
        // Reset the row-fired latch so looking back re-arms shrink + fire.
        hitValid = false
        inXZone = false
        xDwellFired = false
        sideBtnDir = 0; sideBtnFired = false
        sideBtnProg = maxOf(0f, sideBtnProg - 16f / 600f)
        dwellFiredFor = -2
        lastFiredU = Float.NaN
        fireBlockedLogged = false
        sliderHoverU = -1f
        xProgF = maxOf(0f, xProgF - 16f / 600f)
        browProgF = maxOf(0f, browProgF - 16f / 600f)
        scrollTrigF = maxOf(0f, scrollTrigF - 16f / 600f)
    }

    /** Gaze on a scroll strip (file pages only): a short still-gaze
     *  engages it, then the rows window glides continuously while the gaze
     *  stays on the strip. Looking away stops immediately. */
    private fun stripGaze(dir: Int, dtMs: Long, still: Boolean, hx: Float, hy: Float, hz: Float) {
        hitX = hx; hitY = hy; hitZ = hz; hitValid = true
        highlight = -1; dwellFiredFor = -2
        sliderHoverU = -1f; lastFiredU = Float.NaN; fireBlockedLogged = false
        inXZone = false; xDwellFired = false
        browProgF = maxOf(0f, browProgF - dtMs / 600f)
        xProgF = maxOf(0f, xProgF - dtMs / 600f)
        if (scrollEngage == dir) {
            val pin = pinTopRows.coerceIn(0, 2)
            val maxS = maxOf(0, browserRows.size - pin - winRows(pin)).toFloat()
            scrollPos = (scrollPos + dir * STRIP_ROWS_PER_SEC * dtMs / 1000f).coerceIn(0f, maxS)
            return
        }
        if (scrollTrigDir != dir) { scrollTrigDir = dir; scrollTrigF = 0f }
        if (still) scrollTrigF += dtMs / STRIP_TRIG_MS
        else scrollTrigF = maxOf(0f, scrollTrigF - dtMs / 600f)
        if (scrollTrigF >= 1f) { scrollTrigF = 1f; scrollEngage = dir }
    }

    /** Panel open/close fade state (GL thread). */
    private var browFadeVis = false
    private var browFadeT0 = 0L
    private var menuFadeVis = false
    private var menuFadeT0 = 0L

    /** Fast fade alpha for a panel: smoothstep over PANEL_FADE_MS after
     *  each visibility transition. `which=true` = browser, false = menu. */
    private fun fadeAlpha(want: Boolean, which: Boolean): Float {
        val t0: Long
        if (which) {
            if (want != browFadeVis) { browFadeVis = want; browFadeT0 = now() }
            t0 = browFadeT0
        } else {
            if (want != menuFadeVis) { menuFadeVis = want; menuFadeT0 = now() }
            t0 = menuFadeT0
        }
        val t = ((now() - t0).toFloat() / PANEL_FADE_MS).coerceIn(0f, 1f)
        val s = t * t * (3f - 2f * t)
        return if ((if (which) browFadeVis else menuFadeVis)) s else 1f - s
    }

    private fun ensureVisible() {
        val pin = pinTopRows.coerceIn(0, 2)
        val stripsOn = pin > 0 || browserRows.size > VISIBLE_ROWS
        val win = if (stripsOn) winRows(pin) else VISIBLE_ROWS
        val maxS = maxOf(0, browserRows.size - pin - win).toFloat()
        scrollPos = scrollPos.coerceIn(0f, maxS)
        val h = highlight
        if (h < 0 || h >= browserRows.size) return
        if (h < pin) return // pinned row: always visible, never scrolls
        val base = scrollPos.toInt()
        if (h < pin + base) scrollPos = (h - pin).toFloat()
        else if (h >= pin + base + win) scrollPos = (h - pin - win + 1).toFloat()
        scrollPos = scrollPos.coerceIn(0f, maxS)
    }

    private fun now() = System.currentTimeMillis()

    /** Angle between two head-forward vectors, degrees. */
    private fun angleDeg(a: FloatArray, b: FloatArray): Float {
        val dot = (a[0] * b[0] + a[1] * b[1] + a[2] * b[2]).coerceIn(-1f, 1f)
        return Math.toDegrees(kotlin.math.acos(dot).toDouble()).toFloat()
    }

    /** Panel-wide gaze fraction -> slider-bar fraction (bar spans x 44..1000 of TEX 1024). */
    private fun barFrac(u: Float) = ((u * TEX - 44f) / 956f).coerceIn(0f, 1f)

    /** Design-space panel x -> seek fraction, from the bar's own rect. */
    private fun seekFrac(x: Float) =
        (((x - menuBar.x) / menuBar.hw) * 0.5f + 0.5f).coerceIn(0f, 1f)

    // ---------- play menu ----------
    // World-locked panel floating up (or down) in the recentered frame.
    // Look past the side's trigger angle to open; the close band sits 25° lower.
    // The pointer stays hidden during video until the menu opens.
    /** Windowed stillness gate: displacement over the trailing ~250ms.
     *  Per-frame deltas are useless — game-RV jitter trips a per-frame
     *  gate ~30×/s (see the menu motion-reset bursts in the log), so
     *  dwell can only accumulate during lucky-still streaks. Over a
     *  window, zero-mean noise cancels while real motion accumulates. */
    private class MotionStillness(private val windowMs: Long, private val limitDeg: Float) {
        private val refM = FloatArray(16).also { Matrix.setIdentityM(it, 0) }
        private var refT = 0L
        private var init = false
        private val tmpA = FloatArray(16)
        private val tmpB = FloatArray(16)
        /** Call on the GL thread with the live rotation matrix. */
        fun update(raw: FloatArray, nowMs: Long): Boolean {
            if (!init) {
                System.arraycopy(raw, 0, refM, 0, 16)
                refT = nowMs
                init = true
                return true
            }
            Matrix.transposeM(tmpA, 0, refM, 0)
            Matrix.multiplyMM(tmpB, 0, tmpA, 0, raw, 0)
            val tr = tmpB[0] + tmpB[5] + tmpB[10]
            val ang = Math.toDegrees(
                kotlin.math.acos(((tr - 1f) / 2f).toDouble().coerceIn(-1.0, 1.0))
            ).toFloat()
            if (nowMs - refT >= windowMs) {
                System.arraycopy(raw, 0, refM, 0, 16)
                refT = nowMs
            }
            return ang < limitDeg
        }
    }
    private val menuStill = MotionStillness(250L, 4f)
    private val browserStill = MotionStillness(250L, 2.5f)
    // leaky dwell integrators: progress grows while still on target and
    // drains slowly otherwise. Resets can never win against tremor or
    // target churn — flicker only dents progress instead of zeroing it.
    // Slots are menuSlot(id): ids 0..19 plus the seek bar folded onto 18.
    private val menuProg = FloatArray(20)
    private var menuProgT = 0L
    private var browProgF = 0f
    private var browProgT = 0L
    private fun menuSlot(id: Int) = if (id == -1) 18 else id
    /** Whether [id] may fill a dwell and fire it. Sweep and dwell are
     *  alternatives, never both: with sweep on NOTHING on this panel dwells,
     *  the seek bar included — the buttons are swept and the bar is dragged
     *  by the grip. A dwell filling underneath would fire a control the user
     *  is mid-gesture on, or has just let go of, which is the exact class of
     *  accident sweep exists to remove. Decorative ids never fire. */
    private fun menuUsable(id: Int) = id != -2 && !sweepEnabled

    /** Whether [id] should read as "on" right now: gaze, for an ordinary
     *  button, but for a swept button only while the sweep holds it.
     *  The dips mark the entry sides, so an approach from any other
     *  direction is as invisible as no approach at all — no highlight, no
     *  tooltip, no dwell, no activation. */
    private fun menuHot(id: Int): Boolean =
        if (sweepEnabled && id in SWEEP_MENU_IDS) sweepBtn(id)?.armed == true
        else id == menuHighlight

    /** A gaze target on the play-menu panel, in DESIGN panel space (the
     *  layout authored for a MENU_DESIGN_R viewing distance; menuScale()
     *  maps it onto the live panel distance). id: the MenuEvent.Press idx
     *  (-1 = seek bar, -2 = decorative, never fired). */
    private data class MenuBtn(
        val id: Int, val x: Float, val y: Float,
        val hw: Float = MENU_BTN_HALF, val hh: Float = MENU_BTN_HALF,
        val glyph: String = ""
    )

    /** Panel-local names, indexed by MenuEvent.Press idx (tooltips). */
    private val MENU_NAMES = arrayOf(
        "settings", "shape", "files", "prev", "rew", "play",
        "ff", "next", "zoom+", "zoom−", "vol+", "vol−",
        "flip", "recenter", "fov−", "fov+", "", "",
        "web"   // 18, beside files; 19 (cue) reads its state instead
    )

    /** The whole play menu: transport row on the bar's centreline, the seek
     *  bar under it (shifted left), the zoom/fov/volume pairs as three
     *  columns at the right (+ just above −, both on the seek-bar band),
     *  recenter top left and flip top right (both level with the title
     *  strip; the pane's top edge hugs them so the top band stays tight).
     *  The left column is three small squares — recenter, settings, cue
     *  toggle — clear of the backdrop and of the seek bar's left end.
     *  Title and backdrop are decorative. Every button here takes the
     *  sweep gesture (see SWEEP_MENU_IDS); with sweep off the whole panel
     *  falls back to dwell, seek bar included. */
    private val menuButtons = arrayOf(
    // Transport row runs one pitch further left now that web sits beside
    // the files button (9 buttons, still clear of the zoom/fov/vol columns).
    MenuBtn(0, MENU_COL_X, 0f, MENU_COL_HALF, MENU_COL_HALF, glyph = "⚙"), // settings
    MenuBtn(1, -3.75f, 0f, glyph = "⧗"),
    MenuBtn(2, -3.00f, 0f, glyph = "📁"),
    MenuBtn(18, -2.25f, 0f, glyph = "🌐"),  // web, beside files
    // The transport row is drawn as artwork (transportGlyph), not as text:
    // the blue tiles and the play / pause state would otherwise come out of
    // whichever fonts happen to be installed, and on this device that means
    // Samsung's colour emoji font for some of them and a monochrome symbol
    // font for the rest — the row then disagrees with itself.
    MenuBtn(3, -1.50f, 0f),  // previous track
        MenuBtn(4, -0.75f, 0f),
        MenuBtn(5, 0.00f, 0f),   // play / pause, drawn from menuPlaying
        MenuBtn(6, 0.75f, 0f),
        MenuBtn(7, 1.50f, 0f),  // next track
        // adjustment columns down the right side, left to right
        // zoom / fov / volume, + paired just above − on the seek-bar line.
        // All three now take the standard 0.6 face, so the + row moves up
        // to keep the two rows apart (− stays on the seek-bar line).
        MenuBtn(8, 3.15f, -0.05f),   // zoom+
        MenuBtn(9, 3.15f, -0.75f),  // zoom−
        MenuBtn(15, 3.90f, -0.05f),  // fov+
        MenuBtn(14, 3.90f, -0.75f), // fov−
        MenuBtn(10, 4.65f, -0.05f),  // vol+
        MenuBtn(11, 4.65f, -0.75f), // vol−
        MenuBtn(12, 4.65f, 0.70f),  // flip: ⇅ top right, atop vol+ column
        MenuBtn(13, MENU_COL_X, 0.70f, MENU_COL_HALF, MENU_COL_HALF), // recenter
        // cue toggle: repeat <-> autocue, the column's bottom square
        MenuBtn(MENU_CUE_ID, MENU_COL_X, MENU_CUE_Y, MENU_COL_HALF, MENU_COL_HALF)
    )
    /** Every play-menu button takes the sweep gesture instead of the dwell
     *  while sweep is on — one entry dip per side and the same commit rules
     *  as the toolbar buttons. Derived from the list above so a button
     *  cannot be added without being swept (read after menuButtons for that
     *  reason). With sweep off they are ordinary dwell buttons again. The
     *  seek bar (id -1) is never in this list — it is not swept at all: with
     *  sweep on it is dragged by the grip, [seekDrag], and with sweep off it
     *  dwells, a click anywhere on the bar being a seek. Sweep and dwell
     *  never overlap: see menuUsable. */
    private val SWEEP_MENU_IDS: IntArray = menuButtons.map { it.id }.toIntArray()
    /** Seek bar (id -1), decorative title and backdrop, all design units.
     *  The bar sits left of centre so the − row of the ± columns has its
     *  own lane at the right edge. */
    private val menuBar = MenuBtn(-1, -0.45f, -0.75f, 3.0f, 0.3f)
    /** A committed seek, shown as the grip's position until playback gets
     *  there (see seekShownU). -1 = nothing pending. */
    private var seekStickyU = -1f
    private var seekStickyT = 0L
    /** Last frame the grip's drag was stepped, for the frame-gap rule. */
    private var seekLastT = 0L
    /** The seek bar's grip: a drag rather than a momentary, so it is stepped
     *  from updateMenu on the same frame as the buttons and for the same
     *  reason — a release must resolve exactly once. Engine space, y-down,
     *  like every other rect here.
     *
     *  The track is the bar widened by half a handle either side, so value 0
     *  and value 1 land on the bar's ENDS rather than a half-handle in from
     *  them; that makes thumbFraction = handle / (bar + handle) and puts the
     *  grip's centre exactly where seekFrac() answers, so the grip, the
     *  progress fill it sits on and the hit test can only ever agree.
     *
     *  relativeGrab is the point of the exercise: latching must not seek. A
     *  snap would move the video the instant the gesture is taken, by
     *  however far the reticle happened to land from the grip's centre — so
     *  the offset between value and reticle is captured on entry instead and
     *  carried for the whole drag. */
    private val seekDrag = net.sweepvr.player.sweep.ValueDragControl(
        axis = net.sweepvr.player.sweep.ValueDragControl.Axis.HORIZONTAL,
        thumbFraction = SEEK_HANDLE_W / (menuBar.hw * 2f + SEEK_HANDLE_W),
        leeway = 0.03f, rearm = 0.06f, endLeeway = SEEK_END_LEEWAY,
        centreSnap = true, relativeGrab = true,
        cornerFraction = MENU_CORNER_FRAC
    ).apply {
        track = net.sweepvr.player.sweep.Rect(
            menuBar.x - menuBar.hw - SEEK_HANDLE_W * 0.5f,
            -menuBar.y - SEEK_HANDLE_H * 0.5f,
            menuBar.x + menuBar.hw + SEEK_HANDLE_W * 0.5f,
            -menuBar.y + SEEK_HANDLE_H * 0.5f)
        onCommit = { v ->
            seekStickyU = v
            seekStickyT = now()
            onMenuEvent(MenuEvent.Seek(v))
        }
    }
    private val menuTitleRect = MenuBtn(-2, 0f, 0.70f, 2.55f, 0.3f)
    /** Pane: bottom stays at −1.2; top hugs the title row (1.05) so the
     *  top band uses less vertical space than the old symmetric 1.2. */
    private val menuBackdrop = MenuBtn(-2, 0f, -0.075f, 5.1f, 1.125f)

    /** Effective open angle (always 10..60) and side from the ⇅ toggle. */
    private fun menuOpenAngle(): Float =
        if (menuSideUp) menuAngleUp.coerceIn(10f, 60f)
        else kotlin.math.abs(menuAngleDown).coerceIn(10f, 60f)
    private fun menuIsBelow(): Boolean = !menuSideUp
    /** Menu panel elevation (deg): the whole panel floats past the open
     *  angle so looking at its NEAREST edge keeps you beyond the trigger.
     *  That edge sits MENU_MARGIN_DEG past the trigger, and its angular
     *  offset from the panel centre is atan(nearExtent / MENU_DESIGN_R) —
     *  constant, because menuScale() shrinks the panel with the distance
     *  (the design rect is authored for MENU_DESIGN_R). 6.7° reproduces the
     *  old bottom-edge clearance bit-for-bit (40° open → 55.3°, old 54.5°).
     *  No hysteresis anywhere: open at/above the angle, closed below it. */
    private fun menuElevDeg(): Float {
        // side up: the near edge is the panel's BOTTOM; side down: its TOP.
        val near = if (menuIsBelow()) MENU_EXT_TOP else MENU_EXT_BOT
        val extDeg = Math.toDegrees(kotlin.math.atan(near / MENU_DESIGN_R).toDouble()).toFloat()
        return ((menuOpenAngle() + extDeg + MENU_MARGIN_DEG).coerceAtMost(85f)) *
            (if (menuIsBelow()) -1f else 1f)
    }
    /** Animated elevation for the ⇅ flip: smooth sweep through the middle,
     *  settling on the target side. */
    fun menuElevCurrent(): Float {
        val target = menuElevDeg()
        val dt = now() - menuAnimT0
        if (dt >= menuAnimMs) return target
        val t = (dt.toFloat() / menuAnimMs).coerceIn(0f, 1f)
        val s = t * t * (3f - 2f * t)
        return menuAnimFrom + (target - menuAnimFrom) * s
    }
    /** The design rect is authored for a MENU_DESIGN_R viewing distance —
     *  the same 11.95 m the flat screen sits at — so scaling the WHOLE
     *  rect by panelDistM/MENU_DESIGN_R makes the panel's APPARENT size
     *  constant (±5.3 at any depth = 47.9° across; the old 0.62·√d panel
     *  measured 43.6° at the 2.4 m default and shrank as you moved it)
     *  while the slider still changes the panel's depth (real stereo
     *  parallax). The extra ~4° is the right-margin column the old
     *  transport-only panel didn't have. */
    private fun menuScale(): Float = panelDistM.coerceIn(0.5f, 20f) / MENU_DESIGN_R

    // ---------- play menu ----------

    /** Circle-to-recenter gesture master switch (2D settings, default on). */
    @Volatile var circleGestureEnabled = true
    /** UI-thread request: close the menu and arm the aim pointer (GL consumes). */
    @Volatile private var aimRequest = false
    /** Aim pointer live. Volatile: armed/cleared on GL, read on UI (tap cancels). */
    @Volatile private var aimArmed = false
    // GL-thread aim dwell state (head-locked big blue pointer, 2x dwell)
    private var aimProg = 0f
    private var aimT = 0L
    private var aimArmedAt = 0L
    private val aimW = FloatArray(3)
    // circle detector: fixed ring buffer, zero per-frame allocation
    // (160 slots ≈ 5.3s at the 33ms sample step: the window must be
    // longer than the gesture, or the first loop ages out mid-draw)
    private val circT = LongArray(160)
    private val circX = FloatArray(160)
    private val circY = FloatArray(160)
    private var circHead = 0
    private var circN = 0
    private var circLastT = 0L
    private var circCooldownUntil = 0L
    private var circNearT = 0L

    /** Dwell the toolbar button / draw a circle to call this: the menu
     *  closes and a big blue pointer arms — stare at the new forward for
     *  2x the gaze delay and the snap fires there. */
    fun requestAim() { aimRequest = true }
    /** Screen-position calibration (§8.6): UI-thread request; the GL thread
     *  runs a SCREEN_SWEEP_MS window in which dwell is suspended and the
     *  reticle shrinks to a point, then recenters the head tracker and the
     *  app basis together. The screenpos menu button that used to fire it
     *  is gone; the entry point stays for future binds. */
    @Volatile private var sweepRequest = false
    private var screenSweep = false
    private var screenSweepT0 = 0L
    fun requestScreenSweep() { sweepRequest = true }
    /** Tap while armed cancels the aim instead of snapping. True if consumed. */
    fun cancelAim(): Boolean {
        if (!aimArmed && !aimRequest) return false
        aimArmed = false; aimRequest = false; aimProg = 0f
        FileLog.i("SweepVR-menu", "aim cancelled")
        return true
    }

    /** Aim dwell: progress grows while still (2x the menu dwell), drains
     *  on motion; at full the basis snaps to the faced direction. */
    private fun updateAim() {
        val nowMs = now()
        val dtMs = (nowMs - aimT).coerceIn(0L, 500L)
        aimT = nowMs
        if (nowMs - aimArmedAt > 15000L) {
            aimArmed = false; aimProg = 0f
            FileLog.i("SweepVR-menu", "aim timeout")
            return
        }
        val still = synchronized(headViewM) { menuStill.update(headViewM, nowMs) }
        if (still) aimProg += dtMs / dwellMs.toFloat()
        else aimProg = maxOf(0f, aimProg - dtMs / 600f)
        val d = panelDistM
        val f = lastEffFwd
        aimW[0] = f[0] * d; aimW[1] = f[1] * d; aimW[2] = f[2] * d
        if (aimProg >= 1f) {
            aimProg = 0f; aimArmed = false
            recenter("aim")
            FileLog.i("SweepVR-menu", "aim FIRE -> recenter")
        }
    }

    /** Circle-to-recenter: signed turning angle of the forward-vector
     *  trail in the x/y plane. ONE full loop accumulates to ±~300°;
     *  nods, shakes and look-and-returns self-cancel to ~0, which is
     *  what makes circles robust where linear swipes false-positive.
     *  Fires aim mode (never a blind snap). */
    private fun updateCircle() {
        val nowMs = now()
        if (nowMs < circCooldownUntil) return
        if (nowMs - circLastT < 33L) return
        circLastT = nowMs
        val f = lastEffFwd
        circT[circHead] = nowMs; circX[circHead] = f[0]; circY[circHead] = f[1]
        circHead = (circHead + 1) % circT.size
        if (circN < circT.size) circN++
        // window: trailing 5000ms, oldest -> newest
        var m = 0
        var mx = 0f; var my = 0f
        var idx = (circHead - circN + circT.size * 2) % circT.size
        for (k in 0 until circN) {
            val t = circT[idx]
            if (nowMs - t <= 5000L) {
                winT[m] = t; winX[m] = circX[idx]; winY[m] = circY[idx]
                mx += winX[m]; my += winY[m]; m++
            }
            idx = (idx + 1) % circT.size
        }
        if (m < 18) return
        val span = winT[m - 1] - winT[0]
        if (span < 600L) return
        mx /= m; my /= m
        var maxR = 0f
        for (i in 0 until m) {
            val dx = winX[i] - mx; val dy = winY[i] - my
            maxR = maxOf(maxR, kotlin.math.sqrt(dx * dx + dy * dy))
        }
        val ex = winX[m - 1] - winX[0]; val ey = winY[m - 1] - winY[0]
        val closure = kotlin.math.sqrt(ex * ex + ey * ey)
        var accum = 0.0; var pos = 0; var neg = 0; var travel = 0f
        for (i in 1 until m - 1) {
            val ax = winX[i] - winX[i - 1]; val ay = winY[i] - winY[i - 1]
            val bx = winX[i + 1] - winX[i]; val by = winY[i + 1] - winY[i]
            val la = kotlin.math.sqrt(ax * ax + ay * ay)
            val lb = kotlin.math.sqrt(bx * bx + by * by)
            if (la < 0.004f || lb < 0.004f) continue
            travel += la
            val a = Math.atan2((ax * by - ay * bx).toDouble(), (ax * bx + ay * by).toDouble())
            accum += a
            if (a > 0) pos++ else neg++
        }
        val signFrac = if (pos + neg > 0) maxOf(pos, neg).toFloat() / (pos + neg) else 0f
        val accDeg = Math.toDegrees(accum)
        fun stats() = "span=${span}ms travel=${"%.2f".format(travel)} " +
            "acc=${accDeg.toInt()}° maxR=${"%.3f".format(maxR)} " +
            "close=${"%.3f".format(closure)} sign=${"%.2f".format(signFrac)}"
        // sign ≥0.60: measured real circles score 0.65-0.74 (heads
        // wobble), random motion ~0.52 — margin on both sides
        if (maxR in 0.09f..0.65f && closure <= 0.18f && travel >= 1.0f &&
            kotlin.math.abs(accum) >= 5.2 && signFrac >= 0.6f
        ) {
            circN = 0
            circCooldownUntil = nowMs + 3000L
            aimRequest = true
            FileLog.i("SweepVR-circle", "FIRE x2 ${stats()}")
            return
        }
        // tuning capture: real rotation that didn't qualify (throttled 2s).
        // The numbers say which guard failed: acc (need ±300° one loop),
        // maxR (need 0.09..0.65 ≈ 5..40° radius), close (need ≤0.18),
        // travel (need ≥1.0), sign (need ≥0.60), span.
        if (kotlin.math.abs(accum) > 2.6 && nowMs - circNearT > 2000L) {
            circNearT = nowMs
            FileLog.i("SweepVR-circle", "near-miss ${stats()}")
        }
    }
    // scratch window for the circle detector (fields, never allocated per frame)
    private val winT = LongArray(160)
    private val winX = FloatArray(160)
    private val winY = FloatArray(160)

    /** One sweep engine per swept button. The rect is built from the
     *  MenuBtn itself, so the gesture, the hit test and the drawn face
     *  can never disagree. Built once: the panel never moves. */
    private class SweepMenuBtn(b: MenuBtn) {
        val id = b.id
        val rect = net.sweepvr.player.sweep.Rect(
            b.x - b.hw, -b.y - b.hh, b.x + b.hw, -b.y + b.hh)
        val ctl = net.sweepvr.player.sweep.MomentaryControl()
        /** True while the sweep holds this button: the face is drawn from
         *  it, so the picture cannot disagree with the gesture (GL only). */
        var armed = false
        var cfg = false
        var lastT = 0L
    }

    private val sweepBtns: List<SweepMenuBtn> = menuButtons
        .filter { it.id in SWEEP_MENU_IDS }
        .map { SweepMenuBtn(it) }
    private fun sweepBtn(id: Int) = sweepBtns.firstOrNull { it.id == id }
    private fun menuButton(id: Int) = menuButtons.firstOrNull { it.id == id }

    private fun resetSweeps() {
        for (s in sweepBtns) {
            if (s.ctl.active || s.armed) { s.ctl.reset(); s.armed = false }
        }
        // The seek grip is deliberately not reset here. Its history has to
        // survive a close for the same reason these engines' does (see
        // stepMenuSweeps above), and a grip actually HELD across one is
        // caught by the frame-gap rule in stepSeekDrag, where the reticle's
        // position is known and the history can be re-seeded rather than
        // left empty. The sticky seek is not cleared either: it is about
        // where the video is going, not about the gesture, and it expires
        // (or is met) as soon as playback catches up.
    }

    /** Step every button's sweep for this frame. Called once per open
     *  menu frame, hit or not, so each release is resolved exactly once.
     *
     *  Each button gets the same gesture: enter through its left or right
     *  edge (the two the dips are drawn on) arms it, leaving through the
     *  top or the bottom commits, leaving sideways cancels, and parking on
     *  it does nothing. Coordinates are panel design space with y flipped
     *  to the engine's y-down rule.
     *
     *  Off the panel there is no surface to release against, so a held
     *  gesture is cancelled (reset never fires). An idle engine is left
     *  alone instead: its last on-panel position is a real approach, and
     *  clearing it would make the engine invent one.
     *
     *  With sweep disabled nothing here runs at all — the panel's own dwell
     *  fires these buttons instead, and no sweep logic is altered. */
    private fun stepMenuSweeps(nowMs: Long, hit: Boolean) {
        if (!sweepEnabled) { resetSweeps(); return }
        for (s in sweepBtns) {
            val c = s.ctl
            // A frame gap means the menu stopped being stepped mid-hold: the
            // mode changed under it (the non-VIDEO branch clears menuOpen but
            // not the fresh-open flags). The gesture is over then — resolving
            // it against this frame's position would commit a move the user
            // never made.
            if (c.active && nowMs - s.lastT > 100L) { c.reset(); s.armed = false }
            if (!hit) {
                if (c.active) { c.reset(); s.armed = false }
                continue
            }
            val id = s.id
            if (!s.cfg) { s.cfg = true; c.onFire = { onMenuEvent(MenuEvent.Press(id)) } }
            c.onTrace = if (bookDbg) ({ FileLog.i("SweepVR-menu", "sweep $id $it") }) else null
            c.rect = s.rect
            c.cornerFraction = MENU_CORNER_FRAC
            val dt = (nowMs - s.lastT).coerceIn(0L, 250L)
            s.lastT = nowMs
            s.armed = c.step(menuHitU, -menuHitV, dt)
        }
    }

    /** What the grip shows when no drag holds it: where playback is, except
     *  for the few frames after a commit, where it shows the commit — see
     *  [SEEK_STICKY_MS]. Cleared the moment playback reaches it (within a
     *  frame of it at any speed) so the handover is seamless, or when the
     *  seek clearly is not coming. */
    private fun seekShownU(): Float {
        val play = if (menuDurMs > 0) (menuPosMs.toFloat() / menuDurMs).coerceIn(0f, 1f) else 0f
        val s = seekStickyU
        if (s < 0f) return play
        if (menuDurMs <= 0) { seekStickyU = -1f; return play }
        // 1.6s of the duration: a frame of playback at any speed a seek can
        // be waiting on, and no longer than a slow one deserves.
        if (kotlin.math.abs(play - s) <= 1600f / menuDurMs ||
            now() - seekStickyT > SEEK_STICKY_MS
        ) { seekStickyU = -1f; return play }
        return s
    }

    /** The grip's own sweep, stepped once per open-menu frame before the
     *  buttons: sideways onto the grip to take hold of it, up or down to
     *  drop it (which seeks), past either end to abandon it. Returns true
     *  while the drag owns the frame — no dwell may fill under a hand that
     *  is already moving the video.
     *
     *  Off the panel, or with sweep off, there is no gesture to hold, so
     *  the grip is re-pointed at playback instead: it is the playhead
     *  marker first and a handle second. */
    private fun stepSeekDrag(nowMs: Long, hit: Boolean): Boolean {
        if (sweepEnabled && hit) {
            // The buttons' frame-gap rule, for the same reason: a hold that
            // outlived the menu (a mode change under it) is over, and
            // resolving it against this frame's reticle would seek to a
            // place the user never took the grip. The reset empties the
            // history the entry test reads, so it is re-seeded with the
            // live reticle in the same breath — otherwise a grip already
            // under the reticle reads as an entry on the very next line.
            if (seekDrag.engaged && nowMs - seekLastT > 100L) {
                FileLog.i("SweepVR-menu", "seek drag died: frame gap " +
                    "${nowMs - seekLastT}ms at value=${"%.2f".format(seekDrag.value)}")
                seekDrag.reset()
                seekDrag.prime(menuHitU, -menuHitV)
            }
            seekLastT = nowMs
            seekDrag.onTrace =
                if (bookDbg) ({ FileLog.i("SweepVR-menu", "seek $it") }) else null
            if (seekDrag.step(menuHitU, -menuHitV)) {
                // Held, just taken, or just dropped this frame: the grip is
                // wherever the gesture left it, and the live time it would
                // jump to is the value in hand.
                menuSeekHoverU = seekDrag.value
                return true
            }
        } else if (seekDrag.engaged) {
            // A HELD drag dies off the panel (no surface to release
            // against) and when sweep goes off (no gesture left). An idle
            // engine is left alone instead — its last position is a real
            // approach, and clearing it would make the entry test invent
            // one. Primed only where the reticle's position is live.
            // Logged because this used to end a drag with nothing in the log
            // to say so, and an end that appears to be a commit is a bug you
            // cannot argue with from the other side.
            FileLog.i("SweepVR-menu", "seek drag died: ${if (hit) "sweep off" else "left the panel"} " +
                "at value=${"%.2f".format(seekDrag.value)}")
            seekDrag.reset()
            if (hit) seekDrag.prime(menuHitU, -menuHitV)
        }
        seekDrag.syncTo(seekShownU())
        return false
    }

    private fun updateMenu() {
        // The grip's "held" state is read elsewhere — the seek-preview capture
        // stands aside for a drag, and the bandwidth gate gives the link up
        // — so it has to mean "held right now" on EVERY path out of this
        // function, including the ones that return before the grip is
        // stepped. Left stale it silently disables previews for the rest of
        // the session, which is exactly what it did.
        seekTipOnThumb = false
        // recenter aim flow: consume the UI-thread request, close the menu,
        // arm the big blue pointer (suppresses the open logic below)
        if (aimRequest) {
            aimRequest = false
            if (menuWasOpen) FileLog.i("SweepVR-menu", "menu close (aim)")
            menuOpen = false; menuHitValid = false
            menuHighlight = -2; menuDwellFiredFor = -3
            menuSeekHoverU = -1f
            menuBelowSince = 0L
            menuWasOpen = false
            menuProgFresh = true
            aimArmed = true; aimProg = 0f; aimT = now(); aimArmedAt = aimT
            circN = 0
            FileLog.i("SweepVR-menu", "aim armed")
        }
        if (aimArmed) { updateAim(); return }
        // circle gesture: menu-closed VIDEO only; stale arcs die when the menu opens
        if (!menuOpen) { if (circleGestureEnabled) updateCircle() }
        else if (circN > 0) circN = 0
        // Trigger metric: head-TILT (angle of the head-up vector from
        // vertical), NOT gaze elevation. asin(fwd.y) conflates yaw with
        // pitch once the head is tilted back: yawing ±30° about the tilted
        // neck axis swings gaze on a cone whose elevation falls ~20°
        // (77°→57°), closing the menu exactly when reaching for the end
        // buttons. Head-up is preserved by yaw (local-Y rotation) exactly,
        // so tilt = atan2(up.z, up.y) is yaw-invariant: + = tipped back,
        // - = tipped forward, roll reads ~0 (never opens).
        val up = lastEffUp
        val tilt = Math.toDegrees(kotlin.math.atan2(up[2].toDouble(), up[1].toDouble())).toFloat()
        val ang = menuOpenAngle()
        val below = menuIsBelow()
        // Open exactly at the angle; close a small band below it (see
        // closeAt). Closing additionally needs 400ms continuously below,
        // killing sensor-noise flapping at the boundary.
        val openAt = ang
        // Close below the open angle (not at the panel edge): the panel
        // stays put while the gaze wanders around and below it, and only
        // hides once the gaze drops past the band and stays there 400ms.
        // The band is deliberately small (1°) but nonzero — tilt dips
        // ~15-20° while operating the end buttons, so closing at the panel
        // edge strobes the menu mid-dwell and forces a re-dip + re-aim to
        // bring it back. Opening is still exact at the angle.
        val closeAt = (ang - 1f).coerceAtLeast(2.5f)
        val above = if (!below) tilt >= (if (!menuOpen) openAt else closeAt)
            else tilt <= -(if (!menuOpen) openAt else closeAt)
        val isUp: Boolean
        if (above) {
            menuBelowSince = 0L
            isUp = true
        } else if (!menuOpen) {
            menuBelowSince = 0L
            isUp = false
        } else {
            if (menuBelowSince == 0L) menuBelowSince = now()
            isUp = now() - menuBelowSince < 400
        }
        if (!isUp) {
            menuOpen = false; menuHitValid = false
            menuHighlight = -2; menuDwellFiredFor = -3
            menuSeekHoverU = -1f
            menuBelowSince = 0L
            if (menuWasOpen) FileLog.i("SweepVR-menu", "menu close")
            menuWasOpen = false
            menuProgFresh = true
            return
        }
        menuOpen = true
        menuWasOpen = true
        // fresh progress each open: stale banks must never insta-fire
        if (menuProgFresh) {
            menuProgFresh = false
            for (i in menuProg.indices) menuProg[i] = 0f
            menuProgT = now()
            // ...and a sweep held across the close dies with the panel:
            // reopening must not inherit an arm the user never completed.
            resetSweeps()
        }
        // Windowed stillness: displacement over 250ms, immune to the
        // per-frame sensor jitter that trips instant gates ~30×/s.
        val nowMs = now()
        val still = synchronized(headViewM) { menuStill.update(headViewM, nowMs) }
        // World-locked panel (no yaw following): the head-forward ray meets
        // its plane and the button table says what was hit. Uses the
        // animated elevation so the pointer tracks the ⇅ flip.
        val hit = menuHitTest()
        val seeking = stepSeekDrag(nowMs, hit)
        seekTipOnThumb = seeking
        stepMenuSweeps(nowMs, hit)
        // Sweep and dwell are alternatives, never both: with sweep on the
        // integrators have no job at all (menuUsable refuses every id), and
        // anything banked before the toggle would sit there un-drained and
        // dim its control until the reticle happened to hover it. Zeroed
        // here rather than per branch so no path can skip it.
        if (sweepEnabled) for (i in menuProg.indices) menuProg[i] = 0f
        if (seeking) {
            // The grip owns this frame: no hover or dwell bookkeeping runs
            // under a hand that is already moving the video. The tooltip
            // reads the value in hand instead.
            menuHighlight = -1
            menuDwellFiredFor = -3
            updateTooltip(-1)
            requestSeekThumb()
            return
        }
        // Not dragging: nothing to preview, and the card must not linger over
        // a slider nobody is holding.
        thumbAskedBucket = -1
        if (hit) {
            menuHitValid = true
            val id = menuHitId
            menuSeekHoverU = if (id == -1) seekFrac(menuHitU) else -1f
            // Leaky dwell: adopt immediately; progress grows while still
            // on target and drains slowly otherwise. Churn and motion
            // only dent progress instead of zeroing the timer. Under
            // sweep this never accumulates — menuUsable says no — but the
            // highlight and tooltip still run off the same hover.
            if (id != menuHighlight) {
                menuHighlight = id; menuDwellFiredFor = -3
                // cap carried progress on adopt: churn can never bank
                // a full dwell, and stale slots can't insta-fire
                if (menuUsable(id)) menuProg[menuSlot(id)] = minOf(menuProg[menuSlot(id)], 0.3f)
                FileLog.i("SweepVR-menu", "dwell start: id=$id tilt=${tilt.toInt()}°")
            }
            // After the highlight, so menuHot() sees this frame's hover.
            // A swept button stays anonymous until its sweep holds it.
            updateTooltip(id)
            val slot = menuSlot(id)
            val dtMs = (nowMs - menuProgT).coerceIn(0L, 500L)
            menuProgT = nowMs
            for (i in menuProg.indices)
                if (i != slot) menuProg[i] = maxOf(0f, menuProg[i] - dtMs / 600f)
            // -2 = decorative (title/backdrop): hover shows the pointer,
            // never fires. Hold shrunken state after firing; re-entry
            // restarts it.
            if (still && menuUsable(id) && menuHighlight != menuDwellFiredFor)
                menuProg[slot] += dtMs / dwellMs.toFloat()
            if (still && menuUsable(id) && menuProg[slot] >= 1f && menuHighlight != menuDwellFiredFor) {
                menuDwellFiredFor = menuHighlight
                menuProg[slot] = 1f
                FileLog.i("SweepVR-menu", "FIRE id=$id")
                if (id == -1) onMenuEvent(MenuEvent.Seek(seekFrac(menuHitU)))
                else onMenuEvent(MenuEvent.Press(id))
                return
            }
            return
        }
        // off-panel: log the transition once (menuHitValid still true from
        // the last hitting frame), then clear highlight (progress per slot
        // is kept and drains slowly, so brief leaves are forgiven)
        if (menuHitValid)
            FileLog.i("SweepVR-menu", "left panel (was id=$menuHighlight)")
        menuHitValid = false
        menuSeekHoverU = -1f
        tooltipVisible = false
        if (menuHighlight != -2) {
            menuHighlight = -2; menuDwellFiredFor = -3
        }
    }

    /** Where the head-forward ray meets the play-menu plane, in DESIGN
     *  panel space (the frame the bitmap and the button table are authored
     *  in). False when the ray misses the panel entirely. */
    private fun menuHitTest(): Boolean {
        val o = invHeadWorldM
        val d = lastEffFwd
        val el = Math.toRadians(menuElevCurrent().toDouble()).toFloat()
        val ce = kotlin.math.cos(el); val se = kotlin.math.sin(el)
        // plane through C = (0, se·R, −ce·R) with normal n = (0,−se, ce):
        // y·se − z·ce = R.  Solve o + t·d against it.
        val den = d[2] * ce - d[1] * se
        if (kotlin.math.abs(den) < 1e-6f) return false
        val t = (o[13] * se - o[14] * ce - panelDistM) / den
        if (t <= 0f) return false
        val px = o[12] + t * d[0]
        val py = o[13] + t * d[1]
        val pz = o[14] + t * d[2]
        val k = menuScale()
        val u = px / k
        val v = (py * ce + pz * se) / k
        if (kotlin.math.abs(u) > MENU_RECT_HW || v < MENU_Y0 || v > MENU_Y1) return false
        menuHitU = u; menuHitV = v
        // buttons first: they sit inside the backdrop and must win.
        var id = -2
        for (b in menuButtons)
            if (kotlin.math.abs(u - b.x) <= b.hw && kotlin.math.abs(v - b.y) <= b.hh) { id = b.id; break }
        if (id == -2 && kotlin.math.abs(u - menuBar.x) <= menuBar.hw &&
            kotlin.math.abs(v - menuBar.y) <= menuBar.hh
        ) id = -1
        menuHitId = id
        return true
    }

    /** Tooltip pill text for whatever the gaze is on: the button's name, or
     *  the time a seek-bar hover would jump to — which, mid-drag, is the
     *  position the drop will commit, anchored to the thumb (see
     *  [seekTipOnThumb]). A swept button only gets named while the sweep
     *  holds it — entry to exit, never sooner. */
    private fun updateTooltip(id: Int) {
        val txt = when {
            !enableTooltip -> ""
            !menuHot(id) -> ""
            // While the grip is held, the seek bar's readout is the preview
            // card under the slider (time above, picture below): the pill
            // stands aside rather than repeating the same number a foot away
            // from the picture it belongs to.
            id == -1 && seekTipOnThumb -> ""
            id == -1 && menuDurMs > 0 && menuSeekHoverU >= 0f ->
                fmtTime((menuSeekHoverU.coerceIn(0f, 1f) * menuDurMs).toLong())
            // The cue toggle's name is its state, so it is read live.
            id == MENU_CUE_ID -> if (autoCue) "autocue" else "repeat"
            id in MENU_NAMES.indices -> MENU_NAMES[id]
            else -> ""
        }
        val prev = tooltipText
        tooltipText = txt
        tooltipVisible = txt.isNotEmpty()
        if (txt != prev) FileLog.i("SweepVR-menu", "tip '${txt}' id=$id")
        if (!tooltipVisible) return
        // Float just above and to the right of the control: the pill's
        // bottom-left corner clears the control's top-right corner. Fixed
        // relative to the control, so it never slides around while the gaze
        // creeps across the button. The seek bar keeps the head-locked spot
        // on a bare hover, where it stands in for the time readout and has
        // to follow the scrub point; mid-drag it is the card's job instead.
        val b = if (id == -1) null else menuButton(id)
        tipAnchored = b != null
        if (b != null) {
            tipU = b.x + b.hw + TIP_GAP + TIP_HALF_W
            tipV = b.y + b.hh + TIP_GAP + TIP_HALF_H
        }
    }

    // ---------- drawing ----------
    /** One video eye, method doc §7.1: one strip, homogeneous texcoords,
     *  projective divide in-shader (u_verticalCrop uploads 0 for this
     *  path), stereo halves in the buffers, zoom as a model-space Z
     *  translate in the zoomPan matrix. */
    private fun drawVideo(eye: Int) {
        val g = videoGeom ?: return
        // Texcoord buffer by layout and eye (§6.1); uEye already accounts
        // for swapEyes, so the pick is direct.
        val li = when (stereo) {
            Stereo.SBS -> 1 + eye
            Stereo.TB -> 3 + eye
            else -> 0
        }
        val proj = effProj()
        val pos: FloatBuffer
        val tex: FloatBuffer
        val count: Int
        if (proj == Projection.FLAT && g.capPos != null) {
            pos = g.capPos; tex = g.capTex!![li]; count = g.capCount
        } else if (proj == Projection.FLAT) {
            pos = flatPosBuf; tex = g.flatTex[li]; count = 6
        } else {
            val span = when (proj) {
                Projection.DEG220 -> 220f
                Projection.DEG270 -> 270f
                Projection.DEG360 -> 360f
                else -> 180f
            }
            pos = g.domePos[span] ?: return
            tex = g.domeTex[li]
            count = g.domeCount
        }
        buildVideoModel()
        val crop = buildZoomPan()
        Matrix.multiplyMM(tmpB, 0, modelM, 0, zoomM, 0)
        // Panoramic video rides the FOV-scaled projection; the FLAT screen
        // shares the unscaled one with every panel (§6.3).
        val projBase = if (proj == Projection.FLAT) ovM else domeOvM
        Matrix.multiplyMM(mvpM, 0, projBase, 0, tmpB, 0)
        if (proj == Projection.FISHEYE) {
            drawVideoFisheye(eye, pos, count, crop)
            return
        }
        // §7.4 state discipline: the overlay passes run premultiplied, so
        // the video pass sets its own blend explicitly and restores it.
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glUseProgram(progVideo)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, videoTextureId)
        GLES20.glUniform1i(uVideoTexVid, 0)
        GLES20.glUniform1f(uCropVid, crop)
        GLES20.glUniformMatrix4fv(uTexMatVid, 1, false, texMat, 0)
        GLES20.glUniformMatrix4fv(uTexTransVid, 1, false, texMat, 0)
        GLES20.glUniformMatrix4fv(uMvpVid, 1, false, mvpM, 0)
        GLES20.glEnableVertexAttribArray(aPositionVid)
        GLES20.glVertexAttribPointer(aPositionVid, 3, GLES20.GL_FLOAT, false, 0, pos)
        GLES20.glEnableVertexAttribArray(aTexCoordVid)
        GLES20.glVertexAttribPointer(aTexCoordVid, 4, GLES20.GL_FLOAT, false, 0, tex)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, count)
        GLES20.glDisableVertexAttribArray(aPositionVid)
        GLES20.glDisableVertexAttribArray(aTexCoordVid)
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
    }

    // ---------- web mode ----------
    private val IDENTITY16 = FloatArray(16).also { Matrix.setIdentityM(it, 0) }

    /** The page on the flat screen: its own mesh, model, curve and zoom range
     *  as the 2D video (same placement), but a plain 2D texture (the page is
     *  a capture, not a decoder surface), so it needs its own tiny program
     *  for the zoom. */
    private fun drawWeb(eye: Int) {
        val m = webMesh ?: return
        if (webTexId < 0 || webConsumedFrames == 0L) return
        buildVideoModel()
        Matrix.multiplyMM(mvpM, 0, ovM, 0, modelM, 0)
        GLES20.glUseProgram(progWeb)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, webTexId)
        GLES20.glUniform1i(uTexWeb, 0)
        GLES20.glUniform1f(uZoomWeb, webZoomF())
        GLES20.glUniformMatrix4fv(uMvpWeb, 1, false, mvpM, 0)
        GLES20.glEnableVertexAttribArray(aPosWeb)
        GLES20.glVertexAttribPointer(aPosWeb, 3, GLES20.GL_FLOAT, false, 0, m.verts)
        GLES20.glEnableVertexAttribArray(aTexWeb)
        GLES20.glVertexAttribPointer(aTexWeb, 2, GLES20.GL_FLOAT, false, 0, m.tex)
        GLES20.glDrawElements(GLES20.GL_TRIANGLES, m.indexCount, GLES20.GL_UNSIGNED_SHORT, m.indices)
        GLES20.glDisableVertexAttribArray(aPosWeb)
        GLES20.glDisableVertexAttribArray(aTexWeb)
    }

    /** World point of page texture coord (u,v) — the exact geometry the
     *  flat screen mesh uses (plane at curve 0, eye-centred cap blend above
     *  it), including buildVideoModel's +0.25 lift. v is GL texture space
     *  (v=1 is the TOP row of the page). */
    private fun webPointAt(u: Float, v: Float, out: FloatArray) {
        val (w, h) = screenDims()
        val c = screenCurve.coerceIn(0f, 1f)
        val fx = (u - 0.5f) * w
        val fy = (v - 0.5f) * h
        if (c <= 0f) {
            out[0] = fx; out[1] = fy + 0.25f; out[2] = -FLAT_DIST
            return
        }
        val arcMax = Math.toRadians(SCREEN_ARC_MAX_DEG.toDouble()).toFloat()
        val rt = maxOf(SCREEN_R_MIN, w / arcMax)
        val ax = (u - 0.5f) * w / rt
        val by = (v - 0.5f) * h / rt
        val cyb = kotlin.math.cos(by); val syb = kotlin.math.sin(by)
        val cx = rt * cyb * kotlin.math.sin(ax)
        val ry = rt * syb
        val cz = -rt * cyb * kotlin.math.cos(ax)
        out[0] = fx + (cx - fx) * c
        out[1] = fy + (ry - fy) * c + 0.25f
        out[2] = -FLAT_DIST + (cz + FLAT_DIST) * c
    }

    /** Gaze ray -> page texture coord by solving P(u,v) parallel to the ray
     *  with Newton iterations (the curved cap is not a plane, so a single
     *  plane intersection would miss by most of a button at curve 1).
     *  Null when the ray leaves the screen. */
    @Volatile private var webGazeDist = -1f

    private fun webGazeUv(fwd: FloatArray): FloatArray? {
        val hx = invHeadWorldM[12]; val hy = invHeadWorldM[13]; val hz = invHeadWorldM[14]
        val p = FloatArray(3); val pu = FloatArray(3); val pv = FloatArray(3)
        var u = 0.5f; var v = 0.5f
        val h = 0.002f
        for (it in 0 until 5) {
            webPointAt(u, v, p)
            val qx = p[0] - hx; val qy = p[1] - hy; val qz = p[2] - hz
            val fx = qy * fwd[2] - qz * fwd[1]
            val fy = qz * fwd[0] - qx * fwd[2]
            if (kotlin.math.abs(fx) < 2e-4f && kotlin.math.abs(fy) < 2e-4f) break
            webPointAt(u + h, v, pu); webPointAt(u, v + h, pv)
            val a = pu[0] - hx; val b = pu[1] - hy; val cc = pu[2] - hz
            val d0 = pv[0] - hx; val d1 = pv[1] - hy; val d2 = pv[2] - hz
            val jx1 = b * fwd[2] - cc * fwd[1]; val jy1 = cc * fwd[0] - a * fwd[2]
            val jx2 = d1 * fwd[2] - d2 * fwd[1]; val jy2 = d2 * fwd[0] - d0 * fwd[2]
            val a11 = (jx1 - fx) / h; val a12 = (jx2 - fx) / h
            val a21 = (jy1 - fy) / h; val a22 = (jy2 - fy) / h
            val det = a11 * a22 - a12 * a21
            if (kotlin.math.abs(det) < 1e-9f) return null
            u = (u + (-fx * a22 + a12 * fy) / det).coerceIn(-0.4f, 1.4f)
            v = (v + (-a11 * fy + a21 * fx) / det).coerceIn(-0.4f, 1.4f)
        }
        // Off the page the solution still lies on the screen PLANE, so return
        // it (outside 0..1) rather than null: that gives the reticle a target
        // which keeps moving smoothly as the gaze leaves the page, instead of
        // pinning it to the page edge where it stalled in the gap. Only a ray
        // that stops facing the plane returns null.
        if (u > -0.4f && u < 1.4f && v > -0.4f && v < 1.4f) {
            webPointAt(u, v, p)
            val dx = p[0] - hx; val dy = p[1] - hy; val dz = p[2] - hz
            webGazeDist = kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
            return floatArrayOf(u, v)
        }
        return null
    }

    /** Scrollbar strip geometry, in screen texture space. Fixed, so the
     *  mesh only rebuilds when the screen (curve/size) changes. The strip is
     *  deliberately wide and the arrows tall: they are the only gaze targets
     *  on the page, and a few pixels of head wobble used to mean "miss".
     *
     *  u > 1 puts the strip just OUTSIDE the right edge of the screen. It used
     *  to sit inside the page (0.930-0.994), which was invisible while the
     *  page was zoomed in and smaller than the screen, but now that the page
     *  always fills the screen exactly, an inside strip lies on top of the
     *  page's right-hand content. Outside, it stays adjacent to the page
     *  edge with nothing covered. webPointAt extrapolates linearly past the
     *  screen (and round the cap when curved), so u > 1 is a real position
     *  just beyond the surface, not a clamp. */
    private val barU0 = 1.010f
    private val barU1 = 1.074f
    private val barV0 = 0.010f
    private val barV1 = 0.990f
    private val BAR_ARROW = 0.090f
    /** Gaze slop around a bar target, in screen units. */
    private val BAR_SLOP_U = 0.020f
    private val BAR_BM_H = 1024f
    private val BAR_REARM_V = 0.06f
    private val BAR_REARM_U = 0.03f
    /** Leeway sideways before a release counts as letting go.
     *
     *  EXIT ONLY, and this is the only leeway the bar has. It applies from the
     *  moment the thumb is held, and governs how far the reticle may drift
     *  before letting go. It is never an acquisition radius. See THE RULE at
     *  the top of this file. */
    private val BAR_GRIP_TOL = 0.055f
    /** Entry entry-point sides, as a fraction of the strip's width at each
     *  end. A hit that close to a strip edge is on a corner rather than on the
     *  top or bottom of the thumb, and a corner is not an entry point.
     *
     *  14/64 is the radius the strip bitmap actually rounds its corners with -
     *  see the drawRoundRect in maybeUploadWebBar - so this refuses exactly the
     *  part of the strip that is drawn as curved rather than as an end. The
     *  bookmarks button derives its equivalent the same way, from its own
     *  corner radius. */
    private val BAR_CORNER_FRAC = 14f / 64f
    /** How far past either end of the strip the reticle may go before that
     *  counts as a deliberate exit rather than an overshoot. */
    private val BAR_END_LEEWAY = 0.09f
    /** How far clear the reticle must get before a new grab is judged. */

    /** -1 / 1 when the gaze is on a scrollbar arrow, else 0. The slop makes
     *  the small targets forgiving of a pixel or two of mapping error.
     *  v is screen texture space: v = 1 is the TOP of the screen. */
    /**
     * The scrollbar thumb: a vertical value drag on the bar's own v axis.
     *
     * The bar's v counts UPWARD (0.010 at the bottom of the strip, 0.990 at
     * the top), which is texture space, and that flip is confined to
     * [barFracOf] and [barThumbSpan] rather than being spread through the
     * gesture.
     */
    @Volatile private var barDragging = false
    @Volatile private var barDragFrac = 0f
    @Volatile private var barEnterLocked = false
    @Volatile private var barEnterOpen = false
    private var barCurV = 0.5f
    private var barPrevV = 0.5f
    private var barCurU = 0.5f
    private var barPrevU = 0.5f
    private var barDbgT0 = 0L
    @Volatile private var barSettleUntil = 0L
    @Volatile private var barSized = false
    val webBarDragging: Boolean get() = barDragging
    val webBarSettling: Boolean get() = barSettleUntil > now()
    /** Hold the thumb at a dropped position briefly so the state poll, which
     *  reports the page's PREVIOUS position for a frame or two, cannot flick
     *  it back before goFrac lands. */
    fun webBarSettle(frac: Float) {
        barDragFrac = frac
        webScrollFrac = frac
        barSettleUntil = now() + 130L
    }
    fun webBarMarkSized() { barSized = true }
    fun webBarForgetSize() { barSized = false }

    /* ---- the two arrow buttons, as repeat controls ----
     *
     * These replace a dwell that fired once and latched, so holding the gaze
     * on an arrow scrolled the page by one step and then nothing at all until
     * you looked away and back. Entering the arrow now fires immediately and
     * keeps firing while the reticle stays, which is what a scroll arrow is
     * for. The entry rule is the dropdown's - swept into from the left or the
     * right, on the button itself - so it is [RepeatControl]'s rule and is not
     * restated or relaxed here. See THE RULE at the top of this file.
     *
     * Each button is a SQUARE of side BAR_BTN, flush to its end of the strip.
     * The hit rects below are those same squares, so the hit test and the
     * picture cannot disagree. BAR_ARROW is deliberately NOT reused here: it
     * also sizes the thumb track, and the thumb feel is committed - so the
     * slivers of bare strip between button and track belong to neither
     * control, and gaze there does nothing.
     */
    private val BAR_ARROW_LEEWAY = 0.004f

    /** Button square side, in gaze units: exactly the strip width, so the
     *  button reads as square on screen. */
    private val BAR_BTN = barU1 - barU0
    /** Corner rounding, dip depth and dip mouth half-height, in strip-bitmap
     *  px. The radius is deliberately small ("barely rounded"); the dips are
     *  scaled from the dropdown button's 8f/26f on 108px to this 58px face. */
    private val BAR_BTN_R = 4f
    private val BAR_BTN_DIP = 5f
    private val BAR_BTN_DIP_H = 14f
    /** Corner refusal for the buttons: the drawn radius over the drawn width,
     *  mirroring how the dropdown derives BOOK_BTN_R / width. This refuses
     *  exactly the drawn curve, nothing more. */
    private val BAR_BTN_CORNER_FRAC = BAR_BTN_R / 64f

    /** Leeway past an arrow before it stops repeating, in bar-u/bar-v.
     *
     *  A couple of thousandths, which is a couple of pixels: enough to ride
     *  out head jitter on a small target, and nothing like the drag thumb's
     *  0.055. These arrows have no gesture to complete, so holding the page
     *  scrolling after the user has looked away is the only failure mode worth
     *  designing against. */
    private val barUp: net.sweepvr.player.sweep.RepeatControl
    private val barDown: net.sweepvr.player.sweep.RepeatControl

    init {
        // RepeatControl's space is y-DOWN (THE RULE in SweepTypes.kt) while the
        // bar's v counts UP, so the squares are flipped here - once, in the one
        // place that owns them - rather than inside either control. They are
        // the same BAR_BTN squares the bitmap draws below, so the hit test
        // and the picture cannot disagree.
        fun square(fromTop: Boolean) = net.sweepvr.player.sweep.Rect(
            barU0,
            if (fromTop) 1f - barV1 else 1f - (barV0 + BAR_BTN),
            barU1,
            if (fromTop) 1f - (barV1 - BAR_BTN) else 1f - barV0
        )
        barUp = net.sweepvr.player.sweep.RepeatControl(400L, 300L).apply {
            rect = square(true)
            leeway = BAR_ARROW_LEEWAY
            cornerFraction = BAR_BTN_CORNER_FRAC
            // Entry nudge only: fireCount is 1 on the entry hit, higher on
            // repeats, which the glide (below) owns - so repeats stay silent
            // here and motion never double-drives.
            onFire = { if (fireCount == 1) onWebEvent(WebEvent.Nudge(-1)) }
            onRepeatStart = { onWebEvent(WebEvent.GlideStart(-1)) }
            onRepeatStop = { onWebEvent(WebEvent.GlideStop) }
        }
        barDown = net.sweepvr.player.sweep.RepeatControl(400L, 300L).apply {
            rect = square(false)
            leeway = BAR_ARROW_LEEWAY
            cornerFraction = BAR_BTN_CORNER_FRAC
            onFire = { if (fireCount == 1) onWebEvent(WebEvent.Nudge(1)) }
            onRepeatStart = { onWebEvent(WebEvent.GlideStart(1)) }
            onRepeatStop = { onWebEvent(WebEvent.GlideStop) }
        }
    }

    /** Step both arrows for this frame. True if either is held, so the page
     *  dwell does not also fire underneath a held arrow. */
    /** Deepest arrow dwell fill this frame, for the reticle shrink. */
    private var barDwellProg = 0f

    /** The dwell keyboard's charge, 0..1, so the RETICLE shrinks.
     *
     *  The key used to fill with a moving bar instead. The reticle is the
     *  application's dwell indicator everywhere else - page, rows, toolbar -
     *  and a second, different indicator on the keyboard means learning a new
     *  rule for one surface. One indicator, used consistently. */
    private var kbdDwellProg = 0f
    private fun barArrows(u: Float, v: Float, dtMs: Long, still: Boolean): Boolean {
        barDwellProg = 0f
        if (!webScrollable) { barUp.reset(); barDown.reset(); return false }
        // Trace wiring is per-frame, like the dropdown's: bookDbg can change
        // at runtime, and a stale hook would either spam or go blind.
        barUp.onTrace = if (bookDbg) ({ FileLog.i("SweepVR-arrow", "up $it") }) else null
        barDown.onTrace = if (bookDbg) ({ FileLog.i("SweepVR-arrow", "down $it") }) else null
        // Near-miss dump: when the gaze is over the strip but neither button
        // is held, show exactly where it is against both squares, so a miss
        // reads as a position rather than a guess. Throttled like the bar
        // gaze dump.
        if (bookDbg && !barUp.active && !barDown.active &&
            u > barU0 - 0.05f && u < barU1 + 0.05f && now() - barDbgT0 > 400L) {
            barDbgT0 = now()
            val y = 1f - v
            FileLog.i("SweepVR-arrow",
                "gaze u=${"%.3f".format(u)} y=${"%.3f".format(y)} " +
                "up=${"%.3f".format(barUp.rect.top)}..${"%.3f".format(barUp.rect.bottom)} " +
                "down=${"%.3f".format(barDown.rect.top)}..${"%.3f".format(barDown.rect.bottom)}")
        }
        val y = 1f - v                       // the one bar-v -> y-down flip
        barUp.dwellMode = !sweepEnabled
        barUp.dwellMs = dwellMs
        barDown.dwellMode = !sweepEnabled
        barDown.dwellMs = dwellMs
        val heldUp = barUp.step(u, y, dtMs, still)
        val heldDown = barDown.step(u, y, dtMs, still)
        barDwellProg = maxOf(barUp.dwellProgress, barDown.dwellProgress)
        return heldUp || heldDown
    }

    /** The thumb in BAR-V, which is the space the gaze arrives in.
     *
     *  v runs 0.010 at the BOTTOM to 0.990 at the TOP - the opposite of
     *  bitmap rows, and scaled to the bar's own 0.98 range, not to 1.0. An
     *  earlier version computed the span in bitmap pixels and compared it
     *  against a gaze v, so it looked for the thumb at v~0.09-0.33 when the
     *  thumb was actually at v~0.90: nothing ever lined up and no gesture
     *  could fire. One space, one source of truth, for both the hit test
     *  and the drawing (see barThumbPx). */
    private fun barThumbSpan(): FloatArray {
        val h = webViewFrac.coerceIn(0.02f, 1f)
        val trackTop = barV1 - BAR_ARROW
        val trackBot = barV0 + BAR_ARROW
        val thumbV = (h.coerceAtLeast(0.02f) * (trackTop - trackBot))
            .coerceAtMost(trackTop - trackBot)
        val travel = (trackTop - trackBot) - thumbV
        val frac = if (barDragging) barDragFrac else webScrollFrac.coerceIn(0f, 1f)
        val top = trackTop - travel * frac
        return floatArrayOf(top - thumbV, top)
    }

    /** The same thumb in bitmap rows, for drawing. v is flipped and scaled
     *  back to the strip's pixel range so the silhouette and the hit test
     *  cannot drift apart. */
    private fun barThumbPx(): FloatArray {
        val s = barThumbSpan()
        return floatArrayOf(
            (barV1 - s[1]) / (barV1 - barV0) * BAR_BM_H,
            (barV1 - s[0]) / (barV1 - barV0) * BAR_BM_H
        )
    }

    /** Entry test. -2 entered from above, +2 from below, 0 not an entry.
     *
     *  RULE: entry has NO margin. The reticle must be on the thumb itself,
     *  inside its rect, and on a nominated side. The angle of attack is not
     *  read - see THE RULE at the top of this file.
     *
     *  The event is the gaze segment's FIRST contact with the frozen thumb
     *  rect: which edge the prev->cur path touches first decides, not where
     *  the path ends up. Endpoint polling could not do this - on the
     *  nearly-square thumb a point entered on the left but near the top still
     *  reports Top by nearest-edge, so side entries activated. The first hit
     *  cannot lie about which face was crossed.
     *
     *  `span` is frozen by the caller for the whole frame; intersecting a
     *  moving rect is what made an earlier crossing attempt miss. */
    private fun barEntryEdge(span: FloatArray, u: Float): Int {
        val top = span[1]
        val bot = span[0]
        if (top <= bot) return 0
        val x0 = barPrevU
        val y0 = barPrevV
        val x1 = u
        val y1 = barCurV
        // Starting on the thumb is not an event. Resting on it must never
        // fire; only a fresh crossing does.
        if (x0 >= barU0 && x0 <= barU1 && y0 >= bot && y0 <= top) return 0
        val dx = x1 - x0
        val dy = y1 - y0
        if (dx == 0f && dy == 0f) return 0
        // Slab intersection of the gaze segment with the frozen span. tU/tV
        // are the segment parameters where it enters each axis's range; the
        // larger one is the FIRST contact with the rect.
        val tU: Float
        val tV: Float
        if (dx == 0f) {
            if (x0 < barU0 || x0 > barU1) return 0
            tU = Float.NEGATIVE_INFINITY
        } else {
            tU = minOf((barU0 - x0) / dx, (barU1 - x0) / dx)
        }
        if (dy == 0f) {
            if (y0 < bot || y0 > top) return 0
            tV = Float.NEGATIVE_INFINITY
        } else {
            tV = minOf((bot - y0) / dy, (top - y0) / dy)
        }
        val tEnter = maxOf(tU, tV)
        // No contact within this frame's movement: passes wide, goes the
        // wrong way, or would arrive on a later frame.
        if (tEnter > 1f) return 0
        // Contact behind the start: the reticle begins outside and moves
        // AWAY, so the "first hit" is in the past and this frame touches
        // nothing. Without this, drifting off the thumb reads as entering
        // it - the side-entry the trace kept showing, always on departure.
        if (tEnter < 0f) return 0
        // Both slabs entered together: dead on the corner, which is not an
        // entry point.
        if (tU != Float.NEGATIVE_INFINITY && tV != Float.NEGATIVE_INFINITY &&
            kotlin.math.abs(tU - tV) < 1e-6f) {
            if (bookDbg) FileLog.i("SweepVR-bar", "no-entry corner-hit")
            return 0
        }
        if (tV > tU) {
            // First contact is the top or the bottom end. v counts UP, so a
            // path starting above (y0 > top) comes down onto the top end.
            val edge = if (y0 > top) -2 else 2
            // ...but only if the contact pixel is on the flat of the end, not
            // up in the rounded corner. BAR_CORNER_FRAC is the radius the
            // strip bitmap actually rounds with, so this refuses exactly the
            // drawn curve.
            val hx = x0 + dx * tEnter
            val w = barU1 - barU0
            val c = BAR_CORNER_FRAC * w
            if (hx < barU0 + c || hx > barU1 - c) {
                if (bookDbg) FileLog.i("SweepVR-bar",
                    "no-entry end-corner hitU=${"%.3f".format(hx)}")
                return 0
            }
            return edge
        }
        // First contact is the left or the right face. That is never an
        // entry side for the thumb, however near the top the path ends up.
        if (bookDbg) FileLog.i("SweepVR-bar",
            "no-entry side-hit prevU=${"%.3f".format(x0)} curU=${"%.3f".format(x1)}")
        return 0
    }


    /** Scroll fraction for a bar v: 0 = track top, 1 = track bottom. */
    private fun barFracOf(v: Float): Float {
        val top = barV1 - BAR_ARROW
        val bot = barV0 + BAR_ARROW
        return if (top <= bot) 0f else ((top - v) / (top - bot)).coerceIn(0f, 1f)
    }

    /** Sweep control on the scrollbar thumb. Called every frame while the
     *  page has the gaze.
     *
     *  Enter the thumb from above or below and it snaps to the reticle and
     *  follows it - or, with sweep off, dwell anywhere on it (barDwellGrab).
     *  Let go sideways, past the leeway, and it drops at the
     *  current value. Carry on past the end of the track and it exits AT
     *  that extreme: fully scrolled to the top or the bottom.
     *
     *  Returns true while it owns the frame, so the page dwell does not
     *  also fire while the thumb is being dragged. */
    /** Dwell fill toward a thumb grab (sweep off), and tremor time outside. */
    private var barDwellFill = 0f
    private var barDwellOutsideMs = 0L

    private fun barSweep(u: Float, v: Float, still: Boolean, dtMs: Long): Boolean {
        barPrevV = barCurV
        barCurV = v
        barPrevU = barCurU
        barCurU = u
        // Throttled dump: the thumb's extent against the gaze, so a mismatch
        // is visible in the log instead of having to be inferred.
        if (bookDbg && now() - barDbgT0 > 400L) {
            barDbgT0 = now()
            val sp = barThumbSpan()
            FileLog.i("SweepVR-bar", "gaze u=${"%.3f".format(u)} v=${"%.3f".format(v)} " +
                "thumb v=${"%.3f".format(sp[0])}..${"%.3f".format(sp[1])} " +
                "bar u=${barU0}..${barU1} scrollable=$webScrollable " +
                "drag=$barDragging enterOpen=$barEnterOpen")
        }
        if (u < barU0 - BAR_SLOP_U || u > barU1 + BAR_SLOP_U) {
            if (barDragging) barRelease("off-bar", barDragFrac)
            else if (!sweepEnabled) {
                // Far outside the bar: genuine leave, not wobble. The near-
                // edge grace lives inside barDwellGrab; out here the fill
                // goes, or a lit thumb would hover over the page dwell.
                barDwellFill = 0f; barEnterOpen = false; barDwellOutsideMs = 0L
            }
            return false
        }
        val span = barThumbSpan()
        val tol = BAR_GRIP_TOL
        if (!barDragging) {
            // Dwell twin of the edge-entry grab below (sweep off): dwell
            // anywhere on the thumb to grab it. Follow, sideways drop and
            // end-cancel are positional, never sweep, so they run unchanged.
            if (!sweepEnabled) return barDwellGrab(u, v, span, still, dtMs)
            // Leave the latch behind when the reticle goes away, so each
            // approach is judged on its own crossing.
            if (v < span[0] - BAR_REARM_V || v > span[1] + BAR_REARM_V ||
                u < barU0 - BAR_REARM_U || u > barU1 + BAR_REARM_U) {
                barEnterLocked = false; barEnterOpen = false
            }
            val e = barEntryEdge(span, u)
            when {
                // A genuine crossing of the top or bottom edge: this is the
                // entry. Arm the latch HERE, on the frame the boundary was
                // actually crossed.
                e == -2 || e == 2 -> {
                    barEnterLocked = true; barEnterOpen = true
                    barDragging = true
                    barDragFrac = barFracOf(v)          // snap to the reticle
                    FileLog.i("SweepVR-bar", "grab edge=$e frac=${"%.2f".format(barDragFrac)}")
                    return true
                }
                // Anything else - already inside, no crossing, or a corner -
                // is NOT latched. Latching a refusal was the bug: the trace
                // showed the reticle sitting INSIDE the thumb (v 0.375..0.506
                // against a span of 0.373..0.563) for the whole approach, and
                // the verdict was frozen on the first frame it came near, so
                // no crossing was ever evaluated. Nothing has to be decided
                // until a boundary is actually crossed.
                bookDbg -> FileLog.i("SweepVR-bar",
                    "no-entry e=$e prevU=${"%.3f".format(barPrevU)} prev=${"%.3f".format(barPrevV)} cur=${"%.3f".format(barCurV)} span=${"%.3f".format(span[0])}..${"%.3f".format(span[1])}")
            }
            return barDragging
        }
        // Dragging: the thumb tracks the reticle, clamped to the track.
        //
        // The exits are tested BEFORE the position is updated, and they
        // release the position as it was when the reticle was last on the
        // track. Updating first let the out-of-range v clamp the fraction to
        // an extreme, and that clamped value is what then got released - so
        // letting go to the side dropped the page at the top or bottom
        // instead of where the thumb actually was.
        val offX = u < barU0 - tol || u > barU1 + tol
        val offY = v < barV0 - tol || v > barV1 + tol
        if (offX) { barRelease("sideways", barDragFrac); return false }
        if (offY) {
            // Past an end: abandon, and put the thumb back where it was.
            //
            // This is the one deliberate behaviour change from the version
            // that worked. Exiting at the extreme jumped the page to the top
            // or the bottom - a large, hard-to-undo move caused by the
            // easiest slip to make, which is carrying the reticle too far
            // while reaching for something else. Grabbing by accident is now
            // just as easy to back out of, so a mistake costs nothing.
            barRelease("cancel-end", barThumbSpanValue())
            return false
        }
        barDragFrac = barFracOf(v)
        return true
    }

    /** Dwell-to-grab for the thumb (sweep off): dwell anywhere on it and it
     *  snaps exactly as the edge grab does. Whole thumb counts, no edges,
     *  no corners. Stillness fills, motion drains, a brief wobble outside
     *  rides through (same 200ms as the arrow holds), leaving properly
     *  resets. Returns true while filling, so the frame claim matches the
     *  sweep grab and the page cannot fire underneath. */
    private fun barDwellGrab(u: Float, v: Float, span: FloatArray, still: Boolean, dtMs: Long): Boolean {
        val onThumb = u >= barU0 && u <= barU1 && v >= span[0] && v <= span[1]
        if (!onThumb) {
            barDwellOutsideMs += dtMs.coerceAtLeast(0L)
            if (barDwellFill > 0f && barDwellOutsideMs <= 200L) {
                barEnterOpen = true
                barDwellProg = maxOf(barDwellProg, barDwellFill)
                return true
            }
            if (barDwellFill > 0f && bookDbg) FileLog.i("SweepVR-bar", "dwell exit reset")
            barDwellFill = 0f
            barEnterOpen = false
            barDwellOutsideMs = 0L
            return false
        }
        barDwellOutsideMs = 0L
        if (still) barDwellFill = minOf(1f, barDwellFill + dtMs.toFloat() / dwellMs.toFloat().coerceAtLeast(1f))
        else barDwellFill = maxOf(0f, barDwellFill - dtMs / 600f)
        barEnterOpen = true
        barDwellProg = maxOf(barDwellProg, barDwellFill)
        if (barDwellFill < 1f) return true
        barDwellFill = 0f
        barEnterLocked = true
        barDragging = true
        barDragFrac = barFracOf(v)
        FileLog.i("SweepVR-bar", "grab dwell frac=${"%.2f".format(barDragFrac)}")
        return true
    }

    /** The thumb's position as a fraction, for restoring it on a cancel. */
    private fun barThumbSpanValue(): Float = webScrollFrac.coerceIn(0f, 1f)

    private fun barRelease(why: String, frac: Float) {
        if (now() - barDbgT0 > 120L) {
            barDbgT0 = now()
            FileLog.i("SweepVR-bar", "drop $why frac=${"%.2f".format(frac.coerceIn(0f, 1f))}")
        }
        barDragging = false
        barEnterLocked = false; barEnterOpen = false
        if (why == "cancel-end") {
            // Restore the thumb and leave the page exactly where it was.
            webScrollFrac = frac
            barSettleUntil = 0L
            return
        }
        onWebEvent(WebEvent.ScrollTo(frac.coerceIn(0f, 1f)))
    }


    /** Dwell on the page: arrows scroll, links/controls click, inert page
     *  opens the web panel. Same leaky dwell + stillness gate as the menu. */
    private fun updateWebGaze() {
        val nowMs = now()
        val dtMs = (nowMs - webProgT).coerceIn(0L, 500L)
        webProgT = nowMs
        val uv = synchronized(headViewM) { webGazeUv(lastEffFwd) }
        val still = synchronized(headViewM) { webStill.update(headViewM, nowMs) }
        if (uv == null) { webPagePointOk = false; webPageDist = -1f; webProgF = maxOf(0f, webProgF - dtMs / 600f); return }

        // The sweep targets claim the frame before the page dwell, so a drag
        // or a held arrow never also registers as a click on the page.
        barDwellProg = 0f
        // The keyboard owns the frame outright while it is up: every key is a
        // sweep, and a page dwell firing underneath one would be the exact
        // accident sweep exists to prevent. Same rule as barSweep below.
        if (stepKeyboard(dtMs, still)) { webProgF = 0f; return }
        // The scrollbar THUMB before the arrows, as it always was. This line
        // was deleted outright by an edit meant to insert the keyboard claim
        // above it, so the thumb's entry test stopped being evaluated at all
        // and the thumb could not be grabbed - while the arrows, stepped on
        // the next line, kept working, which is a confusing way for it to
        // break.
        if (webScrollable && barSweep(uv[0], uv[1], still, dtMs)) { webProgF = 0f; return }
        if (webScrollable && barArrows(uv[0], uv[1], dtMs, still)) { webProgF = 0f; return }
        // Toolbar above the screen: live whether or not the page scrolls.
        if (toolbarStep(uv[0], uv[1], still, dtMs)) { webProgF = 0f; return }
        // The page is drawn with no texture zoom, so the texture coord under
        // the reticle IS the screen coord. The inversion that used to live
        // here was the source of a run of gaze/page misalignments: it fed
        // the reticle the unzoomed texel position, which is only the same
        // point while zoom happens to be 1.
        val px = uv[0] * webPageW
        val py = (1f - uv[1]) * webPageH
        noteWebPagePoint(uv[0], uv[1])

        // The dwell target is the STATE under the reticle, not the exact
        // pixel: keying on pixel coordinates made every wobble of the head
        // (VR jitter is never quite still) reset the dwell, so a link could
        // never be stared at long enough to fire. The click still uses the
        // live pixel, so it lands where you are actually looking.
        val target = if (webHitInteractive) "hit" else ""
        // A hit answer is up to ~100 ms stale. Only distrust it if the gaze
        // has genuinely gone somewhere else — a tight radius made the
        // reticle flicker between "on a link" and "not" with every wobble,
        // which reset the dwell forever and nothing ever fired.
        if (webAskPx >= 0f &&
            kotlin.math.hypot(px - webAskPx, py - webAskPy) > WEB_HIT_SLOP
        ) webHitInteractive = false

        if (target != webTarget) {
            webTarget = target
            webFiredFor = ""
            // Blank page (or moved off a control) resets hard: the reticle
            // must not stay shrunk with nothing to activate.
            webProgF = if (target.isEmpty()) 0f else minOf(webProgF, 0.25f)
        }
        // Ask the page what is under the reticle (the answer lands in
        // onWebHit). Throttled, and re-asked after firing so moving away and
        // back is a fresh act.
        // Keep the hit state live even after a fire: gating the refresh on
        // the latch deadlocked it (the target can only change if we keep
        // asking, so the first click froze dwell for the whole page).
        if (nowMs - webHitAskedAt > 100L) {
            webHitAskedAt = nowMs
            webHitToken++
            webAskPx = px; webAskPy = py
            onWebEvent(WebEvent.HitTest(webHitToken, px, py))
        }
        // Nothing to activate here: wait (the panel opens on gaze angle, not
        // by staring at blank page).
        if (target.isEmpty()) { webProgF = 0f; return }

        if (webFiredFor == webTarget) webProgF = 1f   // held at full shrink until the gaze leaves
        else if (still) webProgF += dtMs / dwellMs.toFloat()
        else webProgF = maxOf(0f, webProgF - dtMs / 600f)
        if (still && webProgF >= 1f && webFiredFor != webTarget) {
            webFiredFor = webTarget
            webProgF = 1f
            FileLog.i("SweepVR-web", "FIRE target=$webTarget px=$px py=$py")
            onWebEvent(WebEvent.Click(px, py))
            // Re-arm once the gaze leaves, so a second stare is a second act.
        }
    }

    private fun webDwellProg(): Float = webProgF.coerceIn(0f, 1f)

    /** TEMP DEBUG: where the gaze lands on the page, tracked every frame
     *  INCLUDING while the web panel holds the gaze (otherwise the on-page
     *  crosshair froze the moment you looked at the panel). */
    /** The page pixel currently under the gaze, in world space. The reticle
     *  is drawn AT this point, not at a distance along the ray, so it lands
     *  on the same surface and converges with the page in both eyes. */
    @Volatile private var webPagePoint = FloatArray(3)
    @Volatile private var webPagePointOk = false
    @Volatile private var webPageDist = -1f

    /** World point on the ACTUAL page mesh at texture coord (u,v).
     *
     *  The mesh is a regular row-major grid, so the cell is found directly
     *  from (u,v) instead of by hunting for a near vertex. The earlier
     *  nearest-vertex search stepped the v-neighbour by ONE index, but in a
     *  row-major grid the v-neighbour is a whole row away - so it
     *  interpolated against another u-neighbour and the result snapped to
     *  grid rows, which showed up as chunky vertical reticle motion while
     *  horizontal stayed smooth.
     *
     *  Reading the real vertices (rather than the analytic webPointAt model)
     *  means this cannot disagree with the surface actually drawn.
     */
    private fun webPointOnMesh(u: Float, v: Float, out: FloatArray): Boolean {
        val m = webMesh ?: return false
        val vs = m.tex; val ps = m.verts
        val nv = vs.capacity() / 2
        if (nv < 4 || ps.capacity() < nv * 3) return false
        fun tu(i: Int) = vs.get(i * 2)
        fun tv(i: Int) = vs.get(i * 2 + 1)
        fun pu(i: Int, k: Int) = ps.get(i * 3 + k)

        // Row length: u climbs across a row and wraps at the row end.
        var rowLen = 1
        while (rowLen < nv && tu(rowLen) > tu(rowLen - 1)) rowLen++
        if (rowLen < 2 || nv % rowLen != 0) return false
        val rows = nv / rowLen
        if (rows < 2) return false

        // Which row does texture v belong to? Measured on the real mesh:
        // row 0 carries v = 1 and the last row v = 0, so v = 1 is the FIRST
        // row. Guessing this sign wrong mirrors the lookup vertically - and a
        // crosshair cannot reveal it, being symmetric top-to-bottom.
        val firstRowV = tv(0)
        val lastRowV = tv((rows - 1) * rowLen)
        val vIsRowIndex = firstRowV <= lastRowV   // v climbs down the rows

        val fx = (u.coerceIn(0f, 1f)) * (rowLen - 1)
        val fyRaw = (v.coerceIn(0f, 1f)) * (rows - 1)
        val fy = if (vIsRowIndex) fyRaw else (rows - 1) - fyRaw

        var cx = kotlin.math.floor(fx.toDouble()).toInt()
        var cy = kotlin.math.floor(fy.toDouble()).toInt()
        if (cx > rowLen - 2) cx = rowLen - 2
        if (cx < 0) cx = 0
        if (cy > rows - 2) cy = rows - 2
        if (cy < 0) cy = 0
        val ax = fx - cx
        val ay = fy - cy

        val i00 = cy * rowLen + cx
        val i10 = i00 + 1
        val i01 = i00 + rowLen
        val i11 = i01 + 1
        for (k in 0..2) {
            val p00 = pu(i00, k); val p10 = pu(i10, k)
            val p01 = pu(i01, k); val p11 = pu(i11, k)
            val top = p00 + (p10 - p00) * ax
            val bot = p01 + (p11 - p01) * ax
            out[k] = top + (bot - top) * ay
        }
        // The mesh is drawn through modelM (the +0.25 lift, and the flat
        // distance or cap z), so apply it here too or the point lands in the
        // wrong space entirely.
        buildVideoModel()
        val wx = out[0]; val wy = out[1]; val wz = out[2]
        out[0] = modelM[0] * wx + modelM[4] * wy + modelM[8] * wz + modelM[12]
        out[1] = modelM[1] * wx + modelM[5] * wy + modelM[9] * wz + modelM[13]
        out[2] = modelM[2] * wx + modelM[6] * wy + modelM[10] * wz + modelM[14]
        return true
    }

    /** Gaze ray -> the web panel's surface. Writes the world point into
     *  [out] and returns true ONLY when the ray genuinely lands inside the
     *  panel rectangle.
     *
     *  Deliberately no clamping. Clamping the plane intersection to the rect
     *  pinned the reticle to the panel edge whenever the gaze left the panel,
     *  so it latched there and would not come back down until a recenter; and
     *  a ray passing below the panel still hits the same infinite plane, just
     *  on the far side, which flipped the reticle from below the page to
     *  above it. Rejecting off-panel hits instead lets the reticle fall back
     *  to the page, which is the surface actually being looked at. */
    private fun webPanelPoint(out: FloatArray): Boolean {
        val o = invHeadWorldM
        val fwd = lastEffFwd
        val d = panelDistM
        val el = Math.toRadians(browserElevDeg.toDouble()).toFloat()
        val cy = kotlin.math.sin(el) * d
        val cz = -kotlin.math.cos(el) * d
        var pny = -cy; var pnz = -cz
        val pnl = kotlin.math.sqrt(pny * pny + pnz * pnz).coerceAtLeast(1e-6f)
        pny /= pnl; pnz /= pnl
        val denom = fwd[1] * pny + fwd[2] * pnz
        if (denom >= -0.05f) return false      // edge-on or facing away
        val t = ((cy - o[13]) * pny + (cz - o[14]) * pnz) / denom
        if (t <= 0f) return false
        val hx = o[12] + fwd[0] * t
        val hy = o[13] + fwd[1] * t
        val hz = o[14] + fwd[2] * t
        val upx = 0f; val upy = pnz; val upz = -pny
        val upl = kotlin.math.sqrt(upy * upy + upz * upz).coerceAtLeast(1e-6f)
        val hw = panelHalfW(); val hh = panelHalfH()
        val alongRight = hx
        val alongUp = (hy - cy) * (upy / upl) + (hz - cz) * (upz / upl)
        // Compact panel (open web panel only): the surface ends at hc, so
        // points below it are off-panel. Full TEX elsewhere - identical.
        val hhc = hh * panelHc() / TEX
        if (alongRight < -hw || alongRight > hw) return false
        if (alongUp < hh - 2 * hhc || alongUp > hh) return false
        out[0] = hx; out[1] = hy; out[2] = hz
        return true
    }

    /** Where the reticle was last drawn, and the eased position now being
     *  drawn. The page and the panel are at very different depths (12 m vs
     *  ~4 m), so switching surface snapped the reticle; easing over ~120 ms
     *  turns that snap into a short glide without adding lag to normal
     *  tracking, since the target itself is still followed every frame. */
    private val webLastPoint = FloatArray(3)
    @Volatile private var webLastPointOk = false
    private val webSmoothPoint = FloatArray(3)
    @Volatile private var webSmoothOk = false
    private var webSmoothT = 0L

    /** Places the reticle on the gaze RAY at [dist] from the head, seeded
     *  from the current smoothed point so crossing into or out of the gap is
     *  continuous. Used only where neither surface is under the gaze. */
    private fun webReticleRay(dist: Float) {
        val o = invHeadWorldM
        val f = lastEffFwd
        if (!webSmoothOk) {
            for (i in 0..2) webSmoothPoint[i] = o[12 + i] + f[i] * dist
            webSmoothOk = true
        }
        val nowMs = now()
        val dt = ((nowMs - webSmoothT).coerceIn(0L, 100L)).toFloat() / 100f
        webSmoothT = nowMs
        val k = 1f - kotlin.math.exp(-dt / 0.12f)
        for (i in 0..2) {
            val t = o[12 + i] + f[i] * dist
            webSmoothPoint[i] += (t - webSmoothPoint[i]) * k
        }
        webLastPoint[0] = webSmoothPoint[0]
        webLastPoint[1] = webSmoothPoint[1]
        webLastPoint[2] = webSmoothPoint[2]
        webLastPointOk = true
        billboardAt(tmpA, webSmoothPoint)
    }

    /** Half-size of the reticle quad, from the distance to where it is
     *  ACTUALLY drawn. Sizing from a nominal distance (the page depth or the
     *  panel depth, whichever branch we took) is what made the reticle
     *  shrink to about half when it was drawn on the far page using the
     *  panel's near distance, and why a recenter appeared to "fix" it. */
    private fun webRetS(sizeMul: Float, prog: Float): Float {
        val hx = invHeadWorldM[12]; val hy = invHeadWorldM[13]; val hz = invHeadWorldM[14]
        val dx = webSmoothPoint[0] - hx
        val dy = webSmoothPoint[1] - hy
        val dz = webSmoothPoint[2] - hz
        val dist = kotlin.math.sqrt(dx * dx + dy * dy + dz * dz).coerceAtLeast(0.5f)
        return kotlin.math.sin(RETICLE_ANG) * dist * sizeMul * (1f - 0.85f * prog.coerceIn(0f, 1f))
    }

    /** tmpA for a reticle quad centred at world point [p], rotated to face
     *  the eye (billboard).
     *
     *  Position stays world-anchored (stereo convergence), but a pure
     *  translation left the quad world-axis-aligned, so off-centre it was
     *  viewed obliquely and foreshortened into an ellipse - high, low or
     *  wide of centre it looked skewed. Facing the eye keeps it round
     *  everywhere, like the old head-locked ring. Up comes from head-up so
     *  it stays upright under roll. */
    private fun billboardAt(t: FloatArray, p: FloatArray) {
        var zx = invHeadWorldM[12] - p[0]
        var zy = invHeadWorldM[13] - p[1]
        var zz = invHeadWorldM[14] - p[2]
        val zl = kotlin.math.sqrt(zx * zx + zy * zy + zz * zz)
        if (zl < 1e-6f) {
            Matrix.setIdentityM(t, 0)
            Matrix.translateM(t, 0, p[0], p[1], p[2])
            return
        }
        zx /= zl; zy /= zl; zz /= zl
        val up = lastEffUp
        // x = up x z, y = z x x: orthonormal, right-handed, so the ring
        // texture is neither mirrored nor culled.
        var xx = up[1] * zz - up[2] * zy
        var xy = up[2] * zx - up[0] * zz
        var xz = up[0] * zy - up[1] * zx
        var xl = kotlin.math.sqrt(xx * xx + xy * xy + xz * xz)
        if (xl < 1e-4f) {
            // Gaze along head-up: fall back to head-right for x.
            xx = invHeadWorldM[0]; xy = invHeadWorldM[1]; xz = invHeadWorldM[2]
            xl = kotlin.math.sqrt(xx * xx + xy * xy + xz * xz).coerceAtLeast(1e-6f)
        }
        xx /= xl; xy /= xl; xz /= xl
        val yx = zy * xz - zz * xy
        val yy = zz * xx - zx * xz
        val yz = zx * xy - zy * xx
        t[0] = xx; t[1] = xy; t[2] = xz; t[3] = 0f
        t[4] = yx; t[5] = yy; t[6] = yz; t[7] = 0f
        t[8] = zx; t[9] = zy; t[10] = zz; t[11] = 0f
        t[12] = p[0]; t[13] = p[1]; t[14] = p[2]; t[15] = 1f
    }

    /** Builds tmpA for the reticle quad at [target], easing from the
     *  previous position. Callers premultiply tmpA by ovM. */
    private fun webReticleAt(target: FloatArray) {
        val nowMs = now()
        val dt = ((nowMs - webSmoothT).coerceIn(0L, 100L)).toFloat() / 100f
        webSmoothT = nowMs
        if (!webLastPointOk) {
            System.arraycopy(target, 0, webLastPoint, 0, 3)
            System.arraycopy(target, 0, webSmoothPoint, 0, 3)
            webLastPointOk = true; webSmoothOk = true
        } else {
            System.arraycopy(target, 0, webLastPoint, 0, 3)
            if (!webSmoothOk) {
                System.arraycopy(target, 0, webSmoothPoint, 0, 3)
                webSmoothOk = true
            } else {
                // Frame-rate independent exponential approach.
                val k = 1f - kotlin.math.exp(-dt / 0.12f)
                for (i in 0..2) webSmoothPoint[i] += (target[i] - webSmoothPoint[i]) * k
            }
        }
        billboardAt(tmpA, webSmoothPoint)
    }

    private fun noteWebPagePoint(u: Float, v: Float) {
        // Off the page the mesh lookup clamps to the page edge, which parked
        // the reticle there and made it pause in the gap above the page. The
        // analytic form extends smoothly past the edge instead, so the target
        // keeps moving and the reticle glides up into the panel.
        if (u >= 0f && u <= 1f && v >= 0f && v <= 1f)
            webPointOnMesh(u, v, webPagePoint)
        else
            webPointAt(u, v, webPagePoint)
        val hx = invHeadWorldM[12]; val hy = invHeadWorldM[13]; val hz = invHeadWorldM[14]
        val dx = webPagePoint[0] - hx
        val dy = webPagePoint[1] - hy
        val dz = webPagePoint[2] - hz
        webPageDist = kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
        webPagePointOk = true
    }

    /** Track where the gaze lands on the page plane. Runs whether or not the
     *  panel is open, and whether or not the debug crosshair is on, because
     *  the reticle depends on it. */
    fun updateWebPagePoint() {
        if (mode != Mode.WEB) return
        val uv = synchronized(headViewM) { webGazeUv(lastEffFwd) } ?: run {
            webPagePointOk = false
            return
        }
        noteWebPagePoint(uv[0], uv[1])
    }

    fun updateWebDebugPoint() {
        if (!webDbgOn || mode != Mode.WEB) return
        val uv = synchronized(headViewM) { webGazeUv(lastEffFwd) } ?: return
        webDebugPoint = floatArrayOf(uv[0] * webPageW, (1f - uv[1]) * webPageH)
    }

    /** Keep the web panel shut for a moment. The gaze is often still over it
     *  right after it was used (a bookmark row, a recenter), and letting it
     *  spring open again takes the gaze off the page it just loaded. */
    fun webLockPanel() { webPanelHold = 0f; webPanelLockUntil = now() + (PANEL_TOGGLE_MS * 6).toLong() }

    /** TEST BUILD ONLY: pin the panel open regardless of gaze, so it can be
     *  inspected with the phone flat on a desk. */
    fun webPanelPin(on: Boolean) { webPanelPinned = on; if (on) webPanelOpen = true }

    /** Forget the fired latch, so a freshly loaded page's links are dwellable
     *  straight away. */
    fun webResetDwell() {
        webFiredFor = "\u0000none"
        webTarget = ""
        webProgF = 0f
        webHitInteractive = false
        webPanelHold = 0f
    }

    /** The web panel opens when the gaze actually goes UP ONTO it, and
     *  closes when it goes back to the page — not on a head-tilt threshold.
     *  A tilt gate was unusable: holding the phone leaves you permanently
     *  past the threshold, so the panel stayed open, swallowed the gaze and
     *  the page never got a single dwell. A short dwell on the panel also
     *  stops a glance while turning from firing it.
     *
     *  The trigger is gaze ELEVATION against the panel's lower edge, with a
     *  hysteresis band (webTiltWantOpen). Nothing on the page is read: an
     *  earlier version intersected the gaze with the panel plane and tested
     *  the rect, which fired from empty space that merely lined up with the
     *  old full-height surface. */
    private fun updateWebPanelTilt() {
        // Just recentred: whatever the gaze lands on, the page keeps it for
        // a moment. Without this, recentring while looking at the panel let
        // it reopen at once and swallow the gaze, and the page looked dead.
        if (webPanelPinned) { webPanelHold = 1f; webPanelOpen = true; return }
        if (now() < webPanelLockUntil) { webPanelHold = 0f; return }
        // While the flyout is up, the panel stays: the gesture deliberately
        // carries the gaze off the panel edge, and without this the tilt
        // logic would close the whole panel before the exit resolved.
        if (webBookIconRow >= 0 && bookActive) { webPanelHold = 1f; return }
        val onPanel = webTiltWantOpen()
        val dt = (now() - webPanelT0).coerceIn(0L, 500L)
        webPanelT0 = now()
        if (onPanel) {
            webPanelHold = minOf(1f, webPanelHold + dt / PANEL_TOGGLE_MS)
            if (webPanelHold >= 1f && !webPanelOpen) {
                webPanelOpen = true
                FileLog.i("SweepVR-web", "web panel open (gaze on panel) " +
                    "gazeEl=${"%.1f".format(lastTiltGazeEl)} edge=${"%.1f".format(lastTiltEdge)}")
                onWebEvent(WebEvent.Panel(true))
            }
        } else {
            webPanelHold = maxOf(0f, webPanelHold - dt / PANEL_TOGGLE_MS)
            if (webPanelHold <= 0f && webPanelOpen) {
                webPanelOpen = false
                // Re-arm the dwell for the page. While the panel was open
                // the page gaze did not run, so the fired latch kept the
                // target it had when you looked up; coming back down onto
                // the same link then read as already-fired and would not
                // activate until a recenter cleared it.
                webResetDwell()
                FileLog.i("SweepVR-web", "web panel close (gaze back on page) " +
                    "gazeEl=${"%.1f".format(lastTiltGazeEl)} edge=${"%.1f".format(lastTiltEdge)}")
                onWebEvent(WebEvent.Panel(false))
            }
        }
    }

    /** Last tilt-trigger inputs, for the open/close trace lines. */
    private var lastTiltGazeEl = 0f
    private var lastTiltEdge = 0f
    /** Tilt trigger for the web panel, with hysteresis.
     *
     *  The open edge is the elevation of the panel's LOWER edge (upper edge
     *  if the panel ever hangs below the horizon): once open, it stays open
     *  until the gaze drops back past the hysteresis band, so hovering near
     *  the edge can't flutter it. Governed by tilt angle alone - nothing on
     *  the page is tested, so no page content can ever open it.
     *
     *  The edge follows the compact height, so the trigger tracks the drawn
     *  surface; full height until the first upload computes the compact one
     *  (today's behaviour on a fresh panel exactly). */
    private fun webTiltWantOpen(): Boolean {
        val gazeEl = Math.toDegrees(kotlin.math.asin(lastEffFwd[1].coerceIn(-1f, 1f)).toDouble())
        val hc = if (webSideBtns) webCompactH.coerceIn(200f, TEX.toFloat()) else TEX.toFloat()
        val hhc = panelHalfH() * hc / TEX.toFloat()
        // Lower edge of the DRAWN surface: the mesh is top-anchored (its top
        // never moves), so the bottom sits centre + (hh - 2*hhc) up the panel
        // axis - for the compact web panel that is ABOVE centre, not below
        // it. Centring it here put the trigger ~8 degrees under the visible
        // panel, and the address bar kept firing it.
        val edge = browserElevDeg +
            Math.toDegrees(kotlin.math.atan2(
                (panelHalfH() - 2 * hhc).toDouble(), panelDistM.toDouble())).toFloat()
        lastTiltGazeEl = gazeEl.toFloat(); lastTiltEdge = edge
        // Panel above the horizon opens looking up; below it opens looking
        // down.
        return if (edge >= 0f) {
            if (webPanelOpen) gazeEl >= edge - PANEL_HYST_DEG else gazeEl >= edge
        } else {
            if (webPanelOpen) gazeEl <= edge + PANEL_HYST_DEG else gazeEl <= edge
        }
    }

    /** The scrollbar: up/down arrows plus a thumb, drawn as a strip pinned
     *  to the right edge of the (possibly curved) screen. */
    private fun drawWebBar() {
        if (!webScrollable) return
        val key = screenCurve.toString() + "|" + webScreenSize.toString() + "|" +
            webPageW + "x" + webPageH
        if (webBarMesh == null || webBarMeshKey != key) {
            webBarMesh = webBarMeshBuild()
            webBarMeshKey = key
        }
        val m = webBarMesh ?: return
        maybeUploadWebBar()
        drawMesh2d(m, webBarTexId, ovM, 1f)
    }

    // ---- browser toolbar ----
    /* A strip above the screen, mirroring the scrollbar's strip beside it:
     * full width u 0..1, v 1.010..1.074, same 0.064 chrome rhythm, placed
     * with webPointAt corners (which extrapolate past the edge) and pulled
     * 5cm toward the viewer like the bar. Back, forward, reload, home,
     * address, bookmarks, zoom-, zoom+, menu - all sweep entry, Left/Right
     * only, so vertical passes (page<->panel travel) go over inert.
     *
     * One rule governs every rect here: the SAME constants below build the
     * hit rects in toolbarStep and the pixels in maybeUploadToolbar, so the
     * drawn silhouette and the hit area cannot drift apart. */
    private val TB_V0 = 1.010f
    private val TB_V1 = 1.074f
    private val TB_BTN = 0.064f
    /** y-down flip, once, here: controls demand y-down (THE RULE), bar-v
     *  counts up. Negative is fine - Rect is pure math, no [0,1] claim. */
    private val TB_Y0 = 1f - TB_V1
    private val TB_Y1 = 1f - TB_V0
    private val TB_ADDR_U0 = 0.256f
    private val TB_ADDR_U1 = 0.744f
    private val TBW = 1024
    private val TBH = 64
    private val tbP = FloatArray(3)
    private var toolbarMesh: Mesh? = null
    private var toolbarMeshKey = ""
    private var toolbarBitmap: Bitmap? = null
    /** Sweep controls on/off (2D Display setting). Off rejects the sweep
     *  activation wholesale - toolbarStep never steps a sweep engine and
     *  runs dwell buttons over the same rects instead. No sweep logic is
     *  altered; it simply never runs. */
    @Volatile var sweepEnabled: Boolean = true
    /** Deepest dwell fill across the toolbar dwell buttons this frame, for
     *  the reticle shrink. Zero unless dwell mode is holding the gaze. */
    private var toolbarDwellProg = 0f
    private val tbBack = net.sweepvr.player.sweep.MomentaryControl()
    private val tbFwd = net.sweepvr.player.sweep.MomentaryControl()
    private val tbReload = net.sweepvr.player.sweep.MomentaryControl()
    private val tbHome = net.sweepvr.player.sweep.MomentaryControl()
    private val tbMenu = net.sweepvr.player.sweep.MomentaryControl()
    /** Dwell twins of the seven toolbar buttons above (back, forward,
     *  reload, home, menu, zoom-out, zoom-in). Stepped only with sweep off,
     *  over the same rects with the same fires. */
    private val dwBack = net.sweepvr.player.sweep.DwellButton()
    private val dwFwd = net.sweepvr.player.sweep.DwellButton()
    private val dwReload = net.sweepvr.player.sweep.DwellButton()
    private val dwHome = net.sweepvr.player.sweep.DwellButton()
    private val dwMenu = net.sweepvr.player.sweep.DwellButton()
    private val dwZoomOut = net.sweepvr.player.sweep.DwellButton()
    private val dwZoomIn = net.sweepvr.player.sweep.DwellButton()
    /** Bar-space bookmarks pane: a second DropdownControl in flipped bar
     *  coords, hanging below the strip over the page. Same items, same
     *  commit/cancel rule as the panel flyout; the two never meet. */
    private val bookBarCtl = net.sweepvr.player.sweep.DropdownControl()
    private var bookBarOpen = false
    private val barMeasurePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    /** Pane text size and side padding, strip-bitmap px. Rows are 50px. */
    private val TB_PANE_TEXT = 24f
    private val TB_PANE_PAD_PX = 20f
    @Volatile private var toolbarAddrFocused = false

    /** Step the toolbar for this frame. True if anything claimed it, so the
     *  page dwell does not fire underneath. Live in every web frame - unlike
     *  the bar, nothing here needs a scrollable page. */
    private fun toolbarStep(u: Float, v: Float, still: Boolean, dtMs: Long): Boolean {
        val y = 1f - v
        toolbarDwellProg = 0f
        // Sweep disabled: the sweep engines below never step (their entries,
        // sides and corners are rejected wholesale, not altered) - seven
        // dwell buttons own the frame instead. Bookmarks stay sweep-driven
        // until chunk 2 gives them their two dwells; the slot sits inert.
        if (!sweepEnabled) return toolbarDwell(u, v, still, dtMs)
        fun mrect(u0: Float, u1: Float) =
            net.sweepvr.player.sweep.Rect(u0, TB_Y0, u1, TB_Y1)
        fun cfgMomentary(c: net.sweepvr.player.sweep.MomentaryControl,
                         u0: Float, u1: Float, tag: String,
                         fire: (net.sweepvr.player.sweep.Side) -> Unit) {
            c.rect = mrect(u0, u1)
            // Same drawn curve as every other sweep button (r=4f on a ~65px
            // face), so the refused corners equal the drawn rounding.
            c.cornerFraction = BAR_BTN_CORNER_FRAC
            if (bookDbg) c.onTrace = { FileLog.i("SweepVR-tools", "$tag $it") }
            else c.onTrace = null
            c.onFire = fire
        }
        cfgMomentary(tbBack, 0f, TB_BTN, "back") { _ -> onWebEvent(WebEvent.GoBack) }
        cfgMomentary(tbFwd, TB_BTN, TB_BTN * 2, "fwd") { _ -> onWebEvent(WebEvent.GoForward) }
        cfgMomentary(tbReload, TB_BTN * 2, TB_BTN * 3, "reload") { _ -> onWebEvent(WebEvent.ReloadPage) }
        cfgMomentary(tbHome, TB_BTN * 3, TB_BTN * 4, "home") { _ -> onWebEvent(WebEvent.GoHome) }
        cfgMomentary(tbMenu, 1f - TB_BTN, 1f, "menu") { _ -> onWebEvent(WebEvent.OpenMenu) }
        // Bar-space bookmarks pane. Configured from the same bookmark list
        // as the panel flyout; the pane it opens hangs below the strip.
        cfgBookBar()
        val bb = bookBarCtl.step(u, y, dtMs)
        bookBarOpen = bookBarCtl.open
        if (bookBarOpen) {
            // The pane owns the frame outright: the strip buttons go quiet
            // rather than stale. Reset (not skip) keeps every engine's prev
            // fresh, so no frozen position can mis-route the next entry.
            // A zoom glide cannot be in flight here - it needs the gaze on
            // the zoom square, and the pane only opens from the bookmarks
            // square. Same single-gaze argument as everywhere else.
            tbBack.reset(); tbFwd.reset(); tbReload.reset()
            tbHome.reset(); tbMenu.reset()
            zoomOutCtl.reset(); zoomInCtl.reset()
            toolbarAddrFocused = false
            return bb
        }
        // The zoom steppers moved here from the panel icon row: same
        // controls, new squares. commitOnExit: arm on side-entry,
        // tap-commit on vertical exit - sliding along the toolbar row to a
        // far button fires nothing.
        //
        // onFire is TextZoom on EVERY fire, entry tap and hold repeats alike:
        // this is the panel steppers' old contract (a hold keeps stepping
        // the size). The glide hooks from init are deliberately cleared -
        // they belong to the scrollbar arrows; a held zoom must resize,
        // never scroll the page.
        zoomOutCtl.rect = mrect(1f - TB_BTN * 3, 1f - TB_BTN * 2)
        zoomInCtl.rect = mrect(1f - TB_BTN * 2, 1f - TB_BTN)
        zoomOutCtl.commitOnExit = true
        zoomInCtl.commitOnExit = true
        // Few-px exit leeway, exit-only per THE RULE. The panel steppers used
        // 8px on a 108px face; same proportion here. (RepeatControl defaults
        // to zero leeway, which would end every hold on a 1px wobble.)
        zoomOutCtl.leeway = 0.008f
        zoomInCtl.leeway = 0.008f
        zoomOutCtl.onFire = { onWebEvent(WebEvent.TextZoom(-1)) }
        zoomInCtl.onFire = { onWebEvent(WebEvent.TextZoom(1)) }
        zoomOutCtl.onRepeatStart = null
        zoomOutCtl.onRepeatStop = null
        zoomInCtl.onRepeatStart = null
        zoomInCtl.onRepeatStop = null
        zoomOutCtl.commitOnExit = true
        zoomInCtl.commitOnExit = true
        if (bookDbg) {
            zoomOutCtl.onTrace = { FileLog.i("SweepVR-tools", "zoomout $it") }
            zoomInCtl.onTrace = { FileLog.i("SweepVR-tools", "zoomin $it") }
        } else { zoomOutCtl.onTrace = null; zoomInCtl.onTrace = null }
        // Every control steps every frame - no short-circuit: each engine
        // tracks its own previous position.
        val b0 = if (webCanGoBack) tbBack.step(u, y, dtMs) else { tbBack.reset(); false }
        val b1 = if (webCanGoForward) tbFwd.step(u, y, dtMs) else { tbFwd.reset(); false }
        val b2 = tbReload.step(u, y, dtMs)
        val b3 = tbHome.step(u, y, dtMs)
        val b4 = tbMenu.step(u, y, dtMs)
        val z0 = zoomOutCtl.step(u, y, dtMs)
        val z1 = zoomInCtl.step(u, y, dtMs)
        // The address field is hover, not sweep: gaze inside = focused.
        toolbarAddrFocused =
            u >= TB_ADDR_U0 && u <= TB_ADDR_U1 && v >= TB_V0 && v <= TB_V1
        val addrP = stepAddrField(u, v, dtMs, still)
        // Fold the address field in HERE, before toolbarDwellProg is assigned.
        // Returning early on it - which is what this did - meant the frame went
        // out before the assignment, so the one control that opens the
        // keyboard was the one control with no visible charge.
        toolbarDwellProg = maxOf(toolbarDwellProg,
            if (!sweepEnabled) addrDwell.progress else 0f)
        if (addrP) return true
        return b0 || b1 || b2 || b3 || b4 || z0 || z1
    }

    /** Dwell twin of toolbarStep for sweep-disabled mode. Same rects, same
     *  fires, one shared DwellButton each - no sides, no dips, no angles.
     *  The sweep engines are reset, never stepped. Returns true while any
     *  button is filling, and feeds the reticle shrink through
     *  toolbarDwellProg. */
    private fun toolbarDwell(u: Float, v: Float, still: Boolean, dtMs: Long): Boolean {
        tbBack.reset(); tbFwd.reset(); tbReload.reset()
        tbHome.reset(); tbMenu.reset()
        zoomOutCtl.reset(); zoomInCtl.reset()
        toolbarAddrFocused =
            u >= TB_ADDR_U0 && u <= TB_ADDR_U1 && v >= TB_V0 && v <= TB_V1
        val y = 1f - v
        // Bar-space bookmarks pane, second dwell (first is the panel icon):
        // dwell the slot opens it, dwell a row commits. Same ownership rule
        // as sweep: an open pane owns the frame outright.
        cfgBookBar()
        bookBarCtl.dwellMode = true
        bookBarCtl.dwellMs = dwellMs
        bookBarCtl.step(u, y, dtMs, still)
        bookBarOpen = bookBarCtl.open
        if (bookBarOpen) {
            toolbarDwellProg = bookBarCtl.dwellProgress
            return true
        }
        fun cfgDwell(c: net.sweepvr.player.sweep.DwellButton,
                     u0: Float, u1: Float, tag: String, fire: () -> Unit): Float {
            c.rect = net.sweepvr.player.sweep.Rect(u0, TB_Y0, u1, TB_Y1)
            c.dwellMs = dwellMs
            if (bookDbg) c.onTrace = { FileLog.i("SweepVR-tools", "$tag $it") }
            else c.onTrace = null
            c.onFire = fire
            return c.step(u, y, still, dtMs)
        }
        var prog = 0f
        prog = maxOf(prog, if (webCanGoBack)
            cfgDwell(dwBack, 0f, TB_BTN, "back") { onWebEvent(WebEvent.GoBack) }
            else { dwBack.reset(); 0f })
        prog = maxOf(prog, if (webCanGoForward)
            cfgDwell(dwFwd, TB_BTN, TB_BTN * 2, "fwd") { onWebEvent(WebEvent.GoForward) }
            else { dwFwd.reset(); 0f })
        prog = maxOf(prog, cfgDwell(dwReload, TB_BTN * 2, TB_BTN * 3, "reload") { onWebEvent(WebEvent.ReloadPage) })
        prog = maxOf(prog, cfgDwell(dwHome, TB_BTN * 3, TB_BTN * 4, "home") { onWebEvent(WebEvent.GoHome) })
        prog = maxOf(prog, cfgDwell(dwMenu, 1f - TB_BTN, 1f, "menu") { onWebEvent(WebEvent.OpenMenu) })
        prog = maxOf(prog, cfgDwell(dwZoomOut, 1f - TB_BTN * 3, 1f - TB_BTN * 2, "zoomout") { onWebEvent(WebEvent.TextZoom(-1)) })
        prog = maxOf(prog, cfgDwell(dwZoomIn, 1f - TB_BTN * 2, 1f - TB_BTN, "zoomin") { onWebEvent(WebEvent.TextZoom(1)) })
        // Icon fill counts too, so the reticle shrinks while opening.
        prog = maxOf(prog, bookBarCtl.dwellProgress)
        // The address field, like every other control in here.
        addrDwell.rect = net.sweepvr.player.sweep.Rect(
            TB_ADDR_U0, TB_Y0, TB_ADDR_U1, TB_Y1)
        addrDwell.onFire = { openKeyboard(webBarUrl) }
        prog = maxOf(prog, addrDwell.step(u, 1f - v, still, dtMs))
        // The address field counts when dwell is how it is opened. Without
        // this it opened on a dwell nobody could see filling - the one control
        // in the toolbar with no feedback.
        if (!sweepEnabled) prog = maxOf(prog, addrDwell.progress)
        toolbarDwellProg = prog
        return prog > 0f
    }

    /** Configure the bar-space bookmarks pane for this frame. Units are gaze
     *  (y-down after the flip); the control is unit-agnostic, so a row is
     *  0.05 tall here the way it is 64px on the panel. The pane hangs below
     *  the strip over the page, capped at mid-page by texH - a 50-bookmark
     *  collection scrolls inside it instead of covering the page. */
    private fun cfgBookBar() {
        val c = bookBarCtl
        c.texH = 0.5f
        c.rowH = 0.05f
        c.arrowH = 0.045f
        c.gap = 0.008f
        c.iconRect = net.sweepvr.player.sweep.Rect(
            1f - TB_BTN * 4, TB_Y0, 1f - TB_BTN * 3, TB_Y1)
        // Pane width hugs the longest label, clamped on-screen: a centred
        // pane wider than the remaining room would hang off the screen edge,
        // where it can be neither seen nor hit.
        val mp = barMeasurePaint
        mp.textSize = TB_PANE_TEXT
        var widest = 0f
        for (it in webBookList) widest = maxOf(widest, mp.measureText(it.first))
        val cx = 1f - TB_BTN * 3.5f
        val maxHalf = minOf(cx, 1f - cx)
        c.paneHalfW = minOf((widest * 0.5f + TB_PANE_PAD_PX) / TBW, maxHalf)
        c.cornerFraction = 0.06f
        c.leeway = 0.012f
        c.rearm = 0.02f
        c.items = webBookList.map { net.sweepvr.player.sweep.DropdownControl.Item(it.first, it.second) }
        if (bookDbg) c.onTrace = { FileLog.i("SweepVR-bookbar", it) } else c.onTrace = null
        c.onCommit = { onWebEvent(WebEvent.Navigate(it.value)) }
    }

    private fun drawToolbar() {
        // The mesh grows downward while the bookmarks pane is open: the
        // pane hangs below the strip, so the surface has to cover it. Key
        // carries the pane bottom, so a static strip never rebuilds.
        val paneV0 = if (bookBarOpen) bookBarCtl.pane?.let { 1f - it.bottom } else null
        val key = screenCurve.toString() + "|" + webScreenSize.toString() + "|" +
            (paneV0?.let { "%.3f".format(it) } ?: "strip")
        if (toolbarMesh == null || toolbarMeshKey != key) {
            toolbarMesh = toolbarMeshBuild(paneV0 ?: TB_V0)
            toolbarMeshKey = key
        }
        val m = toolbarMesh ?: return
        maybeUploadToolbar(paneV0 ?: TB_V0)
        drawMesh2d(m, webToolbarTexId, ovM, 1f)
    }

    private fun toolbarMeshBuild(paneV0: Float): Mesh {
        fun corner(u: Float, v: Float): FloatArray {
            webPointAt(u, v, tbP)
            val len = kotlin.math.sqrt(tbP[0] * tbP[0] + tbP[1] * tbP[1] + tbP[2] * tbP[2])
                .coerceAtLeast(1e-3f)
            val k = (len - 0.05f) / len
            return floatArrayOf(tbP[0] * k, tbP[1] * k, tbP[2] * k)
        }
        // Bitmap row 0 is the top (v = TB_V1): same straight-through mapping
        // as the bar strip. paneV0 is TB_V0 normally, lower while the
        // bookmarks pane hangs below the strip.
        return gridQuadP(
            corner(0f, TB_V1), corner(1f, TB_V1),
            corner(0f, paneV0), corner(1f, paneV0), 1, 1
        )
    }

    private fun maybeUploadToolbar(paneV0: Float = TB_V0) {
        // Rows stay 1000px per v-unit whatever the height, so the strip rows
        // are always the top 64 and every y below maps the same way. Only
        // the height varies: strip-only, or strip plus the open pane.
        val BH = ((TB_V1 - paneV0) * 1000f).toInt().coerceAtLeast(64)
        fun rowY(v: Float) = (TB_V1 - v) / (TB_V1 - paneV0).coerceAtLeast(1e-6f) * BH
        val bmp = Bitmap.createBitmap(TBW, BH, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.TRANSPARENT)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = Color.argb(150, 10, 14, 22)
        c.drawRoundRect(0f, 0f, TBW.toFloat(), BH.toFloat(), 14f, 14f, p)
        fun bx(u: Float) = u * TBW
        // Momentary buttons: rounded square, side dips, vector glyph. Inert
        // when disabled (back/forward with empty history) - dimmed, never
        // stepped, so the gaze passes over to whatever is behind.
        drawToolButton(c, p, bx(0f), TB_BTN, webCanGoBack, hot = tbBack.active || dwBack.progress > 0f) { cx, my ->
            c.drawPath(Path().apply {
                moveTo(cx - 9f, my); lineTo(cx + 7f, my - 11f); lineTo(cx + 7f, my + 11f)
                close()
            }, p)
        }
        drawToolButton(c, p, bx(TB_BTN), TB_BTN, webCanGoForward, hot = tbFwd.active || dwFwd.progress > 0f) { cx, my ->
            c.drawPath(Path().apply {
                moveTo(cx + 9f, my); lineTo(cx - 7f, my - 11f); lineTo(cx - 7f, my + 11f)
                close()
            }, p)
        }
        drawToolButton(c, p, bx(TB_BTN * 2), TB_BTN, enabled = true, hot = tbReload.active || dwReload.progress > 0f) { cx, my ->
            p.style = Paint.Style.STROKE; p.strokeWidth = 4f
            c.drawArc(cx - 11f, my - 11f, cx + 11f, my + 11f, -60f, 270f, false, p)
            p.style = Paint.Style.FILL
            val a = Math.toRadians(150.0).toFloat()
            val ex = cx + 11f * kotlin.math.cos(a); val ey = my + 11f * kotlin.math.sin(a)
            val tx = -kotlin.math.sin(a); val ty = kotlin.math.cos(a)
            c.drawPath(Path().apply {
                moveTo(ex + tx * 8f, ey + ty * 8f)
                lineTo(ex - ty * 5f, ey + tx * 5f); lineTo(ex + ty * 5f, ey - tx * 5f)
                close()
            }, p)
        }
        drawToolButton(c, p, bx(TB_BTN * 3), TB_BTN, enabled = true, hot = tbHome.active || dwHome.progress > 0f) { cx, my ->
            c.drawRect(cx - 10f, my - 3f, cx + 10f, my + 12f, p)
            c.drawPath(Path().apply {
                moveTo(cx - 14f, my - 2f); lineTo(cx + 14f, my - 2f); lineTo(cx, my - 14f)
                close()
            }, p)
        }
        drawToolButton(c, p, bx(1f - TB_BTN), TB_BTN, enabled = true, hot = tbMenu.active || dwMenu.progress > 0f) { cx, my ->
            c.drawCircle(cx - 9f, my, 3f, p)
            c.drawCircle(cx, my, 3f, p)
            c.drawCircle(cx + 9f, my, 3f, p)
        }
        // Bookmarks slot: the bar-space pane hangs here (drawn below).
        // Hot while its pane is open.
        drawToolButton(c, p, bx(1f - TB_BTN * 4), TB_BTN, enabled = true,
            hot = bookBarOpen) { cx, my ->
            drawBookmarksGlyph(c, p, cx, my,
                if (bookBarOpen) Color.WHITE else Color.rgb(203, 213, 225),
                s = (TB_BTN * TBW) / BOOK_BTN)
        }
        // Zoom steppers, moved from the panel icon row: same side-entry
        // silhouette + magnifier, sized to the toolbar square.
        drawToolButton(c, p, bx(1f - TB_BTN * 3), TB_BTN, enabled = true,
            hot = zoomOutCtl.active || dwZoomOut.progress > 0f) { cx, my ->
            magnifierGlyph(c, p, cx, my, 27f, if (zoomOutCtl.active || dwZoomOut.progress > 0f) 255 else 190, false)
        }
        drawToolButton(c, p, bx(1f - TB_BTN * 2), TB_BTN, enabled = true,
            hot = zoomInCtl.active || dwZoomIn.progress > 0f) { cx, my ->
            magnifierGlyph(c, p, cx, my, 27f, if (zoomInCtl.active || dwZoomIn.progress > 0f) 255 else 190, true)
        }
        drawToolbarAddress(c, p)
        if (bookBarOpen) drawBookBarPane(c, p, ::rowY)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, webToolbarTexId)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
        toolbarBitmap?.recycle()
        toolbarBitmap = bmp
    }

    /** The bar-space bookmarks pane, below the strip over the page. Drawn
     *  from the control's state - pane, cursor, scroll window, arrow tell -
     *  never a second layout pass, so the silhouette and the hit area are
     *  the same object. y-down control coords reach bitmap rows through
     *  rowY; x is u*TBW like the strip. */
    private fun drawBookBarPane(c: Canvas, p: Paint, rowY: (Float) -> Float) {
        val pr = bookBarCtl.pane ?: return
        val n = webBookList.size
        val x0 = pr.left * TBW
        val x1 = pr.right * TBW
        // v of a y-down coord; rows then follow the shared mapping.
        fun prow(y: Float) = rowY(1f - y)
        val pTop = prow(pr.top)
        val pBot = prow(pr.bottom)
        p.style = Paint.Style.FILL
        p.color = Color.rgb(13, 20, 28)
        c.drawRoundRect(x0, pTop, x1, pBot, 8f, 8f, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = 1.5f
        p.color = Color.rgb(71, 85, 105)
        c.drawRoundRect(x0, pTop, x1, pBot, 8f, 8f, p)
        p.style = Paint.Style.FILL
        val tp = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG)
        tp.textSize = TB_PANE_TEXT
        tp.textAlign = Paint.Align.LEFT
        val scrolls = bookBarCtl.needsArrows
        val rowHpx = 0.05f * 1000f
        val arrowHpx = 0.045f * 1000f
        val padPx = TB_PANE_PAD_PX
        if (scrolls) {
            val up = pTop
            p.color = if (bookBarCtl.arrow == -1) Color.rgb(8, 145, 178)
                      else Color.rgb(21, 32, 45)
            c.drawRect(x0, up, x1, up + arrowHpx, p)
            p.color = if (bookBarCtl.arrow == -1) Color.WHITE
                      else Color.rgb(148, 163, 184)
            val cx = (x0 + x1) * 0.5f
            c.drawPath(Path().apply {
                moveTo(cx - 12f, up + arrowHpx - 12f)
                lineTo(cx + 12f, up + arrowHpx - 12f)
                lineTo(cx, up + 12f)
                close()
            }, p)
        }
        val top = pTop + if (scrolls) arrowHpx else 0f
        var y = top
        val avail = x1 - x0 - padPx * 2f
        for (k in bookBarCtl.scroll until minOf(bookBarCtl.scroll + bookBarCtl.visibleCount, n)) {
            val sel = k == bookBarCtl.cursor
            p.color = if (sel) Color.rgb(8, 145, 178) else Color.rgb(21, 32, 45)
            c.drawRect(x0, y, x1, y + rowHpx - 2f, p)
            tp.color = if (sel) Color.WHITE else Color.rgb(203, 213, 225)
            val label = android.text.TextUtils.ellipsize(
                webBookList[k].first, tp, avail,
                android.text.TextUtils.TruncateAt.END).toString()
            c.drawText(label, x0 + padPx, y + 33f, tp)
            y += rowHpx
        }
        if (scrolls) {
            val dn = pBot - arrowHpx
            p.color = if (bookBarCtl.arrow == 1) Color.rgb(8, 145, 178)
                      else Color.rgb(21, 32, 45)
            c.drawRect(x0, dn, x1, pBot, p)
            p.color = if (bookBarCtl.arrow == 1) Color.WHITE
                      else Color.rgb(148, 163, 184)
            val cx = (x0 + x1) * 0.5f
            c.drawPath(Path().apply {
                moveTo(cx - 12f, dn + 12f)
                lineTo(cx + 12f, dn + 12f)
                lineTo(cx, dn + arrowHpx - 12f)
                close()
            }, p)
        }
        p.textAlign = Paint.Align.LEFT
    }

    /**
     * A key's outline: rounded rectangle with a V-dip cut into each edge the
     * key is ENTERED through, the same language as the toolbar's buttons.
     *
     * Only the admitting edges are cut. A dip on an edge that refuses entry
     * advertises a gesture that does nothing, and a key with no dip where
     * entry is allowed makes a working gesture invisible - so the sets are
     * read from the control's own entry configuration rather than restated
     * here, and the artwork cannot drift from the behaviour.
     *
     * Drawn as one path so the border follows the notch instead of running
     * straight across the cutout.
     */
    private val KBD_DIP = 5f
    private val KBD_DIP_H = 4f

    /** Border on a key you can act on now: half way between the bright
     *  version and the old dark one, on colour and alpha alike, because a
     *  half-measure on one axis only reads as a different colour rather than
     *  as a lighter version of the same thing. */
    private val KBD_EDGE_LIVE = Color.argb(183, 99, 117, 141)

    /** The original faint border, kept for the rows the drum has parked.
     *
     *  A row that is not live is inert - nothing on it fires - and drawing it
     *  with the same bright outline as the live row made all three look
     *  equally available. The dim border costs nothing and says which row
     *  actually takes input, which is the drum's whole job. */
    private val KBD_EDGE_IDLE = Color.argb(110, 71, 85, 105)

    private val KBD_ARMED_EDGE = Color.argb(255, 240, 246, 252)

    private fun dipKeyPath(
        x0: Float, y0: Float, x1: Float, y1: Float,
        sides: Set<net.sweepvr.player.sweep.Side>,
        r: Float, dip: Float, dh: Float
    ): android.graphics.Path {
        val hasT = net.sweepvr.player.sweep.Side.Top in sides
        val hasB = net.sweepvr.player.sweep.Side.Bottom in sides
        val hasL = net.sweepvr.player.sweep.Side.Left in sides
        val hasR = net.sweepvr.player.sweep.Side.Right in sides
        val mx = (x0 + x1) * 0.5f
        val my = (y0 + y1) * 0.5f
        val rr = minOf(r, minOf(x1 - x0, y1 - y0) * 0.5f)
        val dd = minOf(dip, (if (hasL || hasR) (x1 - x0) else Float.MAX_VALUE) * 0.5f)
        val dv = minOf(dip, (if (hasT || hasB) (y1 - y0) else Float.MAX_VALUE) * 0.5f)
        val dhv = minOf(dh, (y1 - y0) * 0.25f)
        val dhw = minOf(dh, (x1 - x0) * 0.25f)
        return android.graphics.Path().apply {
            moveTo(x0 + rr, y0)
            if (hasT) {
                lineTo(mx - dv, y0); lineTo(mx, y0 + dhv); lineTo(mx + dv, y0)
            }
            lineTo(x1 - rr, y0)
            quadTo(x1, y0, x1, y0 + rr)
            if (hasR) {
                lineTo(x1, my - dhw); lineTo(x1 - dd, my); lineTo(x1, my + dhw)
            }
            lineTo(x1, y1 - rr)
            quadTo(x1, y1, x1 - rr, y1)
            if (hasB) {
                lineTo(mx + dv, y1); lineTo(mx, y1 - dhv); lineTo(mx - dv, y1)
            }
            lineTo(x0 + rr, y1)
            quadTo(x0, y1, x0, y1 - rr)
            if (hasL) {
                lineTo(x0, my + dhw); lineTo(x0 + dd, my); lineTo(x0, my - dhw)
            }
            lineTo(x0, y0 + rr)
            quadTo(x0, y0, x0 + rr, y0)
            close()
        }
    }

    /** One toolbar momentary button: rounded square with side dips (the entry
     *  sides, same language as every other sweep button), then [glyph]
     *  centred. (x0, w) in bitmap px; full strip height. Dimmed when
     *  disabled, which always pairs with never-stepped in toolbarStep.
     *
     *  Fill and border share one dip-conforming path (like sideEntryPath),
     *  so the border follows the notch instead of drawing straight sides
     *  across the dip cutout. With sweep disabled the dips are not drawn
     *  (plain rounded square, same rect): entry notches must not advertise
     *  a gesture that is turned off. */
    private fun toolButtonPath(x0p: Float, y0p: Float, x1p: Float, y1p: Float): Path {
        val r = BAR_BTN_R
        val dip = BAR_BTN_DIP
        val dh = BAR_BTN_DIP_H
        val my = (y0p + y1p) * 0.5f
        return Path().apply {
            moveTo(x0p + r, y0p)
            lineTo(x1p - r, y0p)
            quadTo(x1p, y0p, x1p, y0p + r)
            lineTo(x1p, my - dh)
            lineTo(x1p - dip, my)
            lineTo(x1p, my + dh)
            lineTo(x1p, y1p - r)
            quadTo(x1p, y1p, x1p - r, y1p)
            lineTo(x0p + r, y1p)
            quadTo(x0p, y1p, x0p, y1p - r)
            lineTo(x0p, my + dh)
            lineTo(x0p + dip, my)
            lineTo(x0p, my - dh)
            lineTo(x0p, y0p + r)
            quadTo(x0p, y0p, x0p + r, y0p)
            close()
        }
    }

    private fun drawToolButton(c: Canvas, p: Paint, x0: Float, wU: Float,
                               enabled: Boolean, hot: Boolean = false,
                               glyph: (cx: Float, my: Float) -> Unit) {        val bw = wU * TBW
        val x0p = x0 + 2f
        val x1p = x0 + bw - 2f
        val y0p = 2f
        val y1p = TBH - 2f
        val cx = (x0p + x1p) * 0.5f
        val my = (y0p + y1p) * 0.5f
        p.style = Paint.Style.FILL
        p.color = when {
            hot -> Color.argb(245, 56, 189, 248)
            enabled -> Color.rgb(21, 32, 45)
            else -> Color.rgb(13, 20, 28)
        }
        if (sweepEnabled) c.drawPath(toolButtonPath(x0p, y0p, x1p, y1p), p)
        else c.drawRoundRect(x0p, y0p, x1p, y1p, BAR_BTN_R, BAR_BTN_R, p)
        p.style = Paint.Style.FILL
        p.color = if (enabled) Color.rgb(203, 213, 225) else Color.rgb(71, 85, 105)
        glyph(cx, my)
        p.style = Paint.Style.STROKE
        p.strokeWidth = 1.5f
        p.color = if (hot) Color.WHITE else Color.rgb(71, 85, 105)
        if (sweepEnabled) c.drawPath(toolButtonPath(x0p, y0p, x1p, y1p), p)
        else c.drawRoundRect(x0p, y0p, x1p, y1p, BAR_BTN_R, BAR_BTN_R, p)
        p.style = Paint.Style.FILL
    }

    /** The address field: URL text, focus ring on gaze, blinking cursor.
     *  Focus is hover, not sweep - gaze inside is focused. No input yet;
     *  tapping does nothing, and that is honest: the field shows it. */
    private fun drawToolbarAddress(c: Canvas, p: Paint) {
        val ax0 = TB_ADDR_U0 * TBW + 4f
        val ax1 = TB_ADDR_U1 * TBW - 4f
        val ay0 = 4f
        val ay1 = TBH - 4f
        val focused = toolbarAddrFocused
        p.style = Paint.Style.FILL
        p.color = Color.rgb(13, 20, 28)
        c.drawRoundRect(ax0, ay0, ax1, ay1, 8f, 8f, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = if (focused) 2.5f else 1.5f
        p.color = if (focused) Color.rgb(8, 145, 178) else Color.rgb(71, 85, 105)
        c.drawRoundRect(ax0, ay0, ax1, ay1, 8f, 8f, p)
        p.style = Paint.Style.FILL
        p.textAlign = Paint.Align.LEFT
        // ellipsize needs a TextPaint; a plain Paint does not typecheck.
        // Same size and flags as everything else on this bitmap.
        val tp = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG)
        tp.textSize = 26f
        val avail = ax1 - ax0 - 24f
        val shown = android.text.TextUtils.ellipsize(
            webBarUrl, tp, avail, android.text.TextUtils.TruncateAt.MIDDLE).toString()
        val tx = ax0 + 12f
        val baseline = TBH * 0.5f + 9f
        tp.color = Color.rgb(203, 213, 225)
        c.drawText(shown, tx, baseline, tp)
        if (focused && (now() / 500L) % 2L == 0L) {
            val cx = tx + tp.measureText(shown)
            p.color = Color.WHITE
            p.strokeWidth = 2f
            c.drawLine(cx, ay0 + 12f, cx, ay1 - 12f, p)
            p.strokeWidth = 1f
        }
        p.textAlign = Paint.Align.LEFT
    }

    // ---- scrollbar thumb drag ----
    /** The shared value-drag control. The renderer supplies the track, the
     *  page's scroll position, and the drawing; the gesture lives in the
     *  control, where it is covered by tests. */


    private val barP = FloatArray(3)
    private val wpTmp = FloatArray(3)
    private fun webBarMeshBuild(): Mesh {
        // Pull the strip a few cm toward the viewer so it never z-fights
        // the page underneath.
        fun corner(u: Float, v: Float): FloatArray {
            webPointAt(u, v, barP)
            val len = kotlin.math.sqrt(barP[0] * barP[0] + barP[1] * barP[1] + barP[2] * barP[2])
                .coerceAtLeast(1e-3f)
            val k = (len - 0.05f) / len
            return floatArrayOf(barP[0] * k, barP[1] * k, barP[2] * k)
        }
        // The strip bitmap's row 0 is its top (the up arrow), so the mesh
        // maps it straight through: no flip.
        return gridQuadP(
            corner(barU0, barV1), corner(barU1, barV1),
            corner(barU0, barV0), corner(barU1, barV0), 1, 1
        )
    }

    private fun maybeUploadWebBar() {
        val W = 64; val H = 1024
        val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.TRANSPARENT)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = Color.argb(150, 10, 14, 22)
        c.drawRoundRect(3f, 2f, (W - 3).toFloat(), (H - 2).toFloat(), 14f, 14f, p)
        // arrows: one square button at each end of the strip
        val bh = BAR_BTN / (barV1 - barV0) * H
        drawRepeatButton(c, p, 2f, bh, up = true, hot = barUp.active || barUp.dwellProgress > 0f)
        drawRepeatButton(c, p, H - 2f - bh, bh, up = false, hot = barDown.active || barDown.dwellProgress > 0f)
        // thumb: size = visible fraction, position = scroll fraction
        // Position and size come from the control's own thumb rect - the same
        // rect the entry test hits - so the drawn silhouette and the hit area
        // cannot drift apart. It arrives in bar-v, which counts upward.
        val span = barThumbPx()
        if (barSized && span[1] > span[0]) {
            val ty = span[0]
            val th = maxOf(18f, span[1] - span[0])
            // Same tell as the bookmarks button: grey until the reticle has
            // arrived on a valid entry edge, blue while it is held.
            p.color = when {
                barDragging -> Color.argb(245, 56, 189, 248)
                barEnterOpen -> Color.argb(235, 125, 211, 252)
                else -> Color.argb(150, 148, 163, 184)
            }
            // Entry notches only in sweep mode: dwell grabs anywhere, so with
            // sweep off the thumb is a plain bar (same rect, same tell).
            drawThumbDips(c, p, 7f, ty, (W - 7).toFloat(), ty + th,
                if (sweepEnabled) minOf(8f, th * 0.15f) else 0f)
        }
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, webBarTexId)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
        webBarBitmap?.recycle()
        webBarBitmap = bmp
    }

    /** A square repeat button for a strip end: rounded square, triangular dips
     *  cut into the left and right edges (the entry sides, same language as
     *  the dropdown button and the thumb notches), and a small direction
     *  triangle in the middle. With sweep disabled the dips are skipped
     *  (plain square, same rect).
     *
     *  (x0..x1) matches the strip interior so the button sits on the strip;
     *  (yTop..yTop+h) is the BAR_BTN square converted to bitmap rows by the
     *  caller. Grey idle, blue held - the same tell as the thumb. */
    private fun drawRepeatButton(c: Canvas, p: Paint, yTop: Float, h: Float,
                                 up: Boolean, hot: Boolean) {
        // x matches the strip interior (3..W-3, W=64) so the button sits on
        // the strip exactly where its hit square is.
        val x0 = 3f; val x1 = 64f - 3f
        val y1 = yTop + h
        val cx = (x0 + x1) * 0.5f
        val my = (yTop + y1) * 0.5f
        val r = BAR_BTN_R
        val dip = BAR_BTN_DIP
        val dh = BAR_BTN_DIP_H
        p.style = Paint.Style.FILL
        p.color = if (hot) Color.argb(245, 56, 189, 248)
                  else Color.argb(150, 148, 163, 184)
        c.drawRoundRect(x0, yTop, x1, y1, r, r, p)
        // Side dips, cut inward with CLEAR exactly like the thumb notches.
        // Skipped with sweep off: no entry sides to advertise.
        if (sweepEnabled) {
            val mode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.CLEAR)
            val oldMode = p.xfermode
            p.xfermode = mode
            Path().apply {
                moveTo(x0, my - dh); lineTo(x0, my + dh); lineTo(x0 + dip, my)
                close()
            }.let { c.drawPath(it, p) }
            Path().apply {
                moveTo(x1, my - dh); lineTo(x1, my + dh); lineTo(x1 - dip, my)
                close()
            }.let { c.drawPath(it, p) }
            p.xfermode = oldMode
        }
        // Direction triangle, same proportions as the arrows this replaces,
        // scaled to the square: ~0.27 of the half-width, ~0.55 of the height.
        val tw = (x1 - x0) * 0.27f
        val th = h * 0.55f
        p.color = if (hot) Color.WHITE else Color.rgb(13, 20, 28)
        c.drawPath(Path().apply {
            if (up) {
                moveTo(cx, my - th * 0.5f)
                lineTo(cx - tw, my + th * 0.5f); lineTo(cx + tw, my + th * 0.5f)
            } else {
                moveTo(cx, my + th * 0.5f)
                lineTo(cx - tw, my - th * 0.5f); lineTo(cx + tw, my - th * 0.5f)
            }
            close()
        }, p)
    }

    /** The scrollbar thumb: a rounded bar with a triangular notch cut out
     *  of its top and bottom edges, pointing inward. Those are the edges the
     *  reticle arrives from for a drag; the sides are where it lets go, so
     *  they are left plain.
     *
     *  The notches are cut with CLEAR rather than traced into the outline.
     *  A notched top and bottom cannot be described by one closed path: the
     *  boundary has to visit the top edge on one pass and the bottom edge on
     *  another, so the path self-intersects and closing it drags a line back
     *  across the shape. That is what put the crisscrossing lines on screen. */
    private fun drawThumbDips(c: Canvas, p: Paint, x0: Float, y0: Float,
                              x1: Float, y1: Float, dip: Float) {
        val r = 9f
        val cx = (x0 + x1) * 0.5f
        c.drawRoundRect(x0, y0, x1, y1, r, r, p)
        if (dip < 3f) return
        val notchH = dip * 2.1f
        val mode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.CLEAR)
        val oldMode = p.xfermode
        p.xfermode = mode
        // Top notch: a triangle biting down into the thumb.
        Path().apply {
            moveTo(x0 + r, y0)
            lineTo(x1 - r, y0)
            lineTo(cx, y0 + notchH)
            close()
        }.let { c.drawPath(it, p) }
        // Bottom notch, biting up.
        Path().apply {
            moveTo(x0 + r, y1)
            lineTo(x1 - r, y1)
            lineTo(cx, y1 - notchH)
            close()
        }.let { c.drawPath(it, p) }
        p.xfermode = oldMode
    }

    /** World size of the flat screen: aspect-corrected width/height for the
     *  plane (§5, base 8.5·screenSize). The shader samples ONE half of an
     *  SBS/TB frame, so the aspect is the per-eye content aspect, not the
     *  full frame. Shared by buildVideoModel() and the curved-cap mesh.
     *  WEB borrows this path with the page's own aspect (and no stereo
     *  split) so the browser sits on exactly the same screen as a video -
     *  same geometry, but sized by its own webScreenSize, so the two
     *  pictures keep independent sizes. Mode decides which: this is the
     *  single choke point every consumer goes through (drawWeb, the gaze
     *  hit-test, both meshes), so they can never disagree. */
    private fun screenDims(): Pair<Float, Float> {
        val full = if (mode == Mode.WEB) webPageAspect() else videoAspect
        val a = when (if (mode == Mode.WEB) Stereo.MONO else stereo) {
            Stereo.SBS -> full / 2f; Stereo.TB -> full * 2f; else -> full
        }
            .coerceIn(0.25f, 4f)
        val size = if (mode == Mode.WEB) webScreenSize else screenSize
        val base = 8.5f * size.coerceIn(0.5f, 10f)
        val w = if (a >= 1.7777778f) base else base / 1.7777778f * a
        return w to (w / a)
    }

    /** WEB always rides the FLAT screen (same size/curve/zoom as 2D video),
     *  whatever dome projection the video is set to. */
    private fun effProj(): Projection = if (mode == Mode.WEB) Projection.FLAT else projection

    private fun webPageAspect(): Float =
        (webPageW.toFloat() / webPageH.toFloat().coerceAtLeast(1f)).coerceIn(0.25f, 4f)

    /** Model matrix for the video: FLAT = aspect-correct plane of world
     *  width 8.5·screenSize (unit quad scaled, §5) — or, when screenCurve
     *  > 0, the bent cap whose world size is baked into the mesh, so only
     *  the eye-height lift remains; every other projection = a plain
     *  screen-size scale of the unit sphere. modelM carries screen size
     *  and nothing else; zoom lives in the zoomPan matrix (§7.2) as a
     *  model-space Z translate - texture-space only on the fisheye path
     *  (see buildZoomPan). */
    private fun buildVideoModel() {
        Matrix.setIdentityM(modelM, 0)
        if (effProj() == Projection.FLAT) {
            val (w, h) = screenDims()
            if (screenCurve > 0f) {
                // Cap vertices already carry world w/h and their own z.
                Matrix.translateM(modelM, 0, 0f, 0.25f, 0f)
            } else {
                Matrix.translateM(modelM, 0, 0f, 0.25f, -FLAT_DIST)
                Matrix.scaleM(modelM, 0, w / 2f, h / 2f, 1f)
            }
        } else {
            // screen_size on the dome/fisheye: anisotropic model scale of
            // the unit sphere. Identity at 1.0, monotonic either way,
            // bigger = larger picture, and the viewer stays inside the
            // resulting ellipsoid at every setting in 0.5-10, so nothing
            // is ever clipped or black (a Z ride, by contrast, crosses the
            // near plane past ~1.15 and leaves the sphere behind the viewer
            // at >= 2). vDir = aPos.xyz is model-space and untransformed,
            // so fisheye's circle scales with it and its content follows.
            // FLAT already takes its size through screenDims() - not here.
            // WEB is always FLAT (effProj), so this is the video picture
            // and screenSize is its own field; the mode check is only to
            // keep the same rule as screenDims() if that ever changes.
            val size = if (mode == Mode.WEB) webScreenSize else screenSize
            val s = size.coerceIn(0.5f, 10f)
            Matrix.scaleM(modelM, 0, s, s, 1f)
        }
    }

    /** Zoom state for the drawing eye. Dome/Flat: zoom is a model-space Z
     *  translate of (zoom - 1), identity at 1.0, clamped [0, 2], delivered
     *  through zoomM in the existing multiply chain - texcoords never
     *  change, so the shader's vertical crop stays 0 and no sample can
     *  leave the frame (a negative crop used to pull v near 0 and 1
     *  outside [0,1] and punch a hole/curl at both dome poles). Fisheye:
     *  no model slide (its fragment derives the sample from the
     *  model-space view direction, so a slide would not zoom it); its zoom
     *  is texture-space and is returned here as the crop, consumed as
     *  uZoomOutFish = 1/(1-crop). */
    private val zoomM = FloatArray(16)
    private fun buildZoomPan(): Float {
        Matrix.setIdentityM(zoomM, 0)
        val z = zoom.coerceIn(0f, 2f)
        if (effProj() == Projection.FISHEYE) return (z - 1f) * 0.2f
        if (z != 1f) Matrix.translateM(zoomM, 0, 0f, 0f, z - 1f)
        return 0f
    }

    /** Fisheye video path (§8): same mesh/basis/projection/zoom as the
     *  equirect path — only the texture lookup differs (equidistant circle
     *  inversion from the per-fragment view direction). */
    private fun drawVideoFisheye(eye: Int, pos: FloatBuffer, count: Int, crop: Float) {
        GLES20.glUseProgram(progFish)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, videoTextureId)
        GLES20.glUniform1i(uTexFish, 0)
        val st = when (stereo) { Stereo.MONO -> 0; Stereo.SBS -> 1; Stereo.TB -> 2 }
        GLES20.glUniform1i(uStereoFish, st)
        GLES20.glUniform1i(uEyeFish, eye)
        GLES20.glUniformMatrix4fv(uTexMatFish, 1, false, texMat, 0)
        // FRAG_OES_FISH does t = zc + (t - zc)/uZoomOut about the half's
        // centre, so 1/(1-crop) magnifies about that centre. Fisheye's zoom
        // is texture-space only (no model slide - see buildZoomPan): with
        // the [0, 2] zoom range |crop| <= 0.2, so the divisor stays in
        // [0.8, 1.2] and never flips. Zoom-out leaves the half and the
        // shader's oob check writes black there - expected for fisheye.
        GLES20.glUniform1f(uZoomOutFish, 1f / (1f - crop))
        GLES20.glUniformMatrix4fv(uMvpFish, 1, false, mvpM, 0)
        // Active circle: per-layout defaults (§8: half-image center, radius
        // of one quarter frame width) plus calibration offsets.
        val ar = videoAspect.coerceIn(0.5f, 4f)
        val rs = fisheyeRadiusScale.coerceIn(0.25f, 2f)
        val cx: Float; val cy: Float; val ru: Float; val rv: Float
        when (st) {
            1 -> { // side-by-side: left circle in left half, right in right
                cx = eye * 0.5f + 0.25f + fisheyeCxOff
                cy = 0.5f + fisheyeCyOff
                ru = 0.25f * rs; rv = 0.25f * ar * rs
            }
            2 -> { // stacked halves
                cx = 0.5f + fisheyeCxOff
                cy = eye * 0.5f + 0.25f + fisheyeCyOff
                ru = 0.25f * rs; rv = 0.5f * ar * rs
            }
            else -> { // single circle over the full frame
                cx = 0.5f + fisheyeCxOff
                cy = 0.5f + fisheyeCyOff
                ru = 0.5f * rs; rv = 0.5f * ar * rs
            }
        }
        GLES20.glUniform2f(uFishC, cx, cy)
        GLES20.glUniform2f(uFishR, ru, rv)
        GLES20.glUniform1f(uFishMirror, if ((eye == 0 && fisheyeMirrorL) || (eye == 1 && fisheyeMirrorR)) 1f else 0f)
        GLES20.glEnableVertexAttribArray(aPosFish)
        GLES20.glVertexAttribPointer(aPosFish, 3, GLES20.GL_FLOAT, false, 0, pos)
        // Shaped delta for the fragment path above (same 180° strip order as
        // pos). Unshaped: zeros, which leave the coords alone.
        val g = videoGeom
        val shape = g?.fishDelta?.getOrNull(eye)
        if (aShapeFish >= 0 && shape != null && shape.capacity() == count * 2) {
            GLES20.glEnableVertexAttribArray(aShapeFish)
            GLES20.glVertexAttribPointer(aShapeFish, 2, GLES20.GL_FLOAT, false, 0, shape)
        } else if (aShapeFish >= 0) {
            GLES20.glDisableVertexAttribArray(aShapeFish)
        }
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, count)
        GLES20.glDisableVertexAttribArray(aPosFish)
        GLES20.glDisableVertexAttribArray(aTexFish)
        if (aShapeFish >= 0) GLES20.glDisableVertexAttribArray(aShapeFish)
    }

    /** Bilinear grid over the quad (p00 top-left, p10 top-right, p01
     *  bottom-left, p11 bottom-right). Per-vertex lens warp needs real
     *  vertices across the surface — a 2-triangle quad warps wrong.
     *  flipV = true for decoder-fed video quads: V is emitted in
     *  displayed-image space (v up) with the decoder flip left to uTexMat;
     *  canvas panels (plain sampler2D, no transform) use flipV = false. */
    private fun gridQuadP(
        p00: FloatArray, p10: FloatArray, p01: FloatArray, p11: FloatArray,
        nx: Int, ny: Int, flipV: Boolean = false, vMax: Float = 1f
    ): Mesh {
        val verts = FloatArray((nx + 1) * (ny + 1) * 3)
        val texs = FloatArray((nx + 1) * (ny + 1) * 2)
        var vi = 0; var ti = 0
        for (iy in 0..ny) {
            // t places the VERTEX between the corner rows, v places the
            // TEXEL. They differ only when vMax < 1 (the compact web panel):
            // the corners already carry the raised bottom edge, so vertices
            // must travel the full top->bottom span while texcoords cover
            // 0..vMax. One parameter for both squeezed the panel into a
            // (hc/TEX)^2 sliver.
            val t = iy.toFloat() / ny
            val v = t * vMax
            for (ix in 0..nx) {
                val u = ix.toFloat() / nx
                for (k in 0..2) {
                    val top = p00[k] + (p10[k] - p00[k]) * u
                    val bot = p01[k] + (p11[k] - p01[k]) * u
                    verts[vi++] = top + (bot - top) * t
                }
                texs[ti++] = u; texs[ti++] = if (flipV) 1f - v else v
            }
        }
        val idx = mutableListOf<Short>()
        for (iy in 0 until ny) for (ix in 0 until nx) {
            val a = (iy * (nx + 1) + ix).toShort()
            val b = (a + 1).toShort(); val c = ((iy + 1) * (nx + 1) + ix).toShort(); val d = (c + 1).toShort()
            idx += listOf(a, c, b, b, c, d)
        }
        return Mesh(fb(verts), fb(texs), sb(idx.toShortArray()), idx.size)
    }

    private fun drawMesh2d(m: Mesh, texId: Int, mat: FloatArray, alpha: Float = 1f) {
        GLES20.glUseProgram(prog2d)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId)
        GLES20.glUniform1i(uTex2d, 0)
        GLES20.glUniform1f(uAlpha2d, alpha.coerceIn(0f, 1f))
        GLES20.glUniformMatrix4fv(uMvp2d, 1, false, mat, 0)
        GLES20.glEnableVertexAttribArray(aPos2d)
        GLES20.glVertexAttribPointer(aPos2d, 3, GLES20.GL_FLOAT, false, 0, m.verts)
        GLES20.glEnableVertexAttribArray(aTex2d)
        GLES20.glVertexAttribPointer(aTex2d, 2, GLES20.GL_FLOAT, false, 0, m.tex)
        GLES20.glDrawElements(GLES20.GL_TRIANGLES, m.indexCount, GLES20.GL_UNSIGNED_SHORT, m.indices)
        GLES20.glDisableVertexAttribArray(aPos2d)
        GLES20.glDisableVertexAttribArray(aTex2d)
    }

    private var browserGrid: Mesh? = null
    private var browserGridD = -1f
    private var browserGridEl = -999f
    private var browserGridHc = -1f
    /** Web-panel-only compact height, texture px. Full TEX everywhere else,
     *  so the file browser and menu panels keep their exact current geometry:
     *  same corners, same UVs, same gaze mapping. */
    private var webCompactH: Float = TEX.toFloat()
    /** Visible panel height: the web panel fits its content (open, fading,
     *  or closed - the stored height stands until recomputed); every other
     *  panel uses the full texture. Content is never scaled - text keeps its
     *  size, the bottom whitespace just goes away. */
    private fun panelHc(): Float =
        if (mode == Mode.WEB && webSideBtns)
            webCompactH.coerceIn(200f, TEX.toFloat())
        else TEX.toFloat()
    private fun drawBrowser(alpha: Float = 1f) {
        maybeUploadBrowser()
        val d = panelDistM; val hw = panelHalfW(); val hh = panelHalfH()
        // rotation-only UI matrix: identical in both eyes, always fuses.
        // Grid cached: rebuilding it per frame churned direct buffers and
        // strobed the whole scene through GC. Panel floats at browserElevDeg
        // (0 = centered; elevated = below the play menu, facing viewer).
        val el = Math.toRadians(browserElevDeg.toDouble()).toFloat()
        // hc: visible content height (web panel only - TEX elsewhere, which
        // reduces to the old full quad exactly). The top edge stays where it
        // always was and the bottom rises to fit; the mesh maps texture rows
        // 0..hc at unchanged scale.
        val hc = panelHc()
        val syBot = 1f - 2f * hc / TEX
        if (browserGrid == null || browserGridD != d || browserGridEl != el ||
            browserGridHc != hc) {
            val cx = 0f; val cy = (kotlin.math.sin(el) * d); val cz = (-kotlin.math.cos(el) * d)
            var nx = -cx; var ny = -cy; var nz = -cz
            val nl = kotlin.math.sqrt(nx * nx + ny * ny + nz * nz).coerceAtLeast(1e-6f)
            nx /= nl; ny /= nl; nz /= nl
            // up = n × (1,0,0) = (0, nz, -ny)
            val ux = 0f; val uy = nz; val uz = -ny
            fun corner(sx: Float, sy: Float) = floatArrayOf(
                cx + sx * hw + ux * sy * hh,
                cy + uy * sy * hh,
                cz + uz * sy * hh
            )
            browserGrid = gridQuadP(
                corner(-1f, 1f), corner(1f, 1f), corner(-1f, syBot), corner(1f, syBot),
                12, 8, vMax = hc / TEX
            )
            browserGridD = d; browserGridEl = el; browserGridHc = hc
            FileLog.i("SweepVR-browser", "grid rebuild d=$d el=$el hc=$hc")
        }
        drawMesh2d(browserGrid!!, browserTexId, ovM, alpha)
    }

    private fun makeReticle(color: Int): Int {
        val tex = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)
        // 256, not 96. The reticle is minified hard the moment it is drawn:
        // dwell shrinks it to 15% of its size (1 - 0.85·prog), and on the web
        // page it is a quad at the page's depth rather than the panel's. At 96
        // a single dwell step threw away four texels per axis and the ring went
        // to visible steps — which read as "pixelated" rather than as "small".
        // The resolution buys back the headroom that shrink spends.
        val S = 256
        val cxy = S * 0.5f
        val bmp = Bitmap.createBitmap(S, S, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        // transparent background (needs BLEND enabled)
        c.drawColor(Color.TRANSPARENT)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)

        // Geometry as fractions of the half-width, so every ring below keeps
        // its proportion to the reticle at any texture size.
        val R = cxy * 0.62f          // ring radius
        val ringW = cxy * 0.115f    // the bright ring's own thickness
        val keyW = ringW * 2.35f    // the dark outline around it

        // A dark ring UNDER the coloured one, slightly wider. This is what
        // makes it read as one clean object instead of a shape that vanishes
        // over white and buzzes over video: the keyline is the constant edge,
        // and the colour is the highlight inside it. Drawing it as a wider
        // stroke underneath rather than as a second path means the coloured
        // ring's edge is still a single antialiased curve.
        p.style = Paint.Style.STROKE
        p.strokeWidth = keyW
        p.color = Color.argb(215, 6, 9, 14)
        c.drawCircle(cxy, cxy, R, p)

        p.strokeWidth = ringW
        p.color = color
        c.drawCircle(cxy, cxy, R, p)

        // The centre dot, same construction: dark disc under a bright one, so
        // it survives whatever it lands on. Slightly under half the ring's
        // thickness across, which is the ratio that reads as a dot rather than
        // as a second ring.
        p.style = Paint.Style.FILL
        val dotR = cxy * 0.105f
        p.color = Color.argb(225, 6, 9, 14)
        c.drawCircle(cxy, cxy, dotR * 1.7f, p)
        p.color = color
        c.drawCircle(cxy, cxy, dotR, p)

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex[0])
        // Mipmapped, because minification is the normal case for this quad and
        // GL_LINEAR alone point-samples it: at dwell's 15% size one screen
        // pixel was covering several ring texels and the sample landed on
        // whichever happened to be there, which is the crawl. Mip levels make
        // it a proper average, so shrinking stays smooth all the way down.
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR_MIPMAP_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
        // After the upload, at level 0: a mipmapped texture with no levels is
        // incomplete and samples as solid black.
        GLES20.glGenerateMipmap(GLES20.GL_TEXTURE_2D)
        bmp.recycle()
        return tex[0]
    }

    /** Shaping preview cell: distorted 9x9 grid + moved dots (amber→red by
     *  magnitude) + displacement vectors + minimum convex polygon of moved
     *  points. Box is 56px at row left; grid coords [0,1], y down. */
    private fun drawShapePreview(c: Canvas, p: Paint, r: BrowserRow, y: Int) {
        val mags = r.previewMags ?: return
        val pos = r.previewPos ?: return
        val n = r.previewN.coerceAtLeast(2)
        if (mags.size < n * n || pos.size < n * n * 2) return
        val bx0 = 28f; val by0 = y.toFloat() + 4f; val bs = 56f
        fun px(j: Int) = bx0 + pos[j * 2].toFloat().coerceIn(-0.2f, 1.2f) * bs
        fun py(j: Int) = by0 + pos[j * 2 + 1].toFloat().coerceIn(-0.2f, 1.2f) * bs
        fun nx(j: Int) = bx0 + (j % n).toFloat() / (n - 1) * bs
        fun ny(j: Int) = by0 + (j / n).toFloat() / (n - 1) * bs
        // convex hull fill + stroke
        val hull = r.previewHull
        if (hull != null && hull.size >= 3) {
            val path = Path()
            path.moveTo(px(hull[0]), py(hull[0]))
            for (k in 1 until hull.size) path.lineTo(px(hull[k]), py(hull[k]))
            path.close()
            p.style = Paint.Style.FILL; p.color = Color.argb(40, 8, 145, 178)
            c.drawPath(path, p)
            p.style = Paint.Style.STROKE; p.strokeWidth = 2f; p.color = Color.rgb(8, 145, 178)
            c.drawPath(path, p)
            p.style = Paint.Style.FILL; p.strokeWidth = 1f
        }
        // distorted grid lines
        p.style = Paint.Style.STROKE; p.strokeWidth = 1f; p.color = Color.rgb(150, 150, 150)
        for (i in 0 until n) {
            val rowPath = Path()
            rowPath.moveTo(px(i * n), py(i * n))
            for (j in 1 until n) rowPath.lineTo(px(i * n + j), py(i * n + j))
            c.drawPath(rowPath, p)
            val colPath = Path()
            colPath.moveTo(px(i), py(i))
            for (k in 1 until n) colPath.lineTo(px(k * n + i), py(k * n + i))
            c.drawPath(colPath, p)
        }
        p.style = Paint.Style.FILL
        // displacement vectors (nominal -> offset), faint (style still STROKE)
        p.color = Color.argb(120, 125, 211, 252); p.strokeWidth = 1f
        for (j in mags.indices) {
            if (mags[j] <= 1e-6f) continue
            c.drawLine(nx(j), ny(j), px(j), py(j), p)
        }
        p.style = Paint.Style.FILL
        // dots like shapemesh.py: grey unmoved; moved toward the centre
        // (dot of displacement vs to-centre vector > eps) green, else red.
        // Sign-based, so grid-space (y down) works unchanged.
        for (j in mags.indices) {
            val m = mags[j].coerceIn(0f, 1f)
            val x = px(j); val yy = py(j)
            if (m <= 1e-6f) {
                p.color = Color.rgb(170, 170, 170)
                c.drawCircle(x, yy, 2f, p)
            } else {
                val ix = (j % n).toFloat() / (n - 1)
                val iy = (j / n).toFloat() / (n - 1)
                val dx = pos[j * 2].toFloat() - ix
                val dy = pos[j * 2 + 1].toFloat() - iy
                val cx = 0.5f - ix; val cy = 0.5f - iy
                val t = m.coerceAtLeast(0.15f)
                val inward = (dx * dx + dy * dy) > 1e-18f &&
                    (cx * cx + cy * cy) > 1e-18f && (dx * cx + dy * cy) > 1e-9f
                if (inward)
                    p.color = Color.rgb((120 - 90 * t).toInt(), (200 - 20 * t).toInt(), (120 - 90 * t).toInt())
                else
                    p.color = Color.rgb(255, (200 - 170 * t).toInt(), (60 - 40 * t).toInt())
                c.drawCircle(x, yy, 2f + 4f * t, p)
            }
        }
        p.strokeWidth = 1f
    }

    private fun maybeUploadBrowser() {
        ensureVisible()
        val rows = browserRows
        val pin = pinTopRows.coerceIn(0, 2)
        val stm = pin > 0 || rows.size > VISIBLE_ROWS // strips mode
        val win = if (stm) winRows(pin) else VISIBLE_ROWS
        val base = scrollPos.toInt()
        val winStart = (pin + base).coerceAtMost(rows.size)
        val winEnd = (winStart + win + (if (stm) 1 else 0)).coerceAtMost(rows.size)
        // Stored only from the open web panel - but RETAINED across closes,
        // never reset to full: the tilt trigger reads this between uploads,
        // and resetting it dropped the trigger edge from ~22 to ~13 degrees,
        // where the address-bar zone sits - open, snap shut, reopen flutter
        // plus a full-size fade on every close. The file browser and menu
        // panels never read this field (panelHc gates them to TEX), and
        // exitWeb clears webSideBtns, so nothing stale can leak out of web.
        if (mode == Mode.WEB && webPanelOpen && webSideBtns) {
            val rowsBottom = if (stm) downStripY0(pin) + STRIP_H
                             else (ROWS_Y0 + (winEnd - winStart) * ROW_H).toFloat()
            val iconBottom = if (webBookIconRow >= 0) BOOK_ICON_Y0 + BOOK_BTN else 0f
            val paneBottom = if (bookOpen) bookControl.pane?.bottom ?: 0f else 0f
            webCompactH =
                maxOf(rowsBottom, iconBottom, paneBottom, 200f).coerceAtMost(TEX.toFloat())
        }
        // No else: the last compact value stands until the next open-panel
        // upload recomputes it (see above).
        var h = browserTitle.hashCode() * 31 + (if (stm) (scrollPos * ROW_H).toInt() else base) + pin * 7919
        for (i in 0 until pin.coerceAtMost(rows.size)) h = h * 31 + rowHash(rows, i)
        for (i in winStart until winEnd) h = h * 31 + rowHash(rows, i)
        h = h * 31 + highlight + (if (webSideBtns) 20011 else 0) +
            (sideBtnDir * 7919 + (sideBtnProg * 255).toInt()) +
            (if (sliderHoverU >= 0f) (sliderHoverU * 128).toInt() else 0)
        // Flyout state changes the picture, so it has to be in the hash or
        // the pane would only appear on whatever else happened to change.
        if (webBookIconRow >= 0) h = h * 31 + (if (bookOpen) 1 else 0) * 131071 +
            bookCursor * 5171 + bookScroll * 307 + (bookArrow + 1) * 61 +
            (if (bookActive) 7 else 0)
        if (stm) h += scrollEngage * 131071 + scrollTrigDir * 1031 + (scrollTrigF * 32).toInt()
        if (h == lastPanelHash && browserBitmap != null) return
        lastPanelHash = h
        val bmp = Bitmap.createBitmap(TEX, TEX, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.rgb(13, 20, 28))
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = Color.WHITE; p.textSize = 44f
        // Title shares its bar with buttons: PgUp/PgDn on the web panel,
        // the X close button everywhere else. Shrink slightly to fit rather
        // than cutting the path short, and middle-truncate only if even the
        // floor overflows.
        val titleAvail = (if (webSideBtns) SIDE_BTN_X0 else 912f) - 40f
        var titleTs = 44f
        var title = browserTitle.take(64)
        p.textSize = titleTs
        while (titleTs > 30f && p.measureText(title) > titleAvail) {
            titleTs -= 2f; p.textSize = titleTs
        }
        if (p.measureText(title) > titleAvail && title.length > 12) {
            var keep = title.length - 1
            fun mid(k: Int) = title.take(k / 2) + "…" + title.takeLast(k - k / 2)
            while (keep > 12 && p.measureText(mid(keep)) > titleAvail) keep--
            title = mid(keep)
        }
        c.drawText(title, 40f, 72f, p)
        // X close button, top right of the title bar (hit zone u>0.90,
        // y<TITLE_Y1) - everywhere except the open web panel, which must
        // not offer a close that springs straight back open.
        if (!webPanelOpen) {
            if (inXZone) {
                p.color = Color.rgb(30, 58, 95)
                c.drawRect(920f, 16f, 1004f, 96f, p)
            }
            p.color = Color.WHITE; p.textSize = 44f; p.textAlign = Paint.Align.CENTER
            c.drawText("✕", 962f, 72f, p)
            p.textAlign = Paint.Align.LEFT
        }
        if (webSideBtns) {
            p.textSize = 26f; p.textAlign = Paint.Align.CENTER
            val upProg = if (sideBtnDir == -1 && !sideBtnFired) sideBtnProg else 0f
            val dnProg = if (sideBtnDir == 1 && !sideBtnFired) sideBtnProg else 0f
            drawSideBtn(c, p, SIDE_BTN_UP_Y0, "PgUp", sideBtnDir == -1, upProg)
            drawSideBtn(c, p, SIDE_BTN_DN_Y0, "PgDn", sideBtnDir == 1, dnProg)
            p.textAlign = Paint.Align.LEFT; p.textSize = 44f
        }
        if (stm) {
            // File pages: pinned nav rows, scroll strips, fractional window.
            val upY0 = upStripY0(pin); val rY0 = rowsY0(pin); val dnY0 = downStripY0(pin)
            val scrollable = rows.size > pin + win
            var py = PIN_Y0.toFloat()
            for (i in 0 until pin.coerceAtMost(rows.size)) {
                drawBrowserRow(c, p, rows, i, py.toInt())
                py += ROW_H
            }
            if (scrollable) drawScrollStrip(c, p, upY0, -1)
            val fracPx = scrollPos * ROW_H - base * ROW_H
            c.save()
            c.clipRect(0f, rY0, TEX.toFloat(), rY0 + win * ROW_H)
            c.translate(0f, -fracPx)
            var y = rY0
            for (i in winStart until winEnd) {
                drawBrowserRow(c, p, rows, i, y.toInt())
                y += ROW_H
            }
            c.restore()
            if (scrollable) drawScrollStrip(c, p, dnY0, 1)
        } else {
            // Settings pages - and the short web panel: plain fixed window,
            // no strips. Every row is live; the bookmarks icon floats
            // top-right (not on a row) and paints last, over everything.
            var y = ROWS_Y0
            for (i in winStart until winEnd) {
                drawBrowserRow(c, p, rows, i, y)
                y += ROW_H
            }
        }
        // Icon + pane paint last: on the open web panel the icon sits over
        // live rows (not on its own dead row), and the pane hangs over
        // everything beneath it. Elsewhere the legacy row-homed icon draws
        // exactly where it always did.
        if (webBookIconRow >= 0 && webSideBtns) {
            drawBookFlyout(c, p, rows)
        }
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, browserTexId)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
        browserBitmap?.recycle()
        browserBitmap = bmp
    }

    /** Side-entry control: a small button, just wide enough for the glyph,
     *  with triangular dips cut into the left and right edges pointing
     *  inward. The dips ARE the instruction - they mark the edges you are
     *  meant to arrive from - so it reads without a label, and it is
     *  reusable for any future hover control. With sweep disabled the dips
     *  are not drawn (plain rounded square, same rect). */
    private fun drawSideEntryButton(c: Canvas, p: Paint, x0: Float, y0: Float, hot: Boolean) {
        val b = BOOK_BTN
        if (sweepEnabled) {
            c.drawPath(sideEntryPath(x0, y0, b), p.apply {
                color = if (hot) Color.rgb(30, 58, 95) else Color.rgb(21, 32, 45)
            })
        } else {
            p.color = if (hot) Color.rgb(30, 58, 95) else Color.rgb(21, 32, 45)
            c.drawRoundRect(x0, y0, x0 + b, y0 + b, BOOK_BTN_R, BOOK_BTN_R, p)
        }
        p.style = Paint.Style.STROKE
        p.strokeWidth = 2f
        p.color = if (hot) Color.rgb(8, 145, 178) else Color.rgb(71, 85, 105)
        if (sweepEnabled) c.drawPath(sideEntryPath(x0, y0, b), p)
        else c.drawRoundRect(x0, y0, x0 + b, y0 + b, BOOK_BTN_R, BOOK_BTN_R, p)
        p.style = Paint.Style.FILL
        drawBookmarksGlyph(c, p, x0 + b * 0.5f, y0 + b * 0.5f, if (hot) Color.WHITE else Color.rgb(203, 213, 225))
    }

    /** The side-entry silhouette shared by the bookmarks icon and the zoom
     *  steppers: square with rounded corners, triangular notch pushed into
     *  each side edge pointing inward. The notches mark the two edges you
     *  are meant to arrive from; they are omitted from the top and bottom,
     *  which are the edges you are NOT meant to arrive from. */
    private fun sideEntryPath(x0: Float, y0: Float, b: Float): Path {
        val x1 = x0 + b
        val y1 = y0 + b
        val r = BOOK_BTN_R
        val dip = BOOK_BTN_DIP
        val dh = BOOK_BTN_DIP_H
        val my = y0 + b * 0.5f
        val path = Path()
        val rf = r
        val x0f = x0; val x1f = x1; val y0f = y0; val y1f = y1
        val myf = my; val dhf = dh; val dipf = dip
        path.moveTo(x0f + rf, y0f)
        path.lineTo(x1f - rf, y0f)
        path.quadTo(x1f, y0f, x1f, y0f + rf)            // top-right
        path.lineTo(x1f, myf - dhf)
        path.lineTo(x1f - dipf, myf)                    // right dip
        path.lineTo(x1f, myf + dhf)
        path.lineTo(x1f, y1f - rf)
        path.quadTo(x1f, y1f, x1f - rf, y1f)            // bottom-right
        path.lineTo(x0f + rf, y1f)
        path.quadTo(x0f, y1f, x0f, y1f - rf)            // bottom-left
        path.lineTo(x0f, myf + dhf)
        path.lineTo(x0f + dipf, myf)                    // left dip
        path.lineTo(x0f, myf - dhf)
        path.lineTo(x0f, y0f + rf)
        path.quadTo(x0f, y0f, x0f + rf, y0f)            // top-left
        path.close()
        return path
    }

    /** Ribbon-and-dots bookmark glyph, centred on (cx,cy). s scales the
     *  whole mark for smaller faces; 1 = the 108px panel button. */
    private fun drawBookmarksGlyph(c: Canvas, p: Paint, cx: Float, cy: Float, col: Int,
                                   s: Float = 1f) {
        val w = 15f * s; val h = 22f * s
        p.color = col
        p.style = Paint.Style.FILL
        val g = Path()
        g.moveTo(cx - w, cy - h)
        g.lineTo(cx + w, cy - h)
        g.lineTo(cx + w, cy + h)
        g.lineTo(cx, cy + h - 8f * s)
        g.lineTo(cx - w, cy + h)
        g.close()
        c.drawPath(g, p)
        // Punch the two holes in the ribbon.
        p.color = Color.rgb(13, 20, 28)
        c.drawCircle(cx - 6f * s, cy - 10f * s, 2.6f * s, p)
        c.drawCircle(cx + 6f * s, cy - 10f * s, 2.6f * s, p)
    }

    /** The bookmarks pane, drawn over the rows beneath the icon. Only drawn
     *  while open; the icon draws from the same rect the hit test uses
     *  (bookIconRect), so the picture cannot disagree with the gesture. */
    private fun drawBookFlyout(c: Canvas, p: Paint, rows: List<BrowserRow>) {
        val icon = bookIconRect()
        if (icon[2] > icon[0]) {
            drawSideEntryButton(c, p, icon[0], icon[1],
                bookActive || bookControl.dwellProgress > 0f)
        }
        if (!bookOpen) return
        // Read the pane from the control, not from a second layout pass: the
        // drawn silhouette and the rect the hit test uses have to be the same
        // object, or the reticle ends up aimed at something that is not there.
        val pr = bookControl.pane ?: return
        val pane = floatArrayOf(pr.left, pr.top, pr.right, pr.bottom)
        val n = webBookList.size
        val scrolls = bookControl.needsArrows
        p.textAlign = Paint.Align.LEFT
        p.textSize = BOOK_PANE_TEXT

        if (scrolls) {
            // Up arrow, only while there is anything above.
            val up = pane[1]
            p.color = if (bookArrow == -1) Color.rgb(8, 145, 178) else Color.rgb(21, 32, 45)
            c.drawRect(pane[0], up, pane[2], up + BOOK_ARROW_H, p)
            p.color = if (bookArrow == -1) Color.WHITE else Color.rgb(148, 163, 184)
            p.textAlign = Paint.Align.CENTER
            c.drawText("\u25B2", TEX * 0.5f, up + 36f, p)
            p.textAlign = Paint.Align.LEFT
        }

        val top = pane[1] + if (scrolls) BOOK_ARROW_H else 0f
        var y = top
        for (k in bookScroll until minOf(bookScroll + bookControl.visibleCount, n)) {
            val sel = k == bookCursor
            p.color = if (sel) Color.rgb(8, 145, 178) else Color.rgb(21, 32, 45)
            c.drawRect(pane[0], y, pane[2], y + BOOK_PANE_ROW_H - 2f, p)
            p.color = if (sel) Color.WHITE else Color.rgb(203, 213, 225)
            c.drawText(webBookList[k].first, pane[0] + BOOK_PANE_PAD_X, y + 40f, p)
            y += BOOK_PANE_ROW_H
        }

        if (scrolls) {
            val dn = pane[3] - BOOK_ARROW_H
            p.color = if (bookArrow == 1) Color.rgb(8, 145, 178) else Color.rgb(21, 32, 45)
            c.drawRect(pane[0], dn, pane[2], pane[3], p)
            p.color = if (bookArrow == 1) Color.WHITE else Color.rgb(148, 163, 184)
            p.textAlign = Paint.Align.CENTER
            c.drawText("\u25BC", TEX * 0.5f, dn + 36f, p)
            p.textAlign = Paint.Align.LEFT
        }
    }

    /** Hash contribution of one browser row (matches what drawBrowserRow paints). */
    private fun rowHash(rows: List<BrowserRow>, i: Int): Int {
        var h = rows[i].label.hashCode() * 7 + rows[i].meta.hashCode() + rows[i].segSelected
        // shaping previews change with weights/toggles: sample magnitudes into the hash
        val pm = rows[i].previewMags
        if (pm != null) {
            var j = 0
            while (j < pm.size) { h = h * 31 + (pm[j] * 1000).toInt(); j += 7 }
            h = h * 31 + (rows[i].previewHull?.size ?: 0)
        }
        return h
    }

    /** Pinned scroll strip (file pages only): wide bar with
     *  trigger-progress fill while earning engagement, solid while
     *  gliding, dimmed at the travel end. */
    /** One PgUp/PgDn button; fills as the dwell completes. */
    private fun drawSideBtn(c: Canvas, p: Paint, y0: Float, label: String,
                            hot: Boolean, prog: Float) {
        p.color = when {
            hot -> Color.rgb(30, 58, 95)
            else -> Color.rgb(21, 32, 45)
        }
        c.drawRect(SIDE_BTN_X0, y0, SIDE_BTN_X1, y0 + SIDE_BTN_H, p)
        if (prog > 0f) {
            p.color = Color.rgb(8, 145, 178)
            c.drawRect(SIDE_BTN_X0, y0, SIDE_BTN_X0 + (SIDE_BTN_X1 - SIDE_BTN_X0) * prog,
                y0 + SIDE_BTN_H, p)
        }
        p.color = if (hot) Color.WHITE else Color.rgb(148, 163, 184)
        p.textSize = 26f
        c.drawText(label, (SIDE_BTN_X0 + SIDE_BTN_X1) * 0.5f, y0 + 32f, p)
    }

    private fun drawScrollStrip(c: Canvas, p: Paint, y0: Float, dir: Int) {
        val pin = pinTopRows.coerceIn(0, 2)
        val maxS = maxOf(0, browserRows.size - pin - winRows(pin)).toFloat()
        val atEnd = (dir < 0 && scrollPos <= 0f) || (dir > 0 && scrollPos >= maxS)
        val active = scrollEngage == dir
        p.color = when {
            atEnd -> Color.rgb(30, 41, 55)
            active -> Color.rgb(8, 145, 178)
            else -> Color.rgb(30, 58, 95)
        }
        c.drawRect(20f, y0, 1004f, y0 + STRIP_H, p)
        val prog = if (scrollTrigDir == dir && !active) scrollTrigF.coerceIn(0f, 1f) else 0f
        if (prog > 0f) {
            p.color = Color.rgb(8, 145, 178)
            c.drawRect(20f, y0, 20f + 984f * prog, y0 + STRIP_H, p)
        }
        p.color = if (atEnd) Color.rgb(100, 116, 139) else Color.WHITE
        p.textSize = 30f; p.textAlign = Paint.Align.CENTER
        val g = if (dir < 0) "▲" else "▼"
        c.drawText("$g  scroll ${if (dir < 0) "up" else "down"}  $g", 512f, y0 + 38f, p)
        p.textAlign = Paint.Align.LEFT
    }

    /** Single browser list row at panel y (int, TEX coords). */
    private fun drawBrowserRow(c: Canvas, p: Paint, rows: List<BrowserRow>, i: Int, y: Int) {
            val r = rows[i]
            if (i == highlight) {
                p.color = Color.rgb(30, 58, 95)
                c.drawRect(20f, y.toFloat(), 1004f, (y + ROW_H).toFloat(), p)
            }
            if (r.previewMags != null && r.previewPos != null) {
                // shaping preview row: mini 9x9 grid with moved dots, hull + vectors
                drawShapePreview(c, p, r, y)
                p.color = Color.WHITE; p.textSize = 30f; p.textAlign = Paint.Align.LEFT
                c.drawText(r.label.take(24), 100f, (y + 36).toFloat(), p)
                if (r.meta.isNotEmpty()) {
                    p.color = Color.rgb(148, 163, 184); p.textSize = 20f
                    c.drawText(r.meta.take(40), 100f, (y + 58).toFloat(), p)
                }
            } else if (r.dead) {
                // rest zone: thin divider, nothing to activate
                p.color = Color.rgb(51, 65, 85)
                c.drawRect(44f, (y + ROW_H / 2 - 1).toFloat(), 1000f, (y + ROW_H / 2 + 1).toFloat(), p)
            } else if (r.segLabels.isNotEmpty()) {
                // segmented button row: N equal buttons across the row width
                val n = r.segLabels.size
                val x0 = 20f; val x1 = 1004f
                val bw = (x1 - x0) / n
                p.textSize = 30f; p.textAlign = Paint.Align.CENTER
                for (s in 0 until n) {
                    val sx0 = x0 + s * bw + 3f
                    val sx1 = x0 + (s + 1) * bw - 3f
                    if (s == r.segSelected) {
                        p.color = Color.rgb(8, 145, 178)
                        c.drawRect(sx0, y.toFloat() + 6f, sx1, (y + ROW_H - 6).toFloat(), p)
                        p.color = Color.WHITE
                    } else {
                        p.color = Color.rgb(51, 65, 85)
                        c.drawRect(sx0, y.toFloat() + 6f, sx1, (y + ROW_H - 6).toFloat(), p)
                        p.color = Color.rgb(203, 213, 225)
                    }
                    c.drawText(r.segLabels[s].take(12), (sx0 + sx1) / 2f, (y + 41).toFloat(), p)
                }
                // live tooltip: name of the segment the gaze would select
                if (i == highlight && sliderHoverU >= 0f) {
                    val fx = ((sliderHoverU * 1024f - 20f) / 984f).coerceIn(0f, 0.999f)
                    val seg = (fx * n).toInt().coerceIn(0, n - 1)
                    val txt = r.segLabels[seg].take(12)
                    val cx = x0 + (seg + 0.5f) * bw
                    p.textSize = 20f
                    val tw = p.measureText(txt)
                    val bx0 = (cx - tw / 2f - 10f).coerceAtLeast(24f)
                    val bx1 = (cx + tw / 2f + 10f).coerceAtMost(1000f)
                    p.color = Color.rgb(10, 14, 22)
                    c.drawRect(bx0, (y + 18).toFloat(), bx1, (y + 46).toFloat(), p)
                    p.color = Color.WHITE
                    p.style = Paint.Style.STROKE; p.strokeWidth = 3f
                    c.drawRect(bx0, (y + 18).toFloat(), bx1, (y + 46).toFloat(), p)
                    p.style = Paint.Style.FILL
                    c.drawText(txt, (bx0 + bx1) / 2f, (y + 39).toFloat(), p)
                }
                p.textAlign = Paint.Align.LEFT
            } else {
            if (r.slideKey == null) {
                val icon = when (r.kind) {
                    BrowserRow.FOLDER -> "📁"
                    BrowserRow.VIDEO -> "🎬"
                    BrowserRow.ACTION -> "⚙"
                    else -> "📄"
                }
                p.color = Color.WHITE; p.textSize = 36f
                c.drawText("$icon  ${r.label.take(30)}", 44f, (y + 34).toFloat(), p)
            }
            if (r.slideKey != null) {
                // gaze slider (compact): label + value on top line, bar
                // mid-row, live tooltip bubble below the bar at the gaze
                // position showing the value a dwell would select
                val frac = ((r.slideVal - r.slideMin) / (r.slideMax - r.slideMin)).coerceIn(0f, 1f)
                p.color = Color.WHITE; p.textSize = 28f
                c.drawText(r.label.take(30), 44f, (y + 26).toFloat(), p)
                p.color = Color.rgb(125, 211, 252); p.textSize = 20f; p.textAlign = Paint.Align.RIGHT
                c.drawText(r.meta.take(20), 1000f, (y + 26).toFloat(), p)
                p.textAlign = Paint.Align.LEFT
                p.color = Color.rgb(51, 65, 85)
                c.drawRect(44f, (y + 30).toFloat(), 1000f, (y + 42).toFloat(), p)
                p.color = Color.rgb(125, 211, 252)
                c.drawRect(44f, (y + 30).toFloat(), 44f + 956f * frac, (y + 42).toFloat(), p)
                if (i == highlight && sliderHoverU >= 0f) {
                    val hf = barFrac(sliderHoverU)
                    var rraw = r.slideMin + hf * (r.slideMax - r.slideMin)
                    val fm = r.slideFmt
                    if (fm != null && fm.snap > 0f) rraw = Math.round(rraw / fm.snap).toFloat() * fm.snap
                    val disp = rraw * (fm?.scale ?: 1f) + (fm?.offset ?: 0f)
                    val dec = fm?.decimals ?: 0
                    val num = if (dec == 0) Math.round(disp).toString()
                        else String.format(Locale.US, "%.${dec}f", disp)
                    val txt = num + (fm?.suffix ?: "")
                    val tx = (44f + hf * 956f).coerceIn(70f, 954f)
                    p.textSize = 16f; p.textAlign = Paint.Align.CENTER
                    val tw = p.measureText(txt)
                    val bx0 = (tx - tw / 2f - 10f).coerceAtLeast(24f)
                    val bx1 = (tx + tw / 2f + 10f).coerceAtMost(1000f)
                    p.color = Color.rgb(10, 14, 22)
                    c.drawRect(bx0, (y + 44).toFloat(), bx1, (y + 62).toFloat(), p)
                    p.color = Color.rgb(125, 211, 252)
                    p.style = Paint.Style.STROKE; p.strokeWidth = 2f
                    c.drawRect(bx0, (y + 44).toFloat(), bx1, (y + 62).toFloat(), p)
                    p.style = Paint.Style.FILL
                    p.color = Color.WHITE
                    c.drawText(txt, (bx0 + bx1) / 2f, (y + 58).toFloat(), p)
                    p.textAlign = Paint.Align.LEFT
                }
            } else if (r.meta.isNotEmpty()) {
                p.color = Color.rgb(148, 163, 184); p.textSize = 24f
                c.drawText("    ${r.meta.take(56)}", 44f, (y + 58).toFloat(), p)
            }
            }
    }

    override fun onFrameAvailable(st: SurfaceTexture?) { frameAvailable = true; arrivedFrames++ }

    // ---------- play menu drawing ----------
    private var menuGrid: Mesh? = null
    private var menuGridD = -1f
    private var menuGridEl = -999f
    private fun drawMenuPanel(alpha: Float = 1f) {
        maybeUploadMenu()
        // World-locked plane at radius panelDistM whose elevation animates
        // through the ⇅ flip (the grid rebuilds each frame mid-flip, then
        // settles and is cached). The rect is authored in DESIGN space —
        // MENU_X0..X1 × MENU_Y0..Y1, tuned for MENU_DESIGN_R — and every
        // extent is multiplied by menuScale(), so the panel keeps its
        // apparent size while the distance slider only changes its depth.
        val d = panelDistM
        val el = Math.toRadians(menuElevCurrent().toDouble()).toFloat()
        val k = menuScale()
        if (menuGrid == null || menuGridD != d || menuGridEl != el) {
            val cx = 0f; val cy = (kotlin.math.sin(el) * d); val cz = (-kotlin.math.cos(el) * d)
            var nx = -cx; var ny = -cy; var nz = -cz
            val nl = kotlin.math.sqrt(nx * nx + ny * ny + nz * nz).coerceAtLeast(1e-6f)
            nx /= nl; ny /= nl; nz /= nl
            // up = n × (1,0,0) = (0, nz, -ny)
            val ux = 0f; val uy = nz; val uz = -ny
            fun corner(dx: Float, dy: Float) = floatArrayOf(
                cx + dx * k + ux * dy * k,
                cy + uy * dy * k,
                cz + uz * dy * k
            )
            menuGrid = gridQuadP(
                corner(MENU_X0, MENU_Y1), corner(MENU_X1, MENU_Y1),
                corner(MENU_X0, MENU_Y0), corner(MENU_X1, MENU_Y0),
                16, 6
            )
            menuGridD = d; menuGridEl = el
        }
        drawMesh2d(menuGrid!!, menuTexId, ovM, alpha)
    }

    /** One bitmap for the whole panel: backdrop, title, transport row,
     *  seek bar, the right-hand zoom/fov/volume columns and the transport
     *  row — all in design units mapped to texels, re-uploaded on the GL thread
     *  whenever state changes. Alpha follows doc §7.4: gazed brightens,
     *  dwell focus dims (1 − 0.5·progress), backdrop sits at 0.3. */
    private fun maybeUploadMenu() {
        val posSec = (menuPosMs / 1000).toInt()
        val durSec = (menuDurMs / 1000).toInt()
        val flashing = menuFlash.isNotEmpty() && now() < menuFlashUntil
        // Every button dims with its own dwell, so progress is part of the
        // cache key (quantised: it moves every frame while a dwell runs).
        var dwellSum = 0
        for (i in menuProg.indices) dwellSum += (menuProg[i] * 64f).toInt()
        // Armed sweep: the held button's face turns blue, and the dips come
        // and go with sweep itself — the grip is held the same way, so it
        // counts here too.
        val armedSweeps = sweepBtns.count { it.armed } + (if (seekDrag.engaged) 1 else 0)
        val h = menuHighlight * 31 + posSec * 131 + durSec * 17 +
            (if (menuPlaying) 1 else 0) + (if (flashing) 1009 else 0) + menuFlash.hashCode() +
            (if (menuSeekHoverU >= 0f) (menuSeekHoverU * 128).toInt() else 0) +
            // The grip does not wait for a second boundary while a hand is
            // on it: one term per texel of the bar keeps the picture under
            // the reticle. Idle it deliberately has none — the grip then
            // steps with posSec, exactly as the fill under it already does —
            // and a sticky commit only changes as it is taken and handed back.
            (if (seekDrag.engaged) (seekDrag.value * 956f).toInt() else 0) * 7 +
            (if (seekStickyU >= 0f) (seekStickyU * 956f).toInt() else -1) * 13 +
            menuTitle.hashCode() * 7 + skipSecs + dwellSum +
            (if (autoCue) 8191 else 0) + armedSweeps * 65537 +
            (if (sweepEnabled) 262147 else 0)
        if (h == lastMenuHash && menuBitmap != null) return
        lastMenuHash = h
        val W = MENU_TEX_W; val H = MENU_TEX_H
        val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.TRANSPARENT)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.style = Paint.Style.FILL
        // design -> texel: bitmap y grows downward, design y grows up
        val sx = W / (MENU_X1 - MENU_X0)
        val sy = H / (MENU_Y1 - MENU_Y0)
        fun tx(x: Float) = (x - MENU_X0) * sx
        fun ty(y: Float) = (MENU_Y1 - y) * sy
        fun box(b: MenuBtn) = floatArrayOf(
            tx(b.x - b.hw), ty(b.y + b.hh), tx(b.x + b.hw), ty(b.y - b.hh)
        )
        fun white(a: Int) = Color.argb(a, 255, 255, 255)
        /** §7.4 alpha: gazed 1.0, idle 0.9, times (1 − 0.5·dwell). */
        fun alphaOf(id: Int): Int {
            val base = if (menuHot(id)) 1f else 0.9f
            val focus = if (id != -2) menuProg[menuSlot(id)].coerceIn(0f, 1f) else 0f
            return (255f * base * (1f - 0.5f * focus)).toInt().coerceIn(0, 255)
        }
        fun text(s: String, cx: Float, cy: Float, size: Float, a: Int, outline: Boolean = false) {
            p.textSize = size; p.textAlign = Paint.Align.CENTER
            val by = cy + size * 0.35f
            if (outline) {
                // black rim first so the white glyphs stay legible over
                // any frame behind the (transparent) panel edge
                p.style = Paint.Style.STROKE; p.strokeWidth = size / 5f
                p.color = Color.argb(a, 0, 0, 0)
                c.drawText(s, cx, by, p)
            }
            p.style = Paint.Style.FILL
            p.color = white(a)
            c.drawText(s, cx, by, p)
            p.textAlign = Paint.Align.LEFT
        }
        // backdrop pane first: everything else draws over it (§7.3)
        val bd = box(menuBackdrop)
        p.color = Color.argb(77, 184, 188, 196)
        c.drawRect(bd[0], bd[1], bd[2], bd[3], p)
        // file name across the top strip
        val tr = box(menuTitleRect)
        p.color = white(255)
        p.textSize = 44f; p.textAlign = Paint.Align.CENTER
        val title = menuTitle.take(48)
        while (p.textSize > 18f && p.measureText(title) > (tr[2] - tr[0] - 24f)) p.textSize -= 2f
        c.drawText(title, (tr[0] + tr[2]) / 2f, (tr[1] + tr[3]) / 2f + p.textSize * 0.35f, p)
        p.textAlign = Paint.Align.LEFT
        // ---- buttons ----
        for (b in menuButtons) {
            val a = alphaOf(b.id)
            val cx = tx(b.x); val cy = ty(b.y)
            // Every button carries the same face — the entry dips ride on it —
            // so the old hover rect has no job left and is gone: two boxes
            // behind one button would read as a mistake. The face is always
            // there; only the dips wait for sweep, since a notch must never
            // advertise a gesture that is turned off. The fill brightens and
            // the border goes blue only while the sweep holds the button.
            sweepFace(c, p, box(b), a,
                hot = menuHot(b.id),
                armed = sweepBtn(b.id)?.armed == true)
            when (b.id) {
                // Transport, files / web and the ± columns draw their icons
                // at MENU_ICON_FRAC of the old size: at full size they came
                // too close to the button edges. The faces don't move.
                2, 18 -> text(b.glyph, cx, cy, 44f * MENU_ICON_FRAC, a)
                // transport row: our own artwork (blue tile, white shapes),
                // never the fonts — see transportGlyph
                3 -> transportGlyph(c, p, cx, cy, 44f * MENU_ICON_FRAC, a, TransportArt.PREV)
                4 -> transportGlyph(c, p, cx, cy, 44f * MENU_ICON_FRAC, a, TransportArt.REW)
                5 -> transportGlyph(c, p, cx, cy, 44f * MENU_ICON_FRAC, a,
                    if (menuPlaying) TransportArt.PAUSE else TransportArt.PLAY)
                6 -> transportGlyph(c, p, cx, cy, 44f * MENU_ICON_FRAC, a, TransportArt.FF)
                7 -> transportGlyph(c, p, cx, cy, 44f * MENU_ICON_FRAC, a, TransportArt.NEXT)
                // the ± columns: glyphs sized to their 0.6-unit faces
                8 -> magnifierGlyph(c, p, cx, cy, 50f * MENU_ICON_FRAC, a, true)
                9 -> magnifierGlyph(c, p, cx, cy, 50f * MENU_ICON_FRAC, a, false)
                10 -> speakerGlyph(c, p, cx, cy, 18f * MENU_ICON_FRAC, a, true)
                11 -> speakerGlyph(c, p, cx, cy, 18f * MENU_ICON_FRAC, a, false)
                14 -> fovGlyph(c, p, cx, cy, 44f * MENU_ICON_FRAC, a, false)
                15 -> fovGlyph(c, p, cx, cy, 44f * MENU_ICON_FRAC, a, true)
                12 -> flipGlyph(c, p, cx, cy, 40f, a)
                13 -> crosshairGlyph(c, p, cx, cy, 40f, a)
                0 -> text(b.glyph, cx, cy, 36f, a)
                MENU_CUE_ID -> cueIcon(c, p, box(b), a)
                else -> text(b.glyph, cx, cy, 44f, a)
            }
        }
        // ---- seek bar ----
        val bar = box(menuBar)
        val barA = alphaOf(-1)
        p.color = Color.argb(barA, 51, 65, 85)
        c.drawRect(bar[0], bar[1], bar[2], bar[3], p)
        val frac = if (menuDurMs > 0) (menuPosMs.toFloat() / menuDurMs).coerceIn(0f, 1f) else 0f
        p.color = Color.argb(barA, 125, 211, 250)
        c.drawRect(bar[0], bar[1], bar[0] + (bar[2] - bar[0]) * frac, bar[3], p)
        if (menuHighlight == -1) {
            p.color = white(barA); p.style = Paint.Style.STROKE; p.strokeWidth = 4f
            c.drawRect(bar[0], bar[1], bar[2], bar[3], p)
            p.style = Paint.Style.FILL
        }
        // The grip: drawn from the drag's own thumb rect, so the shape the
        // entry test hits and the shape drawn here are one number. It carries
        // the same face as every button (dips and all — the notches are the
        // entry sides), turns blue only while the sweep holds it, and sits on
        // the playhead. Sweep only: with sweep off there is no gesture to
        // advertise, and the bar alone is the seek target.
        if (sweepEnabled) {
            val g = seekDrag.thumbRect()   // engine space, y-down
            sweepFace(c, p, floatArrayOf(
                tx(g.left), ty(-g.top), tx(g.right), ty(-g.bottom)),
                alphaOf(-1), hot = seekDrag.engaged, armed = seekDrag.engaged,
                // Purple, not the shared armed blue. The grip's border sits
                // directly against the seek bar's blue historical fill, so a
                // blue rim on a blue fill is the one thing that cannot be seen
                // — and it is the border that says the drag is live, which is
                // exactly what is wanted at that moment. Purple is near enough
                // to the far side of the wheel to read as its complement while
                // staying light against the dark face.
                armedRgb = SEEK_GRIP_ARMED_RGB,
                // Half the fill's opacity, so the bar reads through the grip
                // while it is held. The border stays opaque: that is the part
                // carrying the state, and a translucent rim on a translucent
                // fill would leave nothing to see. 255 (the default) while the
                // grip is idle, so an untouched grip is as solid as every
                // other face on the panel.
                hotAlpha = if (seekDrag.engaged) 128 else 255)
            // A line down the middle of the grip, in the border's own blue:
            // the affordance that says "this slides" without a glyph, and the
            // one part of the grip that reads the same whether it is held or
            // not. Drawn inside the face's own width rather than to the tips,
            // so it cannot be mistaken for the notch on either end.
            p.style = Paint.Style.STROKE
            p.strokeCap = Paint.Cap.ROUND
            p.strokeWidth = ((g.right - g.left) * 0.13f).coerceAtLeast(2f)
            p.color = Color.argb(alphaOf(-1), (SEEK_GRIP_MARK_RGB shr 16) and 0xFF,
                (SEEK_GRIP_MARK_RGB shr 8) and 0xFF, SEEK_GRIP_MARK_RGB and 0xFF)
            val ml = tx(g.left)
            val mr = tx(g.right)
            val mt = ty(-g.top)
            val mb = ty(-g.bottom)
            val mcx = (ml + mr) * 0.5f
            val mInset = (mb - mt) * 0.22f
            c.drawLine(mcx, mt + mInset, mcx, mb - mInset, p)
            p.strokeCap = Paint.Cap.BUTT
        }
        // position / duration under the bar (the live seek time rides the
        // head-locked tooltip pill instead, so the two never overlap).
        // 31.2 = 24 × 1.3: read at arm's length across the room, which the
        // old size did not manage.
        if (!(menuHighlight == -1 && menuSeekHoverU >= 0f && menuDurMs > 0)) {
            text(
                if (flashing) menuFlash else "${fmtTime(menuPosMs)} / ${fmtTime(menuDurMs)}",
                tx(menuBar.x), ty(-1.5f), 31.2f, 255, outline = true
            )
        }
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, menuTexId)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
        menuBitmap?.recycle()
        menuBitmap = bmp
    }

    // ---- menu glyphs (white on transparent; `a` = 0..255 alpha) ----
    /** Magnifier with + / − inside the lens (emoji turns to mush small). */
    private fun magnifierGlyph(c: Canvas, p: Paint, cx: Float, cy: Float, s: Float, a: Int, plus: Boolean) {
        val r = s * 0.30f
        val lx = cx - r * 0.35f; val ly = cy - r * 0.25f
        val sw = (s * 0.09f).coerceAtLeast(3f)
        p.color = Color.argb(a, 255, 255, 255); p.style = Paint.Style.STROKE; p.strokeWidth = sw
        c.drawCircle(lx, ly, r, p)
        val hx = lx + r * 0.72f; val hy = ly + r * 0.72f
        c.drawLine(hx, hy, hx + r * 0.85f, hy + r * 0.85f, p)
        p.style = Paint.Style.FILL
        val bw = r * 1.1f
        c.drawRect(lx - bw / 2f, ly - sw / 2f, lx + bw / 2f, ly + sw / 2f, p)
        if (plus) c.drawRect(lx - sw / 2f, ly - bw / 2f, lx + sw / 2f, ly + bw / 2f, p)
        p.style = Paint.Style.FILL
    }
    /** Speaker body; loud adds the second wave (identical geometry both). */
    private fun speakerGlyph(c: Canvas, p: Paint, cx: Float, cy: Float, s: Float, a: Int, loud: Boolean) {
        val col = Color.argb(a, 255, 255, 255)
        val sw = (s * 0.09f).coerceAtLeast(3f)
        val bx1 = cx - s * 0.35f
        p.color = col; p.style = Paint.Style.FILL
        c.drawRect(cx - s * 1.1f, cy - s * 0.55f, bx1, cy + s * 0.55f, p)
        val tipX = cx + s * 0.25f
        c.drawPath(Path().apply {
            moveTo(bx1, cy - s * 0.55f); lineTo(tipX, cy - s)
            lineTo(tipX, cy + s); lineTo(bx1, cy + s * 0.55f); close()
        }, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = sw
        fun wave(rr: Float) = c.drawArc(tipX - rr, cy - rr, tipX + rr, cy + rr, -55f, 110f, false, p)
        wave(s * 0.62f)
        if (loud) wave(s * 1.12f)
        p.style = Paint.Style.FILL
    }
    /** Screen frame with − / +: FOV narrower / wider. */
    private fun fovGlyph(c: Canvas, p: Paint, cx: Float, cy: Float, s: Float, a: Int, plus: Boolean) {
        val col = Color.argb(a, 255, 255, 255)
        val sw = (s * 0.09f).coerceAtLeast(3f)
        val fw = s * 0.46f; val fh = s * 0.32f
        p.color = col; p.style = Paint.Style.STROKE; p.strokeWidth = sw
        c.drawRect(cx - fw, cy - fh, cx + fw, cy + fh, p)
        val b = s * 0.30f
        c.drawLine(cx - b, cy, cx + b, cy, p)
        if (plus) c.drawLine(cx, cy - b, cx, cy + b, p)
        p.style = Paint.Style.FILL
    }
    /** Recenter crosshair: stroked ring + center dot. */
    private fun crosshairGlyph(c: Canvas, p: Paint, cx: Float, cy: Float, s: Float, a: Int) {
        val r = s * 0.30f
        val sw = (s * 0.09f).coerceAtLeast(3f)
        p.color = Color.argb(a, 255, 255, 255); p.style = Paint.Style.STROKE; p.strokeWidth = sw
        c.drawCircle(cx, cy, r, p)
        p.style = Paint.Style.FILL
        c.drawCircle(cx, cy, sw, p)
    }
    /** Menu-side flip: up chevron over down chevron (the old ⇅ text glyph,
     *  drawn vector so it can't tofu). */
    private fun flipGlyph(c: Canvas, p: Paint, cx: Float, cy: Float, s: Float, a: Int) {
        val col = Color.argb(a, 255, 255, 255)
        val w = s * 0.22f; val h = s * 0.16f; val gap = s * 0.07f
        p.color = col; p.style = Paint.Style.FILL
        c.drawPath(Path().apply {
            moveTo(cx - w, cy - gap); lineTo(cx + w, cy - gap); lineTo(cx, cy - gap - h); close()
        }, p)
        c.drawPath(Path().apply {
            moveTo(cx - w, cy + gap); lineTo(cx + w, cy + gap); lineTo(cx, cy + gap + h); close()
        }, p)
    }

    /** What a transport button draws (see transportGlyph): the blue tile
     *  with its white shapes, or just the play triangle while paused. */
    private enum class TransportArt { PREV, REW, PLAY, PAUSE, FF, NEXT }

    /** The transport row's own artwork, drawn here rather than handed to the
     *  text shaper: as text, this device painted ⏪ ⏩ ⏸ out of Samsung's
     *  colour emoji font while ⏮ ⏭ ▶ fell through to a monochrome symbol
     *  font listed ahead of the colour fonts, so the row's look depended on
     *  which fonts happen to be installed. Same proportions as that
     *  artwork — a rounded blue tile (TRACK_BLUE, the emoji font's own
     *  gradient) carrying white shapes — and play draws no tile at all, so
     *  the button still reads blue while it plays and dark while paused. */
    private fun transportGlyph(c: Canvas, p: Paint, cx: Float, cy: Float,
                               s: Float, a: Int, art: TransportArt) {
        val t = s * 1.13f          // the emoji tile's own footprint
        val l = cx - t * 0.5f
        val tp = cy - t * 0.5f
        fun ux(u: Float) = l + u * t
        fun vy(v: Float) = tp + v * t
        // the white band inside the tile, per art
        var v0 = 0.235f; var v1 = 0.774f   // rewind / ff
        when (art) {
            TransportArt.PLAY -> { v0 = 0.192f; v1 = 0.817f }
            TransportArt.PAUSE -> { v0 = 0.202f; v1 = 0.808f }
            TransportArt.PREV, TransportArt.NEXT -> { v0 = 0.260f; v1 = 0.750f }
            else -> {}
        }
        p.style = Paint.Style.FILL
        if (art != TransportArt.PLAY) {
            // alpha folded into the stops so the tile dims with the face
            val cols = IntArray(TRACK_BLUE.size) { i ->
                (TRACK_BLUE[i] and 0x00FFFFFF) or (a shl 24)
            }
            p.shader = LinearGradient(l, tp, l, tp + t, cols, TRACK_BLUE_POS,
                Shader.TileMode.CLAMP)
            val tile = Path()
            tile.addRoundRect(RectF(l, tp, l + t, tp + t),
                t * 0.04f, t * 0.04f, Path.Direction.CW)
            c.drawPath(tile, p)
            p.shader = null
        }
        p.color = Color.argb(a, 255, 255, 255)
        fun bar(u0: Float, u1: Float) =
            c.drawRect(ux(u0), vy(v0), ux(u1), vy(v1), p)
        fun tri(u0: Float, u1: Float, right: Boolean) {
            val base = if (right) ux(u0) else ux(u1)
            val tip = if (right) ux(u1) else ux(u0)
            c.drawPath(Path().apply {
                moveTo(base, vy(v0)); lineTo(tip, vy((v0 + v1) * 0.5f))
                lineTo(base, vy(v1)); close()
            }, p)
        }
        when (art) {
            // A pair overlaps slightly at the centre row the way the
            // artwork does, so the notch between the two opens upward and
            // downward instead of running the full height as a straight gap.
            TransportArt.REW -> {
                tri(0.144f, 0.500f, false); tri(0.450f, 0.808f, false)
            }
            TransportArt.FF -> {
                tri(0.202f, 0.546f, true); tri(0.515f, 0.856f, true)
            }
            TransportArt.PREV -> {
                bar(0.173f, 0.250f); tri(0.251f, 0.569f, false)
                tri(0.527f, 0.836f, false)
            }
            TransportArt.NEXT -> {
                tri(0.164f, 0.473f, true); tri(0.431f, 0.749f, true)
                bar(0.750f, 0.827f)
            }
            TransportArt.PAUSE -> { bar(0.317f, 0.423f); bar(0.577f, 0.683f) }
            TransportArt.PLAY -> tri(0.269f, 0.788f, true)
        }
    }

    /** A swept button's face: a rounded square with a triangular dip cut
     *  into each SIDE edge — the two edges the sweep may be entered through
     *  — in the same language as the toolbar buttons and the bookmarks
     *  square. Top and bottom are the committing edges, so they stay
     *  un-notched.
     *
     *  With sweep off the dips are not drawn (plain rounded square, same
     *  rect): an entry notch must never advertise a gesture that is turned
     *  off. Coordinates are texel-space edges — left, top, right, bottom. */
    private fun sweepFacePath(l: Float, t: Float, r: Float, b: Float): Path {
        val rad = MENU_CORNER_FRAC * (r - l)
        val dip = if (sweepEnabled) (r - l) * 0.09f else 0f
        val dh = (r - l) * 0.24f
        val my = (t + b) * 0.5f
        return Path().apply {
            moveTo(l + rad, t)
            lineTo(r - rad, t)
            quadTo(r, t, r, t + rad)
            if (dip > 0f) {
                lineTo(r, my - dh)
                lineTo(r - dip, my)      // right dip
                lineTo(r, my + dh)
            }
            lineTo(r, b - rad)
            quadTo(r, b, r - rad, b)
            lineTo(l + rad, b)
            quadTo(l, b, l, b - rad)
            if (dip > 0f) {
                lineTo(l, my + dh)
                lineTo(l + dip, my)      // left dip
                lineTo(l, my - dh)
            }
            lineTo(l, t + rad)
            quadTo(l, t, l + rad, t)
            close()
        }
    }

    /** Fill and stroke that face. Every button on the panel is a solid dark
     *  square with a white glyph over it — the dips are the only thing that
     *  comes and goes with sweep, since a notch must never advertise a
     *  gesture that is turned off. The fill brightens and the border goes
     *  blue only while the sweep holds the button, so the picture cannot
     *  disagree with the gesture. */
    private fun sweepFace(c: Canvas, p: Paint, box: FloatArray, a: Int,
                          hot: Boolean, armed: Boolean, armedRgb: Int = 0x38BDF8,
                          hotAlpha: Int = -1) {
        val l = box[0]; val t = box[1]; val r = box[2]; val b = box[3]
        val path = sweepFacePath(l, t, r, b)
        p.style = Paint.Style.FILL
        // The grip's held face is the one fill that gives up opacity: it lies
        // across the seek bar, and opaque it buried the track and the playhead
        // under the very thing being dragged along it. -1 means "use the
        // caller's alpha", which is what every other button wants.
        val ha = if (hotAlpha >= 0) (a * hotAlpha / 255).coerceIn(0, 255) else a
        p.color = if (hot) Color.argb(ha, 30, 58, 95) else Color.argb(ha, 21, 32, 45)
        c.drawPath(path, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = ((r - l) * 0.08f).coerceAtLeast(3f)
        p.color = if (armed) Color.argb(a, (armedRgb shr 16) and 0xFF,
                  (armedRgb shr 8) and 0xFF, armedRgb and 0xFF)
                  else Color.argb(a, 71, 85, 105)
        c.drawPath(path, p)
        p.style = Paint.Style.FILL
    }

    /** The cue toggle's icon: an oval loop with an arrowhead when the same
     *  video repeats (the default), a straight arrow pointing right when
     *  autocue is set. White over the face the draw loop already laid down,
     *  drawn in the bitmap's own texel space like every other glyph. */
    private fun cueIcon(c: Canvas, p: Paint, box: FloatArray, a: Int) {
        val l = box[0]; val t = box[1]; val r = box[2]; val b = box[3]
        val s = r - l
        val cx = (l + r) * 0.5f
        val cy = (t + b) * 0.5f

        // ---- icon ----
        p.color = Color.argb(a, 255, 255, 255)
        p.strokeWidth = (s * 0.075f).coerceAtLeast(3f)
        p.strokeCap = Paint.Cap.ROUND
        if (autoCue) {
            // straight arrow, point to the right
            val y = cy
            val x0 = cx - s * 0.30f
            val tip = cx + s * 0.30f
            val head = s * 0.13f
            p.style = Paint.Style.STROKE
            c.drawLine(x0, y, tip - head * 0.8f, y, p)
            p.style = Paint.Style.FILL
            c.drawPath(Path().apply {
                moveTo(tip, y)
                lineTo(tip - head * 1.5f, y - head)
                lineTo(tip - head * 1.5f, y + head)
                close()
            }, p)
        } else {
            // oval loop: 300°..600° clockwise leaves the gap at the top,
            // and the head sits where the loop ends, pointing into it.
            val rx = s * 0.30f
            val ry = s * 0.22f
            p.style = Paint.Style.STROKE
            c.drawArc(cx - rx, cy - ry, cx + rx, cy + ry, 300f, 300f, false, p)
            val th = Math.toRadians(240.0)
            val px = (cx + rx * kotlin.math.cos(th)).toFloat()
            val py = (cy + ry * kotlin.math.sin(th)).toFloat()
            var dx = (-rx * kotlin.math.sin(th)).toFloat()   // ellipse tangent
            var dy = (ry * kotlin.math.cos(th)).toFloat()
            val dl = kotlin.math.sqrt(dx * dx + dy * dy).coerceAtLeast(1e-6f)
            dx /= dl; dy /= dl
            val head = s * 0.12f
            val nx = -dy; val ny = dx
            p.style = Paint.Style.FILL
            c.drawPath(Path().apply {
                moveTo(px + dx * head, py + dy * head)
                lineTo(px - dx * head * 0.5f + nx * head * 0.9f,
                       py - dy * head * 0.5f + ny * head * 0.9f)
                lineTo(px - dx * head * 0.5f - nx * head * 0.9f,
                       py - dy * head * 0.5f - ny * head * 0.9f)
                close()
            }, p)
        }
        p.style = Paint.Style.FILL
        p.strokeCap = Paint.Cap.BUTT
    }


    private fun fmtTime(ms: Long): String {
        val s = (ms / 1000).toInt().coerceAtLeast(0)
        return "%d:%02d".format(s / 60, s % 60)
    }

    // ---- meshes ----
    private data class Mesh(val verts: FloatBuffer, val tex: FloatBuffer, val indices: java.nio.ShortBuffer, val indexCount: Int)

    /** One enabled shaping grid: offsets in half-frame normalized units
     *  (ox +right, oy +down/grid-space), weight 0..1. Arrays never mutated
     *  after creation; the whole list is replaced atomically (volatile). */
    data class ActiveShape(val ox: FloatArray, val oy: FloatArray, val weight01: Float, val n: Int)
    @Volatile var shapingActive: List<ActiveShape> = emptyList()
    @Volatile var shapingRevision: Int = 0

    /** Curved flat screen (screenCurve 0..1): every vertex blends the flat
     *  quad's point with the eye-centred spherical-cap point of radius Rt,
     *  so curve 0 reproduces the plane exactly and curve 1 bends the whole
     *  picture around the viewer (IMAX-style wrap, both axes — the top edge
     *  swings forward too, which is what kills the stretch of a huge flat
     *  screen). World size (w × h) is baked in because an eye-centred cap
     *  cannot be scaled to change apparent size, and the wrap angle w/Rt is
     *  clamped to SCREEN_ARC_MAX_DEG so the edges stay in front of the ears
     *  (this is a curved screen, not a dome). Texcoords and winding match
     *  gridQuadP(flipV = true) so the FLAT draw path is untouched. */
    private fun screenCapMesh(curve: Float, w: Float, h: Float): Mesh {
        val c = curve.coerceIn(0f, 1f)
        val arcMax = Math.toRadians(SCREEN_ARC_MAX_DEG.toDouble()).toFloat()
        val rt = maxOf(SCREEN_R_MIN, w / arcMax)
        val cols = 48; val rows = 24
        val verts = FloatArray((cols + 1) * (rows + 1) * 3)
        val texs = FloatArray((cols + 1) * (rows + 1) * 2)
        var vi = 0; var ti = 0
        for (iy in 0..rows) {
            val v = iy.toFloat() / rows
            val by = (0.5f - v) * h / rt
            val cy = kotlin.math.cos(by); val sy = kotlin.math.sin(by)
            val fy = (0.5f - v) * h
            for (ix in 0..cols) {
                val u = ix.toFloat() / cols
                val ax = (u - 0.5f) * w / rt
                val fx = (u - 0.5f) * w
                val cx = rt * cy * kotlin.math.sin(ax)
                val ry = rt * sy
                val cz = -rt * cy * kotlin.math.cos(ax)
                verts[vi++] = fx + (cx - fx) * c
                verts[vi++] = fy + (ry - fy) * c
                verts[vi++] = -FLAT_DIST + (cz + FLAT_DIST) * c
                texs[ti++] = u; texs[ti++] = 1f - v
            }
        }
        val idx = mutableListOf<Short>()
        for (iy in 0 until rows) for (ix in 0 until cols) {
            val a = (iy * (cols + 1) + ix).toShort()
            val b = (a + 1).toShort(); val cc = ((iy + 1) * (cols + 1) + ix).toShort(); val d = (cc + 1).toShort()
            idx += listOf(a, cc, b, b, cc, d)
        }
        FileLog.i("SweepVR-GL", "flat cap built curve=${"%.2f".format(c)} rt=${"%.2f".format(rt)} " +
            "arc=${"%.0f".format(Math.toDegrees((w / rt).toDouble()))}x" +
            "${"%.0f".format(Math.toDegrees((h / rt).toDouble()))}deg " +
            "w=${"%.2f".format(w)} h=${"%.2f".format(h)} verts=${verts.size / 3}")
        return Mesh(fb(verts), fb(texs), sb(idx.toShortArray()), idx.size)
    }

    /** Video geometry set (method doc §§5-6): strip positions plus five
     *  baked texcoord buffers each (2-D, SBS eye 1/2, TB eye 1/2 - §6.1
     *  selection). Rebuilt when density, curve dims or shaping change. */
    private class VideoGeom(
        val flatTex: Array<FloatBuffer>,
        val capPos: FloatBuffer?,
        val capTex: Array<FloatBuffer>?,
        val capCount: Int,
        val domePos: Map<Float, FloatBuffer>,
        val domeTex: Array<FloatBuffer>,
        /** Shaped deltas over the 180° span per eye buffer, for fisheye. */
        val fishDelta: Array<FloatBuffer>,
        val domeCount: Int
    )
    /** Flat unit quad positions, §5.2 order (strip of six). Never changes. */
    private val flatPosBuf: FloatBuffer by lazy {
        fb(floatArrayOf(
            -1f, 1f, 0f, -1f, -1f, 0f, 1f, 1f, 0f,
            -1f, -1f, 0f, 1f, -1f, 0f, 1f, 1f, 0f))
    }

    /** Standard density: longitudeCount 30 (or 60 high), latitudeCount =
     *  longitudeCount / 4 exactly (§5.1 - any other ratio changes the chord
     *  weights and the curvature goes subtly wrong). */
    private fun domeCounts(): Pair<Int, Int> {
        val c = if (panoQuality == "vertexhq") 60 else 30
        return c to c / 4
    }

    private fun domeVertexCount(c: Int, r: Int) = 2 * (c * (r - 1) + (r - 2))

    /** Dome positions on the UNIT sphere (§5.3 verbatim: latStep negative
     *  top-to-bottom, lat0 exactly +90°, span centred on -Z; forward is -Z).
     *  Unit radius: with the dome built from headView (§4.3) both eyes share
     *  the geometry and stereo comes from the frusta alone, so radius is
     *  irrelevant to parallax. Exact poles (degenerate tris, intended). */
    private fun domePosStrip(angleDeg: Float, c: Int, r: Int): FloatArray {
        val angle = Math.toRadians(angleDeg.toDouble()).toFloat()
        val latStep = -(Math.PI / (r - 1)).toFloat()
        val lat0 = (Math.PI / 2).toFloat()
        val lon0 = (-Math.PI / 2).toFloat() - angle / 2f
        val lonStep = angle / (c - 1)
        val out = FloatArray(domeVertexCount(c, r) * 3)
        var o = 0
        fun emit(lat: Float, lon: Float) {
            val lonD = lon.toDouble(); val latD = lat.toDouble()
            out[o++] = (Math.cos(lonD) * Math.cos(latD)).toFloat()
            out[o++] = Math.sin(latD).toFloat()
            out[o++] = (Math.sin(lonD) * Math.cos(latD)).toFloat()
        }
        for (y in 0 until r - 1) {
            val latTop = y * latStep + lat0
            val latBot = (y + 1) * latStep + lat0
            for (x in 0 until c) {
                val lon = x * lonStep + lon0
                emit(latTop, lon)
                emit(latBot, lon)
            }
            if (y < r - 2) {
                // Seam-wrapping degenerates: last vertex of this band, first
                // of the next. All but the last band carry them.
                emit(latBot, lon0 + angle)
                emit(latBot, lon0)
            }
        }
        return out
    }

    /** Per-band projective weights measured on the 360° span (§6.3): the
     *  square-root form is kept verbatim for its guards (degenerate rows
     *  fall back to 1, and pole rows MUST fall back - a horizontal chord
     *  there is exactly zero). Only the chord ratio survives, so one span
     *  serves all four. */
    private fun domeBandWeights(c: Int, r: Int): Pair<FloatArray, FloatArray> {
        val pos = domePosStrip(360f, c, r)
        fun px(k: Int) = pos[k * 3].toDouble()
        fun py(k: Int) = pos[k * 3 + 1].toDouble()
        fun pz(k: Int) = pos[k * 3 + 2].toDouble()
        fun dist(a: Int, b: Int): Double {
            val dx = px(a) - px(b); val dy = py(a) - py(b); val dz = pz(a) - pz(b)
            return Math.sqrt(dx * dx + dy * dy + dz * dz)
        }
        val topW = FloatArray(r - 1) { 1f }
        val botW = FloatArray(r - 1) { 1f }
        for (y in 0 until r - 1) {
            // First column of band y in strip order (bands before it each
            // contributed 2*C verts plus 2 wrap verts).
            val b = y * 2 * (c + 1)
            val topChord = dist(b + 2, b)
            val bottomChord = dist(b + 3, b + 1)
            val sideChord = dist(b + 1, b)
            val halfDiff = (bottomChord - topChord) / 2.0
            val aux = Math.sqrt(sideChord * sideChord - halfDiff * halfDiff)
            val mean = (topChord + bottomChord) / 2.0
            val denom = -2.0 * mean * aux
            if (denom != 0.0) {
                val t = (-mean * aux - aux * halfDiff) / denom
                val bb = (-mean * aux + aux * halfDiff) / denom
                if (t > 0.0 && t < 1.0 && bb > 0.0 && bb < 1.0) {
                    topW[y] = (1.0 / (1.0 - bb)).toFloat()
                    botW[y] = (1.0 / bb).toFloat()
                }
            }
        }
        return topW to botW
    }

    /** Dome texcoords for one layout (0 = 2-D, 1/2 = SBS eye 1/2, 3/4 = TB
     *  eye 1/2): §6.2 windows with §6.3 weights, §6.3 emission order. v is
     *  v-up like every other path: the top band carries the LARGEST v of
     *  its window (domePosStrip walks bands top-to-bottom, so the window is
     *  mirrored onto the bands - bounds unchanged). Span-independent
     *  (u normalises over whatever longitudes), so one set serves all spans.
     *  Wrap verts stay plain w = 1: degenerate steering tris whose texcoords
     *  never shade a pixel. */
    private fun domeTexStrip(layout: Int, c: Int, r: Int, wTop: FloatArray, wBot: FloatArray): FloatArray {
        val (uStep, vStep, uBase, vBase) = when (layout) {
            // 2-D
            0 -> Quad(1f / (c - 1), 1f / (r - 1), 0f, 0f)
            // side-by-side (vStep unchanged)
            1 -> Quad(0.5f / (c - 1), 1f / (r - 1), 0f, 0f)
            2 -> Quad(0.5f / (c - 1), 1f / (r - 1), 0.5f, 0f)
            // top/bottom
            3 -> Quad(1f / (c - 1), 0.5f / (r - 1), 0f, 0f)
            else -> Quad(1f / (c - 1), 0.5f / (r - 1), 0f, 0.5f)
        }
        val out = FloatArray(domeVertexCount(c, r) * 4)
        var o = 0
        fun emit(u: Float, v: Float, w: Float) {
            out[o++] = u * w; out[o++] = v; out[o++] = 0f; out[o++] = w
        }
        // v-up: the sphere's top band (band 0) carries the LARGEST v of the
        // layout window. The window bounds are untouched - only which end
        // of it sits at the top. v' = vBase + vSpan - (v - vBase); for the
        // full window (vBase 0, vSpan 1) that is 1 - v, the same idiom
        // capStripTex uses. Band weights stay with their positions.
        val vSpan = (r - 1) * vStep
        fun flip(v: Float) = vBase + vSpan - (v - vBase)
        for (y in 0 until r - 1) {
            val vTop = flip(y * vStep + vBase)
            val vBot = flip((y + 1) * vStep + vBase)
            val tw = wTop[y]; val bw = wBot[y]
            for (x in 0 until c) {
                val u = x * uStep + uBase
                emit(u, vTop, tw)
                emit(u, vBot, bw)
            }
            if (y < r - 2) {
                emit((c - 1) * uStep + uBase, vBot, 1f)
                emit(uBase, vBot, 1f)
            }
        }
        return out
    }

    private data class Quad(val a: Float, val b: Float, val c: Float, val d: Float)

    /** Flat texcoords: §5.2 rectangles in the pipeline's v-up convention
     *  (top verts carry v = 1, exactly like the old flipV quad), so 2-D
     *  orientation and the per-eye halves sample identically to before -
     *  only the attribute grows a projective w = 1. Vertex order §5.2. */
    private fun flatRectTex(u0: Float, vTop: Float, u1: Float, vBot: Float): FloatArray {
        val us = floatArrayOf(u0, u0, u1, u0, u1, u1)
        val vs = floatArrayOf(vTop, vBot, vTop, vBot, vBot, vTop)
        return FloatArray(24).also { o ->
            for (i in 0..5) { o[i * 4] = us[i]; o[i * 4 + 1] = vs[i]; o[i * 4 + 2] = 0f; o[i * 4 + 3] = 1f }
        }
    }

    /** Curved-cap strip for video (curve > 0 only): same math as
     *  screenCapMesh (kept for the web mesh), re-emitted in strip order
     *  with vec4 texcoords (w = 1: caps were never projectively corrected).
     *  Layout windows map onto the cap's full-frame UVs exactly like flat. */
    private fun capStripTex(layout: Int, cols: Int, rows: Int): FloatArray {
        val (u0, vTop, u1, vBot) = when (layout) {
            1 -> Quad(0f, 1f, 0.5f, 0f)
            2 -> Quad(0.5f, 1f, 1f, 0f)
            3 -> Quad(0f, 0.5f, 1f, 0f)
            4 -> Quad(0f, 1f, 1f, 0.5f)
            else -> Quad(0f, 1f, 1f, 0f)
        }
        // Base cap UVs are full-frame (u, 1-v); window them per layout.
        val out = FloatArray(2 * ((cols + 1) * rows + (rows - 1)) * 4)
        var o = 0
        fun emit(bu: Float, bv: Float) {
            out[o++] = u0 + bu * (u1 - u0)
            out[o++] = vBot + bv * (vTop - vBot)
            out[o++] = 0f; out[o++] = 1f
        }
        for (iy in 0 until rows) {
            for (ix in 0..cols) {
                val u = ix.toFloat() / cols
                emit(u, 1f - iy.toFloat() / rows)
                emit(u, 1f - (iy + 1).toFloat() / rows)
            }
            if (iy < rows - 1) {
                emit(1f, 1f - (iy + 1).toFloat() / rows)
                emit(0f, 1f - (iy + 1).toFloat() / rows)
            }
        }
        return out
    }

    private fun capStripPos(w: Float, h: Float, curve: Float): FloatArray {
        val c = curve.coerceIn(0f, 1f)
        val arcMax = Math.toRadians(SCREEN_ARC_MAX_DEG.toDouble()).toFloat()
        val rt = maxOf(SCREEN_R_MIN, w / arcMax)
        val cols = 48; val rows = 24
        val out = FloatArray(2 * ((cols + 1) * rows + (rows - 1)) * 3)
        var o = 0
        fun emit(u: Float, v: Float) {
            val by = (0.5f - v) * h / rt
            val cy = kotlin.math.cos(by); val sy = kotlin.math.sin(by)
            val fy = (0.5f - v) * h
            val ax = (u - 0.5f) * w / rt
            val fx = (u - 0.5f) * w
            val cx = rt * cy * kotlin.math.sin(ax)
            val ry = rt * sy
            val cz = -rt * cy * kotlin.math.cos(ax)
            out[o++] = fx + (cx - fx) * c
            out[o++] = fy + (ry - fy) * c
            out[o++] = -FLAT_DIST + (cz + FLAT_DIST) * c
        }
        for (iy in 0 until rows) {
            for (ix in 0..cols) {
                val u = ix.toFloat() / cols
                emit(u, 1f - iy.toFloat() / rows)
                emit(u, 1f - (iy + 1).toFloat() / rows)
            }
            if (iy < rows - 1) {
                emit(1f, 1f - (iy + 1).toFloat() / rows)
                emit(0f, 1f - (iy + 1).toFloat() / rows)
            }
        }
        return out
    }

    /** Web screen mesh: today's plain indexed grid (unshaped), shared by
     *  drawWeb and the gaze mesh lookup. Shaping never touches it. */
    private fun buildWebMesh(): Mesh {
        if (screenCurve > 0f) {
            val (w, h) = screenDims()
            return screenCapMesh(screenCurve, w, h)
        }
        return gridQuadP(
            floatArrayOf(-1f, 1f, 0f), floatArrayOf(1f, 1f, 0f),
            floatArrayOf(-1f, -1f, 0f), floatArrayOf(1f, -1f, 0f),
            24, 12, flipV = true
        )
    }

    /** Ensure the video geometry for the current density/layout/curve/
     *  shaping, and the plain web mesh. Called every frame; rebuilds are
     *  rare (settings edits, video changes). */
    private fun ensureMeshes() {
        val cur = mode
        if (cur == Mode.WEB) {
            var wk = "web=${webPageW}x$webPageH"
            if (screenCurve > 0f) {
                val (w, h) = screenDims()
                wk += "|c=" + ((screenCurve * 100f) + 0.5f).toInt() +
                    "|w=" + ((w * 1000f) + 0.5f).toInt() + "|h=" + ((h * 1000f) + 0.5f).toInt()
            }
            if (webMeshKey != wk) {
                webMesh = buildWebMesh()
                webMeshKey = wk
            }
            return
        }
        val (c, r) = domeCounts()
        var key = "c=$c|st=${stereo.name}|sh=$shapingRevision|q=$panoQuality"
        if (effProj() == Projection.FLAT && screenCurve > 0f) {
            val (w, h) = screenDims()
            key += "|c=" + ((screenCurve * 100f) + 0.5f).toInt() +
                "|w=" + ((w * 1000f) + 0.5f).toInt() + "|h=" + ((h * 1000f) + 0.5f).toInt()
        }
        if (videoGeomKey == key) return
        buildVideoGeom(key)
    }

    /** (Re)build the whole video geometry set: positions plus baked texcoord
     *  buffers (shaping folded in) plus fisheye deltas. Keyed on density,
     *  layout, curve dims and shaping revision; all small, all rare. */
    private fun buildVideoGeom(key: String) {
        val (c, r) = domeCounts()
        val (wTop, wBot) = domeBandWeights(c, r)
        // Flat rects (v-up): 2-D full, SBS halves, TB halves (eye 1 = v
        // 0..0.5 exactly like the old shader-side windowing).
        val flatBase = arrayOf(
            flatRectTex(0f, 1f, 1f, 0f),
            flatRectTex(0f, 1f, 0.5f, 0f),
            flatRectTex(0.5f, 1f, 1f, 0f),
            flatRectTex(0f, 0.5f, 1f, 0f),
            flatRectTex(0f, 1f, 1f, 0.5f)
        )
        val flatTex = Array(5) { i -> fb(bakeVideoTex(flatBase[i]).first) }
        val domePos = mapOf(
            180f to fb(domePosStrip(180f, c, r)),
            220f to fb(domePosStrip(220f, c, r)),
            270f to fb(domePosStrip(270f, c, r)),
            360f to fb(domePosStrip(360f, c, r))
        )
        val domeTex = Array(5) { i -> fb(bakeVideoTex(domeTexStrip(i, c, r, wTop, wBot)).first) }
        // Fisheye deltas over the 180° span per eye buffer (same baked
        // values the equirect path samples).
        val fishDelta = Array(2) { e ->
            val li = when (stereo) {
                Stereo.SBS -> 1 + e
                Stereo.TB -> 3 + e
                else -> 0
            }
            fb(bakeVideoTex(domeTexStrip(li, c, r, wTop, wBot)).second)
        }
        var capPos: FloatBuffer? = null
        var capTex: Array<FloatBuffer>? = null
        var capCount = 0
        // Curved cap (video FLAT with curve dialled): positions carry world
        // size like screenCapMesh; texcoords are full-frame windows like
        // flat, w = 1 (caps were never projectively corrected).
        if (screenCurve > 0f) {
            val (w, h) = screenDims()
            capPos = fb(capStripPos(w, h, screenCurve))
            capTex = Array(5) { i -> fb(bakeVideoTex(capStripTex(i, 48, 24)).first) }
            capCount = 2 * (49 * 24 + 23)
        }
        // (Web builds its own from screenCapMesh; this set is video-only.)
        videoGeom = VideoGeom(
            flatTex = flatTex,
            capPos = capPos, capTex = capTex, capCount = capCount,
            domePos = domePos, domeTex = domeTex, fishDelta = fishDelta,
            domeCount = domeVertexCount(c, r)
        )
        videoGeomKey = key
        FileLog.i("SweepVR-mesh", "video geom built key=$key")
    }

    /** Bake the normalized weighted-average shaping grid into a vec4
     *  texcoord array (texture space, so head tracking via MVP is
     *  unaffected). Returns the shaped copy plus the per-vertex delta
     *  (-dx, +dy) for paths that compute their own coords (fisheye);
     *  inputs untouched. Combining (mean of weighted opacities), signs and
     *  the (u, 1-v) grid sampling are unchanged from the Mesh version, so
     *  existing shapes warp identically. */
    private fun bakeVideoTex(src: FloatArray): Pair<FloatArray, FloatArray> {
        val shaped = src.copyOf()
        val delta = FloatArray(src.size / 2)
        val active = shapingActive
        val n = if (active.isNotEmpty()) active[0].n else 0
        var wsum = 0f; var wcnt = 0
        for (a in active) if (a.n == n) { wsum += a.weight01; wcnt++ }
        if (active.isEmpty() || wsum <= 0f) return shaped to delta
        // Combine on the fly per vertex (meshes are small: 1706 verts at
        // 60x15, 6 on the flat quad).
        val count = src.size / 4
        for (k in 0 until count) {
            val u = src[k * 4]
            val v = src[k * 4 + 1]
            // bilinear sample of averaged offsets at grid coords (u, 1-v)
            var dx = 0f; var dy = 0f
            val gx = (u.coerceIn(0f, 1f) * (n - 1)).coerceIn(0f, (n - 1).toFloat())
            val gv = ((1f - v).coerceIn(0f, 1f) * (n - 1)).coerceIn(0f, (n - 1).toFloat())
            val x0 = gx.toInt().coerceAtMost(n - 2); val y0 = gv.toInt().coerceAtMost(n - 2)
            val fx = gx - x0; val fy = gv - y0
            for (a in active) {
                if (a.n != n) continue
                // MEAN of weighted offsets (not normalized average): weights
                // are absolute opacities, so a lone shape at 50% gives half
                // displacement. (Normalized average pinned every nonzero
                // weight to full strength — the slider did nothing.)
                val w = a.weight01 / wcnt
                fun at(ix: Int, iy: Int, arr: FloatArray) = arr[iy * n + ix]
                val ox = (at(x0, y0, a.ox) * (1 - fx) + at(x0 + 1, y0, a.ox) * fx) * (1 - fy) +
                         (at(x0, y0 + 1, a.ox) * (1 - fx) + at(x0 + 1, y0 + 1, a.ox) * fx) * fy
                val oy = (at(x0, y0, a.oy) * (1 - fx) + at(x0 + 1, y0, a.oy) * fx) * (1 - fy) +
                         (at(x0, y0 + 1, a.oy) * (1 - fx) + at(x0 + 1, y0 + 1, a.oy) * fx) * fy
                dx += w * ox; dy += w * oy
            }
            shaped[k * 4] = u - dx
            shaped[k * 4 + 1] = v + dy
            // Same displacement, as a varying for the fisheye path.
            delta[k * 2] = -dx
            delta[k * 2 + 1] = dy
        }
        return shaped to delta
    }

    private fun fb(a: FloatArray): FloatBuffer =
        ByteBuffer.allocateDirect(a.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(a); position(0) }
    private fun sb(a: ShortArray): java.nio.ShortBuffer =
        ByteBuffer.allocateDirect(a.size * 2).order(ByteOrder.nativeOrder()).asShortBuffer().apply { put(a); position(0) }

    private fun buildProgram(vs: String, fs: String): Int {
        fun compile(type: Int, src: String, tag: String): Int {
            val s = GLES20.glCreateShader(type)
            GLES20.glShaderSource(s, src)
            GLES20.glCompileShader(s)
            val ok = IntArray(1)
            GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
            if (ok[0] == 0) {
                val log = GLES20.glGetShaderInfoLog(s) ?: "?"
                android.util.Log.e("SweepVR-GL", "$tag compile FAILED: $log")
                try { FileLog.e("SweepVR-GL", "$tag compile FAILED: $log") } catch (_: Throwable) {}
            }
            return s
        }
        val v = compile(GLES20.GL_VERTEX_SHADER, vs, "VERT")
        val f = compile(GLES20.GL_FRAGMENT_SHADER, fs, "FRAG")
        return GLES20.glCreateProgram().also {
            GLES20.glAttachShader(it, v); GLES20.glAttachShader(it, f); GLES20.glLinkProgram(it)
            val ok = IntArray(1)
            GLES20.glGetProgramiv(it, GLES20.GL_LINK_STATUS, ok, 0)
            if (ok[0] == 0) {
                val log = GLES20.glGetProgramInfoLog(it) ?: "?"
                android.util.Log.e("SweepVR-GL", "link FAILED: $log")
                try { FileLog.e("SweepVR-GL", "link FAILED: $log") } catch (_: Throwable) {}
            }
            GLES20.glDeleteShader(v); GLES20.glDeleteShader(f)
        }
    }
}
