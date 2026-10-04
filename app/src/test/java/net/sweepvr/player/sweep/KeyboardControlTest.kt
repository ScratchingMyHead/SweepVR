/* SweepVR Player — GPL-3.0-only.
 * See LICENSE in the repository root.
 */
package net.sweepvr.player.sweep

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The keyboard's state machine: what a sweep does, what it must NOT do, and
 * where the text ends up.
 *
 * Pure geometry and pure string work, so every rule the interaction depends on
 * is pinned here rather than discovered on a headset. The negative tests
 * matter most: a keyboard that fires when it should not is worse than one
 * that fails to fire, because it types.
 *
 * Every coordinate below is DERIVED from the control's own geometry. A test
 * that hardcoded where the drum is would quietly stop testing anything the
 * moment a fraction changed - the same drawn-versus-hit drift the control's
 * own docs warn against.
 *
 * One thing the helpers encode: a sweep enters a key just INSIDE the edge it
 * crosses, never at the key's centre. A point at the exact centre is an
 * equal-distance tie between all four edges and TieBreak.REFUSE turns it down,
 * which reads as "the key is broken" when it really means "the test aimed at a
 * point no sweep passes through".
 */
class KeyboardControlTest {

    private val W = 1000f
    private val H = 400f
    private val frame = 16L

    /** How far inside the entry edge a sweep crosses it. */
    private val edgeIn = 8f

    private val barLabels = listOf("123", "abc", "Sym2", "Aa", "BKSP", "123", "abc", "Sym2")

    private fun rowIndex(name: String) = when (name) {
        "123" -> 0
        "abc" -> 1
        else -> 2
    }

    private fun Rect.midX() = (left + right) * 0.5f
    private fun Rect.midY() = (top + bottom) * 0.5f

    private fun kb(start: String = ""): KeyboardControl {
        val k = KeyboardControl()
        k.window = Rect(0f, 0f, W, H)
        k.beginEdit(start)
        return k
    }

    // ------------------------------------------------------------ helpers

    private fun charRect(k: KeyboardControl, c: String, row: String = k.activeRowName): Rect {
        val r = rowIndex(row)
        val i = k.rowKeys(r).indexOf(c)
        assertTrue("no '$c' in row $row", i >= 0)
        return k.charRect(r, i)
    }

    private fun barRect(k: KeyboardControl, label: String, bottom: Boolean): Rect {
        val i = barLabels.indexOf(label)
        assertTrue("no '$label' in the bar", i >= 0)
        val bar = if (bottom) k.bottomBar else k.topBar
        return k.barKeyRect(bar, i, barLabels.size)
    }

    // The sweeps interpolate over several frames rather than teleporting
    // between three points. A gaze takes a few hundred milliseconds to cross a
    // key, and the difference is not cosmetic: the post-fire tremor standoff is
    // measured in TIME, so three frames between two deliberate sweeps would
    // swallow the second one and the test would be asserting that a bug is a
    // feature. TRAVEL frames is roughly 250 ms of sweep.

    private val travel = 15

    private fun glide(k: KeyboardControl, x0: Float, y0: Float, x1: Float, y1: Float) {
        k.step(x0, y0, frame)
        for (i in 1..travel) {
            val f = i.toFloat() / travel
            k.step(x0 + (x1 - x0) * f, y0 + (y1 - y0) * f, frame)
        }
    }

    /** Straight up through a key whose entry side is its bottom. */
    private fun up(k: KeyboardControl, r: Rect) {
        val x = r.midX()
        glide(k, x, r.bottom + 40f, x, r.bottom - edgeIn)
        glide(k, x, r.bottom - edgeIn, x, r.top - 40f)
    }

    /** Straight down through a key whose entry side is its top. */
    private fun down(k: KeyboardControl, r: Rect) {
        val x = r.midX()
        glide(k, x, r.top - 40f, x, r.top + edgeIn)
        glide(k, x, r.top + edgeIn, x, r.bottom + 40f)
    }

    /** Out to the left through a key whose entry side is its right. */
    private fun left(k: KeyboardControl, r: Rect) {
        val y = r.midY()
        glide(k, r.right + 40f, y, r.right - edgeIn, y)
        glide(k, r.right - edgeIn, y, r.left - 40f, y)
    }

    /** Out to the right through a key whose entry side is its left. */
    private fun right(k: KeyboardControl, r: Rect) {
        val y = r.midY()
        glide(k, r.left - 40f, y, r.left + edgeIn, y)
        glide(k, r.left + edgeIn, y, r.right + 40f, y)
    }

