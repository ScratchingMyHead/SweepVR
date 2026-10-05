/* SweepVR Player — GPL-3.0-only.
 * See LICENSE in the repository root.
 */
package net.sweepvr.player.sweep

/**
 * A VR keyboard you type on by sweeping, rather than by pointing at keys.
 *
 * ## The idea
 *
 * A full alphanumeric grid in a headset is unusable: the far column is never
 * a comfortable gaze angle. So only ONE row of letters is live at a time and
 * it is always centred in the window. The rows above and below it are visible
 * but inert. Changing row turns the drum, which slides the newly chosen row
 * into the centre. That is the slot-machine tumbler, and its real job is
 * reachability: every letter the user can type is inside a short sweep of the
 * middle of the window.
 *
 * ## The gesture
 *
 * Every key is entered on its edge FACING THE CENTRE and swept OUTWARDS, and
 * commits by leaving through its far edge:
 *
 *  - letters, symbols and space (active row only): in through the top or the
 *    bottom, out through the other. Either direction types one character.
 *  - top bar: in through the BOTTOM only, out through the top. Coming back
 *    down through it toward the centre passes over it and does nothing.
 *  - bottom bar: in through the TOP only, out through the bottom.
 *  - ENTER (left edge): in through its RIGHT edge, out through the left.
 *    Up or down cancels - its ends are inert.
 *  - DELWORD (right edge): in through its LEFT edge, out through the right.
 *  - X (bottom of each edge column): in through the BOTTOM only.
 *  - leaving a letter sideways cancels.
 *
 * ## Why each letter is its own engine, and what stops a drag spelling a word
 *
 * The obvious alternative is one engine over the whole active row, which gets
 * "one letter per sweep" for free from the engine's transition test. It does
 * not work here, and the reason is worth keeping.
 *
 * [Rect.nearestSide] decides the entry edge by normalising each axis by its
 * own length, which is what lets one test serve a wide short button and a
 * narrow tall thumb. On a band 850 wide and 50 tall that normalisation is
 * degenerate: a point 10 px below the top edge but only 2% of the way across
 * is nearest the LEFT edge, so the outermost letters of the row - the first
 * letter of every word - could never be typed from above at all.
 *
 * So each character has its own engine, and one letter per sweep is enforced
 * above them by [charClaim]: the first character to arm during a traverse
 * takes the claim, and any other that arms while the claim is held is
 * discarded. The claim is cleared when the claimed character is released.
 *
 * That is a single-shot guard, not a latch in the sense [SweepEngine] warns
 * about. The bug there was latching a REFUSAL, which froze the verdict so a
 * later valid approach was never evaluated. This latches a SUCCESS for the
 * duration of one traverse and is released by the traverse itself.
 *
 * ## Caps
 *
 * One key, three states, drawn `aaa` / `Aaa` / `AAA`:
 *  - activated with a character typed since: back to [CapsMode.LOWER].
 *  - activated with none since: advance LOWER -> SINGLE -> LOCK -> LOWER.
 *
 * So one sweep gives single caps; looping straight back up again without
 * typing gives caps lock; and typing consumes a single cap.
 *
 * Pure Kotlin, no Android imports: geometry and behaviour only. The renderer
 * draws it and hands it coordinates. See THE RULE in [SweepTypes.kt].
 */
class KeyboardControl {

    /** How capitals are applied to the letters row. */
    enum class CapsMode { LOWER, SINGLE, LOCK }

    /** What a key does when it commits. */
    private enum class Action { CHAR, ROW_123, ROW_ABC, ROW_SYM2, CAPS, BKSP, DELWORD, ENTER, CANCEL, CLEAR }

    /**
     * One character key: what it types, and how wide it is relative to a
     * letter.
     *
     * Space is the only wide one, and it carries extra blank either side, so
     * it reads as its own target instead of one more character to land on
     * between two groups of letters.
     */
    private class Glyph(val label: String, val weight: Float = 1f)

    // ---------------------------------------------------------------- text

    /** The text as it stands, caret at the end. Appending is all this does:
     *  an address is built left to right and a mistake is fixed with BKSP,
     *  which is cheaper than a caret to move around. */
    var text: String = ""
        private set

    /** What [text] was when the edit began, so X can put it back. */
    var initialText: String = ""
        private set

    /** Begin an edit of [value], replacing whatever was there. */
    fun beginEdit(value: String) {
        initialText = value
        text = value
        capsMode = CapsMode.LOWER
        activeRow = 1
        for (k in keys) k.reset()
        for (row in charKeys) for (k in row) k.reset()
        charClaim = null
        tumble = 0f
        settleT = 0L
        changed?.invoke()
    }

    /** Forget the gesture, keeping the text. For when the surface under a
     *  parked reticle is replaced. Never fires. */
    fun reset() {
        for (k in keys) k.reset()
        for (row in charKeys) for (k in row) k.reset()
        charClaim = null
    }

    // ---------------------------------------------------------------- modes

    var capsMode: CapsMode = CapsMode.LOWER
        private set

    /** True while the drum is turning, when nothing may be typed. */
    val tumbling: Boolean get() = kotlin.math.abs(tumble) > 0.5f

