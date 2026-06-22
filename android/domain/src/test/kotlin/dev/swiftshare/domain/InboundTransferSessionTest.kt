package dev.swiftshare.domain

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.security.MessageDigest
import java.util.ArrayDeque
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InboundTransferSessionTest {
    @Test
    fun `completed conversation commits through the production interface`() {
        val payload = "swiftshare".toByteArray()
        val fixture = Fixture(payload = payload)

        val outcome = fixture.session.execute(fixture.connection)

        assertEquals(TransferTerminalResult.COMPLETED, outcome.result)
        assertEquals(listOf(CommittedArtifact("content://downloads/shared.txt")), outcome.committedArtifacts)
        assertEquals(payload.toList(), fixture.reservation.output.toByteArray().toList())
        assertTrue(fixture.reservation.committed)
        assertFalse(fixture.reservation.aborted)
        assertEquals(
            listOf(
                TransferRecordType.SESSION_DECISION,
                TransferRecordType.APPROVAL,
                TransferRecordType.PROGRESS,
                TransferRecordType.TERMINAL_RESULT,
            ),
            fixture.channel.sent.map { it.type },
        )
        assertEquals(
            listOf(
                TransferActivityPhase.AWAITING_APPROVAL,
                TransferActivityPhase.TRANSFERRING,
                TransferActivityPhase.TRANSFERRING,
                TransferActivityPhase.VERIFYING,
                TransferActivityPhase.COMMITTING,
                TransferActivityPhase.COMPLETED,
            ),
            fixture.events.values.map { it.phase },
        )
        assertFalse(fixture.scheduler.hasActiveSession())
    }

    @Test
    fun `receiver rejection sends approval and allocates no destination`() {
        val fixture = Fixture(approval = InboundApprovalDecision.REJECTED)

        val outcome = fixture.session.execute(fixture.connection)

        assertEquals(TransferTerminalResult.REJECTED, outcome.result)
        assertEquals(TransferErrorCategory.APPROVAL, outcome.failure?.category)
        assertEquals(0, fixture.destination.reserveCalls)
        val approval = fixture.channel.sent.single { it.type == TransferRecordType.APPROVAL }
        assertEquals(
            TransferApproval(false, "user_rejected"),
            TransferControlCodec.decode(TransferRecordType.APPROVAL, approval.payload),
        )
        assertFalse(fixture.scheduler.hasActiveSession())
    }

    @Test
    fun `auto accept policy bypasses the manual approval adapter`() {
        val approval = ApprovalSpy()
        val fixture = Fixture(
            approvalGateway = approval,
            approvalPolicy = PeerApprovalPolicy.AUTO_ACCEPT,
        )

        val outcome = fixture.session.execute(fixture.connection)

        assertEquals(TransferTerminalResult.COMPLETED, outcome.result)
        assertEquals(0, approval.awaitCalls)
        assertTrue(fixture.events.values.none { it.phase == TransferActivityPhase.AWAITING_APPROVAL })
    }

    @Test
    fun `a committed artifact stays completed when terminal delivery is lost`() {
        val fixture = Fixture(failTerminalSend = true)

        val outcome = fixture.session.execute(fixture.connection)

        assertEquals(TransferTerminalResult.COMPLETED, outcome.result)
        assertTrue(fixture.reservation.committed)
        assertFalse(fixture.reservation.aborted)
        assertFalse(fixture.scheduler.hasActiveSession())
    }

    @Test
    fun `closed Receive Availability rejects new admission without affecting a lease`() {
        val fixture = Fixture(admissionOpen = { false })

        val outcome = fixture.session.execute(fixture.connection)

        assertEquals(TransferTerminalResult.FAILED, outcome.result)
        assertEquals(TransferErrorCategory.BUSY, outcome.failure?.category)
        assertEquals("receive_unavailable", outcome.failure?.code)
        assertEquals(0, fixture.destination.reserveCalls)
        assertFalse(fixture.scheduler.hasActiveSession())
        val decision = fixture.channel.sent.single()
        assertEquals(TransferRecordType.SESSION_DECISION, decision.type)
        val message = SessionBootstrapCodec.decodeDecision(decision.payload)
        assertFalse(message.accepted)
        assertEquals(BootstrapRejectionCategory.BUSY, message.rejectionCategory)
    }

    @Test
    fun `closing Receive Availability does not cancel an admitted transfer`() {
        var admissionOpen = true
        val fixture = Fixture(
            approvalGateway = HookApproval {
                admissionOpen = false
                InboundApprovalDecision.ACCEPTED
            },
            admissionOpen = { admissionOpen },
        )

        val outcome = fixture.session.execute(fixture.connection)

        assertFalse(admissionOpen)
        assertEquals(TransferTerminalResult.COMPLETED, outcome.result)
        assertTrue(fixture.reservation.committed)
        assertFalse(fixture.reservation.aborted)
    }

    @Test
    fun `chunk integrity failure aborts pending destination and stays typed`() {
        val fixture = Fixture(chunkDigest = ByteArray(32) { 7 })

        val outcome = fixture.session.execute(fixture.connection)

        assertEquals(TransferTerminalResult.FAILED, outcome.result)
        assertEquals(TransferErrorCategory.INTEGRITY, outcome.failure?.category)
        assertEquals("chunk_sha256_mismatch", outcome.failure?.code)
        assertTrue(fixture.reservation.aborted)
        assertFalse(fixture.reservation.committed)
        val terminal = fixture.channel.sent.last { it.type == TransferRecordType.TERMINAL_RESULT }
        val message = TransferControlCodec.decode(TransferRecordType.TERMINAL_RESULT, terminal.payload)
            as TransferWireTerminalResult
        assertEquals(WireTerminalStatus.FAILED, message.status)
        assertEquals(TransferErrorCategory.INTEGRITY, message.errorCategory)
        assertFalse(fixture.scheduler.hasActiveSession())
    }

    @Test
    fun `peer revocation is owned by the active session and releases admission`() {
        lateinit var session: InboundTransferSession
        val approval = object : InboundApprovalGateway {
            override fun awaitDecision(
                summary: InboundTransferSummary,
                timeoutMillis: Long,
            ): InboundApprovalDecision {
                session.cancel("peer_unpaired", PEER_KEY)
                return InboundApprovalDecision.ACCEPTED
            }

            override fun cancel() = Unit
        }
        val fixture = Fixture(approvalGateway = approval) { created -> session = created }

        val outcome = fixture.session.execute(fixture.connection)

        assertEquals(TransferTerminalResult.CANCELLED, outcome.result)
        assertEquals(TransferErrorCategory.AUTHENTICATION, outcome.failure?.category)
        assertEquals("peer_unpaired", outcome.failure?.code)
        val cancel = fixture.channel.sent.first { it.type == TransferRecordType.CANCEL }
        assertEquals(
            TransferCancel("peer_unpaired"),
            TransferControlCodec.decode(TransferRecordType.CANCEL, cancel.payload),
        )
        assertEquals(0, fixture.destination.reserveCalls)
        assertTrue(fixture.channel.closed)
        assertFalse(fixture.scheduler.hasActiveSession())
    }

    private class Fixture(
        payload: ByteArray = "swiftshare".toByteArray(),
        approval: InboundApprovalDecision = InboundApprovalDecision.ACCEPTED,
        chunkDigest: ByteArray = MessageDigest.getInstance("SHA-256").digest(payload),
        approvalGateway: InboundApprovalGateway = ImmediateApproval(approval),
        approvalPolicy: PeerApprovalPolicy = PeerApprovalPolicy.REQUIRE_APPROVAL,
        failTerminalSend: Boolean = false,
        admissionOpen: () -> Boolean = { true },
        onSession: (InboundTransferSession) -> Unit = {},
    ) {
        private val sessionId = UUID.fromString("00000000-0000-0000-0000-000000000021")
        private val itemId = UUID.fromString("00000000-0000-0000-0000-000000000022")
        private val fileDigest = MessageDigest.getInstance("SHA-256").digest(payload)
        val scheduler = TransferSessionScheduler(LOCAL_ID)
        val channel = RecordChannelFake(
            records = listOf(
                record(
                    TransferRecordType.SESSION_HELLO,
                    SessionBootstrapCodec.encode(SessionHello(SessionOffer.DEFAULT)),
                    sessionId,
                    0,
                    0,
                ),
                record(
                    TransferRecordType.MANIFEST,
                    TransferControlCodec.encode(
                        TransferManifest(
                            listOf(
                                TransferFileEntry(
                                    itemId,
                                    "shared.txt",
                                    payload.size.toLong(),
                                    fileDigest,
                                    "text/plain",
                                    TRANSFER_CHUNK_TARGET,
                                ),
                            ),
                        ),
                    ),
                    sessionId,
                ),
                record(
                    TransferRecordType.CHUNK,
                    TransferChunkPrelude(itemId, 0, 0, payload.size, chunkDigest).encode() + payload,
                    sessionId,
                ),
                record(
                    TransferRecordType.SENDER_FINISHED,
                    TransferControlCodec.encode(TransferSenderFinished(itemId, payload.size.toLong(), fileDigest)),
                    sessionId,
                ),
            ),
            failTerminalSend = failTerminalSend,
        )
        val reservation = ReservationFake()
        val destination = DestinationFake(reservation)
        val events = EventSinkFake()
        val session = InboundTransferSession(
            scheduler = scheduler,
            endpointTickets = EndpointTicketsFake(),
            approvalGateway = approvalGateway,
            destination = destination,
            events = events,
            admission = InboundTransferAdmitting { peerId, sessionId ->
                if (admissionOpen()) scheduler.admitInbound(peerId, sessionId)
                else SessionAdmission.Rejected("receive_unavailable")
            },
        ).also(onSession)
        val connection = InboundAuthenticatedConnection(
            peerId = PEER_ID,
            peerIdKey = PEER_KEY,
            firstRecordPrefix = byteArrayOf(0x53, 0x53, 0x48, 0x52),
            channel = channel,
            authorizer = InboundPeerAuthorizing {
                InboundAuthorizedPeer(PEER_KEY, "Paired Mac", approvalPolicy)
            },
        )
    }

    private companion object {
        val LOCAL_ID = ByteArray(32) { 1 }
        val PEER_ID = ByteArray(32) { 2 }
        const val PEER_KEY = "paired-mac"

        fun record(
            type: TransferRecordType,
            payload: ByteArray,
            sessionId: UUID,
            major: Int = 1,
            minor: Int = 0,
        ) = TransferWireRecord(TransferRecordHeader(type, payload.size, sessionId, major, minor), payload)
    }
}

