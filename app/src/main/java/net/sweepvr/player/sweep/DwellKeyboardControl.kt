/* SweepVR Player — GPL-3.0-only.
 * See LICENSE in the repository root.
 */
package net.sweepvr.player.sweep

/**
 * A QWERTY keyboard for when sweep controls are OFF, where a key fires by
 * dwelling on it rather than by sweeping through it.
 *
 * This is deliberately a SEPARATE control from [KeyboardControl] rather than a
 * mode of it. The drum keyboard's whole shape - a tumbler of rows with one
 * live row, entry sides on every key, a band you traverse through - is the
 * sweep gesture, and none of it survives when the gesture is gone. Sharing a
 * class would mean a keyboard that is half one design and half the other, and
 * every one of its fields would need a "which keyboard am I" answer. What is
 * genuinely shared is the text model and the callbacks, and those are small.
 *
 * What is borrowed from the sweep keyboard, having been learned there:
 *
 *  - The layout is DATA, and the drawn rects and the hit rects come from the
 *    same [layout] pass. The sweep keyboard once had its drawn geometry and
 *    its hit geometry restated in two places, and they drifted.
 *  - Nothing here decides anything about entry sides. A dwell key's whole
 *    rect counts, and there is no corner rule to get wrong.
 *  - Feedback is part of the feature, not polish. [progress] is exposed so the
 *    renderer can fill the key as it fills, because a key that lights only
 *    when it fires gives nothing to look at while it is charging.
 */
class DwellKeyboardControl {

    /** How capitals are applied, matching the sweep keyboard's three states. */
    enum class CapsMode { LOWER, SHIFT, CAPS }

    enum class Kind { CHAR, SPACE, SHIFT, BKSP, ENTER, CLEAR, DELWORD, SWITCH }

    class KeySpec(
        val label: String,
        val kind: Kind,
        val payload: String = "",
        /** Width relative to a letter. Wide keys (space) get more. */
        val weight: Float = 1f
    )

    /** One key: its spec, its rect, and its dwell engine. */
    class Key(val spec: KeySpec) {
        var rect: Rect = Rect(0f, 0f, 0f, 0f)
        val dwell = DwellButton()
        fun reset() { dwell.reset() }
    }

    // ---------------------------------------------------------------- text

    var text: String = ""
        private set

    /** What [text] was when the edit began, so X can put it back. */
    var initialText: String = ""
        private set

    var capsMode: CapsMode = CapsMode.LOWER
        private set

    fun beginEdit(value: String) {
        initialText = value
        text = value
        capsMode = CapsMode.LOWER
        built.forEach { it.reset() }
        changed?.invoke()
    }

    /** Forget the gesture, keeping the text. Never fires. */
    fun reset() { built.forEach { it.reset() } }

    var changed: (() -> Unit)? = null
    var committed: ((String) -> Unit)? = null
    var cancelled: (() -> Unit)? = null
    var onTrace: ((String) -> Unit)? = null

    // -------------------------------------------------------------- layout

    /**
     * The two layers, as data.
     *
     * Standard phone shape: ten, nine, then shift + seven + backspace, then
     * the bottom row. Row 2 is shorter than row 1 and row 3 is centred, which
     * is what makes a QWERTY grid readable as QWERTY rather than as four
     * equal strips - the stagger is the pattern.
     */
    private enum class Layer { ALPHA, SYMBOLS }

    private val layers: Map<Layer, List<List<KeySpec>>> = mapOf(
        Layer.ALPHA to listOf(
            listOf(*"qwertyuiop".map { KeySpec(it.toString(), Kind.CHAR, it.toString()) }.toTypedArray()),
            listOf(*"asdfghjkl".map { KeySpec(it.toString(), Kind.CHAR, it.toString()) }.toTypedArray()),
            listOf(KeySpec("^", Kind.SHIFT)) +
                "zxcvbnm".map { KeySpec(it.toString(), Kind.CHAR, it.toString()) } +
                listOf(KeySpec("<", Kind.BKSP)),
            listOf(
                KeySpec("?123", Kind.SWITCH),
                KeySpec(",", Kind.CHAR, ","),
                KeySpec("space", Kind.SPACE, " ", 4.6f),
                KeySpec(".", Kind.CHAR, "."),
                KeySpec("return", Kind.ENTER)
            )
        ),
        Layer.SYMBOLS to listOf(
            listOf(*"1234567890".map { KeySpec(it.toString(), Kind.CHAR, it.toString()) }.toTypedArray()),
            listOf(*listOf("-", "/", ":", ";", "(", ")", "$", "&", "@", "\"")
                .map { KeySpec(it, Kind.CHAR, it) }.toTypedArray()),
            listOf(KeySpec("^", Kind.SHIFT)) +
                "'.,?!_".map { KeySpec(it.toString(), Kind.CHAR, it.toString()) } +
                listOf(KeySpec("<", Kind.BKSP)),
            listOf(
                KeySpec("ABC", Kind.SWITCH),
                KeySpec("clear", Kind.CLEAR, "", 1.6f),
                KeySpec("space", Kind.SPACE, " ", 4.6f),
                KeySpec("delword", Kind.DELWORD, "", 1.6f),
                KeySpec("return", Kind.ENTER)
            )
        )
    )