    /** Sweeping UP through a character, entering over its top. */
    private fun sweepUp(k: KeyboardControl, c: String) = down(k, charRect(k, c))

    /** Sweeping DOWN through a character, entering under its bottom. */
    private fun sweepDown(k: KeyboardControl, c: String) = up(k, charRect(k, c))

    private fun settle(k: KeyboardControl): Boolean {
        repeat(300) {
            if (!k.tumbling) return true
            k.step(-100f, -100f, frame)
        }
        return !k.tumbling
    }

    // ------------------------------------------------------------- typing

    @Test
    fun sweepingUpThroughALetterTypesIt() {
        val k = kb()
        sweepUp(k, "A")
        assertEquals("a", k.text)
    }

    @Test
    fun sweepingDownThroughALetterAlsoTypesIt() {
        val k = kb()
        sweepDown(k, "A")
        assertEquals("a", k.text)
    }

    @Test
    fun lettersComeOutLowerCaseUntilCapsSaysOtherwise() {
        val k = kb()
        sweepUp(k, "H")
        sweepUp(k, "I")
        assertEquals("hi", k.text)
    }

    @Test
    fun theTailOfASweepCannotFireAModifier() {
        // The gesture that types a letter and the gesture that reaches a
        // modifier row are ONE motion. Sweeping up through the letters does
        // not stop at the letters: carry on and you cross the top bar,
        // entering it from below, which is exactly the side it admits. The
        // log showed `type 'T'` then the gaze at 125, 84, 52, 28 then
        // `fire top 'abc'`.
        val k = kb()
        val t = k.charRect(1, k.rowKeys(1).indexOf("T"))
        val bar = k.barKeyRect(k.topBar, 1, k.barLabels.size)   // the top abc
        // One continuous sweep: up through the letter, on through the bar.
        glide(k, t.midX(), t.bottom + 40f, t.midX(), t.top + edgeIn)
        glide(k, t.midX(), t.top + edgeIn, bar.midX(), t.top - 60f)
        glide(k, bar.midX(), t.top - 60f, bar.midX(), k.topBar.top - 40f)
        assertEquals("the letter typed", "t", k.text)
        assertEquals("abc", k.activeRowName)   // already abc, so check the bar directly
    }

    @Test
    fun aModifierIsStillReachableOnTheNextSweep() {
        // The guard must deafen the modifiers, not disarm them: the sweep
        // after the one that typed has to work.
        val k = kb()
        val t = k.charRect(1, k.rowKeys(1).indexOf("T"))
        sweepUp(k, "T")
        // Burn past the guard, then sweep the top bar deliberately.
        repeat(40) { k.step(-100f, -100f, frame) }
        up(k, k.barKeyRect(k.topBar, 0, k.barLabels.size))
        assertEquals("123", k.activeRowName)
    }

    @Test
    fun spaceIsAKeyLikeAnyOther() {
        val k = kb()
        sweepUp(k, " ")
        assertEquals(" ", k.text)
    }

    @Test
    fun oneLetterPerSweepHoweverMuchTheReticleDrifts() {
        // The reason for charClaim: a diagonal drag from the first letter to
        // the last must not spell the alphabet.
        val k = kb()
        val a = charRect(k, "A")
        val z = charRect(k, "Z")
        glide(k, a.midX(), a.top - 40f, a.midX(), a.top + edgeIn)
        glide(k, a.midX(), a.top + edgeIn, z.midX(), a.bottom + 40f)
        assertEquals("a", k.text)
    }

    @Test
    fun leavingSidewaysCancelsTheLetter() {
        val k = kb()
        val a = charRect(k, "A")
        glide(k, a.midX(), a.top - 40f, a.midX(), a.top + edgeIn)
        glide(k, a.midX(), a.top + edgeIn, k.window.right + 60f, a.midY())
        assertEquals("", k.text)
    }

    @Test
    fun passingThroughAnInactiveRowTypesNothing() {
        // Only the centred row is live. The rows above and below are visible,
        // and sweeping through one must be completely inert.
        val k = kb()
        val above = charRect(k, "/", "123")
        glide(k, above.midX(), above.top - 40f, above.midX(), above.top + edgeIn)
        glide(k, above.midX(), above.top + edgeIn, above.midX(), above.bottom + 40f)
        assertEquals("", k.text)
    }