    /** The three caps states, as the key should be drawn. */
    fun capsLabel(): String = when (capsMode) {
        CapsMode.LOWER -> "aaa"
        CapsMode.SINGLE -> "Aaa"
        CapsMode.LOCK -> "AAA"
    }

    // -------------------------------------------------------------- layout

    /** The window, in the owner's space, y-down. Everything else is derived
     *  from it, so the drawn silhouette and the hit rect cannot drift apart -
     *  the rule the bookmarks flyout states for itself. */
    var window: Rect = Rect(0f, 0f, 0f, 0f)

    /** Height of the selector bars as a fraction of the window height. */
    var barFraction: Float = 0.105f
    /** Height of one letter row as a fraction of the window height. */
    var rowFraction: Float = 0.125f
    /** Width of the ENTER / DELWORD / X columns as a fraction of the window. */
    var edgeFraction: Float = 0.075f
    /** Blank between neighbouring keys, as a fraction of the window width. */
    var gapFraction: Float = 0.004f
    /** Width of a bar button, as a fraction of the window width. The bar is
     *  centred around them rather than filled. */
    var barKeyFraction: Float = 0.082f
    /** Blank above and below each character key, as a fraction of the row
     *  height, so the three rows read as three bands.
     *
     *  Drawn edge to edge they were one block of keys with no way to tell
     *  which band the live row was - and aiming is done by band. Small,
     *  because it shortens the sweep target to shorten it. */
    var rowInnerInsetFraction: Float = 0.07f

    private val barH get() = window.height * barFraction
    private val rowH get() = window.height * rowFraction
    private val edgeW get() = window.width * edgeFraction
    internal val gap get() = window.width * gapFraction

    val topBar: Rect get() = Rect(window.left, window.top, window.right, window.top + barH)
    val bottomBar: Rect get() = Rect(window.left, window.bottom - barH, window.right, window.bottom)
    val drum: Rect get() = Rect(window.left, topBar.bottom, window.right, bottomBar.top)

    /**
     * The two dead zones: the drum above and below the live band. With a 50px
     * band in a 316px drum these are ~130px each, which is the room the side
     * keys are laid out in.
     *
     * The side keys are SHORT and sit inside these zones rather than running
     * the full drum height. At 0.62 of the drum they overlapped the live band,
     * so they closed in on the sides and a downward sweep through a character
     * ran straight into one - there was no way down through the text without
     * meeting a key, and no way to reach ENTER from below the band at all.
     * Short keys with gaps either side can be gone around.
     */
    val topDead: Rect get() = Rect(window.left, drum.top, window.right, bandTop())
    val bottomDead: Rect get() = Rect(window.left, bandBottom(), window.right, drum.bottom)

    /** The live band's edges with the drum SETTLED. The side keys are laid
     *  out against these, not against the moving band: a layout that slid
     *  with the tumble would drag the side keys about during every turn. */
    private fun bandTop() = drum.top + drum.height * 0.5f - rowH * 0.5f
    private fun bandBottom() = drum.top + drum.height * 0.5f + rowH * 0.5f

    /** Height of a side key. Deliberately under half a dead zone, so two of
     *  them stack inside one with padding to spare AND the middle of the zone
     *  can still be aimed at to miss both. */
    private val sideH get() = drum.height * 0.15f
    private val sidePad get() = drum.height * 0.035f

private fun side(right: Boolean, zone: Rect, slot: Int): Rect {
        val x0 = if (right) window.right - edgeW else window.left
        val top = zone.top + sidePad + slot * (sideH + sidePad)
        return Rect(x0, top, x0 + edgeW, top + sideH)
    }

    /** Every side button exists in BOTH dead zones.
     *
     *  The band is between them and cannot be crossed without typing a
     *  character, so a button present in only one of them is unreachable
     *  from one side: ENTER was bottom-only until it was duplicated, and
     *  CLEAR would have had the same fault. Symmetry is the whole point -
     *  whichever way the gaze leaves the band, the same set of actions is
     *  there waiting. */
    /** ENTER, level with the top dead zone. */
    val enterTop: Rect get() = side(true, topDead, 0)
    /** CLEAR, in the top dead zone. */
    val clearTop: Rect get() = side(true, topDead, 1)
    /** ENTER, level with the bottom dead zone, reachable from under the band. */
    val enterBottom: Rect get() = side(true, bottomDead, 0)
    /** CLEAR, in the bottom dead zone. */
    val clearKey: Rect get() = side(true, bottomDead, 1)
    /** Delete word, in BOTH dead zones on the left. One at the top, one at
     *  the bottom, so it is reachable from either side of the live band
     *  without crossing it - the band is between them and cannot be skipped
     *  without typing a character. */
    val delWordTop: Rect get() = side(false, topDead, 1)
    val delWordBottom: Rect get() = side(false, bottomDead, 1)
    /** Dismiss in the top half, on the left. */
    val cancelTop: Rect get() = side(false, topDead, 0)
    /** Dismiss in the bottom half, also on the left. */
    val cancelBottom: Rect get() = side(false, bottomDead, 0)
    val cancelLeft: Rect get() = cancelBottom
    val delWordKey: Rect get() = delWordBottom

