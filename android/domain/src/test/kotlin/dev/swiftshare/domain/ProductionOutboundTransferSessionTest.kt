package dev.swiftshare.domain

import java.security.MessageDigest
import java.util.UUID
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProductionOutboundTransferSessionTest {
    @Test
    fun `production outbound conversation reaches receiver Commit`() = runProductionSuspend {
        val channel = ScriptedProductionOutboundChannel(accepted = true)
        val scheduler = TransferSessionScheduler(ByteArray(32) { 0x11 })
        val events = ProductionEventRecorder()
        val session = ProductionOutboundTransferSession(
            scheduler,
            OutboundAuthenticatedPeerLocating { OutboundAuthenticatedPeerLocation(channel) },
            ProductionImmediateDeadline(),
        )

        val outcome = session.execute(
            OutboundTransferIntent(ByteArray(32) { 0x22 }, ProductionPayload("abc".encodeToByteArray())),
            events,
        )

        assertEquals(TransferTerminalResult.COMPLETED, outcome.result)
        assertEquals(
            listOf(
                TransferRecordType.SESSION_HELLO,
                TransferRecordType.MANIFEST,
                TransferRecordType.CHUNK,
                TransferRecordType.SENDER_FINISHED,
            ),
            channel.sent.map { it.first },
        )
        assertEquals(
            listOf(
                TransferActivityPhase.CONNECTING,
                TransferActivityPhase.AWAITING_APPROVAL,
                TransferActivityPhase.TRANSFERRING,
                TransferActivityPhase.TRANSFERRING,
                TransferActivityPhase.VERIFYING,
                TransferActivityPhase.COMPLETED,
            ),
            events.events.map { it.phase },
        )
        assertFalse(scheduler.hasActiveSession())
    }

    @Test
    fun `rejection never opens the source stream`() = runProductionSuspend {
        val channel = ScriptedProductionOutboundChannel(accepted = false)
        val payload = ProductionPayload("abc".encodeToByteArray())
        val session = ProductionOutboundTransferSession(
            TransferSessionScheduler(ByteArray(32) { 0x11 }),
            OutboundAuthenticatedPeerLocating { OutboundAuthenticatedPeerLocation(channel) },
            ProductionImmediateDeadline(),
        )

        val outcome = session.execute(
            OutboundTransferIntent(ByteArray(32) { 0x22 }, payload),
            ProductionEventRecorder(),
        )

        assertEquals(TransferTerminalResult.REJECTED, outcome.result)
        assertEquals(0, payload.openCount)
    }

    @Test
    fun `changed source cancels before sender finished`() = runProductionSuspend {
        val channel = ScriptedProductionOutboundChannel(accepted = true)
        val payload = ProductionPayload("abc".encodeToByteArray(), "abd".encodeToByteArray())
        val session = ProductionOutboundTransferSession(
            TransferSessionScheduler(ByteArray(32) { 0x11 }),
            OutboundAuthenticatedPeerLocating { OutboundAuthenticatedPeerLocation(channel) },
            ProductionImmediateDeadline(),
        )

        val outcome = session.execute(
            OutboundTransferIntent(ByteArray(32) { 0x22 }, payload),
            ProductionEventRecorder(),
        )

        assertEquals(TransferTerminalResult.FAILED, outcome.result)
        assertEquals(TransferFailure(TransferErrorCategory.SOURCE, "source_changed"), outcome.failure)
        assertTrue(channel.sent.any { it.first == TransferRecordType.CANCEL })
        assertFalse(channel.sent.any { it.first == TransferRecordType.SENDER_FINISHED })
    }
}

private class ProductionPayload(
    private val bytes: ByteArray,
    private val streamedBytes: ByteArray = bytes,
) : OutboundTransferPayload {
    override val itemId: UUID = UUID.fromString("00000000-0000-0000-0000-000000000042")
    override val displayName = "report.txt"
    override val byteCount = bytes.size.toLong()
    override val sha256 = MessageDigest.getInstance("SHA-256").digest(bytes)
    override val mediaType = "text/plain"
    var openCount = 0

    override suspend fun validateForTransfer() = Unit
    override suspend fun openStream(): OutboundTransferPayloadStream {
        openCount += 1
        return object : OutboundTransferPayloadStream {
            private var pending: ByteArray? = streamedBytes
            override suspend fun read(upToCount: Int): ByteArray? = pending.also { pending = null }
            override suspend fun close() = Unit
        }
    }
}

private class ScriptedProductionOutboundChannel(private val accepted: Boolean) : OutboundTransferRecordChannel {
    val sent = mutableListOf<Pair<TransferRecordType, ByteArray>>()
    private lateinit var sessionId: UUID
    private var receiveIndex = 0

    override suspend fun sendRecord(
        type: TransferRecordType,
        payload: ByteArray,
        sessionId: UUID,
        protocolMajor: Int,
        protocolMinor: Int,
    ) {
        this.sessionId = sessionId
        sent += type to payload
    }

    override suspend fun receiveRecord(): TransferWireRecord {
        val result = when (receiveIndex++) {
            0 -> {
                val negotiated = (SessionNegotiator.negotiate(SessionOffer.DEFAULT, SessionOffer.DEFAULT)
                    as NegotiationResult.Accepted).parameters
                record(
                    TransferRecordType.SESSION_DECISION,
                    SessionBootstrapCodec.encode(SessionDecision.accepted(SessionOffer.DEFAULT, negotiated)),
                    0,
                    0,
                )
            }
            1 -> record(
                TransferRecordType.APPROVAL,
                TransferControlCodec.encode(TransferApproval(accepted, if (accepted) "" else "user_rejected")),
            )
            2 -> {
                val chunk = sent.last { it.first == TransferRecordType.CHUNK }.second
                val prelude = TransferChunkPrelude.decode(chunk.copyOfRange(0, TRANSFER_CHUNK_PRELUDE_SIZE))
                record(
                    TransferRecordType.PROGRESS,
                    TransferControlCodec.encode(
                        TransferProgress(
                            prelude.itemId,
                            prelude.offset + prelude.dataLength,
                            prelude.chunkIndex + 1,
                            TransferProgressPhase.TRANSFERRING,
                        ),
                    ),
                )
            }
            else -> record(
                TransferRecordType.TERMINAL_RESULT,
                TransferControlCodec.encode(TransferWireTerminalResult(WireTerminalStatus.COMPLETED)),
            )
        }
        return result
    }

    override suspend fun close() = Unit

    private fun record(
        type: TransferRecordType,
        payload: ByteArray,
        major: Int = 1,
        minor: Int = 0,
    ) = TransferWireRecord(TransferRecordHeader(type, payload.size, sessionId, major, minor), payload)
}

private class ProductionImmediateDeadline : TransferDeadlineScheduler {
    override suspend fun <T> run(timeoutMillis: Long, operation: suspend () -> T): T = operation()
}

private class ProductionEventRecorder : TransferEventSink {
    val events = mutableListOf<TransferSessionEvent>()
    override suspend fun emit(event: TransferSessionEvent) { events += event }
}

private fun <T> runProductionSuspend(block: suspend () -> T): T {
    var outcome: Result<T>? = null
    block.startCoroutine(object : Continuation<T> {
        override val context = EmptyCoroutineContext
        override fun resumeWith(result: Result<T>) { outcome = result }
    })
    return requireNotNull(outcome).getOrThrow()
}
