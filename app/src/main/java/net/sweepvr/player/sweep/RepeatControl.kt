/* SweepVR Player — GPL-3.0-only.
 * See LICENSE in the repository root.
 */
package net.sweepvr.player.sweep

/**
 * A button that repeats while it is held: one hit on entry, a pause, then a
 * steady stream of hits until the reticle leaves.
 *
 * This is the scrollbar's up and down arrow, and it is deliberately a
 * [DropdownControl] with the list removed. The two share how they are
 * entered - swept into from the left or the right, on the button itself and
 * nowhere near it, with the corner arcs refused. That entry rule is the whole
 * point and is not restated here; see THE RULE at the top of [SweepTypes.kt].
 * What differs is only what happens after entry, and both of those differences
 * follow from the button having nothing to show.
 *
 * The gesture:
 *  - enter from the left or right and it fires ONCE, immediately
 *  - keep the reticle on it and after [delayMs] it fires again, then again
 *    every [intervalMs], for as long as the reticle stays
 *  - leave and it stops. The reticle does not have to travel far: the leeway
 *    is a couple of pixels, or none at all, because there is no gesture to
 *    complete and nothing to undo. A long exit here would keep scrolling the
 *    page after the user had clearly looked away.
 *
 * [onFire] is called with the count of how many times it has fired this visit,
 * so an owner that wants a different cadence (accumulating scroll, say) can
 * read the total rather than being called an unknown number of times.
 */