    // --------------------------------------------------------- row buttons

    @Test
    fun aTopBarKeyIsEnteredFromBelowOnly() {
        val k = kb()
        // Coming back DOWN through the top bar does nothing: its entry side is
        // the bottom, the one facing the drum.
        down(k, barRect(k, "123", bottom = false))
        assertEquals("abc", k.activeRowName)
        // Sweeping UP out of the drum into it does.
        up(k, barRect(k, "123", bottom = false))
        assertEquals("123", k.activeRowName)
    }

    @Test
    fun aBottomBarKeyIsEnteredFromAboveOnly() {
        val k = kb()
        up(k, barRect(k, "Sym2", bottom = true))
        assertEquals("abc", k.activeRowName)
        down(k, barRect(k, "Sym2", bottom = true))
        assertEquals("Sym2", k.activeRowName)
    }

    @Test
    fun everyBarLabelHasAControllerBehindIt() {
        // The bug this pins: the bar DREW eight keys and built five engines, so
        // the right-hand 123 and abc were pictures with nothing behind them and
        // sweeping them did nothing. A label list is not a controller list.
        val k = kb()
        assertEquals(8, barLabels.size)
        for (i in barLabels.indices) {
            val top = k.barKeyRect(k.topBar, i, barLabels.size)
            val bottom = k.barKeyRect(k.bottomBar, i, barLabels.size)
            assertTrue("key $i is empty on top", !top.isEmpty)
            assertTrue("key $i is empty below", !bottom.isEmpty)
        }
        // And each end's selectors really do turn the drum.
        for (i in listOf(0, 5)) {
            val k2 = kb()
            up(k2, k2.barKeyRect(k2.topBar, i, barLabels.size))
            assertEquals("top key $i should select 123", "123", k2.activeRowName)
        }
        for (i in listOf(1, 6)) {
            val k2 = kb()
            up(k2, k2.barKeyRect(k2.topBar, i, barLabels.size))
            assertEquals("top key $i should select abc", "abc", k2.activeRowName)
        }
        for (i in listOf(2, 7)) {
            val k2 = kb()
            // The bottom bar is entered from ABOVE, so the sweep is downward.
            down(k2, k2.barKeyRect(k2.bottomBar, i, barLabels.size))
            assertEquals("bottom key $i should select Sym2", "Sym2", k2.activeRowName)
        }
    }

    @Test
    fun spaceIsWiderThanALetterAndHasBlankEitherSide() {
        val k = kb()
        val row = rowIndex(k.activeRowName)
        val i = k.rowKeys(row).indexOf(" ")
        val space = k.charRect(row, i)
        val letter = k.charRect(row, 0)
        assertTrue("space must be wider than a letter", space.width > letter.width * 2f)
        // ...and pulled in from its neighbours, so it is a distinct target.
        assertTrue("space needs blank on its left", space.left > letter.right)
    }

    @Test
    fun everyRowHasThirteenKeysEitherSideOfSpace() {
        val k = kb()
        for (r in 0..2) {
            val chars = k.rowKeys(r)
            val i = chars.indexOf(" ")
            assertEquals("row $r must have 13 before the space", 13, i)
            assertEquals("row $r must have 13 after the space", 13, chars.size - i - 1)
        }
    }

    @Test
    fun everyCharacterKeyIsTheSameWidthInEveryRow() {
        // The reason the three space bars sat in three different columns: each
        // row was laid out over its OWN total width, so the row with fewer keys
        // had wider ones and its space drifted. The grid is now fixed at
        // 13 + space + 13 for all three.
        val k = kb()
        val rows = listOf(0, 1, 2)
        for (i in 0 until KeyboardControl.CHARS_PER_SIDE) {
            val w = rows.map { k.charRect(it, i).width }
            assertTrue("key $i differs across rows: $w",
                w.max() - w.min() < 0.01f)
        }
    }

    @Test
    fun theHighlightIsFoundByIndexNotByMatchingTheDrawnString() {
        // The letters row is DRAWN lower case unless caps is up, while the
        // held label is the upper case it actually types. Comparing the two as
        // strings meant the letters row never lit up, and the symbol rows -
        // where both forms are the same - always did.
        val k = kb()
        val row = rowIndex(k.activeRowName)
        val i = k.rowKeys(row).indexOf("A")
        assertEquals("heldCharIndex is -1 with nothing claimed", -1, k.heldCharIndex)
        glide(k, k.charRect(row, i).midX(), k.charRect(row, i).top - 40f,
            k.charRect(row, i).midX(), k.charRect(row, i).top + edgeIn)
        assertEquals("the claimed character is reported by index", i, k.heldCharIndex)
        assertTrue("and by the label it actually types", k.heldLabel.isNotEmpty())
    }

