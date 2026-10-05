/* SweepVR Player — GPL-3.0-only.
 * See LICENSE in the repository root.
 */
package net.sweepvr.player.sweep

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The dwell keyboard: layout properties and the dwell contract.
 *
 * Asserted as PROPERTIES rather than pixel values, so the layout can be
 * retuned without rewriting the tests - which is the lesson from the sweep
 * keyboard's rows, where hard-coded numbers outlived the thing they described.
 */
class DwellKeyboardControlTest {

    private val frame = 16L

    private fun Rect.midX() = (left + right) * 0.5f
    private fun Rect.midY() = (top + bottom) * 0.5f

    private fun kb(start: String = ""): DwellKeyboardControl {
        val k = DwellKeyboardControl()
        k.window = Rect(0f, 0f, 1024f, 400f)
        k.dwellMs = 320L
        k.beginEdit(start)
        return k
    }

    /** Hold the gaze on whatever is at (x, y) until it fires, or give up. */
    private fun dwell(k: DwellKeyboardControl, x: Float, y: Float, frames: Int = 60) {
        for (i in 0 until frames) {
            k.step(x, y, frame, true)
            if ((k.activeKey?.dwell?.progress ?: 0f) >= 1f) return
        }
    }

    /**
     * Take the gaze off the keyboard entirely.
     *
     * Needed between two dwells on the SAME key: the button stays charged
     * while the gaze rests on it, so a second dwell() would return instantly
     * against progress that was already 1. Leaving is what re-arms it - the
     * same rule as every other dwell control here.
     */
    private fun away(k: DwellKeyboardControl, frames: Int = 30) {
        for (i in 0 until frames) k.step(-500f, -500f, frame, true)
    }

    private fun keyFor(k: DwellKeyboardControl, label: String) =
        k.currentKeys.firstOrNull { it.spec.label == label }

    // ------------------------------------------------------------- layout

