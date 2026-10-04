/* SweepVR Player — GPL-3.0-only.
 * See LICENSE in the repository root.
 */
package net.sweepvr.player.sweep

/**
 * A value you drag along a track: a scrollbar thumb, a slider, a scrub bar.
 *
 * Enter it from either END of its axis, and it snaps to the reticle and
 * follows - or, with [relativeGrab], it simply picks the value up where it
 * was and follows from there. Let go across the axis to commit the value; let go past either end
 * to abandon it and leave the value alone.
 *
 * That last rule is the whole reason this is a shared control rather than
 * scrollbar-specific code. Exiting at an end used to jump the page to the top
 * or the bottom, which is a large, hard-to-undo move triggered by the easiest
 * slip to make - carrying the reticle too far while reaching for something
 * else. Dragging is easy to start by accident and now equally easy to back
 * out of, so a mistake costs nothing.
 *
 * The thumb rect is derived from the value, never stored alongside it, so the
 * shape the renderer draws and the shape the entry test hits cannot drift
 * apart.
 */
class ValueDragControl(
    var axis: Axis = Axis.VERTICAL,
    /** How much of the track the thumb occupies, as a fraction. */
    var thumbFraction: Float = 0.24f,
    var leeway: Float = 0.03f,
    var rearm: Float = 0.06f,
    /** How far past either END of the track the reticle may go before that
     *  counts as a deliberate exit rather than an overshoot. */
    var endLeeway: Float = 40f,
    /**
     * Map the reticle to the thumb's CENTRE (true), or straight across the
     * track (false).
     *
     * The scrollbar uses false, because that is what its working version did:
     * it snapped the thumb so its leading edge sat under the reticle. A centre
     * snap is defensible on a fresh control, but it moves the point on the
     * track that means each value, and the scrollbar was tuned by feel - so
     * changing it silently changes the gesture. The dropdown is unaffected.
     */
    var centreSnap: Boolean = true,
    /**
     * Carry on from where you grabbed (true), or snap the value to the
     * reticle on entry (false, the default).
     *
     * Snapping is right for a scrollbar, where the thumb is only a handle
     * on a position the page owns anyway. A seek bar is the other way
     * round: the thumb IS the position, so a snap moves the video the
     * moment the gesture is latched and before the user has moved at all -
     * and always by however far from the centre the grab happened to land.
     * With this on, entry records the offset between the value and the
     * reticle and the value keeps that offset for the whole drag, so
     * latching changes nothing and every movement after it is 1:1.
     */
    var relativeGrab: Boolean = false,
    var cornerFraction: Float = 0f
) {
    enum class Axis { VERTICAL, HORIZONTAL }

    /** The full travel of the control, in the same space as the rects. */
    var track: Rect = Rect(0f, 0f, 0f, 0f)
    /** Where the thumb currently is, 0 = start of the track, 1 = end. */
    var value: Float = 0f
        private set

    /**
     * Set the value from outside a gesture - the page reporting where it
     * actually is. Deliberately does nothing while a drag is in progress:
     * that is the poll writing back the page's old position, which yanked
     * the thumb out from under the reticle mid-drag. The owner is expected
     * to skip it entirely while held, and this is the backstop.
     */
    fun syncTo(v: Float) {
        if (engaged) return
        value = v.coerceIn(0f, 1f)
        valueBeforeDrag = value
    }
    /** What the value was before this drag, so a cancel can put it back. */
    var valueBeforeDrag: Float = 0f
        private set

    var engaged: Boolean = false; private set

    /** The offset a relative grab carries: value minus the value the grab
     *  point maps to. Zero while no relative drag has happened. */
    private var grabOffset = 0f

    /** Fired with the value to commit to, on a sideways release. */
    var onCommit: ((Float) -> Unit)? = null
    /** Fired on a release past either end: no value change at all. */
    var onCancel: (() -> Unit)? = null
    var onTrace: ((String) -> Unit)? = null

    private val engine = SweepEngine()

    private val entrySides: Set<Side>
        get() = if (axis == Axis.VERTICAL) setOf(Side.Top, Side.Bottom)
                else setOf(Side.Left, Side.Right)

    private fun config() = SweepConfig(
        entrySides = entrySides,
        leeway = leeway, rearm = rearm,
        refuseCorners = cornerFraction > 0f, cornerFraction = cornerFraction,
        tieBreak = TieBreak.REFUSE
    )

    /** The thumb, derived from [value]. This is the rect the entry test uses
     *  and the one the renderer should draw. */
    fun thumbRect(): Rect {
        if (track.isEmpty) return Rect(0f, 0f, 0f, 0f)
        val f = thumbFraction.coerceIn(0.02f, 1f)
        if (axis == Axis.VERTICAL) {
            val travel = track.height * (1f - f)
            val top = track.top + travel * value.coerceIn(0f, 1f)
            return Rect(track.left, top, track.right, top + track.height * f)
        }
        val travel = track.width * (1f - f)
        val left = track.left + travel * value.coerceIn(0f, 1f)
        return Rect(left, track.top, left + track.width * f, track.bottom)
    }

    /**
     * Advance one frame. [x],[y] in control space, y-down; the owner converts
     * if its own space disagrees.
     *
     * Returns true while the control owns the frame.
     *
     * ## Why this does not use the engine for its release
     *
     * The engine's release rule is "stop being within the leeway of the
     * control", which is right for a dropdown - you let go by moving away.
     * It is exactly wrong for a drag, where the control follows the hand: the
     * reticle moving down drags the thumb down with it, so it is never
     * actually outside, and any movement that outruns the thumb releases the
     * gesture instead of moving it. Measured on a 700px track: grabbing at
     * y=219 and moving to y=450 left the thumb 112px behind against a 30px
     * leeway, so a drag could not travel at all.
     *
     * The engine is used for what it is good at here - deciding the entry,
     * with the corner and tie handling that took three bug fixes - and the
     * drag owns its own release, which is a question about the TRACK rather
     * than the thumb: sideways off the track commits, past either end cancels.
     */
    fun step(x: Float, y: Float): Boolean {
        if (track.isEmpty) { reset(); return false }
        val at = Pt(x, y)
        if (!engaged) {
            return when (val ev = engine.step(at, thumbRect(), config())) {
                is SweepEvent.Entered -> {
                    valueBeforeDrag = value
                    if (relativeGrab) grabOffset = value - valueRaw(at)
                    else value = valueAt(at)
                    engaged = true
                    onTrace?.invoke("grab ${"%.2f".format(value)} via ${ev.side}")
                    true
                }
                else -> false
            }
        }
        // Held: the reticle may be anywhere near the track. Movement always
        // wins; the only question is whether it has left the track's box.
        val out = releaseSide(at)
        if (out != null) { release(out, at); return true }
        // Deliberately not traced per frame: at 60fps it is 60 lines a second
        // and it buried the one line that matters, the drop.
        value = if (relativeGrab) (valueRaw(at) + grabOffset).coerceIn(0f, 1f)
                else valueAt(at)
        return true
    }

    /**
     * Has the reticle left the track, and if so which way?
     *
     * Perpendicular to the axis is a commit; along the axis past either end
     * is a cancel. [endLeeway] is the tolerance for overshooting an end, so a
     * small carry-over keeps dragging and a deliberate one abandons.
     */
    private fun releaseSide(at: Pt): Side? {
        if (axis == Axis.VERTICAL) {
            if (at.x < track.left - leeway) return Side.Left
            if (at.x > track.right + leeway) return Side.Right
            if (at.y < track.top - endLeeway) return Side.Top
            if (at.y > track.bottom + endLeeway) return Side.Bottom
        } else {
            if (at.y < track.top - leeway) return Side.Top
            if (at.y > track.bottom + leeway) return Side.Bottom
            if (at.x < track.left - endLeeway) return Side.Left
            if (at.x > track.right + endLeeway) return Side.Right
        }
        return null
    }

    /**
     * Where on the track a point maps to, 0 at the start and 1 at the end.
     *
     * Measured to the thumb's CENTRE, not its leading edge. Mapping the
     * leading edge meant the thumb's own centre always sat a half-thumb
     * beyond the value it represented - on a 700px track with a 238px thumb
     * that is 0.26 - so grabbing a thumb dead centre snapped it a quarter of
     * the way along, and the reticle came to rest on the thumb's edge rather
     * than inside it.
     */
    private fun valueAt(p: Pt): Float = valueRaw(p).coerceIn(0f, 1f)

    /**
     * [valueAt] without the clamp.
     *
     * The clamp is right for a snap - a value past either end is not a value
     * - but it destroys a relative grab: take it on entry and the offset is
     * pinned by wherever the reticle happened to be, so the drag cannot run
     * into either end from the inside. Both forms clamp at the last moment
     * instead, which is also what stops the drag overshooting on its own.
     */
    private fun valueRaw(p: Pt): Float {
        val f = thumbFraction.coerceIn(0.02f, 1f)
        if (!centreSnap) {
            return if (axis == Axis.VERTICAL) {
                if (track.height <= 0f) 0f else (p.y - track.top) / track.height
            } else {
                if (track.width <= 0f) 0f else (p.x - track.left) / track.width
            }
        }
        return if (axis == Axis.VERTICAL) {
            val thumbH = track.height * f
            val travel = track.height - thumbH
            if (travel <= 0f) 0f else (p.y - track.top - thumbH * 0.5f) / travel
        } else {
            val thumbW = track.width * f
            val travel = track.width - thumbW
            if (travel <= 0f) 0f else (p.x - track.left - thumbW * 0.5f) / travel
        }
    }

    private fun release(side: Side, at: Pt) {
        engaged = false
        val along = if (axis == Axis.VERTICAL) side == Side.Top || side == Side.Bottom
                   else side == Side.Left || side == Side.Right
        if (along) {
            // Past an end: abandon, and put the value back where it was. A
            // slip that carried the reticle off the end should cost nothing -
            // not a jump to the top or bottom of the document, and not a
            // thumb left parked at the limit either.
            value = valueBeforeDrag
            onTrace?.invoke("cancel ${side.name.lowercase()} value=${"%.2f".format(value)}")
            onCancel?.invoke()
        } else {
            val v = value.coerceIn(0f, 1f)
            onTrace?.invoke("drop ${side.name.lowercase()} value=${"%.2f".format(v)}")
            onCommit?.invoke(v)
        }
    }

    fun reset() {
        engine.reset()
        engaged = false
    }

    /** Seed the engine's history with where the reticle is now, so a reset
     *  made while it already sits on the thumb cannot turn the next frame
     *  into an entry. See [SweepEngine.prime]. */
    fun prime(x: Float, y: Float) {
        engine.prime(Pt(x, y))
    }
}
