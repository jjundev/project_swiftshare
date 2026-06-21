package dev.swiftshare.domain

import java.util.UUID
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TransferRolesTest {
    @Test
    fun `outbound completion exposes only the receiver committed artifact`() = runRoleSuspend {
        val events = EventRecorder()
        val connection = ConnectionStub(
            approval = TransferApproval(true),
            remote = RemoteTransferResult(WireTerminalStatus.COMPLETED, "content://media/42"),
        )
        val coordinator = outbound(peer = peer, connection = connection, events = events)

        val outcome = coordinator.execute(request)

        assertEquals(TransferTerminalResult.COMPLETED, outcome.result)
        assertEquals(listOf(CommittedArtifact("content://media/42")), outcome.committedArtifacts)
        assertEquals(
            listOf(
                TransferActivityPhase.PREPARING,
                TransferActivityPhase.CONNECTING,
                TransferActivityPhase.AWAITING_APPROVAL,
                TransferActivityPhase.TRANSFERRING,
                TransferActivityPhase.COMPLETED,
            ),
            events.values.map { it.phase },
        )
    }

    @Test
    fun `outbound unpaired Peer rejects before connection`() = runRoleSuspend {
        val events = EventRecorder()
        val connector = ConnectorSpy(ConnectionStub())
        val coordinator = OutboundTransferSessionCoordinator(
            identityStore = IdentityStore { DeviceIdentity("mac") },
            peerDirectory = PeerDirectory { null },
            endpointResolver = PeerEndpointResolver { PeerEndpoint("127.0.0.1", 8443) },
            connector = connector,
            deadlineScheduler = ImmediateDeadlineScheduler(),
            cancellation = TransferCancellationChecking { false },
            events = events,
        )

        val outcome = coordinator.execute(request)

        assertEquals(TransferTerminalResult.REJECTED, outcome.result)
        assertEquals(0, connector.calls)
        assertEquals(listOf(TransferActivityPhase.PREPARING, TransferActivityPhase.REJECTED), events.values.map { it.phase })
    }

    @Test
    fun `inbound rejection allocates no destination`() = runRoleSuspend {
        val receiver = ReceiverSpy()
        val events = EventRecorder()
        val coordinator = InboundTransferSessionCoordinator(
            peerDirectory = PeerDirectory { peer },
            approvalGateway = IncomingApprovalGateway { _, _ -> false },
            receiver = receiver,
            deadlineScheduler = ImmediateDeadlineScheduler(),
            cancellation = TransferCancellationChecking { false },
            events = events,
        )

        val outcome = coordinator.execute(InboundTransferContext(request, peer.id))

        assertEquals(TransferTerminalResult.REJECTED, outcome.result)
        assertEquals(0, receiver.calls)
        assertTrue(outcome.committedArtifacts.isEmpty())
    }

    @Test
    fun `inbound storage permission failure remains typed and uncommitted`() = runRoleSuspend {
        val receiver = ReceiverSpy(
            error = TransferExecutionException(TransferErrorCategory.PERMISSION, "downloads_permission_lost"),
        )
        val coordinator = InboundTransferSessionCoordinator(
            peerDirectory = PeerDirectory { peer },
            approvalGateway = IncomingApprovalGateway { _, _ -> true },
            receiver = receiver,
            deadlineScheduler = ImmediateDeadlineScheduler(),
            cancellation = TransferCancellationChecking { false },
            events = EventRecorder(),
        )

        val outcome = coordinator.execute(InboundTransferContext(request, peer.id))

        assertEquals(TransferTerminalResult.FAILED, outcome.result)
        assertEquals(TransferErrorCategory.PERMISSION, outcome.failure?.category)
        assertTrue(outcome.committedArtifacts.isEmpty())
    }

    @Test
    fun `inbound auto accept bypasses manual approval gateway`() = runRoleSuspend {
        val approval = ApprovalSpy(false)
        val receiver = ReceiverSpy()
        val coordinator = InboundTransferSessionCoordinator(
            peerDirectory = PeerDirectory { peer },
            approvalPolicyResolver = PeerApprovalPolicyResolver { PeerApprovalPolicy.AUTO_ACCEPT },
            approvalGateway = approval,
            receiver = receiver,
            deadlineScheduler = ImmediateDeadlineScheduler(),
            cancellation = TransferCancellationChecking { false },
            events = EventRecorder(),
        )

        val outcome = coordinator.execute(InboundTransferContext(request, peer.id))

        assertEquals(TransferTerminalResult.COMPLETED, outcome.result)
        assertEquals(0, approval.calls)
        assertEquals(1, receiver.calls)
    }

    private fun outbound(peer: PairedPeer?, connection: OutboundTransferConnection, events: EventRecorder) =
        OutboundTransferSessionCoordinator(
            identityStore = IdentityStore { DeviceIdentity("mac") },
            peerDirectory = PeerDirectory { id -> peer?.takeIf { it.id == id } },
            endpointResolver = PeerEndpointResolver { PeerEndpoint("127.0.0.1", 8443) },
            connector = ConnectorSpy(connection),
            deadlineScheduler = ImmediateDeadlineScheduler(),
            cancellation = TransferCancellationChecking { false },
            events = events,
        )

    private val peer = PairedPeer("android", "Android")
    private val request = TransferSessionRequest(
        UUID.fromString("00000000-0000-0000-0000-000000000001"),
        "android",
        listOf(PayloadDescriptor("file-1", 42)),
    )
}

