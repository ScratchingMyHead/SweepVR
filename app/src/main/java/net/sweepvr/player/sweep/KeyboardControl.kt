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
    private enum class Action { CHAR, ROW_123, ROW_ABC, ROW_SYM2, CAPS, BKSP, DELWORD, ENTER, CANCEL }

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
        typedSinceCapsChange = false
        activeRow = 1
        for (k in keys) k.reset()
        for (row in charKeys) for (k in row) k.reset()
        charClaim = null
        guardMs = 0L
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

    val enterKey: Rect get() =
        Rect(window.left, drum.top, window.left + edgeW, drum.top + drum.height * 0.62f)
    val delWordKey: Rect get() =
        Rect(window.right - edgeW, drum.top, window.right, drum.top + drum.height * 0.62f)
    val cancelLeft: Rect get() =
        Rect(window.left, drum.top + drum.height * 0.62f, window.left + edgeW, drum.bottom)
    val cancelRight: Rect get() =
        Rect(window.right - edgeW, drum.top + drum.height * 0.62f, window.right, drum.bottom)

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
    private var typedSinceCapsChange = false

    // ------------------------------------------------------------- keys

    private class Key(
        val label: String,
        val action: Action,
        val payload: String = "",
        val entry: Set<Side>,
        val commit: Set<Side>,
        val cornerFraction: Float = 0.16f
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
            return when (val ev = engine.step(at, rect, cfg())) {
                is SweepEvent.Entered -> {
                    if (!allow) { engine.reset(); armed = false }
                    else if (coolMs < STANDOFF_MS) { engine.reset(); armed = false }
                    else armed = true
                    // Entering is not firing. A momentary's step() returns the
                    // frame CLAIM here; this returns a COMMIT, and conflating
                    // them types the character the moment the key is touched.
                    false
                }
                is SweepEvent.Engaged -> false
                is SweepEvent.Released -> {
                    val fire = armed && ev.side in commit
                    armed = false
                    if (fire) coolMs = 0L
                    if (fire) {
                        onTrace?.invoke("fire ${ev.side.name.lowercase()} '$label'")
                        return true
                    }
                    false
                }
                SweepEvent.Idle -> { armed = false; false }
            }
        }

        fun reset() { engine.reset(); armed = false; coolMs = Long.MAX_VALUE }
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

    private val topKeys = barSequence.map {
        Key(it.second, it.first, entry = setOf(Side.Bottom), commit = setOf(Side.Top))
    }
    private val bottomKeys = barSequence.map {
        Key(it.second, it.first, entry = setOf(Side.Top), commit = setOf(Side.Bottom))
    }
    private val enterKeyCtl = Key("ENT", Action.ENTER,
        entry = setOf(Side.Right), commit = setOf(Side.Left))
    private val delWordCtl = Key("DEL", Action.DELWORD,
        entry = setOf(Side.Left), commit = setOf(Side.Right))
    private val cancelLeftCtl = Key("X", Action.CANCEL,
        entry = setOf(Side.Bottom), commit = setOf(Side.Top))
    private val cancelRightCtl = Key("X", Action.CANCEL,
        entry = setOf(Side.Bottom), commit = setOf(Side.Top))

    private val keys: List<Key> = topKeys + bottomKeys +
        listOf(enterKeyCtl, delWordCtl, cancelLeftCtl, cancelRightCtl)

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
    var heldLabel: String = ""
        private set

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
    fun isBarKeyHeld(label: String): Boolean =
        heldLabel == label || (heldLabel == "BKSP" && label == "BKSP")

    private var lastX = 0f
    private var lastY = 0f

    /**
     * Milliseconds left during which the NON-character keys refuse to arm.
     *
     * The gesture that types a letter and the gesture that reaches a modifier
     * row are the same motion. Sweeping UP through the letters to type T does
     * not stop at the letters: carry on and you cross the top bar, entering
     * it from below, which is precisely the side it admits — so the tail of
     * every sweep fired whatever sat up there. The log showed it exactly:
     * `type 'T'`, then the gaze walking 125, 84, 52, 28, then
     * `fire top 'abc'`.
     *
     * There is no geometric fix: any path from the letters out of the band
     * passes through a bar whose entry side faces the band. So one sweep does
     * one thing, and the modifiers wait for the next one.
     *
     * A countdown of frame time rather than a clock, so it is testable and so
     * it is measured in the same units the rest of the gesture is.
     */
    private var guardMs = 0L

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
        heldLabel = ""

        // The live row's characters. First to arm takes the traverse; any
        // other that arms while the claim is held is discarded, which is what
        // stops a lateral drag spelling a word.
        guardMs = maxOf(0L, guardMs - dtMs.coerceAtLeast(0L))
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
            heldLabel = claim.label
            owned = true
            if (!band.contains(at)) {
                val vertical = at.y <= band.top || at.y >= band.bottom
                if (vertical && allow) {
                    onTrace?.invoke("type '${claim.payload}'")
                    type(claim.payload)
                } else {
                    onTrace?.invoke("row traverse cancelled")
                }
                for (k in charKeys[activeRow]) k.reset()
                charClaim = null
            }
        }

        // Guarded: see guardMs.
        val keyOk = allow && guardMs <= 0L
        for (k in keys) {
            val fired = k.step(at, dtMs, keyOk)
            if (fired) { run(k.action); heldLabel = k.label }
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
        guardMs = CHAR_GUARD_MS
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
            Action.CANCEL -> cancelled?.invoke()
            Action.CHAR -> Unit
        }
    }

    /** The character to actually append, with the caps mode applied and a
     *  single cap spent. */
    private fun alpha(base: String): String {
        if (base.length != 1) return base
        if (!base[0].isLetter()) return base
        typedSinceCapsChange = true
        return when (capsMode) {
            CapsMode.LOCK -> base.uppercase()
            CapsMode.SINGLE -> { capsMode = CapsMode.LOWER; base.uppercase() }
            CapsMode.LOWER -> base.lowercase()
        }
    }

    private fun cycleCaps() {
        capsMode = if (typedSinceCapsChange) CapsMode.LOWER else when (capsMode) {
            CapsMode.LOWER -> CapsMode.SINGLE
            CapsMode.SINGLE -> CapsMode.LOCK
            CapsMode.LOCK -> CapsMode.LOWER
        }
        typedSinceCapsChange = false
        onTrace?.invoke("caps ${capsMode.name.lowercase()}")
        changed?.invoke()
    }

    private fun setRow(i: Int) {
        if (i == activeRow) return
        // Start the slide from where the drum already is, so the row the user
        // was looking at does not jump on the frame the button fires.
        tumble = (activeRow - i) * rowH
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
        enterKeyCtl.rect = enterKey
        delWordCtl.rect = delWordKey
        cancelLeftCtl.rect = cancelLeft
        cancelRightCtl.rect = cancelRight
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

        /** How long after a character the modifiers are deaf. Long enough to
         *  cover the tail of the sweep that typed it, short enough that the
         *  next deliberate sweep is not refused. */
        const val CHAR_GUARD_MS = 340L

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
