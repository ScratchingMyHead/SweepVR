/* SweepVR Player — GPL-3.0-only.
 * See LICENSE in the repository root.
 */
package net.sweepvr.player.sweep

/**
 * A dropdown opened by sweeping into a button from the side.
 *
 * This holds the behaviour and the geometry; drawing lives with whoever owns
 * the Canvas. It has no Android imports, so every rule below - which edges
 * open it, where the pane goes, when a scroll arrow appears, when a release
 * counts as a choice rather than an accident - is testable on the JVM.
 *
 * What the gesture means:
 *  - enter the button from the left or right and the pane opens
 *  - move onto the pane and the cursor follows the reticle, no dwell
 *  - drift off sideways and the release is a *choice*; go up or down and it
 *    is a cancel
 *  - a release sideways only counts as a choice if the reticle actually
 *    reached the pane first, so sweeping across the button on the way to
 *    something else cannot commit whatever row was last under it
 *
 * What the dwell fallback means ([dwellMode], sweep disabled):
 *  - dwell anywhere on the button and the pane opens
 *  - the cursor still follows free - movement is not the enemy, only
 *    activation by movement is
 *  - dwell on a row commits it; leaving the pane cancels. Two dwells: one
 *    to open, one to select.
 */
class DropdownControl(
    var rowH: Float = 64f,
    var arrowH: Float = 52f,
    var gap: Float = 8f,
    /** Half-width of the pane. Measured from the longest label by the owner,
     *  which is the only part that needs a text measurer. */
    var paneHalfW: Float = 120f
) {
    data class Item(val label: String, val value: String)

    var items: List<Item> = emptyList()
    var iconRect: Rect = Rect(0f, 0f, 0f, 0f)

    /** Leeway outside the control before letting go, in owner units. Exit only. */
    var leeway: Float = 24f
    /** How far clear the reticle must get before the button can be re-entered. */
    var rearm: Float = 40f
    /** How near a corner counts as a corner. There is no entry margin here and
     *  there must never be one - see THE RULE in SweepTypes.kt. */
    var cornerFraction: Float = 0.14f

    var open: Boolean = false; private set
    var cursor: Int = -1; private set
    var scroll: Int = 0; private set
    /** Which scroll arrow the reticle is on: 0 none, -1 up, +1 down. */
    var arrow: Int = 0; private set
    var pane: Rect? = null; private set
    /** Rows currently shown, and whether the list overflows. The renderer
     *  draws from these so the picture cannot disagree with the hit test. */
    var visibleCount: Int = 0; private set
    var needsArrows: Boolean = false; private set

    /** Dwell mode (sweep disabled): two dwells, open then select. The sweep
     *  path below is untouched when this is false. */
    var dwellMode: Boolean = false
    /** Still-gaze time to open/commit. Mirrors the page/row dwell. */
    var dwellMs: Long = 1500L
    /** 0..1 fill of the current dwell (icon before open, row after), for
     *  the reticle. */
    var dwellProgress: Float = 0f
        private set

    /** Fired with the chosen item when a release counts as a choice. */
    var onCommit: ((Item) -> Unit)? = null
    /** Trace hook, so the owner can log without this depending on anything. */
    var onTrace: ((String) -> Unit)? = null

    private val engine = SweepEngine()
    private var reached = false

    /** Built per frame from the tunables above, so a caller can change
     *  leeway or rearm at any time without reaching into the engine. */
    private fun config() = SweepConfig(
        entrySides = setOf(Side.Left, Side.Right),
        leeway = leeway, rearm = rearm,
        refuseCorners = true, cornerFraction = cornerFraction,
        tieBreak = TieBreak.REFUSE
    )
    /** The button and the pane are one target once open. They have to be:
     *  the button sits above the pane, so testing the pane alone made every
     *  move down off the button read as an upward exit and cancelled the
     *  pane the instant it opened. */
    private fun hitRect(): Rect {
        val p = pane
        return if (open && p != null) iconRect.union(p) else iconRect
    }

    fun step(x: Float, y: Float, dtMs: Long, still: Boolean = true): Boolean {
        if (items.isEmpty() || iconRect.isEmpty) { close(); return false }
        val at = Pt(x, y)
        if (dwellMode) return dwellStep(at, still, dtMs)
        return when (val ev = engine.step(at, hitRect(), config())) {
            is SweepEvent.Entered -> {
                if (ev.side == Side.Left || ev.side == Side.Right) openPane()
                true
            }
            is SweepEvent.Engaged -> { move(at, dtMs); true }
            is SweepEvent.Released -> { release(ev.side); true }
            SweepEvent.Idle -> open
        }
    }

    /**
     * Dwell twin of the sweep step above: dwell the whole icon rect to
     * open, dwell a row to commit, leave to cancel. Returns true while
     * open or filling (owns the frame, same contract as sweep).
     */
    private var dwellFill = 0f

    private fun dwellStep(at: Pt, still: Boolean, dtMs: Long): Boolean {
        if (!open) {
            if (!iconRect.contains(at)) {
                if (dwellFill > 0f) onTrace?.invoke("dwell exit reset")
                dwellFill = 0f
                dwellProgress = 0f
                return false
            }
            if (still) dwellFill = minOf(1f, dwellFill + dtMs.toFloat() / dwellMs.coerceAtLeast(1L))
            else dwellFill = maxOf(0f, dwellFill - dtMs / 600f)
            dwellProgress = dwellFill
            if (dwellFill >= 1f) {
                dwellFill = 0f
                openPane()
            }
            return dwellFill > 0f || open
        }
        // Open: cursor tracks free (arrows scroll on hover, as in sweep).
        val before = cursor
        move(at, dtMs)
        if (cursor != before) dwellFill = 0f
        val p = pane
        // The icon rect is home base: resting on it after opening holds the
        // pane with no progress either way. Only leaving BOTH icon and pane
        // (past the leeway) cancels - the pane hangs a gap below the icon,
        // so testing the pane alone cancelled on the opening frame itself
        // and the list flashed up and vanished.
        if (p == null || (!p.contains(at, leeway) && !iconRect.contains(at, leeway))) {
            dwellProgress = 0f
            onTrace?.invoke("dwell cancel")
            close()
            return true
        }
        // On the icon, an arrow, or between rows: hold, no progress.
        if (!p.contains(at) || arrow != 0 || cursor !in items.indices) {
            dwellFill = 0f
            dwellProgress = 0f
            return true
        }
        if (still) dwellFill = minOf(1f, dwellFill + dtMs.toFloat() / dwellMs.coerceAtLeast(1L))
        else dwellFill = maxOf(0f, dwellFill - dtMs / 600f)
        dwellProgress = dwellFill
        if (dwellFill >= 1f) {
            val pick = items[cursor]
            onTrace?.invoke("dwell commit cursor=$cursor ${pick.value}")
            close()
            dwellProgress = 0f
            onCommit?.invoke(pick)
        }
        return true
    }

    /** Recompute and expose the visible window for the renderer. */
    private fun publishView() {
        val p = pane
        if (!open || p == null) { visibleCount = 0; needsArrows = false; return }
        val n = items.size
        val vis = visibleRows(p.top > iconRect.top)
        visibleCount = vis
        needsArrows = vis < n
    }

    private fun openPane() {
        pane = layoutPane()
        if (pane == null) { onTrace?.invoke("no room for pane"); close(); return }
        open = true
        reached = false
        publishView()
        onTrace?.invoke("open n=${items.size}")
    }

    /** How many rows the pane can show where it sits, arrows included. */
    private fun visibleRows(below: Boolean): Int {
        val avail = if (below) texH - (iconRect.bottom + gap) else iconRect.top - gap
        val n = items.size
        if (avail / rowH >= n) return n
        return ((avail - 2 * arrowH) / rowH).toInt().coerceAtLeast(1)
    }

    /** Panel height, set by the owner. Kept out of the constructor because
     *  it is a property of the surface, not of the control. */
    var texH: Float = 1024f

    private fun layoutPane(): Rect? {
        val n = items.size
        if (n == 0) return null
        val cx = iconRect.left + iconRect.width * 0.5f
        fun place(top: Float, vis: Int, arrows: Boolean): Rect {
            val h = vis * rowH + if (arrows) 2 * arrowH else 0f
            return Rect(cx - paneHalfW, top, cx + paneHalfW, top + h)
        }
        val visBelow = visibleRows(true)
        val arwBelow = visBelow < n
        val below = iconRect.bottom + gap
        if (below + visBelow * rowH + (if (arwBelow) 2 * arrowH else 0f) <= texH)
            return place(below, visBelow, arwBelow)
        val visAbove = visibleRows(false)
        val arwAbove = visAbove < n
        val top = iconRect.top - gap - (visAbove * rowH + if (arwAbove) 2 * arrowH else 0f)
        if (top >= 0f) return place(top, visAbove, arwAbove)
        return null
    }

    private fun move(at: Pt, dtMs: Long) {
        val p = pane ?: return
        if (!p.contains(at)) return
        reached = true
        val n = items.size
        val vis = visibleRows(p.top > iconRect.top)
        val scrolls = vis < n
        val top = p.top + if (scrolls) arrowH else 0f
        val bot = p.bottom - if (scrolls) arrowH else 0f
        arrow = when {
            !scrolls -> 0
            at.y < top -> -1
            at.y > bot -> 1
            else -> 0
        }
        if (arrow != 0) {
            // Hovering an arrow scrolls the list, continuously.
            val maxS = (n - vis).coerceAtLeast(0)
            val before = scroll
            scroll = (scroll + arrow * dtMs / 220f).toInt().coerceIn(0, maxS)
            if (scroll != before) onTrace?.invoke("scroll=$scroll/$maxS dir=$arrow")
            // Keep the cursor on screen while the list moves under it.
            if (cursor < scroll) cursor = scroll
            if (cursor >= scroll + vis) cursor = scroll + vis - 1
            return
        }
        cursor = (scroll + ((at.y - top) / rowH).toInt()).coerceIn(0, n - 1)
    }

    private fun release(side: Side) {
        // A sideways release is a choice only if the list was reached; going
        // up or down is always a cancel. Without the reach test, sweeping
        // across the button on the way to something else committed whatever
        // row the cursor last held.
        val sideways = side == Side.Left || side == Side.Right
        val pick = if (sideways && reached && cursor in items.indices) items[cursor] else null
        onTrace?.invoke(
            if (pick != null) "commit cursor=$cursor ${pick.value}"
            else "cancel ${side.name.lowercase()} cursor=$cursor reached=$reached")
        close()
        if (pick != null) onCommit?.invoke(pick)
    }

    fun close() {
        open = false
        pane = null
        visibleCount = 0
        needsArrows = false
        cursor = -1
        scroll = 0
        arrow = 0
        reached = false
        dwellFill = 0f
        dwellProgress = 0f
        engine.reset()
    }
}
