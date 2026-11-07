package dev.tasky.android.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HlcTest {
    private var now = 0x0193a4f1c2d0L

    private fun clock(state: HlcState = HlcState(0, 0, 0)) = Hlc("7f3a9c01e5b24d88", state, wallClock = { now })

    @Test
    fun format() {
        assertEquals("0193a4f1c2d0-0000-7f3a9c01e5b24d88", Hlc.format(0x0193a4f1c2d0, 0, "7f3a9c01e5b24d88"))
        assertEquals("0000000000ff-0003-n", Hlc.format(255, 3, "n"))
        assertTrue(Hlc.format(255, 3, "n") < Hlc.format(256, 0, "n"))
    }

    @Test
    fun usesWallClockAndCountsWithinTheSameMillisecond() {
        val hlc = clock()
        assertEquals("0193a4f1c2d0-0000-7f3a9c01e5b24d88", hlc.next())
        assertEquals("0193a4f1c2d0-0001-7f3a9c01e5b24d88", hlc.next())
        now += 1
        assertEquals("0193a4f1c2d1-0000-7f3a9c01e5b24d88", hlc.next())
    }

    @Test
    fun neverGoesBackwardsWhenTheClockDoes() {
        val hlc = clock()
        val a = hlc.next()
        now -= 10_000
        val b = hlc.next()
        val c = hlc.next()
        assertTrue(a < b && b < c)
        assertEquals("0193a4f1c2d0-0001-7f3a9c01e5b24d88", b)
    }

    @Test
    fun appliesServerOffset() {
        val hlc = clock()
        hlc.observeServerTime(now + 60_000) // server is a minute ahead
        assertEquals(60_000, hlc.offsetMs)
        assertEquals(Hlc.format(now + 60_000, 0, "7f3a9c01e5b24d88"), hlc.next())
    }

    @Test
    fun recoversFromAClockThatWasFarAhead() {
        // A previous HLC 10 minutes ahead would only produce ops the server rejects (clock_skew).
        val hlc = clock(HlcState(now + 10 * 60_000, 5, 0))
        assertEquals(Hlc.format(now, 0, "7f3a9c01e5b24d88"), hlc.next())
        // But one 1 minute ahead is kept, so edits stay ordered.
        val hlc2 = clock(HlcState(now + 60_000, 5, 0))
        assertEquals(Hlc.format(now + 60_000, 6, "7f3a9c01e5b24d88"), hlc2.next())
    }

    @Test
    fun counterOverflowMovesToTheNextMillisecond() {
        val hlc = clock(HlcState(now, 0xffff, 0))
        assertEquals(Hlc.format(now + 1, 0, "7f3a9c01e5b24d88"), hlc.next())
    }

    @Test
    fun persistsState() {
        var saved: HlcState? = null
        val hlc = Hlc("n1", wallClock = { now }, persist = { saved = it })
        hlc.next()
        hlc.next()
        assertEquals(HlcState(now, 1, 0), saved)
    }

    @Test
    fun randomNodeIdsAreValid() {
        repeat(20) { assertTrue(Regex("[a-z0-9]{16}").matches(Hlc.randomNodeId())) }
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsInvalidNode() {
        Hlc("Not-Valid")
    }
}