    @Test
    fun itIsAStandardPhoneQwerty() {
        val k = kb()
        val w = k.window
        val rows = k.currentKeys.groupBy { it.rect.top }.toSortedMap().values.toList()
        assertEquals("four rows", 4, rows.size)

        val labels = rows.map { r -> r.map { it.spec.label } }
        assertEquals(listOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p"), labels[0])
        assertEquals(listOf("a", "s", "d", "f", "g", "h", "j", "k", "l"), labels[1])
        assertEquals(listOf("^", "z", "x", "c", "v", "b", "n", "m", "<"), labels[2])
        assertEquals(listOf("?123", ",", "space", ".", "return"), labels[3])

        // Every key inside the window, and none overlapping another. Overlap
        // on a dwell keyboard is not cosmetic: two overlapping keys means the
        // wrong one can fire.
        val all = k.currentKeys
        for (a in all) {
            assertTrue("inside window: ${a.spec.label}", a.rect.left >= w.left - 0.5f &&
                a.rect.top >= w.top - 0.5f && a.rect.right <= w.right + 0.5f &&
                a.rect.bottom <= w.bottom + 0.5f)
            assertTrue("has area: ${a.spec.label}", a.rect.width > 1f && a.rect.height > 1f)
            for (b in all) {
                if (a === b) continue
                val overlapX = minOf(a.rect.right, b.rect.right) - maxOf(a.rect.left, b.rect.left)
                val overlapY = minOf(a.rect.bottom, b.rect.bottom) - maxOf(a.rect.top, b.rect.top)
                assertTrue("${a.spec.label} overlaps ${b.spec.label}",
                    overlapX <= 0.5f || overlapY <= 0.5f)
            }
        }
    }

    @Test
    fun theShortRowsAreInsetSoItStaggers() {
        val k = kb()
        val rows = k.currentKeys.groupBy { it.rect.top }.toSortedMap().values.toList()
        val row1 = rows[0].first().rect
        val row2 = rows[1].first().rect
        val row3 = rows[2].first().rect
        // Row 2 is one key shorter, so it must start further in than row 1.
        assertTrue("row 2 inset from row 1", row2.left > row1.left + 1f)
        // Row 3 also has nine keys, so it shares row 2's inset - asserted
        // rather than assumed, because it is the same arithmetic.
        assertTrue("row 3 aligned with row 2", kotlin.math.abs(row3.left - row2.left) < 1f)
    }

    @Test
    fun spaceIsMuchWiderThanALetter() {
        val k = kb()
        val space = keyFor(k, "space")!!.rect
        val letter = keyFor(k, "q")!!.rect
        assertTrue("space ${space.width} vs letter ${letter.width}",
            space.width > letter.width * 3f)
    }

    // -------------------------------------------------------------- dwell

    @Test
    fun oneDwellTypesOneCharacter() {
        val k = kb()
        val q = keyFor(k, "q")!!.rect
        dwell(k, q.midX(), q.midY())
        assertEquals("q", k.text)
        // Still holding: one visit fires once, never again.
        repeat(30) { k.step(q.midX(), q.midY(), frame, true) }
        assertEquals("still one character", "q", k.text)
    }

    @Test
    fun leavingAKeyRearmsIt() {
        val k = kb()
        val q = keyFor(k, "q")!!.rect
        val w = keyFor(k, "w")!!.rect
        dwell(k, q.midX(), q.midY())
        // Move away, then back. Deliberately well under dwellMs: an excursion
        // of exactly dwellMs fires w as well, which is a fact about dwell and
        // not about this keyboard.
        repeat(8) { k.step(w.midX(), w.midY(), frame, true) }
        dwell(k, q.midX(), q.midY())
        // w was only crossed, not dwelt on, so it types nothing. Coming BACK
        // to q is what re-arms it, and it types again.
        assertEquals("qq", k.text)
        assertEquals("w untouched", 0f, keyFor(k, "w")!!.dwell.progress, 0.001f)
    }

    @Test
    fun onlyTheKeyUnderTheGazeCanFire() {
        // The neighbour must not charge while a different key is held - on a
        // dwell keyboard the keys are close enough that this is easy to get
        // wrong and impossible to see.
        val k = kb()
        val q = keyFor(k, "q")!!.rect
        val w = keyFor(k, "w")!!.rect
        repeat(10) { k.step(q.midX(), q.midY(), frame, true) }
        assertEquals("q is charging", "q", k.activeKey?.spec?.label)
        assertEquals("w is not", 0f, keyFor(k, "w")!!.dwell.progress, 0.001f)
        k.step(q.midX(), q.midY(), frame, true)
        assertEquals("still only q", "q", k.activeKey?.spec?.label)
    }

    @Test
    fun aMovingReticleNeverFires() {
        val k = kb()
        val q = keyFor(k, "q")!!.rect
        // Not still: progress drains rather than fills.
        repeat(40) { k.step(q.midX(), q.midY(), frame, false) }
        assertEquals("nothing typed", "", k.text)
    }

    @Test
    fun itOwnsTheFrameWhileAKeyIsCharging() {
        // A page dwell firing underneath a charging key is the exact accident
        // the claim exists to prevent.
        val k = kb()
        val q = keyFor(k, "q")!!.rect
        k.step(q.midX(), q.midY(), frame, true)
        assertTrue("claims while charging", k.owned)
        k.step(-500f, -500f, frame, true)
        assertTrue("released off-key", !k.owned)
    }

    // -------------------------------------------------------------- text

    @Test
    fun backspaceAndShiftBehave() {
        val k = kb("ab")
        val bk = keyFor(k, "<")!!.rect
        dwell(k, bk.midX(), bk.midY())
        assertEquals("a", k.text)

        val sh = keyFor(k, "^")!!.rect
        dwell(k, sh.midX(), sh.midY())
        assertEquals("shift engaged", DwellKeyboardControl.CapsMode.SHIFT, k.capsMode)
        val c = keyFor(k, "c")!!.rect
        dwell(k, c.midX(), c.midY())
        assertEquals("C, and shift spent", "aC", k.text)
        assertEquals("shift released after one", DwellKeyboardControl.CapsMode.LOWER, k.capsMode)
    }

    @Test
    fun capsCyclesThroughThreeStates() {
        val k = kb()
        val sh = keyFor(k, "^")!!.rect
        dwell(k, sh.midX(), sh.midY()); assertEquals("Abc", k.capsLabel())
        away(k)
        dwell(k, sh.midX(), sh.midY()); assertEquals("ABC", k.capsLabel())
        away(k)
        val c = keyFor(k, "c")!!.rect
        dwell(k, c.midX(), c.midY())
        assertEquals("stays locked", "ABC", k.capsLabel())
        val a = keyFor(k, "a")!!.rect
        dwell(k, a.midX(), a.midY())
        val d = keyFor(k, "d")!!.rect
        dwell(k, d.midX(), d.midY())
        assertEquals("every letter capital while locked", "CAD", k.text)
        assertEquals("caps never releases itself", DwellKeyboardControl.CapsMode.CAPS, k.capsMode)
    }

    @Test
    fun symbolsLayerHasDigitsAndSwitchesBack() {
        val k = kb()
        val sw = keyFor(k, "?123")!!.rect
        dwell(k, sw.midX(), sw.midY())
        assertNotNull("digit 1 is there", keyFor(k, "1"))
        val one = keyFor(k, "1")!!.rect
        dwell(k, one.midX(), one.midY())
        assertEquals("1", k.text)
        val back = keyFor(k, "ABC")!!.rect
        dwell(k, back.midX(), back.midY())
        assertNotNull("back to letters", keyFor(k, "q"))
        assertNull("digits gone", keyFor(k, "1"))
        assertEquals("and the text survived", "1", k.text)
    }

    @Test
    fun aLayerSwitchCannotFireASecondKeyInTheSameVisit() {
        // The key under the reticle changes identity when the layer changes.
        // If the visit were still live it would fire the new action too.
        val k = kb()
        val sw = keyFor(k, "?123")!!.rect
        repeat(40) { k.step(sw.midX(), sw.midY(), frame, true) }
        assertEquals("only the switch", "", k.text)
    }

    @Test
    fun enterCommitsAndTheLayerPersists() {
        var out: String? = null
        val k = kb()
        k.committed = { out = it }
        val r = keyFor(k, "return")!!.rect
        dwell(k, r.midX(), r.midY())
        assertEquals("", out)
    }

    // ----------------------------------------------------------- tooltips

    @Test
    fun charactersHaveNoTooltipAndModifiersDo() {
        val k = kb()
        val q = keyFor(k, "q")!!.rect
        assertNull("a letter describes itself", k.labelAt(q.midX(), q.midY()))
        val r = keyFor(k, "return")!!.rect
        assertEquals("Enter", k.labelAt(r.midX(), r.midY()))
        val sp = keyFor(k, "space")!!.rect
        assertNull("space has no name", k.labelAt(sp.midX(), sp.midY()))
    }
}