    private var layer = Layer.ALPHA

    /**
     * The live keys, row-major, rebuilt whenever the layer changes.
     *
     * Built once and laid out by a parallel walk of the same rows, so a key's
     * rect can never be assigned to a different key than the one it belongs
     * to. An earlier version searched the list by identity to find the index,
     * which is the sort of thing that is quietly wrong the moment two keys
     * share a spec.
     */
    private var built: List<Key> = emptyList()

    private fun rebuild() {
        built = layers[layer]!!.flatten().map { spec ->
            Key(spec).also { k ->
                k.dwell.dwellMs = dwellMs
                // onFire, rather than watching progress for 1. DwellButton
                // already owns the once-per-visit rule with its own fired
                // flag; polling for 1 instead gave me a SECOND copy of that
                // rule, and fire() then reset every key - including the one
                // that had just fired - so the flag cleared and the same key
                // fired again and again without the gaze ever leaving.
                k.dwell.onFire = { fire(k) }
            }
        }
    }

    var window: Rect = Rect(0f, 0f, 0f, 0f)
        set(v) { field = v; layout() }

    /** How long a key must be held to fire.
     *
     *  Applied to each key when it is built. It is a var rather than a const
     *  so it can be retuned, and an earlier version changed it without ever
     *  handing it to the buttons - so every key silently kept DwellButton's
     *  own 1500ms default and nothing could fire at the requested time. */
    var dwellMs: Long = 900L
        set(v) {
            field = v
            built.forEach { it.dwell.dwellMs = v }
        }

    /** How long a fired key stays lit. */
    val HOLD_MS = 150L

    private val pad get() = window.height * 0.018f
    private val gap get() = window.height * 0.012f

    /**
     * Every rect from [window], in one pass, before any engine sees a
     * coordinate. Rows are laid out from the TOP for this keyboard - unlike
     * the drum, whose rows are measured from the middle because the live row
     * has to sit on the centre line.
     */
    private fun layout() {
        if (window.isEmpty) return
        if (built.isEmpty()) rebuild()
        val rows = layers[layer]!!
        val rh = (window.height - pad * 2f) / rows.size
        val rowH = rh - gap
        var y = window.top + pad
        var n = 0
        for (row in rows) {
            // Weights decide the width, and the row is then CENTRED in the
            // full width, so a short row ends up inset symmetrically. That
            // inset is the QWERTY stagger, and it falls out of the arithmetic
            // rather than being hard-coded per row.
            val total = row.sumOf { it.weight.toDouble() }.toFloat()
            val span = unit * total + gap * (row.size - 1)
            var x = window.left + (window.width - span) * 0.5f
            val u = unit
            for (spec in row) {
                val w = u * spec.weight
                val r = Rect(x, y, x + w, y + rowH)
                built[n].rect = r
                // The button keeps its OWN rect, and a dwell step reads that
                // one - not the Key's. Setting only the Key's left every
                // button with an empty rect, so every step bailed on
                // `isEmpty` and no key could ever fire. Two rects, one
                // layout: they are assigned from the same value, here.
                built[n].dwell.rect = r
                x += w + gap
                n++
            }
            y += rh
        }
    }

    private val unit: Float
        get() = (window.width - gap * (layers[layer]!!.first().size - 1)) /
            layers[layer]!!.first().sumOf { it.weight.toDouble() }.toFloat()

    /** The live keys, in layout order. */
    val currentKeys: List<Key> get() = built

    fun keyLabelAt(x: Float, y: Float): Key? {
        val at = Pt(x, y)
        return built.firstOrNull { it.rect.contains(at) }
    }

    /**
     * A spoken-word name for the key under a point, for the hover tooltip.
     * Characters are excluded: their own glyph is the description.
     */
    fun labelAt(x: Float, y: Float): String? {
        val k = keyLabelAt(x, y) ?: return null
        return when (k.spec.kind) {
            Kind.CHAR, Kind.SPACE -> null
            Kind.SHIFT -> "Caps: " + capsLabel()
            Kind.BKSP -> "Backspace"
            Kind.ENTER -> "Enter"
            Kind.CLEAR -> "Clear"
            Kind.DELWORD -> "Delete word"
            Kind.SWITCH -> if (layer == Layer.ALPHA) "Symbols" else "Letters"
        }
    }

