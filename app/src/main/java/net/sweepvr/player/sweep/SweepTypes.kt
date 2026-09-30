/* SweepVR Player — GPL-3.0-only.
 * See LICENSE in the repository root.
 */
package net.sweepvr.player.sweep

import kotlin.math.abs
import kotlin.math.min

/* ===========================================================================
 * THE RULE. Read this before adding anything to a sweep control.
 * ===========================================================================
 *
 * LEEWAY IS AN EXIT TOLERANCE AND NOTHING ELSE.
 *
 *   - Entry has NO margin. There is no slop, no pad, no tolerance, no
 *     "close enough". The reticle must be ON the control.
 *   - Entry is only through a side listed in [SweepConfig.entrySides].
 *   - The angle of attack is irrelevant and must NOT be read. Do not look at
 *     the previous position to decide which side was entered, and do not
 *     compare the direction of travel. Only the position AT WHICH the reticle
 *     enters decides - see [Rect.nearestSide].
 *   - [SweepConfig.leeway] applies from the moment the control is held, and
 *     governs only how far the reticle may drift before letting go. A control
 *     that has a leeway on entry is wrong, however reasonable it looks: an
 *     entry margin means the control activates when the user is NEXT TO it,
 *     which is not a gesture the user ever chose.
 *
 * There used to be a `slop` field here doing exactly that, and a note
 * justifying it as absorbing per-frame noise. It has been removed rather than
 * defaulted to zero, so that it cannot be reintroduced by accident.
 *
 * If a grab feels unreliable, the fix is the entry SIDE, not a wider target.
 * [SweepTypes.kt] / [SweepEngine.kt] is the reference implementation, and
 * [DropdownControl] is a worked example of the rule above.
 * =========================================================================== */

/** A point in a control's own coordinate space.
 *
 *  The space is whatever the owning control uses, with ONE requirement:
 *  y increases DOWNWARD. The two live sweep controls disagree on this - the
 *  browser panel counts down from the top, the scrollbar counts up from the
 *  bottom - and that mismatch is exactly what made "scrolled off the top"
 *  resolve to the bottom of the page. Normalising here means [Side.Top] is
 *  always the edge that looks like the top, whichever space it came from. */
data class Pt(val x: Float, val y: Float)

/** Axis-aligned rectangle in a control's own space, same y-down rule. */
data class Rect(
    val left: Float, val top: Float, val right: Float, val bottom: Float
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val isEmpty: Boolean get() = right <= left || bottom <= top

    /** Is [p] within [pad] of this rect, on both axes? */
    fun contains(p: Pt, pad: Float = 0f): Boolean =
        p.x >= left - pad && p.x <= right + pad && p.y >= top - pad && p.y <= bottom + pad

    /** Distance from [p] to each edge, as a FRACTION of that axis's length.
     *  See [nearestSide] for why this is normalised rather than absolute. */
    fun sideDistances(p: Pt): Map<Side, Float> {
        if (isEmpty) return emptyMap()
        return mapOf(
            Side.Left to abs(p.x - left) / width,
            Side.Right to abs(right - p.x) / width,
            Side.Top to abs(p.y - top) / height,
            Side.Bottom to abs(bottom - p.y) / height
        )
    }

    /**
     * Which edge of this rect is [p] nearest, measured as a FRACTION of each
     * axis rather than in absolute units.
     *
     * Normalising by axis length is what lets one test serve a wide, short
     * button and a narrow, tall thumb. In absolute terms the button's left
     * edge is far closer than its top, so a diagonal approach onto its side
     * would be reported as an entry from the top and refused. As fractions, a
     * point at the middle of the left edge is 0 across and ~0.5 down, so the
     * left wins - for a button and a thumb alike.
     *
     * Returns null on a tie, which the caller resolves via [SweepConfig.tieBreak].
     * [p] may be outside the rect: exit direction is measured the same way.
     */
    fun nearestSide(p: Pt, tieEpsilon: Float = 0f): Side? {
        val d = sideDistances(p)
        if (d.isEmpty()) return null
        val sorted = d.entries.sortedBy { it.value }
        if (abs(sorted[0].value - sorted[1].value) <= tieEpsilon) return null
        return sorted[0].key
    }

    /** Is [p] within [fraction] of BOTH a horizontal and a vertical edge -
     *  that is, up in a corner rather than on a side? The rounded corners are
     *  not part of a side-entry affordance, so controls refuse them. */
    fun isNearCorner(p: Pt, fraction: Float): Boolean {
        if (fraction <= 0f) return false
        val d = sideDistances(p)
        if (d.isEmpty()) return false
        val nearH = min(d.getValue(Side.Left), d.getValue(Side.Right)) < fraction
        val nearV = min(d.getValue(Side.Top), d.getValue(Side.Bottom)) < fraction
        return nearH && nearV
    }

    /** The union of two rects, or null if either is empty. Used by controls
     *  that grow a hit area while open - the bookmarks flyout, where the
     *  button and the pane are one target, because testing the pane alone
     *  made the button read as outside it and cancelled on every move. */
    fun union(o: Rect): Rect =
        Rect(min(left, o.left), min(top, o.top), maxOf(right, o.right), maxOf(bottom, o.bottom))
}

/** A visual edge of a control. Which one you may enter through is the
 *  control's choice, and is drawn as notches on exactly those edges. */
enum class Side { Left, Right, Top, Bottom }

enum class TieBreak { REFUSE, PREFER_HORIZONTAL, PREFER_VERTICAL }

/** What a sweep control can be configured with. All distances are in the
 *  control's own units.
 *
 *  There is deliberately no entry margin here. See THE RULE at the top of this
 *  file: leeway is for leaving, and entering requires being on the control, on
 *  a side the control nominates. */
data class SweepConfig(
    /** Edges the reticle may enter through. Anything else passes over. */
    val entrySides: Set<Side>,
    /** How far outside the control the reticle may drift before letting go.
     *
     *  EXIT ONLY. This never applies to acquiring the control - a control is
     *  not picked up from a distance, however briefly. */
    val leeway: Float,
    /** How far clear the reticle must get before a new entry is considered. */
    val rearm: Float = 0f,
    /** Refuse entry onto a corner, where the edge is ambiguous. */
    val refuseCorners: Boolean = true,
    /** Within this fraction of the edge span, a crossing counts as a corner. */
    val cornerFraction: Float = 0.0f,
    /** What to do when the entry point is exactly diagonal. */
    val tieBreak: TieBreak = TieBreak.REFUSE
)

/** The engine's verdict for one frame. It describes MOTION, never intent:
 *  what the reticle did, not what it should mean. */
sealed interface SweepEvent {
    /** Not engaged, and nothing happened. */
    object Idle : SweepEvent
    /** The reticle crossed in through [side], which the config permits. */
    data class Entered(val side: Side, val at: Pt) : SweepEvent
    /** Engaged, and still within the leeway. */
    object Engaged : SweepEvent
    /** Left past the leeway through [side]. */
    data class Released(val side: Side, val at: Pt) : SweepEvent
}