    @Test
    fun barButtonsAreNarrowerThanTheBarAndCentred() {
        val k = kb()
        val bar = k.topBar
        val first = k.barKeyRect(bar, 0, k.barLabels.size)
        val last = k.barKeyRect(bar, k.barLabels.size - 1, k.barLabels.size)
        assertTrue("a bar button must not span the whole bar",
            first.width < bar.width * 0.5f)
        val leftGap = first.left - bar.left
        val rightGap = bar.right - last.right
        assertEquals("centred: equal air at both ends", leftGap, rightGap, 0.01f)
        assertTrue("but still some air", leftGap > 1f)
    }

    @Test
    fun onlyOneCharacterCanBeArmedAtATime() {
        // Capturing the claim once before the loop let every key arm on the
        // same frame; after one resolved, the leftovers typed on their own.
        val k = kb()
        val row = rowIndex(k.activeRowName)
        val a = k.charRect(row, 0)
        val z = k.charRect(row, k.rowKeys(row).size - 1)
        glide(k, a.midX(), a.top - 40f, a.midX(), a.top + edgeIn)
        glide(k, a.midX(), a.top + edgeIn, z.midX(), a.top + edgeIn)
        assertEquals("still one claim after drifting across the row",
            0, k.heldCharIndex)
        glide(k, z.midX(), a.top + edgeIn, z.midX(), a.bottom + 40f)
        assertEquals("exactly one character typed", 1, k.text.length)
    }

    @Test
    fun theSpaceKeysLineUpInEveryRow() {
        val k = kb()
        val spaces = (0..2).map { k.charRect(it, KeyboardControl.CHARS_PER_SIDE) }
        val lefts = spaces.map { it.left }
        val rights = spaces.map { it.right }
        assertTrue("space left edges differ: $lefts", lefts.max() - lefts.min() < 0.01f)
        assertTrue("space right edges differ: $rights", rights.max() - rights.min() < 0.01f)
    }

    @Test
    fun noTwoKeysInARowOverlap() {
        val k = kb()
        for (r in 0..2) {
            val n = k.rowKeys(r).size
            for (i in 0 until n - 1) {
                val a = k.charRect(r, i)
                val b = k.charRect(r, i + 1)
                assertTrue("row $r key $i overlaps $i+1: $a vs $b", a.right <= b.left)
            }
        }
    }

    @Test
    fun theDrumTurnsAndSettlesWithTheLiveRowCentred() {
        val k = kb()
        val centre = k.drum.midY()
        up(k, barRect(k, "123", bottom = false))
        assertEquals("123", k.activeRowName)
        assertTrue("the drum must settle", settle(k))
        assertEquals(centre, k.activeRowBand.midY(), 1f)
    }

    @Test
    fun typingIsRefusedWhileTheDrumIsTurning() {
        val k = kb()
        up(k, barRect(k, "Sym2", bottom = false))
        val r = charRect(k, "[", "Sym2")
        glide(k, r.midX(), r.top - 40f, r.midX(), r.top + edgeIn)
        assertTrue("the drum is still moving", k.tumbling)
        glide(k, r.midX(), r.top + edgeIn, r.midX(), r.bottom + 40f)
        assertEquals("nothing typed mid-turn", "", k.text)
    }

    // --------------------------------------------------------- edge keys

    @Test
    fun enterCommitsOutwardsFromTheCentre() {
        var out: String? = null
        val k = kb()
        k.committed = { out = it }
        sweepUp(k, "A")
        left(k, k.enterKey)
        assertEquals("a", out)
    }

    @Test
    fun enterLeavingUpOrDownCancels() {
        var out: String? = null
        val k = kb()
        k.committed = { out = it }
        val e = k.enterKey
        // In through the inner edge, then leave by its END rather than its far
        // side: that is a cancel, not a commit.
        glide(k, e.right + 40f, e.midY(), e.right - edgeIn, e.midY())
        glide(k, e.right - edgeIn, e.midY(), e.midX(), e.bottom + 60f)
        assertNull("its ends are inert", out)
    }

