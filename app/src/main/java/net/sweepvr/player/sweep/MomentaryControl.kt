/* SweepVR Player — GPL-3.0-only.
 * See LICENSE in the repository root.
 */
package net.sweepvr.player.sweep

/**
 * A button that arms on entry and fires on leaving up or down. Back,
 * forward, reload, home, menu - controls in a row, where sliding along the
 * row to reach a far button must trigger nothing en route.
 *
 * The gesture, exactly:
 *  - enter from the left or the right, on the button itself: ARMED. The
 *    button shows it (blue), the frame is claimed, nothing fires.
 *  - leave through the top or bottom: COMMIT. Fires once.
 *  - leave through the left or right: CANCEL. Disarms silently.
 *  - hold still: nothing. Parking on a button is not a choice.
 *
 * Entry is the dropdown's rule verbatim - swept in from the sides, corners
 * refused, no margin. See THE RULE at the top of [SweepTypes.kt].
 */
class MomentaryControl {
    /** The button. Entry is judged against this and nothing else. */
    var rect: Rect = Rect(0f, 0f, 0f, 0f)

    /** Ms after a commit during which a fresh entry is swallowed, not armed.
     *  Same tremor guard as [RepeatControl]: a head wobble steps out past
     *  the edge and straight back in, and without this every out-and-back
     *  could commit again - on a back button that means two pages, not one.
     *  A cancel starts no standoff: it left no effect to duplicate. */
    var reentryStandoffMs: Long = 150L
    /** How near a corner counts as a corner. Not an entry margin - it only
     *  carves the rounded corners off the valid entry edges. */
    var cornerFraction: Float = 0.14f

    /** True while armed: entered, not yet left. The owner draws the held
     *  state from this so the picture cannot disagree with the gesture. */
    var active: Boolean = false; private set

    /** Called exactly once per commit, with the side committed through
     *  (Top or Bottom - the only two that commit). */
    var onFire: ((Side) -> Unit)? = null
    /** Trace hook, so the owner can log without this depending on anything. */
    var onTrace: ((String) -> Unit)? = null

    private val engine = SweepEngine()
    private var cooledMs: Long = Long.MAX_VALUE

    private fun config() = SweepConfig(
        entrySides = setOf(Side.Left, Side.Right),
        // No hold exists, so leeway would only delay the release nobody
        // reads; zero keeps the engine honest. Rearm likewise: the standoff
        // below is the re-entry guard, in time rather than distance.
        leeway = 0f, rearm = 0f,
        refuseCorners = true, cornerFraction = cornerFraction,
        tieBreak = TieBreak.REFUSE
    )

    /**
     * Advance one frame. Returns true while armed, so the owner can claim
     * the frame and the page dwell does not fire underneath a button the
     * gaze is sitting on.
     */
    fun step(x: Float, y: Float, dtMs: Long): Boolean {
        val at = Pt(x, y)
        if (rect.isEmpty) { reset(); return false }
        if (cooledMs < reentryStandoffMs)
            cooledMs = minOf(reentryStandoffMs, cooledMs + dtMs.coerceAtLeast(0L))
        return when (val ev = engine.step(at, rect, config())) {
            is SweepEvent.Entered -> {
                if (cooledMs < reentryStandoffMs) {
                    onTrace?.invoke("standoff swallow")
                    engine.reset()
                    false
                } else {
                    active = true
                    onTrace?.invoke("arm ${ev.side.name.lowercase()} " +
                        "at=${"%.3f".format(at.x)},${"%.3f".format(at.y)}")
                    true
                }
            }
            is SweepEvent.Engaged -> active
            is SweepEvent.Released -> {
                val committing = active &&
                    (ev.side == Side.Top || ev.side == Side.Bottom)
                active = false
                if (committing) {
                    cooledMs = 0L
                    onTrace?.invoke("commit exit ${ev.side.name.lowercase()}")
                    onFire?.invoke(ev.side)
                } else {
                    onTrace?.invoke("cancel exit ${ev.side.name.lowercase()}")
                }
                committing
            }
            SweepEvent.Idle -> {
                // Engine idle while armed means the ground moved (rect went
                // empty mid-hold is handled above; this is the remainder):
                // disarm without firing.
                active = false
                false
            }
        }
    }

    /** Forget everything. For when a control is torn down or the surface
     *  under the reticle is replaced mid-gesture. Never fires, never starts
     *  a standoff: this is not a user release. */
    fun reset() {
        engine.reset()
        active = false
        cooledMs = Long.MAX_VALUE
    }
}
