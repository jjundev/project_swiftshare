package dev.swiftshare.android

import dev.swiftshare.domain.CommittedArtifact
import dev.swiftshare.domain.DiscoveryProbeCodec
import dev.swiftshare.domain.InboundApprovalDecision
import dev.swiftshare.domain.InboundApprovalGateway
import dev.swiftshare.domain.InboundAuthenticatedConnection
import dev.swiftshare.domain.InboundAuthorizedPeer
import dev.swiftshare.domain.InboundEndpointTicketAuthorizing
import dev.swiftshare.domain.InboundPeerAuthorizing
import dev.swiftshare.domain.InboundTransferDestination
import dev.swiftshare.domain.InboundTransferAdmitting
import dev.swiftshare.domain.InboundTransferEventSink
import dev.swiftshare.domain.InboundTransferRecordChannel
import dev.swiftshare.domain.InboundTransferReservation
import dev.swiftshare.domain.InboundTransferSession
import dev.swiftshare.domain.InboundTransferSessionEvent
import dev.swiftshare.domain.InboundTransferSummary
import dev.swiftshare.domain.PeerApprovalPolicy
import dev.swiftshare.domain.SessionAdmission
import dev.swiftshare.domain.TransferActivityPhase
import dev.swiftshare.domain.TransferErrorCategory
import dev.swiftshare.domain.TransferRecordHeader
import dev.swiftshare.domain.TransferRecordType
import dev.swiftshare.domain.TransferSessionScheduler
import dev.swiftshare.domain.TransferWireRecord
import java.io.EOFException
import java.io.InputStream
import java.net.SocketException
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket

data class IncomingTransferSummary(
    val authenticatedSender: String,
    val displayName: String,
    val byteCount: Long,
)

sealed interface ReceiveServerState {
    data object Stopped : ReceiveServerState
    data class Listening(val port: Int) : ReceiveServerState
    data class AwaitingApproval(val summary: IncomingTransferSummary) : ReceiveServerState
    data class Receiving(val summary: IncomingTransferSummary, val verifiedBytes: Long) : ReceiveServerState
    data class Verifying(val summary: IncomingTransferSummary) : ReceiveServerState
    data class Completed(val displayName: String, val artifactId: String) : ReceiveServerState
    data class Failed(val category: TransferErrorCategory, val code: String) : ReceiveServerState
}

fun interface ReceiveServerListener {
    fun onState(state: ReceiveServerState)
}

