package dev.swiftshare.domain

import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TransferSessionSchedulerTest {
    @Test fun `one device slot returns busy and releases for retry`() {
        val scheduler = TransferSessionScheduler(ByteArray(32) { 1 })
        val first = requireNotNull(scheduler.reserveOutbound(ByteArray(32) { 2 }, UUID.randomUUID()))
        assertNull(scheduler.reserveOutbound(ByteArray(32) { 3 }, UUID.randomUUID()))
        scheduler.release(first.token)
        assertNotNull(scheduler.reserveOutbound(ByteArray(32) { 3 }, UUID.randomUUID()))
    }

    @Test fun `smaller identity outbound wins simultaneous initiation`() {
        val scheduler = TransferSessionScheduler(ByteArray(32) { 1 })
        val outbound = requireNotNull(scheduler.reserveOutbound(ByteArray(32) { 2 }, UUID.randomUUID()))
        assertEquals(SessionAdmission.Rejected("simultaneous_initiation_lost"), scheduler.admitInbound(ByteArray(32) { 2 }, UUID.randomUUID()))
        assertEquals(SessionLeasePhase.OUTBOUND_PRE_DECISION, scheduler.phase(outbound.token))
    }

    @Test fun `larger identity drains outbound and admits incoming`() {
        val scheduler = TransferSessionScheduler(ByteArray(32) { 2 })
        val outbound = requireNotNull(scheduler.reserveOutbound(ByteArray(32) { 1 }, UUID.randomUUID()))
        val result = scheduler.admitInbound(ByteArray(32) { 1 }, UUID.randomUUID()) as SessionAdmission.Admitted
        assertEquals(outbound.token, result.preemptedOutboundToken)
        assertEquals(SessionLeasePhase.LOST_DRAINING, scheduler.phase(outbound.token))
        assertTrue(scheduler.hasActiveSession())
        scheduler.release(result.lease.token)
        scheduler.release(outbound.token)
        assertFalse(scheduler.hasActiveSession())
    }
}