    /** Where row [i] sits, given the current drum position.
     *
     *  Centred on the drum, not aligned to its middle edge: the live row is
     *  the centre of the window, which is the whole reason only one row is
     *  live. Top-aligning it would sit every row half a row too low. */
    fun rowRect(i: Int): Rect {
        val top = drum.top + drum.height * 0.5f - rowH * 0.5f + (i - activeRow) * rowH + tumble
        return Rect(window.left + edgeW, top, window.right - edgeW, top + rowH)
    }

    /** The live row's band. */
    val activeRowBand: Rect get() = rowRect(activeRow)

    /** The characters of row [i], left to right. Labels only; the widths are
     *  in [rowWeights]. */
    fun rowKeys(i: Int): List<String> = ROWS[i].map { it.label }

    /** Relative widths of row [i]'s keys. Space is wider than a letter, and
     *  carries extra blank either side so it reads as a separate target
     *  rather than as one more character to land on. */
    fun rowWeights(i: Int): List<Float> = ROWS[i].map { it.weight }

    /**
     * The rect of character key [index] of row [row] - what the renderer draws,
     * and what the hit test reads through the same calculation.
     *
     * The grid is CHARS_PER_SIDE keys + space + CHARS_PER_SIDE, for every row,
     * so a character is the same width in all three and every space lands in
     * the same column. Deriving the width from the row's own contents instead
     * is what put the three space bars in three different places.
     */
    fun charRect(row: Int, index: Int): Rect {
        val band = rowRect(row)
        val n = ROWS[row].size
        val usable = band.width - gap * (n + 1)
        val unit = usable / (2f * CHARS_PER_SIDE + SPACE_WEIGHT)
        var x = band.left + gap
        for (k in 0 until index) x += unit * ROWS[row][k].weight + gap
        val w = unit * ROWS[row][index].weight
        val inset = band.height * rowInnerInsetFraction
        val top = band.top + inset
        val bot = band.bottom - inset
        // Space pulls in by a blank on each side, so the gap either side of it
        // is a full one rather than the inter-key gap.
        if (ROWS[row][index].label == " ")
            return Rect(x + gap, top, x + w - gap, bot)
        return Rect(x, top, x + w, bot)
    }

    /**
     * The rect of the [index]th key in [bar] - narrower than the bar and
     * CENTRED in it.
     *
     * Spreading them across the full width made each button over a tenth of
     * the keyboard wide, which is a target you cannot miss but also cannot
     * sweep through quickly without straying onto its neighbour. At
     * [barKeyFraction] they are a comfortable target with air at both ends.
     */
    fun barKeyRect(bar: Rect, index: Int, n: Int): Rect {
        val cw = bar.width * barKeyFraction
        val total = cw * n + gap * (n - 1)
        val x0 = bar.left + (bar.width - total) * 0.5f + cw * index + gap * index
        return Rect(x0, bar.top, x0 + cw, bar.bottom)
    }

    // ---------------------------------------------------------------- rows

    private var activeRow = 1

    /** Which row is live. */
    val activeRowName: String get() = ROW_NAMES[activeRow]

    /** Which row is live, by index — the renderer walks all three. */
    val activeRowIndex: Int get() = activeRow

    private var tumble = 0f
    private var settleT = 0L

    // ------------------------------------------------------------- keys