class FixedEndpointReceiveServer(
    private val tlsContext: SSLContext,
    private val destination: MediaStoreDestination,
    private val authenticatedSenderName: String,
    private val scheduler: TransferSessionScheduler,
    private val trustRepository: AndroidTrustRepository? = null,
    private val endpointTickets: EndpointTicketRegistry = EndpointTicketRegistry(),
    private val listener: ReceiveServerListener,
) : AutoCloseable {
    private val acceptExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "swiftshare-fixed-endpoint-accept").apply { isDaemon = true }
    }
    private val workerExecutor = ThreadPoolExecutor(
        CONNECTION_WORKERS,
        CONNECTION_WORKERS,
        0L,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(CONNECTION_QUEUE_CAPACITY),
        { runnable -> Thread(runnable, "swiftshare-fixed-endpoint-worker").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy(),
    )
    private val handshakeExecutor = ThreadPoolExecutor(
        HANDSHAKE_WORKERS,
        HANDSHAKE_WORKERS,
        0L,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(HANDSHAKE_QUEUE_CAPACITY),
        { runnable -> Thread(runnable, "swiftshare-handshake-probe").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy(),
    )
    private val approvalGateway = AndroidInboundApprovalGateway()
    private val admission = AdvertisingEpochAdmission(scheduler)
    private val transferSession = InboundTransferSession(
        scheduler = scheduler,
        endpointTickets = AndroidEndpointTicketAuthorizer(endpointTickets),
        approvalGateway = approvalGateway,
        destination = AndroidInboundDestination(destination),
        events = InboundTransferEventSink(::onTransferEvent),
        admission = admission,
    )
    private val running = AtomicBoolean(false)
    @Volatile private var serverSocket: SSLServerSocket? = null
    @Volatile private var discoveryRotatingId: ByteArray? = null

    fun start(port: Int = 0) {
        check(running.compareAndSet(false, true)) { "receive server is already running" }
        acceptExecutor.execute {
            try {
                serverSocket = DevelopmentTlsContextFactory(
                    AndroidDevelopmentIdentityStore(),
                ).createServerSocket(tlsContext, port)
                val boundPort = serverSocket?.localPort ?: throw SocketException("listener has no bound port")
                listener.onState(ReceiveServerState.Listening(boundPort))
                while (running.get()) {
                    val socket = serverSocket?.accept() as? SSLSocket ?: break
                    try {
                        handshakeExecutor.execute { handshakeAndDispatch(socket) }
                    } catch (_: RejectedExecutionException) {
                        socket.close()
                    }
                }
            } catch (_: SocketException) {
                if (running.get()) {
                    listener.onState(ReceiveServerState.Failed(TransferErrorCategory.NETWORK, "listener_closed"))
                }
            } catch (_: Exception) {
                if (running.get()) {
                    listener.onState(ReceiveServerState.Failed(TransferErrorCategory.NETWORK, "listener_failed"))
                }
            } finally {
                running.set(false)
                serverSocket = null
            }
        }
    }

    fun approve() = approvalGateway.approve()
    fun reject() = approvalGateway.reject()

    fun setDiscoveryEpoch(rotatingId: ByteArray?) {
        require(rotatingId == null || rotatingId.size == 16)
        discoveryRotatingId = rotatingId?.copyOf()
        if (rotatingId == null) endpointTickets.invalidate()
    }

    fun cancelActive() = transferSession.cancel("receiver_cancelled")

    fun unpairActive(peerIdHex: String) = transferSession.cancel("peer_unpaired", peerIdHex)

    fun hasActiveTransfer(): Boolean = transferSession.hasActiveTransfer()

    fun stopAccepting() {
        admission.close()
        running.set(false)
        runCatching { serverSocket?.close() }
        acceptExecutor.shutdownNow()
        handshakeExecutor.shutdownNow()
        workerExecutor.shutdown()
        listener.onState(ReceiveServerState.Stopped)
    }

    override fun close() {
        stopAccepting()
        transferSession.cancel("receiver_cancelled")
        workerExecutor.shutdownNow()
    }

    private data class AuthenticatedConnection(
        val socket: SSLSocket,
        val peerId: ByteArray,
        val peerIdHex: String,
        val trustToken: TrustGenerationToken?,
        val firstMagic: ByteArray,
    )

    private fun handshakeAndDispatch(socket: SSLSocket) {
        var handedOff = false
        try {
            socket.enabledProtocols = arrayOf("TLSv1.3")
            socket.useClientMode = false
            socket.needClientAuth = true
            socket.soTimeout = CONNECT_TIMEOUT_MILLIS
            socket.startHandshake()
            val peerSpki = (socket.session.peerCertificates.first() as java.security.cert.X509Certificate)
                .publicKey.encoded
            val trustToken = trustRepository?.authenticationToken(peerSpki)
            if (trustRepository != null && trustToken?.let(trustRepository::validate) == null) {
                throw java.security.cert.CertificateException("Peer was unpaired during authentication")
            }
            val peerId = MessageDigest.getInstance("SHA-256").digest(peerSpki)
            socket.soTimeout = PROBE_TIMEOUT_MILLIS
            val magic = readExactly(socket.inputStream, 4) ?: throw EOFException("missing application magic")
            if (magic.contentEquals(DiscoveryProbeCodec.magic)) {
                val rest = readExactly(socket.inputStream, 34) ?: throw EOFException("truncated discovery probe")
                val probe = DiscoveryProbeCodec.decodeRequest(magic + rest)
                val available = discoveryRotatingId?.contentEquals(probe.rotatingId) == true
                socket.outputStream.write(DiscoveryProbeCodec.encodeReply(probe, available))
                socket.outputStream.flush()
                return
            }
            val authenticated = AuthenticatedConnection(
                socket,
                peerId,
                peerId.toTrustHex(),
                trustToken,
                magic,
            )
            workerExecutor.execute { socket.use { handleAuthenticated(authenticated) } }
            handedOff = true
        } catch (_: RejectedExecutionException) {
            // Bounded worker saturation rejects before Transfer Admission.
        } catch (_: Exception) {
            // Authentication and malformed probes do not replace the active Transfer UI.
        } finally {
            if (!handedOff) runCatching { socket.close() }
        }
    }

    private fun handleAuthenticated(connection: AuthenticatedConnection) {
        val authorizer = InboundPeerAuthorizing {
            if (trustRepository == null) {
                InboundAuthorizedPeer(
                    connection.peerIdHex,
                    authenticatedSenderName,
                    PeerApprovalPolicy.REQUIRE_APPROVAL,
                )
            } else {
                connection.trustToken?.let(trustRepository::validate)?.let { peer ->
                    InboundAuthorizedPeer(
                        connection.peerIdHex,
                        peer.displayName,
                        peer.approvalPolicy,
                    )
                }
            }
        }
        transferSession.execute(
            InboundAuthenticatedConnection(
                peerId = connection.peerId,
                peerIdKey = connection.peerIdHex,
                firstRecordPrefix = connection.firstMagic,
                channel = SocketInboundTransferRecordChannel(connection.socket),
                authorizer = authorizer,
            ),
        )
        if (!running.get() && !transferSession.hasActiveTransfer()) {
            listener.onState(ReceiveServerState.Stopped)
        }
    }

    private fun onTransferEvent(event: InboundTransferSessionEvent) {
        val summary = IncomingTransferSummary(
            event.summary.authenticatedSender,
            event.summary.displayName,
            event.summary.byteCount,
        )
        val state = when (event.phase) {
            TransferActivityPhase.AWAITING_APPROVAL -> ReceiveServerState.AwaitingApproval(summary)
            TransferActivityPhase.TRANSFERRING -> ReceiveServerState.Receiving(summary, event.verifiedBytes)
            TransferActivityPhase.VERIFYING,
            TransferActivityPhase.COMMITTING,
            -> ReceiveServerState.Verifying(summary)
            TransferActivityPhase.COMPLETED -> ReceiveServerState.Completed(
                event.summary.displayName,
                requireNotNull(event.artifact).id,
            )
            TransferActivityPhase.REJECTED,
            TransferActivityPhase.CANCELLED,
            TransferActivityPhase.FAILED,
            -> ReceiveServerState.Failed(
                event.failure?.category ?: TransferErrorCategory.PROTOCOL,
                event.failure?.code ?: "receive_failed",
            )
            else -> return
        }
        listener.onState(state)
    }

    private fun readExactly(input: InputStream, count: Int): ByteArray? {
        val result = ByteArray(count)
        var offset = 0
        while (offset < count) {
            val read = input.read(result, offset, count - offset)
            if (read == -1) {
                if (offset == 0) return null
                throw EOFException("truncated record")
            }
            offset += read
        }
        return result
    }

    companion object {
        private const val CONNECT_TIMEOUT_MILLIS = 15_000
        private const val CONNECTION_WORKERS = 4
        private const val CONNECTION_QUEUE_CAPACITY = 4
        private const val HANDSHAKE_WORKERS = 4
        private const val HANDSHAKE_QUEUE_CAPACITY = 8
        private const val PROBE_TIMEOUT_MILLIS = 3_000
    }
}

private class SocketInboundTransferRecordChannel(
    private val socket: SSLSocket,
) : InboundTransferRecordChannel {
    private val writeLock = Any()

    override fun receiveRecord(timeoutMillis: Int, prefix: ByteArray?): TransferWireRecord? {
        socket.soTimeout = timeoutMillis
        val input = socket.inputStream
        val headerBytes = if (prefix == null) {
            readExactly(input, dev.swiftshare.domain.TRANSFER_HEADER_SIZE) ?: return null
        } else {
            require(prefix.size == 4)
            prefix + (readExactly(input, dev.swiftshare.domain.TRANSFER_HEADER_SIZE - 4)
                ?: throw EOFException("truncated record header"))
        }
        val header = TransferRecordHeader.decode(headerBytes)
        val payload = readExactly(input, header.payloadLength) ?: throw EOFException("truncated payload")
        return TransferWireRecord(header, payload)
    }

    override fun sendRecord(
        type: TransferRecordType,
        payload: ByteArray,
        sessionId: UUID,
        protocolMajor: Int,
        protocolMinor: Int,
    ) = synchronized(writeLock) {
        val output = socket.outputStream
        output.write(
            TransferRecordHeader(
                type,
                payload.size,
                sessionId,
                protocolMajor,
                protocolMinor,
            ).encode(),
        )
        output.write(payload)
        output.flush()
    }

    override fun close() = socket.close()

    private fun readExactly(input: InputStream, count: Int): ByteArray? {
        val result = ByteArray(count)
        var offset = 0
        while (offset < count) {
            val read = input.read(result, offset, count - offset)
            if (read == -1) {
                if (offset == 0) return null
                throw EOFException("truncated record")
            }
            offset += read
        }
        return result
    }
}

private class AndroidEndpointTicketAuthorizer(
    private val tickets: EndpointTicketRegistry,
) : InboundEndpointTicketAuthorizing {
    override fun claim(ticket: ByteArray, peerId: String, sessionId: UUID): Boolean =
        tickets.claim(ticket, peerId, sessionId)

    override fun consume() = tickets.consume()
}

private class AdvertisingEpochAdmission(
    private val scheduler: TransferSessionScheduler,
) : InboundTransferAdmitting {
    private var open = true

    @Synchronized override fun admit(peerId: ByteArray, sessionId: UUID): SessionAdmission =
        if (open) scheduler.admitInbound(peerId, sessionId)
        else SessionAdmission.Rejected("receive_unavailable")

    @Synchronized fun close() { open = false }
}

private class AndroidInboundApprovalGateway : InboundApprovalGateway {
    private val lock = Any()
    private var pending: CompletableFuture<Boolean>? = null

    override fun awaitDecision(
        summary: InboundTransferSummary,
        timeoutMillis: Long,
    ): InboundApprovalDecision {
        val decision = CompletableFuture<Boolean>()
        synchronized(lock) { pending = decision }
        return try {
            if (decision.get(timeoutMillis, TimeUnit.MILLISECONDS)) {
                InboundApprovalDecision.ACCEPTED
            } else {
                InboundApprovalDecision.REJECTED
            }
        } catch (_: TimeoutException) {
            InboundApprovalDecision.TIMED_OUT
        } catch (_: CancellationException) {
            InboundApprovalDecision.REJECTED
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            InboundApprovalDecision.REJECTED
        } finally {
            synchronized(lock) {
                if (pending === decision) pending = null
            }
        }
    }

    override fun cancel() {
        synchronized(lock) { pending }?.cancel(true)
    }

    fun approve() { synchronized(lock) { pending }?.complete(true) }
    fun reject() { synchronized(lock) { pending }?.complete(false) }
}

private class AndroidInboundDestination(
    private val destination: MediaStoreDestination,
) : InboundTransferDestination {
    override fun reserve(displayName: String, mediaType: String): InboundTransferReservation =
        AndroidInboundReservation(destination, destination.reserve(displayName, mediaType))
}

private class AndroidInboundReservation(
    private val destination: MediaStoreDestination,
    private val reservation: MediaStoreReservation,
) : InboundTransferReservation {
    override val displayName: String get() = reservation.displayName
    override fun openOutput() = destination.openPendingOutput(reservation)

    override fun commit(): CommittedArtifact {
        destination.publish(reservation)
        return CommittedArtifact(reservation.uri.toString())
    }

    override fun abort() = destination.abort(reservation)
}
