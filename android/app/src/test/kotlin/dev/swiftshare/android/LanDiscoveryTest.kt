package dev.swiftshare.android

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.util.UUID

class LanDiscoveryTest {
    @Test fun `ticket claims once and expires closed`() {
        val registry = EndpointTicketRegistry()
        val now = Instant.ofEpochSecond(1_000)
        val (ticket, _) = registry.issue(now)
        val session = UUID.randomUUID()
        assertTrue(registry.claim(ticket, "peer", session, now.plusSeconds(1)))
        assertTrue(registry.claim(ticket, "peer", session, now.plusSeconds(2)))
        assertFalse(registry.claim(ticket, "other", UUID.randomUUID(), now.plusSeconds(2)))
        registry.consume()
        assertFalse(registry.claim(ticket, "peer", session, now.plusSeconds(3)))
    }
}
