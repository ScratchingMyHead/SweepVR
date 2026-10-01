/* SweepVR Player — GPL-3.0-only.
 * See LICENSE in the repository root.
 */
package net.sweepvr.player.sweep

/**
 * A dwell-to-fire button: the non-sweep alternative to [MomentaryControl]
 * and the toolbar's tap-commit [RepeatControl]s.
 *
 * Sweep logic is deliberately NOT reused here. When sweep is disabled the
 * activation rules are rejected wholesale (a guard at the call site never
 * steps the sweep controls at all) and this owns the frame instead: the
 * whole rect counts, no sides, no corners, no angle. Still gaze fills
 * progress to 1 over [dwellMs] and fires once; moving drains; leaving
 * resets, so holding the gaze fires exactly once per visit.
 *
 * step() returns the current progress 0..1 (for the reticle shrink), so
 * the owner can both claim the frame and animate from one value.
 */
class DwellButton {
    /** The button. The whole rect counts - deliberately no entry sides. */
    var rect: Rect = Rect(0f, 0f, 0f, 0f)

    /** Still-gaze time to fire. Mirrors the page/row dwell. */
    var dwellMs: Long = 1500L

    /** Current fill 0..1. The owner draws the held state from this. */
    var progress: Float = 0f
        private set

    /** Called exactly once per visit, when progress first reaches 1. */
    var onFire: (() -> Unit)? = null
    /** Trace hook, so the owner can log without this depending on anything. */
    var onTrace: ((String) -> Unit)? = null

    /**
     * Advance one frame. Returns progress 0..1; > 0 means the control owns
     * the frame (same claim contract as the sweep controls, so the page
     * dwell does not fire underneath a button the gaze is filling).
     */
    fun step(x: Float, y: Float, still: Boolean, dtMs: Long): Float {
        val at = Pt(x, y)
        if (rect.isEmpty) { reset(); return 0f }
        if (!rect.contains(at)) {
            if (progress > 0f) onTrace?.invoke("exit reset")
            reset()
            return 0f
        }
        if (!fired) {
            if (still) {
                progress = minOf(1f, progress + dtMs.toFloat() / dwellMs.coerceAtLeast(1L))
            } else {
                // Same drain rate as the row dwell: jitter dents progress,
                // it does not zero the visit.
                progress = maxOf(0f, progress - dtMs / 600f)
            }
            if (progress >= 1f) {
                progress = 1f
                fired = true
                onTrace?.invoke("fire")
                onFire?.invoke()
            }
        }
        return progress
    }

    /** Forget everything. Never fires: this is not a user release. */
    fun reset() {
        progress = 0f
        fired = false
    }

    private var fired = false
}