    private class Key(
        val label: String,
        val action: Action,
        val payload: String = "",
        val entry: Set<Side>,
        val commit: Set<Side>,
        val cornerFraction: Float = 0.16f,
        /** Modifier bars fire on contact; the edge keys keep a full traverse
         *  so ENTER can still cancel by leaving through the top or bottom. */
        val fireOnContact: Boolean = false
    ) {
        var rect: Rect = Rect(0f, 0f, 0f, 0f)
        var armed: Boolean = false; private set
        var onTrace: ((String) -> Unit)? = null
        private val engine = SweepEngine()
        private var coolMs: Long = Long.MAX_VALUE

        fun cfg() = SweepConfig(
            entrySides = entry, leeway = 0f, rearm = 0f,
            refuseCorners = true, cornerFraction = cornerFraction,
            tieBreak = TieBreak.REFUSE
        )

        /**
         * Advance one frame.
         *
         * Returns true only on the frame this key COMMITS. "Am I armed" is the
         * [armed] property, not the return value: a momentary's step() returns
         * the frame claim, and reading that as a fire types the character once
         * per frame of the hold.
         *
         * [allow] is false while the drum is turning, when nothing may fire.
         */
        fun step(at: Pt, dtMs: Long, allow: Boolean): Boolean {
            if (rect.isEmpty) { reset(); return false }
            if (coolMs < STANDOFF_MS) coolMs = minOf(STANDOFF_MS, coolMs + dtMs.coerceAtLeast(0L))
            // The modifier bars fire on CONTACT, not on the way out.
            //
            // Requiring a full traverse to commit meant the gesture had to
            // carry on past the key and out the far side, which is exactly
            // what put the bar key underneath into the running: sweeping up
            // toward the X committed the bottom-bar key before the X could
            // finish, and `keys` steps the bars first, so the drum turned and
            // the X lost its traverse. It also made every modifier a two-part
            // gesture for no gain - you had to move right past the thing you
            // had already touched. Entry through the nominated sides is still
            // required and still has no leeway, so this is contact firing, not
            // a looser rule.
            //
            // Only the bars. ENTER and DELWORD deliberately keep a full
            // traverse, because ENTER is specified to CANCEL when it is left
            // through the top or bottom, and firing the moment it is touched
            // would delete that outright.
            return when (val ev = engine.step(at, rect, cfg())) {
                is SweepEvent.Entered -> {
                    if (fireOnContact) {
                        val fire = allow && coolMs >= STANDOFF_MS
                        if (fire) coolMs = 0L
                        // NOT armed: it has already fired. Leaving armed=true
                        // let the same sweep's exit fire it a second time,
                        // which cycled caps twice per touch and deleted two
                        // characters per stroke of BKSP.
                        armed = false
                        if (fire) {
                            onTrace?.invoke("fire ${ev.side.name.lowercase()} '$label'")
                            return true
                        }
                        engine.reset()
                        return false
                    }
                    if (!allow) { engine.reset(); armed = false }
                    else if (coolMs < STANDOFF_MS) { engine.reset(); armed = false }
                    else armed = true
                    false
                }
                is SweepEvent.Engaged -> false
                is SweepEvent.Released -> {
                    // The traverse commit, for the keys that still have one.
                    // Narrowing contact firing back to the bars restored the
                    // Entered branch but this one was left reading
                    // `{ armed = false; false }`, so ENTER, DELWORD and the X
                    // armed and could never fire: they lit up blue and did
                    // nothing. Which is the whole of what was reported.
                    val fire = armed && ev.side in commit
                    armed = false
                    if (fire) {
                        coolMs = 0L
                        onTrace?.invoke("fire ${ev.side.name.lowercase()} '$label'")
                        return true
                    }
                    false
                }
                SweepEvent.Idle -> { armed = false; false }
            }
        }

        /** The sides this key admits entry through, for the renderer to draw
         *  as dips. A notch that is not an entry side is a lie, and one
         *  missing where entry IS allowed is worse: the gesture becomes
         *  invisible. */
        val entrySides: Set<Side> get() = entry

        fun reset() { engine.reset(); armed = false; coolMs = Long.MAX_VALUE }

        /** Refuse to arm for STANDOFF_MS, without forgetting where the gaze
         *  is. Used after a character resolves, so jitter cannot walk the
         *  gaze back into the same key and type it again. */
        fun cooldown() { coolMs = 0L }
        fun prime(at: Pt) { engine.prime(at) }
    }

    /** What each bar key does, in bar order.
     *
     *  Eight, not five: the row selectors are duplicated at both ends so a row
     *  can always be changed without sweeping across the letters, and the
     *  modifiers sit in the left half. The CONTROLLERS are built from this
     *  whole list - building them from a five-entry prefix left the right-hand
     *  123 and abc drawn on screen with no engine behind them, so sweeping
     *  them did nothing at all. */
    private val barSequence = listOf(
        Action.ROW_123 to "123", Action.ROW_ABC to "abc",
        Action.ROW_SYM2 to "Sym2", Action.CAPS to "Aa", Action.BKSP to "BKSP",
        Action.ROW_123 to "123", Action.ROW_ABC to "abc", Action.ROW_SYM2 to "Sym2"
    )

    /** Bar labels, in bar order. */
    val barLabels: List<String> = barSequence.map { it.second }

    /** Every bar key fires on contact, Aa and BKSP included.
     *
     *  They were briefly held back on the grounds that they are not
     *  idempotent - Aa cycles, BKSP deletes - which is true, but the reason
     *  they were actually broken was a separate bug: the contact branch left
     *  `armed` true, so the same sweep's exit fired them a second time and
     *  each touch cycled caps twice and deleted two characters. With that
     *  fixed they behave like the row selectors.
     *
     *  The STANDOFF_MS cooldown is what guards the destructive ones, so a
     *  brush across BKSP deletes one character rather than a handful.
     *
     *  ENTER and DELWORD keep the full traverse: ENTER is specified to cancel
     *  when left through the top or bottom, and DELWORD is entered laterally.
     *  Neither is a bar key. */
    private val topKeys = barSequence.map {
        Key(it.second, it.first, entry = setOf(Side.Bottom), commit = setOf(Side.Top),
            fireOnContact = true)
    }
    private val bottomKeys = barSequence.map {
        Key(it.second, it.first, entry = setOf(Side.Top), commit = setOf(Side.Bottom),
            fireOnContact = true)
    }
    private val enterTopCtl = Key("ENT", Action.ENTER,
        entry = setOf(Side.Bottom), commit = setOf(Side.Top), fireOnContact = true)
    private val enterBottomCtl = Key("ENT", Action.ENTER,
        entry = setOf(Side.Top), commit = setOf(Side.Bottom), fireOnContact = true)
    private val clearTopCtl = Key("CLR", Action.CLEAR,
        entry = setOf(Side.Bottom), commit = setOf(Side.Top), fireOnContact = true)
    private val clearBottomCtl = Key("CLR", Action.CLEAR,
        entry = setOf(Side.Top), commit = setOf(Side.Bottom), fireOnContact = true)
    private val delWordTopCtl = Key("DEL", Action.DELWORD,
        entry = setOf(Side.Bottom), commit = setOf(Side.Top), fireOnContact = true)
    private val delWordBottomCtl = Key("DEL", Action.DELWORD,
        entry = setOf(Side.Top), commit = setOf(Side.Bottom), fireOnContact = true)
    // Entry through the TOP, not the bottom.
    //
    // The X rects end at drum.bottom, directly above the bottom bar, so an
    // entry through their bottom edge can only begin in that bar - which now
    // fires the instant it is touched, and turned the drum before the X could
    // ever commit. That made the key unreachable rather than merely awkward:
    // it would light up and never fire. Approaching down through the letters
    // reaches its top face instead, which nothing else competes for.
    private val cancelTopCtl = Key("X", Action.CANCEL,
        entry = setOf(Side.Bottom), commit = setOf(Side.Top), fireOnContact = true)
    private val cancelBottomCtl = Key("X", Action.CANCEL,
        entry = setOf(Side.Top), commit = setOf(Side.Bottom), fireOnContact = true)

