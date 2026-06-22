package dev.swiftshare.domain

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant

interface PairingInitiatorChannel {
    suspend fun send(message: PairingWireMessage)
    suspend fun receive(): PairingWireMessage
    suspend fun close()
}

fun interface PairingInitiatorConnecting {
    suspend fun connect(payload: PairingQrPayload): PairingInitiatorChannel
}

data class PairingInitiatorIdentity(
    val device: PairingWireDevice,
)

interface PairingInitiatorCryptography {
    suspend fun localIdentity(): PairingInitiatorIdentity
    suspend fun validateRemote(device: PairingWireDevice): PairingDeviceDescriptor
    suspend fun sign(digest: ByteArray): ByteArray
    suspend fun verify(device: PairingWireDevice, digest: ByteArray, signature: ByteArray): Boolean
}

fun interface PairingPeerCommitting {
    suspend fun commit(peer: PairingDeviceDescriptor, at: Instant)
}

data class PairingConfirmationRequest(
    val peer: PairingDeviceDescriptor,
    val comparisonCode: String,
)

fun interface PairingConfirming {
    suspend fun confirm(request: PairingConfirmationRequest): Boolean
}

sealed interface PairingInitiatorEvent {
    data object Connecting : PairingInitiatorEvent
    data class AwaitingConfirmation(val request: PairingConfirmationRequest) : PairingInitiatorEvent
    data object WaitingForRemoteCommit : PairingInitiatorEvent
    data class Completed(val peer: PairingDeviceDescriptor) : PairingInitiatorEvent
}

fun interface PairingInitiatorEventSink {
    suspend fun emit(event: PairingInitiatorEvent)
}

data class PairingExecutionOutcome(
    val peer: PairingDeviceDescriptor,
)

fun interface PairingExecutionClock {
    fun now(): Instant
}

fun interface PairingRandomGenerating {
    fun data(count: Int): ByteArray
}

class PairingInitiatorSession(
    private val connector: PairingInitiatorConnecting,
    private val cryptography: PairingInitiatorCryptography,
    private val committer: PairingPeerCommitting,
    private val confirmation: PairingConfirming,
    private val events: PairingInitiatorEventSink,
    private val clock: PairingExecutionClock = PairingExecutionClock(Instant::now),
    private val random: PairingRandomGenerating = PairingRandomGenerating {
        ByteArray(it).also(SecureRandom()::nextBytes)
    },
) {
    suspend fun execute(uri: String): PairingExecutionOutcome {
        val payload = try {
            PairingQrPayload.decode(uri, clock.now())
        } catch (error: Exception) {
            throw PairingException(PairingErrorCode.INVALID_QR)
        }
        events.emit(PairingInitiatorEvent.Connecting)
        val channel = connector.connect(payload)
        return try {
            run(payload, channel)
        } finally {
            channel.close()
        }
    }

    private suspend fun run(
        payload: PairingQrPayload,
        channel: PairingInitiatorChannel,
    ): PairingExecutionOutcome {
        val identity = cryptography.localIdentity()
        val localDescriptor = identity.device.descriptor
        val clientNonce = random.data(32)
        channel.send(ClientStart(payload.sessionId, payload.token, identity.device, clientNonce))

        val challenge = channel.receive() as? ServerChallenge
            ?: throw PairingException(PairingErrorCode.INVALID_TRANSCRIPT)
        val remoteDescriptor = cryptography.validateRemote(challenge.device)
        val transcript = try {
            PairingTranscript(
                payload.sessionId,
                payload.token,
                payload.certificateSha256,
                clientNonce,
                challenge.nonce,
                remoteDescriptor,
                localDescriptor,
            )
        } catch (error: Exception) {
            throw PairingException(PairingErrorCode.INVALID_TRANSCRIPT)
        }
        channel.send(ClientProof(cryptography.sign(transcript.proofDigest(PairingRole.ANDROID))))
        val proof = channel.receive() as? ServerProof
            ?: throw PairingException(PairingErrorCode.INVALID_TRANSCRIPT)
        if (!cryptography.verify(challenge.device, transcript.proofDigest(PairingRole.MAC), proof.signature)) {
            throw PairingException(PairingErrorCode.INVALID_PROOF)
        }

        val request = PairingConfirmationRequest(remoteDescriptor, transcript.comparisonCode)
        events.emit(PairingInitiatorEvent.AwaitingConfirmation(request))
        val accepted = confirmation.confirm(request)
        channel.send(
            ClientDecision(
                accepted,
                cryptography.sign(pairingDecisionDigest(transcript.digest, accepted)),
            ),
        )
        if (!accepted) throw PairingException(PairingErrorCode.CANCELLED)

        events.emit(PairingInitiatorEvent.WaitingForRemoteCommit)
        when (val response = channel.receive()) {
            is PairingWireError -> throw PairingException(
                if (response.code == "user_rejected") PairingErrorCode.CANCELLED else PairingErrorCode.INVALID_TRANSCRIPT,
            )
            is CommitReceipt -> {
                if (response.sessionId != payload.sessionId ||
                    !MessageDigest.isEqual(response.transcriptSha256, transcript.digest) ||
                    !MessageDigest.isEqual(response.macKeyId, remoteDescriptor.keyId) ||
                    !MessageDigest.isEqual(response.androidKeyId, localDescriptor.keyId)
                ) {
                    throw PairingException(PairingErrorCode.INVALID_TRANSCRIPT)
                }
                if (!cryptography.verify(
                        challenge.device,
                        pairingReceiptDigest(response),
                        response.signature,
                    )
                ) {
                    throw PairingException(PairingErrorCode.INVALID_PROOF)
                }
                try {
                    committer.commit(remoteDescriptor, clock.now())
                } catch (error: Exception) {
                    throw PairingException(PairingErrorCode.STORAGE)
                }
                val encoded = PairingWireCodec.encode(response)
                channel.send(CommitAck(MessageDigest.getInstance("SHA-256").digest(encoded)))
                events.emit(PairingInitiatorEvent.Completed(remoteDescriptor))
                return PairingExecutionOutcome(remoteDescriptor)
            }
            else -> throw PairingException(PairingErrorCode.INVALID_TRANSCRIPT)
        }
    }
}

fun pairingDecisionDigest(transcript: ByteArray, accepted: Boolean): ByteArray =
    MessageDigest.getInstance("SHA-256").digest(
        "SwiftShare-Pairing-v1-decision-${if (accepted) "accept" else "reject"}".toByteArray() + transcript,
    )

fun pairingReceiptDigest(value: CommitReceipt): ByteArray = MessageDigest.getInstance("SHA-256").digest(
    "SwiftShare-Pairing-v1-receipt".toByteArray() +
        value.sessionId.toBytes() +
        value.transcriptSha256 +
        value.macKeyId +
        value.androidKeyId +
        ByteBuffer.allocate(8).putLong(value.committedAt.epochSecond).array(),
)
