/* SweepVR Player — GPL-3.0-only.
 * See LICENSE in the repository root.
 */
package net.sweepvr.player.sweep

import kotlin.math.abs

/**
 * The sweep gesture engine: you point at a control, and where you come from
 * and go to decides what happens.
 *
 * Deliberately knows nothing about what any control *does*. It reports motion
 * - [SweepEvent.Entered] when the reticle crosses in through a permitted edge,
 * [SweepEvent.Engaged] while it stays, [SweepEvent.Released] when it leaves
 * past the leeway - and the owning behaviour decides what those mean. That is
 * what lets a dropdown and a scrollbar share one implementation, and a
 * keyboard be a third behaviour rather than a rewrite.
 *
 * ## Why the entry test is a containment transition
 *
 * The first version of this decided the entry edge by computing the point at
 * which the frame's movement segment crossed each edge, and needed that
 * parameter to land inside the frame. Against a head-tracked reticle that is
 * luck: on the scrollbar thumb the trace held 112 real outside-then-inside
 * transitions and every one was rejected, because no single frame happened to
 * straddle the line. A later version required the reticle to have been within
 * 0.012 of the edge, which is the same bug with a different constant.
 *
 * This asks two questions that cannot be missed - was the reticle outside last
 * frame, is it inside now - and then measures the entry point against the
 * rect. No timing, no thresholds to tune.
 *
 * ## Why there is no latch
 *
 * Both hand-written versions kept a "have I already decided this" latch, and
 * it caused a different failure in each: latching a refusal froze the verdict
 * so a later valid approach was never evaluated, while latching nothing meant
 * a settled approach could re-trigger. Neither is needed. [Entered] fires only
 * on the outside-to-inside transition, so resting on a control cannot re-fire
 * it, and it is emitted exactly once per approach with no state to go stale.
 *
 * One piece of state IS kept: after a release, [SweepConfig.rearm] holds off
 * the next entry until the reticle has genuinely gone away, so a control that
 * cancels in place does not immediately re-open under the reticle.
 */
class SweepEngine {

    private var active = false
    private var prev = Pt(0f, 0f)
    private var havePrev = false
    private var awaitingRearm = false

    /** Is the reticle currently considered to be holding this control? */
    val engaged: Boolean get() = active

    /**
     * Advance one frame. [rect] is supplied every frame rather than stored,
     * because a control may change its hit area while held - the bookmarks
     * flyout grows to cover the button and the pane together.
     *
     * Coordinates must be y-down; see [Pt].
     */
    fun step(at: Pt, rect: Rect, cfg: SweepConfig): SweepEvent {
        if (rect.isEmpty) {
            val ev = if (active) release(at, rect, cfg) else SweepEvent.Idle
            reset()
            return ev
        }

        if (active) {
            if (rect.contains(at, cfg.leeway)) {
                prev = at; havePrev = true
                return SweepEvent.Engaged
            }
            val ev = release(at, rect, cfg)
            prev = at; havePrev = true
            return ev
        }

        // Not holding it. The re-arm hold is only cleared once the reticle
        // has been far enough away, so a cancel that leaves it sitting just
        // outside the control does not re-trigger on the next frame.
        if (awaitingRearm) {
            if (rect.contains(at, cfg.rearm)) { prev = at; havePrev = true; return SweepEvent.Idle }
            awaitingRearm = false
        }

        // The reticle is on the control and was not on it last frame, so it
        // has just crossed an edge. No margin is applied to either test: see
        // THE RULE in SweepTypes.kt. Being merely near the control is not
        // entry.
        //
        // Which edge it crossed is decided by where the reticle IS, not by
        // how it got there - see entrySide(). The angle of attack is
        // deliberately not read, so a fast diagonal and a slow vertical slide
        // onto the same spot are treated identically.
        if (!rect.contains(at)) { prev = at; havePrev = true; return SweepEvent.Idle }
        if (havePrev && rect.contains(prev)) { prev = at; havePrev = true; return SweepEvent.Idle }

        val side = entrySide(at, prev, rect, cfg) ?: run { prev = at; havePrev = true; return SweepEvent.Idle }
        if (side !in cfg.entrySides) { prev = at; havePrev = true; return SweepEvent.Idle }
        if (cfg.refuseCorners && rect.isNearCorner(at, cfg.cornerFraction)) {
            prev = at; havePrev = true
            return SweepEvent.Idle
        }

        active = true
        prev = at; havePrev = true
        return SweepEvent.Entered(side, at)
    }