    private val keys: List<Key> = topKeys + bottomKeys +
        listOf(enterTopCtl, enterBottomCtl, clearTopCtl, clearBottomCtl,
            delWordTopCtl, delWordBottomCtl, cancelTopCtl, cancelBottomCtl)

    /** Every row's characters, built once. Only the live row is ever stepped;
     *  the others are here so the renderer can draw the inert rows from the
     *  same rectangles without a second layout pass. */
    private val charKeys: List<List<Key>> = ROWS.mapIndexed { r, row ->
        // The commit set is empty on purpose. A character engine exists only to
        // answer "was this character entered", and the traverse is resolved
        // against the ROW's edges in step() instead - see the note there. If
        // the key resolved its own release, a lateral drift off it would read
        // as a sideways exit and cancel the letter the user was in the middle
        // of typing.
        row.map { g -> Key(g.label, Action.CHAR, g.label, setOf(Side.Top, Side.Bottom), emptySet()) }
    }

    /** The character that took this traverse. See the class doc for why this
     *  is a single-shot guard and not a latch. */
    private var charClaim: Key? = null

    // ---------------------------------------------------------- callbacks

    /** The text or a mode changed: the owner redraws what shows it. */
    var changed: (() -> Unit)? = null
    /** ENTER committed. */
    var committed: ((String) -> Unit)? = null
    /** X committed: abandon the edit. */
    var cancelled: (() -> Unit)? = null
    var onTrace: ((String) -> Unit)? = null

    // ---------------------------------------------------------------- step

    /** True while the keyboard owns the gaze. */
    var held: Boolean = false
        private set

    /** The key currently held, for the owner to draw. Empty when none. */
    private var heldCharLabel: String = ""
    private var heldKeyLabel: String = ""
    private var heldKeyMs: Long = 0L
    private var heldKeyAge: Long = 0L
    private var heldKeyRect: Rect? = null

    // ---------------------------------------------------------- entry dips
    //
    // The renderer draws a notch on each edge a key is entered through, the
    // same language as the toolbar's buttons. These expose the sets so the
    // artwork and the gesture cannot drift apart: the dips are read FROM the
    // entry configuration rather than restated beside it.

    /** Entry sides of the [i]th key of the top bar. */
    fun topBarEntry(i: Int): Set<Side> = topKeys.getOrNull(i)?.entrySides ?: emptySet()

    /** Entry sides of the [i]th key of the bottom bar. */
    fun bottomBarEntry(i: Int): Set<Side> = bottomKeys.getOrNull(i)?.entrySides ?: emptySet()

    /** Entry sides of a character key. Every row is entered the same way, so
     *  this does not vary with the row. */
    fun charEntry(): Set<Side> =
        charKeys.getOrNull(0)?.getOrNull(0)?.entrySides ?: emptySet()

    /** Entry sides of one of the side keys. */
    fun edgeEntry(which: EdgeKey): Set<Side> = when (which) {
        EdgeKey.ENTER_TOP -> enterTopCtl.entrySides
        EdgeKey.ENTER_BOTTOM -> enterBottomCtl.entrySides
        EdgeKey.CLEAR_TOP -> clearTopCtl.entrySides
        EdgeKey.CLEAR_BOTTOM -> clearBottomCtl.entrySides
        EdgeKey.DISMISS_TOP -> cancelTopCtl.entrySides
        EdgeKey.DISMISS_BOTTOM -> cancelBottomCtl.entrySides
        EdgeKey.DELWORD_TOP -> delWordTopCtl.entrySides
        EdgeKey.DELWORD_BOTTOM -> delWordBottomCtl.entrySides
    }

    /** The keys down the two side columns, in a stable order. */
    enum class EdgeKey {
        ENTER_TOP, ENTER_BOTTOM, CLEAR_TOP, CLEAR_BOTTOM,
        DISMISS_TOP, DISMISS_BOTTOM, DELWORD_TOP, DELWORD_BOTTOM
    }

