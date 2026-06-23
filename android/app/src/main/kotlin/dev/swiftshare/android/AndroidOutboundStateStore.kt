package dev.swiftshare.android

import dev.swiftshare.domain.TransferActivityPhase

internal class AndroidOutboundStateStore(initial: AndroidOutboundSnapshot) {
    private val lock = Any()
    private var value = initial

    fun snapshot(): AndroidOutboundSnapshot = synchronized(lock) { value }

    fun mutate(transform: (AndroidOutboundSnapshot) -> AndroidOutboundSnapshot): AndroidOutboundSnapshot =
        synchronized(lock) {
            transform(value).also { value = it }
        }

    fun beginPreparation(): Boolean = synchronized(lock) {
        if (value.active) return false
        value = value.copy(
            preparing = true,
            phase = TransferActivityPhase.PREPARING,
            failure = null,
        )
        true
    }

    fun beginSend(): Boolean = synchronized(lock) {
        val draft = value.draft ?: return false
        val peer = value.selectedPeerId ?: return false
        if (value.active || peer !in value.onlinePeerIds) return false
        value = value.copy(
            phase = TransferActivityPhase.CONNECTING,
            transferredBytes = 0,
            totalBytes = draft.byteCount,
            failure = null,
        )
        true
    }
}

internal inline fun <T> withPersistableGrant(
    preservesPriorGrant: Boolean,
    take: () -> Unit,
    release: () -> Unit,
    operation: () -> T,
): T {
    take()
    return try {
        operation()
    } catch (error: Exception) {
        if (!preservesPriorGrant) runCatching(release)
        throw error
    }
}
