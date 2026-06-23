package dev.swiftshare.android

import dev.swiftshare.domain.TransferActivityPhase
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class AndroidOutboundStateStoreTest {
    private val peerId = "22".repeat(32)
    private val draft = PersistedDocumentDraft(
        UUID.fromString("00000000-0000-0000-0000-000000000042"),
        "content://documents/report",
        "report.txt",
        "text/plain",
        3,
        ByteArray(32) { 1 },
    )

    @Test
    fun `one confirmation admits exactly one send`() {
        val store = AndroidOutboundStateStore(
            AndroidOutboundSnapshot(
                drafts = listOf(draft),
                selectedPeerId = peerId,
                onlinePeerIds = setOf(peerId),
            ),
        )

        assertTrue(store.beginSend())
        assertFalse(store.beginSend())
        assertEquals(TransferActivityPhase.CONNECTING, store.snapshot().phase)
    }

    @Test
    fun `serialized mutations preserve independent route and transfer fields`() {
        val store = AndroidOutboundStateStore(AndroidOutboundSnapshot(drafts = listOf(draft)))

        store.mutate { it.copy(phase = TransferActivityPhase.TRANSFERRING) }
        store.mutate { it.copy(onlinePeerIds = setOf(peerId)) }

        assertEquals(TransferActivityPhase.TRANSFERRING, store.snapshot().phase)
        assertEquals(setOf(peerId), store.snapshot().onlinePeerIds)
    }

    @Test
    fun `concurrent mutations do not lose updates`() {
        val store = AndroidOutboundStateStore(AndroidOutboundSnapshot(drafts = listOf(draft)))
        val executor = Executors.newFixedThreadPool(8)
        repeat(1_000) {
            executor.execute {
                store.mutate { state -> state.copy(transferredBytes = state.transferredBytes + 1) }
            }
        }
        executor.shutdown()

        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        assertEquals(1_000, store.snapshot().transferredBytes)
    }

    @Test
    fun `failed preparation releases only a newly acquired grant`() {
        var releases = 0

        assertThrows(IllegalStateException::class.java) {
            withPersistableGrant(
                preservesPriorGrant = false,
                take = {},
                release = { releases += 1 },
                operation = { error("hash_failed") },
            )
        }
        assertEquals(1, releases)

        assertThrows(IllegalStateException::class.java) {
            withPersistableGrant(
                preservesPriorGrant = true,
                take = {},
                release = { releases += 1 },
                operation = { error("hash_failed") },
            )
        }
        assertEquals(1, releases)
    }
}