    /** What the renderer should light up.
     *
     *  A fired key holds its label for [KBD_HOLD_MS] instead of for the one
     *  frame it fired on. `heldLabel` was cleared at the top of every step, so
     *  a bar key went blue for a single frame - about 14ms - which read as no
     *  feedback at all. Aa appeared to flash only because firing it changes
     *  its own label (aaa/Aaa/AAA), so the redraw landed while it was
     *  highlighted; the row selectors redraw nothing and so were invisible.
     *
     *  A character still reports continuously while it is armed: that is a
     *  sustained state, not an event. */
    val heldLabel: String
        get() = if (heldKeyMs > 0L) heldKeyLabel else heldCharLabel

    /**
     * Index into the active row of the character currently claimed, or -1.
     *
     * The owner must highlight by INDEX, not by comparing [heldLabel] to the
     * string it drew. The two disagree exactly where it matters: the letters
     * row is drawn lower case unless caps is up, while the label is the upper
     * case it actually types, so matching on the string meant the letters row
     * never lit up and the symbol rows always did.
     */
    val heldCharIndex: Int
        get() {
            val c = charClaim ?: return -1
            val i = charKeys[activeRow].indexOf(c)
            return if (i >= 0) i else -1
        }

    /** True when the held key is the bar key carrying [label]. */
    /** Is the bar key called [label] the one currently lit? Matched on the
     *  key's own name, which for the caps key is "Aa" however it is drawn. */
    fun isBarKeyHeld(label: String): Boolean = heldLabel == label

    private var lastX = 0f
    private var lastY = 0f



    /** Advance one frame. Returns true while the keyboard owns the gaze. */
    fun step(x: Float, y: Float, dtMs: Long, still: Boolean = true): Boolean {
        if (window.isEmpty) { reset(); held = false; return false }
        val at = Pt(x, y)
        lastX = x; lastY = y

        if (tumble != 0f) {
            settleT += dtMs.coerceAtLeast(0L)
            val k = (dtMs.coerceAtLeast(0L)).toFloat() / TUMBLE_MS.coerceAtLeast(1L)
            // Toward zero, which is the settled state. Easing toward the
            // offset we started from would hold the drum open for ever.
            tumble -= tumble * k.coerceIn(0f, 1f)
            if (kotlin.math.abs(tumble) < 0.5f || settleT > TUMBLE_MS * 3) tumble = 0f
        }

        layout()
        val allow = !tumbling

        var owned = false
        heldCharLabel = ""
        val dt = dtMs.coerceAtLeast(0L)
        heldKeyMs = maxOf(0L, heldKeyMs - dt)
        heldKeyAge += dt
        // A fixed hold is not long enough. The sweep that fires a bar key
        // does not stop when the key fires: it carries on across it, so a
        // 140ms hold was spent before the gaze had finished arriving and the
        // key was dark again by the time the user looked at it. Top the hold
        // up for as long as the gaze is still on the key, with a ceiling so a
        // parked reticle does not leave it lit for ever.
        if (heldKeyMs > 0L && heldKeyRect?.contains(at) == true &&
            heldKeyAge < KBD_HOLD_MAX_MS) heldKeyMs = KBD_HOLD_MS

        // The live row's characters. First to arm takes the traverse; any
        // other that arms while the claim is held is discarded, which is what
        // stops a lateral drag spelling a word.
        val band = activeRowBand
        for (k in charKeys[activeRow]) {
            // Re-read charClaim EVERY key, not once before the loop: capturing
            // it up front let all twenty-seven arm on the same frame, so after
            // one resolved there were others still holding and the next frame
            // typed without the user sweeping anything.
            k.step(at, dtMs, allow && charClaim == null)
            if (k.armed && charClaim == null) {
                charClaim = k
                // WHERE it armed, and where the live row actually is. Every
                // sample taken between frames missed the arm, which is the
                // only moment the question is about.
                val b = activeRowBand
                onTrace?.invoke("arm '${k.payload}' at " +
                    "${"%.1f".format(at.x)},${"%.1f".format(at.y)} " +
                    "rect=${"%.0f".format(k.rect.left)},${"%.0f".format(k.rect.top)}.." +
                    "${"%.0f".format(k.rect.right)},${"%.0f".format(k.rect.bottom)} " +
                    "band=${"%.0f".format(b.left)},${"%.0f".format(b.top)}.." +
                    "${"%.0f".format(b.right)},${"%.0f".format(b.bottom)}")
            }
        }
        // Resolve the traverse against the ROW, not against the key that
        // claimed it. Leaving through the top or the bottom types; leaving
        // through a side cancels.
        //
        // Resolving at the key would mean a sweep that wandered sideways for a
        // few pixels released the character key sideways and typed nothing - a
        // small head wobble losing the letter during a deliberate sweep. The
        // row is what the user aimed at, so the row decides.
        val claim = charClaim
        if (claim != null) {
            heldCharLabel = claim.label
            owned = true
            if (!band.contains(at)) {
                val vertical = at.y <= band.top || at.y >= band.bottom
                if (vertical && allow) {
                    onTrace?.invoke("type '${claim.payload}'")
                    type(claim.payload)
                } else {
                    onTrace?.invoke("row traverse cancelled")
                }
                // PRIME the row at where the gaze actually is, rather than
                // only resetting it.
                //
                // reset() throws away the engine's history, and the gaze is
                // OUTSIDE the band at this instant - that is the only reason
                // this resolve happened. So the next frame every engine sees
                // its first-ever sample, sitting outside its own rect, reads
                // that as an entry, finds the gaze still outside the band,
                // and resolves the very same character again. One press, two
                // characters, a frame or two apart. Seeding them with the
                // current point means a new character cannot arm until the
                // gaze genuinely comes back and crosses into it.
                val wasVertical = at.y <= band.top || at.y >= band.bottom
                for (k in charKeys[activeRow]) {
                    k.reset()
                    k.prime(at)
                    // ...and a short refusal on top, because head jitter can
                    // carry the gaze back out of the band and in again within
                    // a frame or two of the resolve. Without this, a sweep
                    // that merely grazes the edge can type twice.
                    if (wasVertical && allow) k.cooldown()
                }
                charClaim = null
            }
        }

        for (k in keys) {
            val fired = k.step(at, dtMs, allow)
            if (fired) {
                run(k.action)
                heldKeyLabel = k.label
                heldKeyMs = KBD_HOLD_MS
                heldKeyAge = 0L
                heldKeyRect = k.rect
            }
            if (k.armed || k.rect.contains(at) || fired) owned = true
        }
        if (band.contains(at)) owned = true

        held = owned
        return owned
    }

