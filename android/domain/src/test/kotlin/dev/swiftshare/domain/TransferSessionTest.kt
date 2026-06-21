package dev.swiftshare.domain

import java.time.Instant
import java.util.UUID
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TransferSessionTest {
    @Test
    fun `unpaired Peer is rejected before endpoint resolution or transport`() = runImmediateSuspend {
        val resolver = EndpointResolverSpy()
        val transport = TransportSpy(TransferTerminalResult.COMPLETED)
        val session = makeSession(
            peer = null,
            resolver = resolver,
            transport = transport,
            artifacts = listOf(CommittedArtifact("should-not-appear")),
        )

        val outcome = session.execute(request)

        assertEquals(TransferTerminalResult.REJECTED, outcome.result)
        assertTrue(outcome.committedArtifacts.isEmpty())
        assertEquals(0, resolver.callCount)
        assertEquals(0, transport.callCount)
    }

    @Test
    fun `completed sessions expose committed artifacts`() = runImmediateSuspend {
        val artifact = CommittedArtifact("artifact-1")
        val transport = TransportSpy(TransferTerminalResult.COMPLETED)
        val session = makeSession(
            peer = PairedPeer("android", "Android"),
            resolver = EndpointResolverSpy(),
            transport = transport,
            artifacts = listOf(artifact),
        )

        val outcome = session.execute(request)

        assertEquals(TransferTerminalResult.COMPLETED, outcome.result)
        assertEquals(listOf(artifact), outcome.committedArtifacts)
        assertEquals(1, transport.callCount)
    }

    @Test
    fun `rejected sessions expose no destination artifacts`() = runImmediateSuspend {
        val storage = DestinationStorageSpy(listOf(CommittedArtifact("hidden")))
        val session = TransferSession(
            identityStore = IdentityStore { DeviceIdentity("mac") },
            peerDirectory = PeerDirectory { PairedPeer("android", "Android") },
            endpointResolver = EndpointResolverSpy(),
            transport = TransportSpy(TransferTerminalResult.REJECTED),
            destinationStorage = storage,
            clock = TestClock(),
        )

        val outcome = session.execute(request)

        assertEquals(TransferTerminalResult.REJECTED, outcome.result)
        assertTrue(outcome.committedArtifacts.isEmpty())
        assertEquals(0, storage.callCount)
    }

    private fun makeSession(
        peer: PairedPeer?,
        resolver: EndpointResolverSpy,
        transport: TransportSpy,
        artifacts: List<CommittedArtifact>,
    ) = TransferSession(
        identityStore = IdentityStore { DeviceIdentity("mac") },
        peerDirectory = PeerDirectory { id -> peer?.takeIf { it.id == id } },
        endpointResolver = resolver,
        transport = transport,
        destinationStorage = DestinationStorageSpy(artifacts),
        clock = TestClock(),
    )

    private val request = TransferSessionRequest(
        id = UUID.fromString("00000000-0000-0000-0000-000000000001"),
        peerId = "android",
        payloads = listOf(PayloadDescriptor("file-1", 42)),
    )
}

private class EndpointResolverSpy : PeerEndpointResolver {
    var callCount = 0

    override suspend fun endpoint(peer: PairedPeer): PeerEndpoint {
        callCount += 1
        return PeerEndpoint("127.0.0.1", 8443)
    }
}

private class TransportSpy(
    private val result: TransferTerminalResult,
) : TransferTransport {
    var callCount = 0

    override suspend fun perform(
        request: TransferSessionRequest,
        localDevice: DeviceIdentity,
        peer: PairedPeer,
        endpoint: PeerEndpoint,
    ): TransferTerminalResult {
        callCount += 1
        return result
    }
}

private class DestinationStorageSpy(
    private val artifacts: List<CommittedArtifact>,
) : DestinationStorage {
    var callCount = 0

    override suspend fun committedArtifacts(sessionId: UUID): List<CommittedArtifact> {
        callCount += 1
        return artifacts
    }
}

private class TestClock : TransferClock {
    private var tick = 0L

    override fun now(): Instant = Instant.ofEpochSecond(tick++)
}

private fun <T> runImmediateSuspend(block: suspend () -> T): T {
    var outcome: Result<T>? = null
    block.startCoroutine(object : Continuation<T> {
        override val context = EmptyCoroutineContext

        override fun resumeWith(result: Result<T>) {
            outcome = result
        }
    })
    return requireNotNull(outcome) {
        "Foundation fakes must complete synchronously; add a coroutine test runtime when real suspension is introduced."
    }.getOrThrow()
}
