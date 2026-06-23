package dev.swiftshare.domain

enum class TransferActivityPhase {
    PREPARING,
    CONNECTING,
    AWAITING_APPROVAL,
    TRANSFERRING,
    VERIFYING,
    COMMITTING,
    COMPLETED,
    REJECTED,
    CANCELLED,
    FAILED,
}

data class TransferSessionEvent(
    val phase: TransferActivityPhase,
    val transferredBytes: Long = 0,
    val verifiedBytes: Long = 0,
    val totalBytes: Long = 0,
    val completedItems: Int = 0,
    val totalItems: Int = 1,
    val currentItemName: String = "",
)

data class TransferFailure(
    val category: TransferErrorCategory,
    val code: String,
)

data class RoleTransferOutcome(
    val result: TransferTerminalResult,
    val committedArtifacts: List<CommittedArtifact> = emptyList(),
    val failure: TransferFailure? = null,
)

data class TransferTimeoutPolicy(
    val connectMillis: Long = 15_000,
    val approvalMillis: Long = 120_000,
    val idleMillis: Long = 60_000,
)

fun interface TransferEventSink {
    suspend fun emit(event: TransferSessionEvent)
}

fun interface TransferCancellationChecking {
    suspend fun isCancelled(): Boolean
}

interface TransferDeadlineScheduler {
    suspend fun <T> run(timeoutMillis: Long, operation: suspend () -> T): T
}

data class OutboundConnectionContext(
    val request: TransferSessionRequest,
    val localDevice: DeviceIdentity,
    val peer: PairedPeer,
    val endpoint: PeerEndpoint,
)

data class RemoteTransferResult(
    val status: WireTerminalStatus,
    val committedArtifactId: String = "",
    val failure: TransferFailure? = null,
)

interface OutboundTransferConnection {
    suspend fun awaitApproval(): TransferApproval

    suspend fun stream(
        events: TransferEventSink,
        cancellation: TransferCancellationChecking,
        deadlineScheduler: TransferDeadlineScheduler,
        idleTimeoutMillis: Long,
    ): RemoteTransferResult

    suspend fun cancel(reasonCode: String)
}

fun interface OutboundTransferConnector {
    suspend fun connect(context: OutboundConnectionContext): OutboundTransferConnection
}

class OutboundTransferSessionCoordinator(
    private val identityStore: IdentityStore,
    private val peerDirectory: PeerDirectory,
    private val endpointResolver: PeerEndpointResolver,
    private val connector: OutboundTransferConnector,
    private val deadlineScheduler: TransferDeadlineScheduler,
    private val cancellation: TransferCancellationChecking,
    private val events: TransferEventSink,
    private val timeouts: TransferTimeoutPolicy = TransferTimeoutPolicy(),
) {
    suspend fun execute(request: TransferSessionRequest): RoleTransferOutcome {
        return try {
            events.emit(event(TransferActivityPhase.PREPARING, request))
            val peer = peerDirectory.pairedPeer(request.peerId)
                ?: return terminal(TransferTerminalResult.REJECTED, TransferActivityPhase.REJECTED, request)
            val localDevice = identityStore.localDeviceIdentity()
            val endpoint = endpointResolver.endpoint(peer)
            events.emit(event(TransferActivityPhase.CONNECTING, request))
            val connection = deadlineScheduler.run(timeouts.connectMillis) {
                connector.connect(OutboundConnectionContext(request, localDevice, peer, endpoint))
            }
            if (cancellation.isCancelled()) {
                connection.cancel("user_cancelled")
                return terminal(TransferTerminalResult.CANCELLED, TransferActivityPhase.CANCELLED, request)
            }
            events.emit(event(TransferActivityPhase.AWAITING_APPROVAL, request))
            val approval = deadlineScheduler.run(timeouts.approvalMillis) { connection.awaitApproval() }
            if (!approval.accepted) {
                return terminal(TransferTerminalResult.REJECTED, TransferActivityPhase.REJECTED, request)
            }
            events.emit(event(TransferActivityPhase.TRANSFERRING, request))
            val remote = connection.stream(events, cancellation, deadlineScheduler, timeouts.idleMillis)
            val status = remote.status.toDomainResult()
            val phase = status.toActivityPhase()
            events.emit(event(phase, request, verified = request.totalBytes()))
            RoleTransferOutcome(
                result = status,
                committedArtifacts = if (status == TransferTerminalResult.COMPLETED && remote.committedArtifactId.isNotEmpty()) {
                    listOf(CommittedArtifact(remote.committedArtifactId))
                } else emptyList(),
                failure = remote.failure,
            )
        } catch (error: TransferExecutionException) {
            events.emit(event(TransferActivityPhase.FAILED, request))
            RoleTransferOutcome(
                result = TransferTerminalResult.FAILED,
                failure = TransferFailure(error.category, error.errorCode),
            )
        } catch (_: Exception) {
            events.emit(event(TransferActivityPhase.FAILED, request))
            RoleTransferOutcome(
                result = TransferTerminalResult.FAILED,
                failure = TransferFailure(TransferErrorCategory.PROTOCOL, "unexpected_failure"),
            )
        }
    }

    private suspend fun terminal(
        result: TransferTerminalResult,
        phase: TransferActivityPhase,
        request: TransferSessionRequest,
    ): RoleTransferOutcome {
        events.emit(event(phase, request))
        return RoleTransferOutcome(result)
    }
}

class TransferExecutionException(
    val category: TransferErrorCategory,
    val errorCode: String,
) : Exception(errorCode)

private fun TransferSessionRequest.totalBytes(): Long = payloads.sumOf { it.byteCount }

private fun event(
    phase: TransferActivityPhase,
    request: TransferSessionRequest,
    verified: Long = 0,
): TransferSessionEvent = TransferSessionEvent(
    phase = phase,
    verifiedBytes = verified,
    totalBytes = request.totalBytes(),
    completedItems = if (phase == TransferActivityPhase.COMPLETED) request.payloads.size else 0,
    totalItems = request.payloads.size,
)

private fun WireTerminalStatus.toDomainResult(): TransferTerminalResult = when (this) {
    WireTerminalStatus.COMPLETED -> TransferTerminalResult.COMPLETED
    WireTerminalStatus.REJECTED -> TransferTerminalResult.REJECTED
    WireTerminalStatus.CANCELLED -> TransferTerminalResult.CANCELLED
    WireTerminalStatus.FAILED -> TransferTerminalResult.FAILED
}

private fun TransferTerminalResult.toActivityPhase(): TransferActivityPhase = when (this) {
    TransferTerminalResult.COMPLETED -> TransferActivityPhase.COMPLETED
    TransferTerminalResult.REJECTED -> TransferActivityPhase.REJECTED
    TransferTerminalResult.CANCELLED -> TransferActivityPhase.CANCELLED
    TransferTerminalResult.FAILED -> TransferActivityPhase.FAILED
}