    /** What the renderer's highlight should follow, if anything. */
    private fun type(c: String) {
        if (c.isEmpty()) return
        text += alpha(c)
        changed?.invoke()
    }

    private fun run(a: Action) {
        when (a) {
            Action.ROW_123 -> setRow(0)
            Action.ROW_ABC -> setRow(1)
            Action.ROW_SYM2 -> setRow(2)
            Action.CAPS -> cycleCaps()
            Action.BKSP -> if (text.isNotEmpty()) { text = text.dropLast(1); changed?.invoke() }
            Action.DELWORD -> {
                val t = text.trimEnd()
                val cut = t.lastIndexOf(' ')
                text = if (cut < 0) "" else t.substring(0, cut + 1)
                changed?.invoke()
            }
            Action.ENTER -> committed?.invoke(text)
            // Restore here rather than leaving it entirely to the owner. The
            // viewer's close handler does put the text back, but a control
            // whose own state survives its own CANCEL is not testable, and
            // the two could drift apart.
            Action.CANCEL -> { text = initialText; cancelled?.invoke() }
            Action.CLEAR -> { text = ""; changed?.invoke() }
            Action.CHAR -> Unit
        }
    }

    /** The character to actually append, with the caps mode applied and a
     *  single cap spent. */
    private fun alpha(base: String): String {
        if (base.length != 1) return base
        if (!base[0].isLetter()) return base
        return when (capsMode) {
            CapsMode.LOCK -> base.uppercase()
            CapsMode.SINGLE -> { capsMode = CapsMode.LOWER; base.uppercase() }
            CapsMode.LOWER -> base.lowercase()
        }
    }

    private fun cycleCaps() {
        // Always cycle. This used to short-circuit to LOWER when a character
        // had been typed since the last caps change, on the theory that the
        // first press should just tidy up. But alpha() has ALREADY reset
        // SINGLE to LOWER as it spends the single cap, so the branch assigned
        // the mode it was already in and did nothing visible while clearing
        // the flag. The press was swallowed: after Aaa reset itself by typing,
        // the first sweep over Aa appeared dead and the second one turned it
        // on. Nothing about this was to do with timing.
        capsMode = when (capsMode) {
            CapsMode.LOWER -> CapsMode.SINGLE
            CapsMode.SINGLE -> CapsMode.LOCK
            CapsMode.LOCK -> CapsMode.LOWER
        }
        onTrace?.invoke("caps ${capsMode.name.lowercase()}")
        changed?.invoke()
    }

    private fun setRow(i: Int) {
        if (i == activeRow) return
        // Start the slide from where the drum already is, so the row the user
        // was looking at does not jump on the frame the button fires.
        //
        // Sign matters. Assigning activeRow = i has ALREADY re-centred row i,
        // because rowY is measured from activeRow. So tumble only has to
        // account for where row `activeRow` USED to sit, and it does that by
        // putting the outgoing row back on the centre line on the first
        // frame: (A - i)*rowH + tumble == 0, hence tumble = (i - A)*rowH.
        // The other sign leaves the outgoing row at 2*(A-i)*rowH - clear
        // across the drum - which is the slide that looked like it was
        // scrolling the wrong way before settling in the right place.
        tumble = (i - activeRow) * rowH
        settleT = 0L
        activeRow = i
        charClaim = null
        // The incoming row's engines have not been stepped since it was last
        // live, so their history is stale and the first step could invent an
        // entry. Seed them where the reticle actually is.
        val at = Pt(lastX, lastY)
        for (k in charKeys[i]) k.prime(at)
        for (k in topKeys) k.prime(at)
        for (k in bottomKeys) k.prime(at)
        // The edge keys too. They are in `keys`, so they are stepped every
        // frame, but nothing ever seeded their engines on a row change and
        // their history was left over from before the previous switch.
        // Only the ones NOT mid-gesture. Seeding an engine that has already
        // entered rewrites its reference point mid-traverse, so the exit it
        // was about to see never arrives - the X would light up and then
        // silently fail to commit, which is precisely how it presented.
        for (k in listOf(enterTopCtl, enterBottomCtl, clearTopCtl, clearBottomCtl,
            delWordTopCtl, delWordBottomCtl, cancelTopCtl, cancelBottomCtl))
            if (!k.armed) k.prime(at)
        onTrace?.invoke("row ${ROW_NAMES[i]}")
        changed?.invoke()
    }

