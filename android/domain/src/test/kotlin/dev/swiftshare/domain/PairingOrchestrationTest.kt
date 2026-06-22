package dev.swiftshare.domain

import java.time.Instant
import java.util.UUID
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingOrchestrationTest {
    @Test
    fun `pairing completes only after durable trust and CommitAck`() = runPairingTest {
        val fixture = Fixture()

        val outcome = fixture.session.execute(fixture.payload.encodedUri())

        assertEquals(fixture.remoteDescriptor, outcome.peer)
        assertEquals(listOf(fixture.remoteDescriptor), fixture.committer.committed)
        assertEquals(
            listOf(
                PairingInitiatorEvent.Connecting::class,
                PairingInitiatorEvent.AwaitingConfirmation::class,
                PairingInitiatorEvent.WaitingForRemoteCommit::class,
                PairingInitiatorEvent.Completed::class,
            ),
            fixture.events.values.map { it::class },
        )
        assertEquals(PairingRecordType.COMMIT_ACK, fixture.channel.sent.last().type)
        assertTrue(fixture.channel.closed)
    }

    @Test
    fun `local rejection sends signed decision without durable trust`() {
        val fixture = Fixture(localAccepted = false)

        val error = assertThrows(PairingException::class.java) {
            runPairingTest { fixture.session.execute(fixture.payload.encodedUri()) }
        }

        assertEquals(PairingErrorCode.CANCELLED, error.code)
        assertTrue(fixture.committer.committed.isEmpty())
        val decision = fixture.channel.sent.filterIsInstance<ClientDecision>().single()
        assertFalse(decision.accepted)
    }

    @Test
    fun `invalid remote proof never asks the user or commits`() {
        val fixture = Fixture(proofIsValid = false)

        val error = assertThrows(PairingException::class.java) {
            runPairingTest { fixture.session.execute(fixture.payload.encodedUri()) }
        }

        assertEquals(PairingErrorCode.INVALID_PROOF, error.code)
        assertEquals(0, fixture.confirmation.calls)
        assertTrue(fixture.committer.committed.isEmpty())
    }

    @Test
    fun `storage failure prevents CommitAck`() {
        val fixture = Fixture(storageFails = true)

        val error = assertThrows(PairingException::class.java) {
            runPairingTest { fixture.session.execute(fixture.payload.encodedUri()) }
        }

        assertEquals(PairingErrorCode.STORAGE, error.code)
        assertTrue(fixture.channel.sent.none { it is CommitAck })
        assertTrue(fixture.channel.closed)
    }

    private class Fixture(
        localAccepted: Boolean = true,
        proofIsValid: Boolean = true,
        storageFails: Boolean = false,
    ) {
        val payload = PairingQrPayload(
            sessionId = UUID.fromString("00000000-0000-0000-0000-000000000088"),
            host = "192.0.2.10",
            port = 8443,
            certificateSha256 = ByteArray(32) { 5 },
            token = ByteArray(32) { 9 },
            expiresAt = Instant.ofEpochSecond(2_000),
        )
        val localDescriptor = PairingDeviceDescriptor(ByteArray(91) { 1 }, "Pixel", "android")
        val remoteDescriptor = PairingDeviceDescriptor(ByteArray(91) { 2 }, "Mac", "macos")
        private val localWire = PairingWireDevice(ByteArray(128) { 3 }, localDescriptor.canonicalSpki, "Pixel", "android")
        private val remoteWire = PairingWireDevice(ByteArray(128) { 4 }, remoteDescriptor.canonicalSpki, "Mac", "macos")
        val channel = InitiatorChannelFake(payload, localDescriptor, remoteDescriptor, remoteWire)
        val confirmation = ConfirmationFake(localAccepted)
        val committer = CommitterFake(storageFails)
        val events = InitiatorEventRecorder()
        val session = PairingInitiatorSession(
            connector = PairingInitiatorConnecting { channel },
            cryptography = InitiatorCryptographyFake(localWire, remoteDescriptor, proofIsValid),
            committer = committer,
            confirmation = confirmation,
            events = events,
            clock = PairingExecutionClock { Instant.ofEpochSecond(1_000) },
            random = PairingRandomGenerating { ByteArray(it) { 6 } },
        )
    }
}

private class InitiatorChannelFake(
    private val payload: PairingQrPayload,
    private val localDescriptor: PairingDeviceDescriptor,
    private val remoteDescriptor: PairingDeviceDescriptor,
    private val remoteWire: PairingWireDevice,
) : PairingInitiatorChannel {
    val sent = mutableListOf<PairingWireMessage>()
    var closed = false
    private var receiveIndex = 0
    private val serverNonce = ByteArray(32) { 7 }

    override suspend fun send(message: PairingWireMessage) { sent += message }

    override suspend fun receive(): PairingWireMessage {
        return when (receiveIndex++) {
            0 -> ServerChallenge(remoteWire, serverNonce)
            1 -> ServerProof(ByteArray(64) { 8 })
            else -> {
                val start = sent.filterIsInstance<ClientStart>().single()
                val transcript = PairingTranscript(
                    payload.sessionId,
                    payload.token,
                    payload.certificateSha256,
                    start.nonce,
                    serverNonce,
                    remoteDescriptor,
                    localDescriptor,
                )
                CommitReceipt(
                    payload.sessionId,
                    transcript.digest,
                    remoteDescriptor.keyId,
                    localDescriptor.keyId,
                    Instant.ofEpochSecond(1_000),
                    ByteArray(64) { 10 },
                )
            }
        }
    }

    override suspend fun close() { closed = true }
}

private class InitiatorCryptographyFake(
    private val localWire: PairingWireDevice,
    private val remote: PairingDeviceDescriptor,
    private val proofIsValid: Boolean,
) : PairingInitiatorCryptography {
    override suspend fun localIdentity() = PairingInitiatorIdentity(localWire)
    override suspend fun validateRemote(device: PairingWireDevice): PairingDeviceDescriptor = remote
    override suspend fun sign(digest: ByteArray): ByteArray = ByteArray(64) { digest.firstOrNull() ?: 0 }
    override suspend fun verify(device: PairingWireDevice, digest: ByteArray, signature: ByteArray): Boolean = proofIsValid
}

private class CommitterFake(
    private val fails: Boolean,
) : PairingPeerCommitting {
    val committed = mutableListOf<PairingDeviceDescriptor>()
    override suspend fun commit(peer: PairingDeviceDescriptor, at: Instant) {
        if (fails) error("storage unavailable")
        committed += peer
    }
}

private class ConfirmationFake(
    private val accepted: Boolean,
) : PairingConfirming {
    var calls = 0
    override suspend fun confirm(request: PairingConfirmationRequest): Boolean {
        calls += 1
        return accepted
    }
}

private class InitiatorEventRecorder : PairingInitiatorEventSink {
    val values = mutableListOf<PairingInitiatorEvent>()
    override suspend fun emit(event: PairingInitiatorEvent) { values += event }
}

private fun <T> runPairingTest(block: suspend () -> T): T {
    var outcome: Result<T>? = null
    block.startCoroutine(object : Continuation<T> {
        override val context = EmptyCoroutineContext
        override fun resumeWith(result: Result<T>) { outcome = result }
    })
    return requireNotNull(outcome).getOrThrow()
}