private class ImmediateDeadlineScheduler : TransferDeadlineScheduler {
    override suspend fun <T> run(timeoutMillis: Long, operation: suspend () -> T): T = operation()
}

private class EventRecorder : TransferEventSink {
    val values = mutableListOf<TransferSessionEvent>()
    override suspend fun emit(event: TransferSessionEvent) { values += event }
}

private class ConnectorSpy(private val connection: OutboundTransferConnection) : OutboundTransferConnector {
    var calls = 0
    override suspend fun connect(context: OutboundConnectionContext): OutboundTransferConnection {
        calls += 1
        return connection
    }
}

private class ConnectionStub(
    private val approval: TransferApproval = TransferApproval(true),
    private val remote: RemoteTransferResult = RemoteTransferResult(WireTerminalStatus.COMPLETED),
) : OutboundTransferConnection {
    override suspend fun awaitApproval(): TransferApproval = approval
    override suspend fun stream(
        events: TransferEventSink,
        cancellation: TransferCancellationChecking,
        deadlineScheduler: TransferDeadlineScheduler,
        idleTimeoutMillis: Long,
    ): RemoteTransferResult = remote
    override suspend fun cancel(reasonCode: String) = Unit
}

private class ReceiverSpy(private val error: TransferExecutionException? = null) : InboundTransferReceiver {
    var calls = 0
    override suspend fun receive(
        peer: PairedPeer,
        request: TransferSessionRequest,
        events: TransferEventSink,
        cancellation: TransferCancellationChecking,
        deadlineScheduler: TransferDeadlineScheduler,
        idleTimeoutMillis: Long,
    ): CommittedArtifact {
        calls += 1
        error?.let { throw it }
        return CommittedArtifact("content://media/42")
    }
}

private class ApprovalSpy(private val approved: Boolean) : IncomingApprovalGateway {
    var calls = 0
    override suspend fun requestApproval(peer: PairedPeer, request: TransferSessionRequest): Boolean {
        calls += 1
        return approved
    }
}

private fun <T> runRoleSuspend(block: suspend () -> T): T {
    var outcome: Result<T>? = null
    block.startCoroutine(object : Continuation<T> {
        override val context = EmptyCoroutineContext
        override fun resumeWith(result: Result<T>) { outcome = result }
    })
    return requireNotNull(outcome).getOrThrow()
}