    /** Recompute every rect from [window], before any engine sees a
     *  coordinate, so drawn and hit geometry are one thing. */
    private fun layout() {
        if (window.isEmpty) return
        val n = barLabels.size
        for (i in topKeys.indices) topKeys[i].rect = barKeyRect(topBar, i, n)
        for (i in bottomKeys.indices) bottomKeys[i].rect = barKeyRect(bottomBar, i, n)
        enterTopCtl.rect = enterTop
        enterBottomCtl.rect = enterBottom
        clearTopCtl.rect = clearTop
        clearBottomCtl.rect = clearKey
        delWordTopCtl.rect = delWordTop
        delWordBottomCtl.rect = delWordBottom
        cancelTopCtl.rect = cancelTop
        cancelBottomCtl.rect = cancelBottom
        for (r in charKeys.indices)
            for (i in charKeys[r].indices)
                charKeys[r][i].rect = charRect(r, i)
    }

    /** Seed every engine with a position, for a surface replaced under a
     *  parked reticle. Without this the next step invents an entry. */
    fun prime(x: Float, y: Float) {
        val at = Pt(x, y)
        for (k in keys) k.prime(at)
        for (row in charKeys) for (k in row) k.prime(at)
    }

    init {
        val all = keys + charKeys.flatten()
        for (k in all) k.onTrace = { onTrace?.invoke(it) }
    }

    companion object {
        /** Ms for the drum to bring a row to the centre. */
        const val TUMBLE_MS = 260L

        /** After a key fires, a fresh entry inside this window is swallowed.
         *  The same tremor guard [MomentaryControl] uses: a head wobble steps
         *  out past the edge and straight back in. A cancel starts no standoff,
         *  because it left no effect to duplicate. */
        const val STANDOFF_MS = 220L

        /** How long a fired key stays lit.
         *
         *  This has to outlast the REST of the sweep, not just be perceptible.
         *  The keys fire on contact, but the gaze does not stop on contact: it
         *  carries on outwards across the key and off it, taking a couple of
         *  hundred milliseconds. At 140ms the highlight had already gone by
         *  the time the gaze arrived, so the keys looked dead. 450ms covers
         *  the sweep; the row state changes immediately regardless, and
         *  STANDOFF_MS is what gates a repeat touch, not this. */
        const val KBD_HOLD_MS = 150L

        /** Ceiling on how long a key stays lit while the gaze rests on it, so
         *  a parked reticle does not pin the highlight on indefinitely. */
        const val KBD_HOLD_MAX_MS = 400L

        private val ROW_NAMES = listOf("123", "abc", "Sym2")

        /**
         * Every row is THIRTEEN keys, the wide space, then THIRTEEN again.
         *
         *  The count is fixed rather than taken from the row's contents, which
         *  is what makes the rows line up. Laying each row out over its own
         *  total width meant a short row laid its keys out wider, so Sym2's
         *  letters were bigger than abc's and its space sat somewhere else
         *  entirely - the space keys were visibly in three different columns.
         *
         *  Filling Sym2's right half to thirteen with real characters rather
         *  than blanks is the other half of the same fix, and it makes the key
         *  useful instead of merely aligned.
         */
        const val CHARS_PER_SIDE = 13
        const val SPACE_WEIGHT = 2.6f

        private fun g(vararg s: String): List<Glyph> = s.map { Glyph(it) }
        private fun row(pre: List<Glyph>, post: List<Glyph>): List<Glyph> = pre + SPACE + post
        private val SPACE = Glyph(" ", SPACE_WEIGHT)

        /** Characters each row has beside the space, for tests. */
        fun charsPerSide(row: Int): Int = ROWS[row].size / 2

        private val ROWS: List<List<Glyph>> = listOf(
            row(g("!", "@", "#", "$", "%", "^", "&", "*", "(", ")", "+", "-", "="),
                g("/", "\\", "_", "1", "2", "3", "4", "5", "6", "7", "8", "9", "0")),
            row(g("A", "B", "C", "D", "E", "F", "G", "H", "I", "J", "K", "L", "M"),
                g("N", "O", "P", "Q", "R", "S", "T", "U", "V", "W", "X", "Y", "Z")),
            row(g("[", "]", "{", "}", ";", ":", "\"", "'", "<", ">", "?", ",", "."),
                g("|", "~", "^", "\u20ac", "\u00a3", "\u00a5", "\u00b0", "\u00a7", "\u00b6",
                  "\u00a9", "\u00ae", "\u2122", "\u00b1"))
        )
    }
}