    /**
     * Which edge this entry came in through, or null if it is a corner or a
     * diagonal the config refuses.
     *
     * The entry POINT alone is not always enough. Dead centre of a control it
     * is equidistant from all four edges, so every approach straight down the
     * middle of a scrollbar thumb - the most natural one there is - came out
     * as a tie and was refused. The entry point says where the reticle ended
     * up; the PREVIOUS position says where it came from, and that is what
     * disambiguates a centred approach.
     */
    private fun entrySide(at: Pt, from: Pt, rect: Rect, cfg: SweepConfig): Side? {
        val nearest = rect.nearestSide(at)
        if (nearest != null) return nearest
        // A tie: fall back to the axis the reticle was outside on, which is
        // the actual direction of approach.
        val ox = maxOf(rect.left - from.x, from.x - rect.right, 0f)
        val oy = maxOf(rect.top - from.y, from.y - rect.bottom, 0f)
        if (ox > 0f || oy > 0f) {
            val horiz = when (cfg.tieBreak) {
                TieBreak.PREFER_VERTICAL -> oy <= 0f
                else -> ox >= oy
            }
            if (horiz) return if (from.x < rect.left) Side.Left else Side.Right
            return if (from.y < rect.top) Side.Top else Side.Bottom
        }
        val d = rect.sideDistances(at)
        val h = minOf(d.getValue(Side.Left), d.getValue(Side.Right))
        val v = minOf(d.getValue(Side.Top), d.getValue(Side.Bottom))
        return when (cfg.tieBreak) {
            TieBreak.REFUSE -> null
            TieBreak.PREFER_HORIZONTAL -> if (h <= v) Side.Left else Side.Top
            TieBreak.PREFER_VERTICAL -> if (v < h) Side.Top else Side.Left
        }
    }

    private fun release(at: Pt, rect: Rect, cfg: SweepConfig): SweepEvent {
        active = false
        awaitingRearm = cfg.rearm > 0f
        return SweepEvent.Released(exitSide(at, rect, cfg), at)
    }

    /**
     * Which way the reticle left.
     *
     * This is NOT the same question as entry, and answering it the entry way
     * was wrong. Entry asks "which edge is this point nearest", normalised by
     * each axis, which is right because the point is inside and you want the
     * side it came through. Exit asks "which way did it go", and the honest
     * measure is how far past each side it got.
     *
     * On a tall control the two disagree. Releasing 82px to the left of a
     * control that is 428 wide and 308 tall puts the point at 0.19 of the
     * width from the left but only 0.18 of the height from the top - so
     * "nearest edge" said it had gone UP, and a sideways release came back
     * as a cancel. Absolute overshoot says 82px across and 0px down, which is
     * unambiguously sideways.
     */
    private fun exitSide(at: Pt, rect: Rect, cfg: SweepConfig): Side {
        val ox = maxOf(rect.left - at.x, at.x - rect.right, 0f)
        val oy = maxOf(rect.top - at.y, at.y - rect.bottom, 0f)
        if (ox <= 0f && oy <= 0f) {
            // Still inside the control, which means it drifted out through a
            // corner. Nearest edge is the best available answer here.
            return rect.nearestSide(at) ?: Side.Top
        }
        val preferHoriz = when (cfg.tieBreak) {
            TieBreak.PREFER_VERTICAL -> oy <= 0f
            else -> ox >= oy
        }
        if (preferHoriz) return if (at.x < rect.left) Side.Left else Side.Right
        return if (at.y < rect.top) Side.Top else Side.Bottom
    }

    /** Forget everything. For when a control is torn down or the surface
     *  under the reticle is replaced mid-gesture. */
    fun reset() {
        active = false
        havePrev = false
        awaitingRearm = false
        prev = Pt(0f, 0f)
    }
}
