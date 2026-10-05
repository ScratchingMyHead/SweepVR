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

    /** Up through a side key, starting just clear of the live band.
     *
     *  The side keys sit IN the dead zones, close to the band, and the 40px
     *  lead-in the other helpers use lands inside it - which armed a
     *  character and typed it before the sweep ever reached the key. */
    private fun upSide(k: KeyboardControl, r: Rect) {
        val x = r.midX()
        glide(k, x, r.bottom + 4f, x, r.bottom - edgeIn)
        glide(k, x, r.bottom - edgeIn, x, r.top - 6f)
    }

    /** Down through a side key, starting just clear of the live band. */
    private fun downSide(k: KeyboardControl, r: Rect) {
        val x = r.midX()
        glide(k, x, r.top - 4f, x, r.top + edgeIn)
        glide(k, x, r.top + edgeIn, x, r.bottom + 6f)
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
    fun onePressTypesOneCharacter() {
        // A single sweep must type ONE character.
        //
        // The resolve used to reset the row's engines with the gaze already
        // OUTSIDE the band - which is the only reason it resolved. reset()
        // discards the history, so the next frame every engine read its
        // first sample outside its own rect as an entry, found the gaze still
        // outside, and typed the same character again: "ll" from one press.
        val k = kb()
        sweepUp(k, "L")
        assertEquals("one press, one character", "l", k.text)
        // And it must stay that way while the gaze lingers outside the band,
        // which is where jitter lives.
        repeat(12) { k.step(k.activeRowBand.midX(), k.activeRowBand.bottom + 30f, frame) }
        assertEquals("no second character from lingering", "l", k.text)
    }

    @Test
    fun onePressTurnsCapsBackOnAfterTheSingleCapIsSpent() {
        // The exact dead-press: Aaa, type one character so it resets itself
        // to aaa, then ONE sweep over Aa. It has to come back on first time.
        // It used to need two, because cycleCaps swallowed the press instead
        // of cycling - which looked like a timing fault and was not one.
        val k = kb()
        up(k, k.barKeyRect(k.topBar, 3, k.barLabels.size))     // Aa
        assertEquals("Aaa", k.capsLabel())
        sweepUp(k, "A")
        assertEquals("the single cap is spent, upper once", "A", k.text)
        assertEquals("aaa", k.capsLabel())
        up(k, k.barKeyRect(k.topBar, 3, k.barLabels.size))     // one press
        assertEquals("Aaa after a single press", "Aaa", k.capsLabel())
    }

    @Test
    fun aFiredBarKeyIsLitWhileTheGazeIsOnItAndBrieflyAfterwards() {
        // The light follows the finger, not the clock: while the gaze is on
        // the key the hold is topped up, and it lingers only briefly once the
        // gaze has gone. 450ms of unconditional linger read as the keyboard
        // running a timer rather than confirming the touch.
        val k = kb()
        val r = k.barKeyRect(k.topBar, 0, k.barLabels.size)     // 123
        val x = r.midX()
        var litDuringSweep = false
        // Up through the key in small steps, watching the highlight as it goes.
        glide(k, x, r.bottom + 40f, x, r.top - 40f)
        for (i in 0..24) {
            val y = r.bottom + 40f + (r.top - 80f - r.bottom) * i / 24f
            k.step(x, y, frame)
            if (k.isBarKeyHeld("123")) litDuringSweep = true
        }
        assertTrue("lit while the gaze is on it", litDuringSweep)
        var after = 0
        while (k.isBarKeyHeld("123")) { after++; k.step(-500f, -500f, frame) }
        assertTrue("lingers a little, got $after frames", after <= 12)
    }

    @Test
    fun theDrumSlidesOneNotchFromWhereItIs() {
        val k = kb()
        assertEquals("abc", k.activeRowName)
        val centre = k.drum.top + k.drum.height * 0.5f
        val abcBefore = k.rowRect(1).top
        // Sampled on the fire frame itself. Measuring after the sweep returns
        // reads the slide already decayed, which is how the old sign got past
        // a casual look.
        var abcAtFire = 0f
        var incomingAtFire = 0f
        k.onTrace = { if (it.startsWith("row 123")) {
            abcAtFire = k.rowRect(1).top
            incomingAtFire = k.rowRect(0).top
        } }
        up(k, k.barKeyRect(k.topBar, 0, k.barLabels.size))     // 123
        assertEquals("123", k.activeRowName)
        val rowH = k.rowRect(0).height
        // The outgoing row must still be on the centre line as the switch
        // happens. The other sign leaves it 2*rowH clear of it, which is the
        // drum appearing to reset to an end and rotate the long way round.
        assertEquals("outgoing row stays on the centre line",
            abcBefore, abcAtFire, 0.6f)
        // The incoming row starts exactly one notch away, on the side it came
        // from. 123 is the top row, so making it live slides it DOWN into the
        // middle slot, and abc drops out through the bottom.
        assertEquals("incoming row starts one notch above",
            centre - rowH, incomingAtFire + rowH / 2f, 0.6f)
        // Then it settles onto the centre line.
        repeat(60) { k.step(-500f, -500f, frame) }
        assertEquals("incoming row settles centred",
            centre, k.rowRect(0).top + rowH / 2f, 0.6f)
    }

    @Test
    fun theDismissXCommits() {
        val k = kb("hello")
        var cancelled = false
        k.cancelled = { cancelled = true }
        // Down through the letters into the X, which now sits on its top face.
        downSide(k, k.cancelBottom)
        assertTrue("X should commit", cancelled)
        assertEquals("edit abandoned, text restored", "hello", k.text)
    }

    @Test
    fun theGapsBetweenKeysStillClaimTheFrame() {
        // A dwell must not reach the page through the blank margins beside
        // the space bar, where there is no key to own the frame.
        val k = kb()
        val band = k.activeRowBand
        val x = k.charRect(1, 20).right + 4f          // just past the last letter
        var claimed = false
        for (i in 0..20) claimed = claimed || k.step(x, band.midY(), frame)
        assertTrue("gap between keys must still claim", claimed)
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
    fun enterCommitsOutOfTheCentre() {
        var out: String? = null
        val k = kb()
        k.committed = { out = it }
        sweepUp(k, "A")
        // ENTER fires on contact from the edge facing the band, like the bars.
        upSide(k, k.enterTop)
        assertEquals("a", out)
    }

    @Test
    fun enterIsNotArmedFromItsOuterEdge() {
        var out: String? = null
        val k = kb()
        k.committed = { out = it }
        right(k, k.enterTop)
        assertNull("ENTER is entered inwards, from the centre", out)
    }

    @Test
    fun delWordRemovesAWholeWord() {
        val k = kb("hello wor")
        downSide(k, k.delWordBottom)
        assertEquals("hello ", k.text)
    }

    @Test
    fun everySideButtonExistsInBothDeadZones() {
        // The band splits the columns in two and cannot be crossed without
        // typing, so a button in only one dead zone is unreachable from the
        // other side. ENTER was bottom-only until it was duplicated; CLEAR
        // would have had the same fault.
        val k = kb("hello world")
        val band = k.activeRowBand
        val top = listOf(k.enterTop, k.clearTop, k.cancelTop, k.delWordTop)
        val bot = listOf(k.enterBottom, k.clearKey, k.cancelBottom, k.delWordBottom)
        (top + bot).forEach { r ->
            assertTrue("clear of the live band: $r",
                r.bottom <= band.top + 0.5f || r.top >= band.bottom - 0.5f)
        }
        listOf(top, bot).forEach { zs ->
            assertEquals("four keys down this side", 4, zs.size)
        }
        // Right side: enter + clear. Left side: dismiss + delete word.
        listOf(k.enterTop, k.enterBottom, k.clearTop, k.clearKey).forEach {
            assertTrue("on the right: $it", it.left >= k.window.right - k.cancelTop.width - 0.5f)
        }
        listOf(k.cancelTop, k.cancelBottom, k.delWordTop, k.delWordBottom).forEach {
            assertTrue("on the left: $it", it.left <= k.window.left + 0.5f)
        }
        // And both CLRs really clear.
        val k2 = kb("hello world")
        upSide(k2, k2.clearTop); assertEquals("top CLR clears", "", k2.text)
        val k3 = kb("hello world")
        downSide(k3, k3.clearKey); assertEquals("bottom CLR clears", "", k3.text)
    }

    @Test
    fun thereIsOneDismissTopAndBottomBothOnTheLeft() {
        val k = kb("http://old")
        val band = k.activeRowBand
        listOf(k.cancelTop, k.cancelBottom).forEach { r ->
            assertTrue("on the left: $r", r.left <= k.window.left + 0.5f)
            assertTrue("clear of the live band: $r",
                r.bottom <= band.top + 0.5f || r.top >= band.bottom - 0.5f)
        }
        assertTrue("upper is in the top dead zone", k.cancelTop.bottom <= band.top + 0.5f)
        assertTrue("lower is in the bottom dead zone", k.cancelBottom.top >= band.bottom - 0.5f)
        // Nothing on the right may be mistaken for a dismiss. CLR used to
        // fall through iconKey's `else`, which draws an X, so the clear
        // button rendered as a second dismiss.
        var fired = false
        k.cancelled = { fired = true }
        upSide(k, k.cancelTop)
        assertTrue("upper dismiss works", fired)
    }

    @Test
    fun deleteWordIsInBothDeadZonesOnTheLeft() {
        val k = kb("hello wor")
        val band = k.activeRowBand
        listOf(k.delWordTop, k.delWordBottom).forEach { r ->
            assertTrue("on the left: $r", r.left <= k.window.left + 0.5f)
            assertTrue("clear of the live band: $r",
                r.bottom <= band.top + 0.5f || r.top >= band.bottom - 0.5f)
        }
        // The upper one is reachable from under the band, the lower from over.
        assertTrue("upper is in the top dead zone", k.delWordTop.bottom <= band.top + 0.5f)
        assertTrue("lower is in the bottom dead zone", k.delWordBottom.bottom >= band.bottom - 0.5f)
        // ...and both actually work.
        var k2 = kb("hello wor")
        upSide(k2, k2.delWordTop)
        assertEquals("upper deletes", "hello ", k2.text)
        k2 = kb("hello wor")
        downSide(k2, k2.delWordBottom)
        assertEquals("lower deletes", "hello ", k2.text)
    }

    @Test
    fun theSideKeysDoNotCloseInOnTheLiveBand() {
        // A downward sweep through a character must be able to continue
        // through the bottom dead zone without meeting anything, and ENTER
        // must be reachable from below the band.
        val k = kb()
        val band = k.activeRowBand
        listOf(k.enterTop, k.enterBottom, k.clearKey, k.cancelLeft, k.delWordKey)
            .forEach { r ->
                assertTrue("no side key overlaps the live band: $r",
                    r.bottom <= band.top + 0.5f || r.top >= band.bottom - 0.5f)
            }
        // Each side key is shorter than its dead zone, so the middle of the
        // zone can be aimed at and the key missed entirely.
        assertTrue("shorter than the top dead zone",
            k.enterTop.height < k.topDead.height)
        assertTrue("shorter than the bottom dead zone",
            k.enterBottom.height < k.bottomDead.height)
        assertTrue("clear sits below the lower enter",
            k.clearKey.top >= k.enterBottom.bottom)
        assertTrue("dismiss is on the left",
            k.cancelLeft.left <= k.window.left + 0.5f)
    }

    @Test
    fun enterBelowTheBandIsReachableFromUnderIt() {
        var out: String? = null
        val k = kb()
        k.committed = { out = it }
        sweepUp(k, "A")
        // Straight down out of the band and into the lower ENTER.
        val e = k.enterBottom
        glide(k, e.midX(), e.top - 40f, e.midX(), e.top + 8f)
        assertEquals("commits", "a", out)
    }

    @Test
    fun dismissFiresAndRestoresTheOriginal() {
        var cancelled = false
        val k = kb("http://old")
        k.cancelled = { cancelled = true }
        sweepUp(k, "A")
        assertEquals("typed on top of the original", "http://olda", k.text)
        downSide(k, k.cancelBottom)
        assertTrue("the X fires", cancelled)
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