    fun capsLabel(): String = when (capsMode) {
        CapsMode.LOWER -> "abc"
        CapsMode.SHIFT -> "Abc"
        CapsMode.CAPS -> "ABC"
    }

    /**
     * The key to light up: whatever is charging, or - just after it fired -
     * the one that did, for [HOLD_MS].
     *
     * A dwell key that goes dark the instant it fires gives no confirmation
     * at all, because the fire IS the moment you look for. The sweep keyboard
     * learned this the hard way: its highlight lasted one frame and read as
     * no feedback whatsoever.
     */
    var activeKey: Key? = null
        private set

    private var holdKey: Key? = null
    private var holdMs = 0L

    /** True while a key is charging or held, so the owner can claim the
     *  frame. A dwell firing underneath a page click is the exact accident
     *  this claim exists to prevent. */
    var owned: Boolean = false
        private set

    /**
     * Advance one frame. Returns true while this keyboard owns the gaze.
     *
     * Only the key actually under the reticle is stepped, so a key cannot
     * charge while another is held, and leaving a key resets it - holding the
     * gaze fires once per visit, never repeatedly.
     */
    fun step(x: Float, y: Float, dtMs: Long, still: Boolean = true): Boolean {
        if (window.isEmpty) { reset(); owned = false; return false }
        layout()
        val under = keyLabelAt(x, y)

        // Every button the gaze is NOT on goes cold. This is what makes leaving
        // a key re-arm it, and it is what stops two neighbouring keys charging
        // at once - on a dwell keyboard they are close enough that both being
        // live is easy to arrange and impossible to see. An earlier edit
        // dropped this loop, and a fired key then stayed charged for ever:
        // one visit, one fire, and no way to fire that key again.
        for (k in built) {
            if (k !== under && k.dwell.progress > 0f) k.reset()
        }

        owned = false
        holdMs = maxOf(0L, holdMs - dtMs.coerceAtLeast(0L))
        if (holdMs <= 0L) holdKey = null
        if (under == null) { activeKey = holdKey; return false }
        val p = under.dwell.step(x, y, still, dtMs)
        owned = p > 0f
        activeKey = if (p > 0f) under else holdKey
        return owned
    }

    // ------------------------------------------------------------- actions

    private fun fire(key: Key) {
        val spec = key.spec
        when (spec.kind) {
            Kind.CHAR -> type(spec.payload)
            Kind.SPACE -> type(" ")
            Kind.SHIFT -> {
                capsMode = when (capsMode) {
                    CapsMode.LOWER -> CapsMode.SHIFT
                    CapsMode.SHIFT -> CapsMode.CAPS
                    CapsMode.CAPS -> CapsMode.LOWER
                }
                changed?.invoke()
            }
            Kind.BKSP -> {
                if (text.isNotEmpty()) { text = text.dropLast(1); changed?.invoke() }
            }
            Kind.CLEAR -> { text = ""; changed?.invoke() }
            Kind.DELWORD -> {
                val t = text.trimEnd()
                val cut = t.lastIndexOf(' ')
                text = if (cut < 0) "" else t.substring(0, cut + 1)
                changed?.invoke()
            }
            Kind.ENTER -> committed?.invoke(text)
            Kind.SWITCH -> {
                layer = if (layer == Layer.ALPHA) Layer.SYMBOLS else Layer.ALPHA
                built = emptyList()
                layout()
                changed?.invoke()
            }
        }
        // Swallow the REST of this visit. Needed only because a layer switch
        // replaces every key: the new key under the reticle starts with a
        // clean fired flag and would otherwise charge immediately, turning one
        // dwell into two actions.
        // The key ITSELF, not a fresh one built from the spec: the renderer
        // positions the highlight from this key's rect, and a copy would have
        // an empty one.
        holdKey = key
        holdMs = HOLD_MS
    }

    /** Append a character with caps applied, spending a single shift. */
    private fun type(c: String) {
        if (c.isEmpty()) return
        text += alpha(c)
        changed?.invoke()
    }

    private fun alpha(base: String): String {
        if (base.length != 1 || !base[0].isLetter()) return base
        return when (capsMode) {
            CapsMode.CAPS -> base.uppercase()
            CapsMode.SHIFT -> { capsMode = CapsMode.LOWER; base.uppercase() }
            CapsMode.LOWER -> base.lowercase()
        }
    }
}
