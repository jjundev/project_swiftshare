package dev.swiftshare.domain

import java.time.Instant
import java.util.UUID

data class DeviceIdentity(val id: String)

data class PairedPeer(
    val id: String,
    val displayName: String,
)

data class PeerEndpoint(
    val host: String,
    val port: Int,
)

data class PayloadDescriptor(
    val id: String,
    val byteCount: Long,
)

data class CommittedArtifact(val id: String)

enum class PeerApprovalPolicy {
    REQUIRE_APPROVAL,
    AUTO_ACCEPT,
}

data class TransferSessionRequest(
    val id: UUID,
    val peerId: String,
    val payloads: List<PayloadDescriptor>,
)

data class TransferSessionOutcome(
    val result: TransferTerminalResult,
    val committedArtifacts: List<CommittedArtifact>,
    val startedAt: Instant,
    val endedAt: Instant,
)

fun interface IdentityStore {
    suspend fun localDeviceIdentity(): DeviceIdentity
}

fun interface PeerDirectory {
    suspend fun pairedPeer(id: String): PairedPeer?
}

fun interface PeerEndpointResolver {
    suspend fun endpoint(peer: PairedPeer): PeerEndpoint
}

fun interface TransferTransport {
    suspend fun perform(
        request: TransferSessionRequest,
        localDevice: DeviceIdentity,
        peer: PairedPeer,
        endpoint: PeerEndpoint,
    ): TransferTerminalResult
}

fun interface DestinationStorage {
    suspend fun committedArtifacts(sessionId: UUID): List<CommittedArtifact>
}

fun interface TransferClock {
    fun now(): Instant
}

class TransferSession(
    private val identityStore: IdentityStore,
    private val peerDirectory: PeerDirectory,
    private val endpointResolver: PeerEndpointResolver,
    private val transport: TransferTransport,
    private val destinationStorage: DestinationStorage,
    private val clock: TransferClock,
) {
    suspend fun execute(request: TransferSessionRequest): TransferSessionOutcome {
        val startedAt = clock.now()

        return try {
            val localDevice = identityStore.localDeviceIdentity()
            val peer = peerDirectory.pairedPeer(request.peerId)
                ?: return TransferSessionOutcome(
                    result = TransferTerminalResult.REJECTED,
                    committedArtifacts = emptyList(),
                    startedAt = startedAt,
                    endedAt = clock.now(),
                )

            val endpoint = endpointResolver.endpoint(peer)
            val result = transport.perform(request, localDevice, peer, endpoint)
            val artifacts = if (result == TransferTerminalResult.COMPLETED) {
                destinationStorage.committedArtifacts(request.id)
            } else {
                emptyList()
            }

            TransferSessionOutcome(
                result = result,
                committedArtifacts = artifacts,
                startedAt = startedAt,
                endedAt = clock.now(),
            )
        } catch (_: Exception) {
            TransferSessionOutcome(
                result = TransferTerminalResult.FAILED,
                committedArtifacts = emptyList(),
                startedAt = startedAt,
                endedAt = clock.now(),
            )
        }
    }
}