private data class SentRecord(
    val type: TransferRecordType,
    val payload: ByteArray,
    val sessionId: UUID,
    val protocolMajor: Int,
    val protocolMinor: Int,
)

private class RecordChannelFake(
    records: List<TransferWireRecord>,
    private val failTerminalSend: Boolean = false,
) : InboundTransferRecordChannel {
    private val records = ArrayDeque(records)
    val sent = mutableListOf<SentRecord>()
    var closed = false

    override fun receiveRecord(timeoutMillis: Int, prefix: ByteArray?): TransferWireRecord? =
        if (records.isEmpty()) null else records.removeFirst()

    override fun sendRecord(
        type: TransferRecordType,
        payload: ByteArray,
        sessionId: UUID,
        protocolMajor: Int,
        protocolMinor: Int,
    ) {
        if (failTerminalSend && type == TransferRecordType.TERMINAL_RESULT) {
            throw IOException("terminal delivery lost")
        }
        sent += SentRecord(type, payload, sessionId, protocolMajor, protocolMinor)
    }

    override fun close() { closed = true }
}

private class EndpointTicketsFake : InboundEndpointTicketAuthorizing {
    override fun claim(ticket: ByteArray, peerId: String, sessionId: UUID): Boolean = true
    override fun consume() = Unit
}