class RepeatControl(
    /** Pause after the entry hit, before repeating starts. */
    var delayMs: Long = 400L,
    /** Gap between hits once repeating. */
    var intervalMs: Long = 90L
) {
    /** The button. Entry is judged against this and nothing else - there is no
     *  pane, no union, no growth. A reticle merely near it does not fire. */
    var rect: Rect = Rect(0f, 0f, 0f, 0f)

    /** Leeway outside the button before it stops repeating. Exit only, and
     *  deliberately tiny; see the class comment. */
    var leeway: Float = 0f
    /** Ms after a release during which a fresh entry is swallowed, not fired.
     *
     *  A head tremor steps out past the tiny leeway and straight back in, and
     *  without this every out-and-back re-fires the entry hit - so each grab
     *  scrolled twice before the pause. The standoff swallows only RE-entry:
     *  the geometry (rect, leeway, corners) is untouched, and a deliberate
     *  re-aim, which always takes longer, fires normally. */
    var reentryStandoffMs: Long = 150L
    /** How far clear the reticle must get before a new entry is considered. */
    var rearm: Float = 0f
    /** How near a corner counts as a corner. As with every control, this is
     *  not an entry margin - it only carves the rounded corners off the edges
     *  that are valid entry points. */
    var cornerFraction: Float = 0.14f

    /** True while the reticle is on the button and repeating. The owner draws
     *  its held state from this so the picture cannot disagree with the timing. */
    var active: Boolean = false; private set
    /** True only during the initial pause, between the entry hit and the first
     *  repeat. Distinct from [active] so the owner can show "held, not yet
     *  firing" differently if it wants to. */
    var waiting: Boolean = false; private set
    /** Times fired since this entry, entry hit included. */
    var fireCount: Int = 0; private set

    /** Called on the entry hit and on every repeat. */
    var onFire: (() -> Unit)? = null
    /** Called once when the initial pause expires and repeating begins.
     *  The scrollbar arrows start their smooth glide here - motion while
     *  held is continuous, so the discrete repeat ticks stay ignored. */
    var onRepeatStart: (() -> Unit)? = null
    /** Called when a started repeat ends via release. Never called if the
     *  hold never got past the pause: stopping something that never started
     *  would send phantom stop events down the wire. */
    var onRepeatStop: (() -> Unit)? = null
    /** When true, entry only arms (blue, no fire) and the single hit fires
     *  on leaving up or down; leaving left or right disarms silently. This
     *  is the toolbar gesture: sliding along a button row to reach a far
     *  button must trigger nothing en route.
     *
     *  When false (default), entry fires immediately, preserving the
     *  scrollbar arrows' validated feel. The strip's travel axis is vertical
     *  there, so this rule would mean something different - do not flip it
     *  without re-validating on the headset. */
    var commitOnExit: Boolean = false
    /** Trace hook, so the owner can log without this depending on anything. */
    var onTrace: ((String) -> Unit)? = null

    private val engine = SweepEngine()
    private var heldMs = 0L
    private var sinceFireMs = 0L
    /** Whether the repeat phase (past the pause) has begun this hold. */
    private var repeating = false
    /** Ms since the last release, capped at [reentryStandoffMs]. Starts
     *  expired so the very first entry is never swallowed. */
    private var cooledMs: Long = Long.MAX_VALUE

    private fun config() = SweepConfig(
        entrySides = setOf(Side.Left, Side.Right),
        leeway = leeway, rearm = rearm,
        refuseCorners = true, cornerFraction = cornerFraction,
        tieBreak = TieBreak.REFUSE
    )

    /**
     * Advance one frame. Returns true while the button is held, so the owner
     * can claim the frame and stop the page dwell from also firing underneath.
     *
     * The repeat clock is driven by [dtMs] rather than wall time, so it behaves
     *  identically whatever the frame rate. It starts when the initial pause
     *  ends, never during it, and fires at most once per frame: a long frame
     *  drops the backlog instead of repaying it as a burst.
     */
    fun step(x: Float, y: Float, dtMs: Long): Boolean {
        val at = Pt(x, y)
        if (rect.isEmpty) { reset(); return false }
        if (cooledMs < reentryStandoffMs)
            cooledMs = minOf(reentryStandoffMs, cooledMs + dtMs.coerceAtLeast(0L))
        return when (val ev = engine.step(at, rect, config())) {
            is SweepEvent.Entered -> {
                // Still inside the standoff from a release moments ago: this
                // is the tremor coming back, not a new grab. Swallow it and
                // forget the engine state, so no hold starts and nothing fires.
                if (cooledMs < reentryStandoffMs) {
                    onTrace?.invoke("standoff swallow")
                    engine.reset()
                    false
                } else { enter(ev.side, at); true }
            }
            is SweepEvent.Engaged -> { hold(dtMs); true }
            is SweepEvent.Released -> { leave("exit ${ev.side.name.lowercase()}", ev.side); false }
            SweepEvent.Idle -> {
                // Not held, so the held flag has to be cleared even though the
                // engine only reports the exit once.
                if (active) leave("idle")
                false
            }
        }
    }

    private fun enter(side: Side, at: Pt) {
        active = true
        waiting = true
        repeating = false
        heldMs = 0L
        sinceFireMs = 0L
        if (commitOnExit) {
            // Armed, blue, silent. fireCount stays 0 so the owner's
            // fireCount==1 nudge check cannot mistake arming for a hit; the
            // hit fires in leave(), on a vertical exit, if it ever comes.
            fireCount = 0
            onTrace?.invoke("arm ${side.name.lowercase()} " +
                "at=${"%.3f".format(at.x)},${"%.3f".format(at.y)}")
            return
        }
        fireCount = 1
        // The side and the exact entry point go on the trace: when a hit goes
        // wrong, "entered left at (x,y)" against the drawn square is the whole
        // diagnosis. Tests only match the "enter" prefix, so this stays free.
        onTrace?.invoke("enter ${side.name.lowercase()} " +
            "at=${"%.3f".format(at.x)},${"%.3f".format(at.y)} fire=$fireCount")
        onFire?.invoke()
    }

    private fun hold(dtMs: Long) {
        if (!active) return
        heldMs += dtMs
        if (heldMs < delayMs) {
            // Still inside the initial pause: the repeat clock has not
            // started, so this time banks no credit. Letting sinceFireMs
            // accumulate here is what used to discharge the whole pause as
            // a burst of repeats the instant it ended.
            waiting = true
            return
        }
        waiting = false
        sinceFireMs += dtMs
        // At most one fire per frame. A long frame drops the backlog instead
        // of repaying it: under lag the repeat slows, it never bursts. A
        // scroll control owes one hit per interval, not compound interest.
        if (sinceFireMs >= intervalMs) {
            sinceFireMs = 0L
            if (!repeating) { repeating = true; onRepeatStart?.invoke() }
            fireCount++
            onFire?.invoke()
        }
        if (fireCount > 1) onTrace?.invoke("repeat fire=$fireCount held=${heldMs}ms")
    }

    private fun leave(why: String, exitSide: Side? = null) {
        if (commitOnExit && !repeating && active &&
            (exitSide == Side.Top || exitSide == Side.Bottom)) {
            // Tap-commit: armed on entry, released vertically before ever
            // gliding. fireCount is 1 during the invoke so the owner's
            // nudge check reads it as the single hit it is.
            fireCount = 1
            onTrace?.invoke("tap exit ${exitSide.name.lowercase()}")
            onFire?.invoke()
        } else if (fireCount > 0) {
            onTrace?.invoke("leave $why after $fireCount")
        }
        if (repeating) onRepeatStop?.invoke()
        active = false
        waiting = false
        repeating = false
        fireCount = 0
        heldMs = 0L
        sinceFireMs = 0L
        cooledMs = 0L
    }

    /** Stop and forget. Used when the surface goes away under the control.
     *  This is not a user release, so it must not start a standoff - the
     *  next genuine entry afterwards has to fire. */
    fun reset() {
        leave("reset")
        engine.reset()
        cooledMs = Long.MAX_VALUE
    }
}
