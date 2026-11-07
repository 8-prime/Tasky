package dev.tasky.android.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class UuidsTest {
    @Test
    fun contributionIdMatchesTheServerTestVector() {
        assertEquals(
            "79f48edd-396a-5e27-b339-9c3997d57a61",
            Uuids.contributionId("00000000-0000-0000-0000-000000000001", "00000000-0000-0000-0000-000000000002"),
        )
        assertEquals("6d1f3c2a-9b8e-4f6d-a1c3-5e7b9d2f4a10", Uuids.CONTRIBUTION_NAMESPACE.toString())
    }

    @Test
    fun v5MatchesRfcExample() {
        // RFC 9562 Appendix A.4: DNS namespace, "www.example.com".
        assertEquals(
            "2ed6657d-e927-568b-95e1-2665a8aea6a2",
            Uuids.v5(UUID.fromString("6ba7b810-9dad-11d1-80b4-00c04fd430c8"), "www.example.com"),
        )
    }

    @Test
    fun v7HasVersionVariantAndTimeOrder() {
        val a = Uuids.v7(1_700_000_000_000)
        val b = Uuids.v7(1_700_000_000_001)
        val u = UUID.fromString(a)
        assertEquals(7, u.version())
        assertEquals(2, u.variant())
        assertTrue(a < b)
        assertTrue(a.startsWith("018bcfe5-6800-7"))
        assertNotEquals(Uuids.v7(5), Uuids.v7(5))
    }
}
