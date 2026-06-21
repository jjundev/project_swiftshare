package dev.swiftshare.domain

import java.security.MessageDigest
import java.util.UUID

enum class SessionLeasePhase { OUTBOUND_PRE_DECISION, ACTIVE_OUTBOUND, ACTIVE_INBOUND, LOST_DRAINING }
data class SessionLease(val token: UUID, val sessionId: UUID, val peerId: ByteArray, val phase: SessionLeasePhase) {
    override fun equals(other: Any?): Boolean = other is SessionLease && token == other.token && sessionId == other.sessionId && peerId.contentEquals(other.peerId) && phase == other.phase
    override fun hashCode(): Int = token.hashCode()
}

sealed interface SessionAdmission {
    data class Admitted(val lease: SessionLease, val preemptedOutboundToken: UUID? = null) : SessionAdmission
    data class Rejected(val code: String) : SessionAdmission
}

class TransferSessionScheduler(private val localDeviceId: ByteArray) {
    private var active: SessionLease? = null
    private val draining = mutableSetOf<UUID>()

    init { require(localDeviceId.size == 32) }

    @Synchronized fun reserveOutbound(peerId: ByteArray, sessionId: UUID): SessionLease? {
        require(peerId.size == 32)
        if (active != null) return null
        return SessionLease(UUID.randomUUID(), sessionId, peerId.copyOf(), SessionLeasePhase.OUTBOUND_PRE_DECISION)
            .also { active = it }
    }

    @Synchronized fun markOutboundAccepted(token: UUID): Boolean {
        val current = active ?: return false
        if (current.token != token || current.phase != SessionLeasePhase.OUTBOUND_PRE_DECISION) return false
        active = current.copy(phase = SessionLeasePhase.ACTIVE_OUTBOUND)
        return true
    }

    @Synchronized fun admitInbound(peerId: ByteArray, sessionId: UUID): SessionAdmission {
        require(peerId.size == 32)
        if (MessageDigest.isEqual(localDeviceId, peerId)) return SessionAdmission.Rejected("identity_collision")
        val current = active
        if (current == null) {
            val lease = SessionLease(UUID.randomUUID(), sessionId, peerId.copyOf(), SessionLeasePhase.ACTIVE_INBOUND)
            active = lease
            return SessionAdmission.Admitted(lease)
        }
        if (current.phase == SessionLeasePhase.OUTBOUND_PRE_DECISION && MessageDigest.isEqual(current.peerId, peerId)) {
            return if (compareUnsigned(localDeviceId, peerId) < 0) {
                SessionAdmission.Rejected("simultaneous_initiation_lost")
            } else {
                draining += current.token
                val lease = SessionLease(UUID.randomUUID(), sessionId, peerId.copyOf(), SessionLeasePhase.ACTIVE_INBOUND)
                active = lease
                SessionAdmission.Admitted(lease, preemptedOutboundToken = current.token)
            }
        }
        return SessionAdmission.Rejected("device_busy")
    }

    @Synchronized fun phase(token: UUID): SessionLeasePhase? = when {
        active?.token == token -> active?.phase
        token in draining -> SessionLeasePhase.LOST_DRAINING
        else -> null
    }

    @Synchronized fun release(token: UUID) {
        if (active?.token == token) active = null
        draining.remove(token)
    }

    @Synchronized fun hasActiveSession(): Boolean = active != null

    private fun compareUnsigned(left: ByteArray, right: ByteArray): Int {
        for (index in left.indices) {
            val comparison = (left[index].toInt() and 0xff).compareTo(right[index].toInt() and 0xff)
            if (comparison != 0) return comparison
        }
        return 0
    }
}