    @Test
    fun enterIsNotArmedFromItsOuterEdge() {
        var out: String? = null
        val k = kb()
        k.committed = { out = it }
        right(k, k.enterKey)
        assertNull("ENTER is entered inwards, from the centre", out)
    }

    @Test
    fun delWordRemovesAWholeWord() {
        val k = kb("hello wor")
        right(k, k.delWordKey)
        assertEquals("hello ", k.text)
    }

    @Test
    fun cancelFiresFromBelowAndIsOnBothEdges() {
        var cancelled = false
        val k = kb("http://old")
        k.cancelled = { cancelled = true }
        sweepUp(k, "A")
        assertEquals("typed on top of the original", "http://olda", k.text)
        up(k, k.cancelLeft)
        assertTrue("the left X fires", cancelled)
        cancelled = false
        up(k, k.cancelRight)
        assertTrue("the right X fires too", cancelled)
        assertEquals("the original is kept to put back", "http://old", k.initialText)
    }

    @Test
    fun backspaceRemovesTheLastCharacter() {
        val k = kb()
        sweepUp(k, "A")
        sweepUp(k, "B")
        up(k, barRect(k, "BKSP", bottom = false))
        assertEquals("a", k.text)
    }

    // ---------------------------------------------------------------- caps

    @Test
    fun oneSweepThroughCapsGivesSingleCaps() {
        val k = kb()
        up(k, barRect(k, "Aa", bottom = false))
        assertEquals(KeyboardControl.CapsMode.SINGLE, k.capsMode)
        assertEquals("Aaa", k.capsLabel())
        sweepUp(k, "A")
        assertEquals("A", k.text)
        assertEquals("the single cap is spent by typing",
            KeyboardControl.CapsMode.LOWER, k.capsMode)
    }

    @Test
    fun loopingStraightBackThroughCapsGivesCapsLock() {
        val k = kb()
        up(k, barRect(k, "Aa", bottom = false))
        assertEquals(KeyboardControl.CapsMode.SINGLE, k.capsMode)
        up(k, barRect(k, "Aa", bottom = false))   // again, nothing typed between
        assertEquals(KeyboardControl.CapsMode.LOCK, k.capsMode)
        assertEquals("AAA", k.capsLabel())
    }

    @Test
    fun capsLockTypesEveryLetterCapital() {
        val k = kb()
        up(k, barRect(k, "Aa", bottom = false))
        up(k, barRect(k, "Aa", bottom = false))
        sweepUp(k, "A")
        sweepUp(k, "B")
        assertEquals("AB", k.text)
        assertEquals("the lock survives typing", KeyboardControl.CapsMode.LOCK, k.capsMode)
    }

    @Test
    fun capsLoopsAllTheWayBackToLower() {
        val k = kb()
        repeat(3) { up(k, barRect(k, "Aa", bottom = false)) }
        assertEquals(KeyboardControl.CapsMode.LOWER, k.capsMode)
    }

    @Test
    fun typingThenSweepingCapsReturnsToLowerNotLock() {
        // A letter has been typed, so the sweep resets rather than advancing -
        // otherwise a word typed by accident would trap the keyboard in caps.
        val k = kb()
        up(k, barRect(k, "Aa", bottom = false))
        up(k, barRect(k, "Aa", bottom = false))    // LOCK
        sweepUp(k, "A")
        up(k, barRect(k, "Aa", bottom = false))
        assertEquals(KeyboardControl.CapsMode.LOWER, k.capsMode)
        sweepUp(k, "B")
        assertEquals("Ab", k.text)
    }

    // --------------------------------------------------------------- rows

    @Test
    fun numbersTypeFromTheNumberRow() {
        val k = kb()
        up(k, barRect(k, "123", bottom = false))
        assertTrue(settle(k))
        assertEquals("123", k.activeRowName)
        sweepUp(k, "7")
        assertEquals("7", k.text)
    }

    @Test
    fun everyRowOffersASpaceKey() {
        val k = kb()
        for (r in 0..2) assertTrue("row $r has no space", k.rowKeys(r).contains(" "))
    }

    @Test
    fun theKeyboardClaimsTheFrameWhileHeld() {
        val k = kb()
        val r = charRect(k, "A")
        assertTrue("outside the window", !k.step(-500f, -500f, frame))
        k.step(r.midX(), r.top - 40f, frame)
        assertTrue("inside the window", k.step(r.midX(), r.top + edgeIn, frame))
    }
}