private class ImmediateApproval(
    private val decision: InboundApprovalDecision,
) : InboundApprovalGateway {
    override fun awaitDecision(
        summary: InboundTransferSummary,
        timeoutMillis: Long,
    ): InboundApprovalDecision = decision

    override fun cancel() = Unit
}

private class ApprovalSpy : InboundApprovalGateway {
    var awaitCalls = 0
    override fun awaitDecision(
        summary: InboundTransferSummary,
        timeoutMillis: Long,
    ): InboundApprovalDecision {
        awaitCalls += 1
        return InboundApprovalDecision.ACCEPTED
    }

    override fun cancel() = Unit
}

private class HookApproval(
    private val decision: () -> InboundApprovalDecision,
) : InboundApprovalGateway {
    override fun awaitDecision(
        summary: InboundTransferSummary,
        timeoutMillis: Long,
    ): InboundApprovalDecision = decision()

    override fun cancel() = Unit
}

private class DestinationFake(
    private val reservation: ReservationFake,
) : InboundTransferDestination {
    var reserveCalls = 0

    override fun reserve(displayName: String, mediaType: String): InboundTransferReservation {
        reserveCalls += 1
        return reservation
    }
}

private class ReservationFake : InboundTransferReservation {
    override val displayName = "shared.txt"
    val output = ByteArrayOutputStream()
    var committed = false
    var aborted = false

    override fun openOutput(): OutputStream = output

    override fun commit(): CommittedArtifact {
        committed = true
        return CommittedArtifact("content://downloads/shared.txt")
    }

    override fun abort() { aborted = true }
}

private class EventSinkFake : InboundTransferEventSink {
    val values = mutableListOf<InboundTransferSessionEvent>()
    override fun emit(event: InboundTransferSessionEvent) { values += event }
}
