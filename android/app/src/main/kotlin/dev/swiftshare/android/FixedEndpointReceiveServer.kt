package dev.swiftshare.android

import dev.swiftshare.domain.TRANSFER_CHUNK_PRELUDE_SIZE
import dev.swiftshare.domain.BootstrapRejectionCategory
import dev.swiftshare.domain.DiscoveryProbeCodec
import dev.swiftshare.domain.NegotiatedSessionParameters
import dev.swiftshare.domain.NegotiationResult
import dev.swiftshare.domain.PeerApprovalPolicy
import dev.swiftshare.domain.SessionAdmission
import dev.swiftshare.domain.SessionBootstrapCodec
import dev.swiftshare.domain.SessionDecision
import dev.swiftshare.domain.SessionLease
import dev.swiftshare.domain.SessionNegotiator
import dev.swiftshare.domain.SessionOffer
import dev.swiftshare.domain.TransferSessionScheduler
import dev.swiftshare.domain.TransferApproval
import dev.swiftshare.domain.TransferCancel
import dev.swiftshare.domain.TransferChunkPrelude
import dev.swiftshare.domain.TransferControlCodec
import dev.swiftshare.domain.TransferErrorCategory
import dev.swiftshare.domain.TransferManifest
import dev.swiftshare.domain.TransferProgress
import dev.swiftshare.domain.TransferProgressPhase
import dev.swiftshare.domain.TransferProtocolException
import dev.swiftshare.domain.TransferRecordHeader
import dev.swiftshare.domain.TransferRecordType
import dev.swiftshare.domain.TransferSenderFinished
import dev.swiftshare.domain.TransferWireRecord
import dev.swiftshare.domain.TransferWireTerminalResult
import dev.swiftshare.domain.WireTerminalStatus
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.SocketException
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ArrayBlockingQueue
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
    private val localDeviceId: ByteArray,
    private val trustRepository: AndroidTrustRepository? = null,
    private val endpointTickets: EndpointTicketRegistry = EndpointTicketRegistry(),
    private val listener: ReceiveServerListener,
) : AutoCloseable {
    init { require(localDeviceId.size == 32) }
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
    private val scheduler = TransferSessionScheduler(localDeviceId)
    private val stateLock = Any()
    private val running = AtomicBoolean(false)
    @Volatile private var serverSocket: SSLServerSocket? = null
    @Volatile private var active: ActiveSession? = null
    @Volatile private var approval: CompletableFuture<Boolean>? = null
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
            } catch (error: SocketException) {
                if (running.get()) listener.onState(ReceiveServerState.Failed(TransferErrorCategory.NETWORK, "listener_closed"))
            } catch (_: Exception) {
                if (running.get()) listener.onState(ReceiveServerState.Failed(TransferErrorCategory.NETWORK, "listener_failed"))
            } finally {
                running.set(false)
                serverSocket = null
            }
        }
    }

    fun approve() { synchronized(stateLock) { approval }?.complete(true) }
    fun reject() { synchronized(stateLock) { approval }?.complete(false) }
    fun setDiscoveryEpoch(rotatingId: ByteArray?) {
        require(rotatingId == null || rotatingId.size == 16)
        discoveryRotatingId = rotatingId?.copyOf()
        if (rotatingId == null) endpointTickets.invalidate()
    }

    fun cancelActive() {
        val session = synchronized(stateLock) { active } ?: return
        session.cancelled.set(true)
        runCatching {
            session.send(
                TransferRecordType.CANCEL,
                TransferControlCodec.encode(TransferCancel("receiver_cancelled")),
            )
        }
        runCatching { session.socket.close() }
    }

    fun unpairActive(peerIdHex: String) {
        val session = synchronized(stateLock) { active } ?: return
        if (session.peerIdHex != peerIdHex) return
        session.cancelled.set(true)
        session.sendTerminalFailure(
            WireTerminalStatus.CANCELLED,
            TransferErrorCategory.AUTHENTICATION,
            "peer_unpaired",
        )
        runCatching { session.socket.close() }
    }

    override fun close() {
        running.set(false)
        synchronized(stateLock) { approval }?.cancel(true)
        cancelActive()
        runCatching { serverSocket?.close() }
        acceptExecutor.shutdownNow()
        handshakeExecutor.shutdownNow()
        workerExecutor.shutdownNow()
        listener.onState(ReceiveServerState.Stopped)
    }

    private data class AuthenticatedConnection(
        val socket: SSLSocket,
        val peerId: ByteArray,
        val peerIdHex: String,
        val authenticatedSender: String,
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
            val peerSpki = (socket.session.peerCertificates.first() as java.security.cert.X509Certificate).publicKey.encoded
            val trustToken = trustRepository?.authenticationToken(peerSpki)
            val authenticatedSender = if (trustRepository != null) trustToken?.let(trustRepository::validate)?.displayName.orEmpty() else authenticatedSenderName
            if (trustRepository != null && authenticatedSender.isEmpty()) throw java.security.cert.CertificateException("Peer was unpaired during authentication")
            val peerId = MessageDigest.getInstance("SHA-256").digest(peerSpki)
            socket.soTimeout = PROBE_TIMEOUT_MILLIS
            val magic = readExactly(socket.inputStream, 4) ?: throw EOFException("missing application magic")
            if (magic.contentEquals(DiscoveryProbeCodec.magic)) {
                val rest = readExactly(socket.inputStream, 34) ?: throw EOFException("truncated discovery probe")
                val probe = DiscoveryProbeCodec.decodeRequest(magic + rest)
                val available = discoveryRotatingId?.contentEquals(probe.rotatingId) == true
                socket.outputStream.write(DiscoveryProbeCodec.encodeReply(probe, available)); socket.outputStream.flush()
                return
            }
            val authenticated = AuthenticatedConnection(socket, peerId, peerId.toTrustHex(), authenticatedSender, trustToken, magic)
            workerExecutor.execute { socket.use { handleAuthenticated(authenticated) } }
            handedOff = true
        } catch (_: RejectedExecutionException) {
            // Bounded worker saturation rejects before a Transfer Session is created.
        } catch (_: Exception) {
            // Authentication and malformed probes do not replace the active Transfer UI.
        } finally {
            if (!handedOff) runCatching { socket.close() }
        }
    }

    private fun handleAuthenticated(connection: AuthenticatedConnection) {
        val socket = connection.socket
        var reservation: MediaStoreReservation? = null
        var output: OutputStream? = null
        var session: ActiveSession? = null
        var lease: SessionLease? = null
        var ownsUi = false
        try {
            val peerId = connection.peerId
            val trustToken = connection.trustToken
            val authenticatedSender = connection.authenticatedSender
            socket.soTimeout = HELLO_TIMEOUT_MILLIS
            val input = socket.inputStream
            val wireOutput = socket.outputStream
            val helloRecord = readRecord(input, connection.firstMagic) ?: throw EOFException("missing SessionHello")
            if (helloRecord.header.type != TransferRecordType.SESSION_HELLO) {
                publishBootstrapFailure(BootstrapRejectionCategory.PROTOCOL, "negotiation_required")
                sendBootstrapDecision(
                    wireOutput,
                    helloRecord.header.sessionId,
                    SessionDecision.rejected(SessionOffer.DEFAULT, BootstrapRejectionCategory.PROTOCOL, "negotiation_required"),
                )
                return
            }
            val hello = try {
                SessionBootstrapCodec.decodeHello(helloRecord.payload)
            } catch (error: Exception) {
                publishBootstrapFailure(BootstrapRejectionCategory.PROTOCOL, "malformed_hello")
                sendBootstrapDecision(
                    wireOutput,
                    helloRecord.header.sessionId,
                    SessionDecision.rejected(SessionOffer.DEFAULT, BootstrapRejectionCategory.PROTOCOL, "malformed_hello"),
                )
                return
            }
            val negotiated = when (val result = SessionNegotiator.negotiate(hello.offer, SessionOffer.DEFAULT)) {
                is NegotiationResult.Accepted -> result.parameters
                is NegotiationResult.Rejected -> {
                    publishBootstrapFailure(result.category, result.code)
                    sendBootstrapDecision(
                        wireOutput,
                        helloRecord.header.sessionId,
                        SessionDecision.rejected(SessionOffer.DEFAULT, result.category, result.code),
                    )
                    return
                }
            }
            val endpointTicket = hello.endpointTicket
            if (endpointTicket != null && !endpointTickets.claim(
                    endpointTicket,
                    connection.peerIdHex,
                    helloRecord.header.sessionId,
                )
            ) {
                sendBootstrapDecision(
                    wireOutput,
                    helloRecord.header.sessionId,
                    SessionDecision.rejected(SessionOffer.DEFAULT, BootstrapRejectionCategory.AUTHENTICATION, "invalid_endpoint_ticket"),
                )
                return
            }
            if (trustRepository != null && (trustToken == null || trustRepository.validate(trustToken) == null)) {
                publishBootstrapFailure(BootstrapRejectionCategory.AUTHENTICATION, "trust_revoked")
                sendBootstrapDecision(
                    wireOutput,
                    helloRecord.header.sessionId,
                    SessionDecision.rejected(SessionOffer.DEFAULT, BootstrapRejectionCategory.AUTHENTICATION, "trust_revoked"),
                )
                return
            }
            when (val admission = scheduler.admitInbound(peerId, helloRecord.header.sessionId)) {
                is SessionAdmission.Rejected -> {
                    val category = if (admission.code == "identity_collision") {
                        BootstrapRejectionCategory.AUTHENTICATION
                    } else BootstrapRejectionCategory.BUSY
                    sendBootstrapDecision(
                        wireOutput,
                        helloRecord.header.sessionId,
                        SessionDecision.rejected(SessionOffer.DEFAULT, category, admission.code),
                    )
                    return
                }
                is SessionAdmission.Admitted -> lease = admission.lease
            }
            if (endpointTicket != null) endpointTickets.consume()
            try {
                sendBootstrapDecision(
                    wireOutput,
                    helloRecord.header.sessionId,
                    SessionDecision.accepted(SessionOffer.DEFAULT, negotiated),
                )
            } catch (error: Exception) {
                scheduler.release(lease.token)
                lease = null
                throw error
            }
            socket.soTimeout = IDLE_TIMEOUT_MILLIS
            val manifestRecord = readRecord(input) ?: throw EOFException("missing Manifest")
            if (manifestRecord.header.type != TransferRecordType.MANIFEST ||
                manifestRecord.header.sessionId != helloRecord.header.sessionId ||
                manifestRecord.header.protocolMajor != negotiated.version.major ||
                manifestRecord.header.protocolMinor != negotiated.version.minor
            ) {
                throw TransferProtocolException("manifest_required")
            }
            val manifest = TransferControlCodec.decode(
                TransferRecordType.MANIFEST,
                manifestRecord.payload,
            ) as TransferManifest
            val entry = manifest.entries.single()
            if (entry.chunkSize != negotiated.chunkSize) throw TransferProtocolException("negotiated_chunk_size_mismatch")
            val summary = IncomingTransferSummary(authenticatedSender, entry.displayName, entry.byteCount)
            session = ActiveSession(
                socket, wireOutput, manifestRecord.header.sessionId, negotiated, connection.peerIdHex,
            )
            if (trustRepository != null) {
                trustToken?.let(trustRepository::validate) ?: throw ReceiverUnpaired()
            }
            synchronized(stateLock) {
                active = session
                ownsUi = true
            }
            val trustedPeer = if (trustRepository != null) {
                trustToken?.let(trustRepository::validate) ?: throw ReceiverUnpaired()
            } else null
            val autoAccept = trustedPeer?.approvalPolicy == PeerApprovalPolicy.AUTO_ACCEPT
            val decision = if (autoAccept) null else CompletableFuture<Boolean>()
            synchronized(stateLock) { approval = decision }
            val accepted = if (autoAccept) true else {
                listener.onState(ReceiveServerState.AwaitingApproval(summary))
                try {
                    requireNotNull(decision).get(APPROVAL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                } catch (_: TimeoutException) {
                    false
                }
            }
            session.send(
                TransferRecordType.APPROVAL,
                TransferControlCodec.encode(
                    TransferApproval(accepted, if (accepted) "" else "user_rejected"),
                ),
            )
            if (!accepted) return

            reservation = destination.reserve(entry.displayName, entry.mediaType)
            val pendingOutput = destination.openPendingOutput(reservation)
            output = pendingOutput
            val fileDigest = MessageDigest.getInstance("SHA-256")
            var expectedIndex = 0L
            var expectedOffset = 0L
            while (true) {
                if (session.cancelled.get()) throw ReceiverCancelled()
                val record = readRecord(input) ?: throw EOFException("connection closed before terminal record")
                if (record.header.sessionId != session.sessionId) throw TransferProtocolException("session_id_mismatch")
                if (record.header.protocolMajor != negotiated.version.major ||
                    record.header.protocolMinor != negotiated.version.minor
                ) throw TransferProtocolException("session_version_mismatch")
                when (record.header.type) {
                    TransferRecordType.CHUNK -> {
                        val prelude = TransferChunkPrelude.decode(
                            record.payload.copyOfRange(0, TRANSFER_CHUNK_PRELUDE_SIZE),
                        )
                        val data = record.payload.copyOfRange(TRANSFER_CHUNK_PRELUDE_SIZE, record.payload.size)
                        if (prelude.itemId != entry.itemId) throw TransferProtocolException("item_id_mismatch")
                        if (prelude.chunkIndex != expectedIndex || prelude.offset != expectedOffset) {
                            throw TransferProtocolException("out_of_order_chunk")
                        }
                        if (prelude.dataLength != data.size) throw TransferProtocolException("chunk_length_mismatch")
                        val remaining = entry.byteCount - expectedOffset
                        val expectedLength = minOf(negotiated.chunkSize.toLong(), remaining).toInt()
                        if (data.size != expectedLength) throw TransferProtocolException("negotiated_chunk_length_mismatch")
                        val digest = MessageDigest.getInstance("SHA-256").digest(data)
                        if (!MessageDigest.isEqual(digest, prelude.sha256)) throw ReceiverIntegrityFailure("chunk_sha256_mismatch")
                        if (expectedOffset + data.size > entry.byteCount) throw ReceiverIntegrityFailure("byte_count_exceeded")
                        pendingOutput.write(data)
                        fileDigest.update(data)
                        expectedOffset += data.size
                        expectedIndex += 1
                        listener.onState(ReceiveServerState.Receiving(summary, expectedOffset))
                        session.send(
                            TransferRecordType.PROGRESS,
                            TransferControlCodec.encode(
                                TransferProgress(
                                    entry.itemId,
                                    expectedOffset,
                                    expectedIndex,
                                    TransferProgressPhase.TRANSFERRING,
                                ),
                            ),
                        )
                    }
                    TransferRecordType.SENDER_FINISHED -> {
                        val finished = TransferControlCodec.decode(
                            TransferRecordType.SENDER_FINISHED,
                            record.payload,
                        ) as TransferSenderFinished
                        pendingOutput.flush()
                        pendingOutput.close()
                        output = null
                        listener.onState(ReceiveServerState.Verifying(summary))
                        if (finished.itemId != entry.itemId || finished.byteCount != entry.byteCount || expectedOffset != entry.byteCount) {
                            throw ReceiverIntegrityFailure("file_length_mismatch")
                        }
                        if (!MessageDigest.isEqual(finished.sha256, entry.sha256) ||
                            !MessageDigest.isEqual(fileDigest.digest(), entry.sha256)
                        ) {
                            throw ReceiverIntegrityFailure("file_sha256_mismatch")
                        }
                        destination.publish(reservation)
                        val artifact = reservation.uri.toString()
                        session.send(
                            TransferRecordType.TERMINAL_RESULT,
                            TransferControlCodec.encode(
                                TransferWireTerminalResult(
                                    WireTerminalStatus.COMPLETED,
                                    committedArtifactId = artifact,
                                ),
                            ),
                        )
                        listener.onState(ReceiveServerState.Completed(reservation.displayName, artifact))
                        return
                    }
                    TransferRecordType.CANCEL -> {
                        val cancel = TransferControlCodec.decode(TransferRecordType.CANCEL, record.payload) as TransferCancel
                        if (cancel.reasonCode == "peer_unpaired") throw ReceiverUnpaired()
                        throw ReceiverCancelled()
                    }
                    else -> throw TransferProtocolException("unexpected_${record.header.type.name.lowercase()}")
                }
            }
        } catch (_: ReceiverCancelled) {
            reservation?.let(destination::abort)
            if (ownsUi) listener.onState(ReceiveServerState.Failed(TransferErrorCategory.CANCELLED, "receiver_cancelled"))
            session?.sendTerminalFailure(WireTerminalStatus.CANCELLED, TransferErrorCategory.CANCELLED, "receiver_cancelled")
        } catch (_: ReceiverUnpaired) {
            reservation?.let(destination::abort)
            if (ownsUi) listener.onState(ReceiveServerState.Failed(TransferErrorCategory.AUTHENTICATION, "peer_unpaired"))
            session?.sendTerminalFailure(WireTerminalStatus.CANCELLED, TransferErrorCategory.AUTHENTICATION, "peer_unpaired")
        } catch (error: ReceiverIntegrityFailure) {
            reservation?.let(destination::abort)
            if (ownsUi) listener.onState(ReceiveServerState.Failed(TransferErrorCategory.INTEGRITY, error.message ?: "integrity_failed"))
            session?.sendTerminalFailure(WireTerminalStatus.FAILED, TransferErrorCategory.INTEGRITY, error.message ?: "integrity_failed")
        } catch (error: Exception) {
            reservation?.let(destination::abort)
            if (!running.get()) return
            if (session?.cancelled?.get() == true) {
                if (ownsUi) listener.onState(ReceiveServerState.Failed(TransferErrorCategory.CANCELLED, "receiver_cancelled"))
                session.sendTerminalFailure(
                    WireTerminalStatus.CANCELLED,
                    TransferErrorCategory.CANCELLED,
                    "receiver_cancelled",
                )
            } else {
                val category = if (error is DestinationStorageException) {
                    TransferErrorCategory.STORAGE
                } else {
                    TransferErrorCategory.PROTOCOL
                }
                if (ownsUi) listener.onState(ReceiveServerState.Failed(category, error.message ?: "receive_failed"))
                session?.sendTerminalFailure(WireTerminalStatus.FAILED, category, error.message ?: "receive_failed")
            }
        } finally {
            runCatching { output?.close() }
            synchronized(stateLock) {
                if (session != null && active === session) {
                    approval = null
                    active = null
                }
            }
            lease?.let { scheduler.release(it.token) }
        }
    }

    private fun sendBootstrapDecision(output: OutputStream, sessionId: UUID, decision: SessionDecision) {
        val payload = SessionBootstrapCodec.encode(decision)
        output.write(TransferRecordHeader(
            TransferRecordType.SESSION_DECISION,
            payload.size,
            sessionId,
            protocolMajor = 0,
            protocolMinor = 0,
        ).encode())
        output.write(payload)
        output.flush()
    }

    private fun publishBootstrapFailure(category: BootstrapRejectionCategory, code: String) {
        if (scheduler.hasActiveSession()) return
        val domainCategory = when (category) {
            BootstrapRejectionCategory.INCOMPATIBLE_VERSION,
            BootstrapRejectionCategory.CAPABILITY,
            -> TransferErrorCategory.INCOMPATIBLE_VERSION
            BootstrapRejectionCategory.BUSY -> TransferErrorCategory.BUSY
            BootstrapRejectionCategory.AUTHENTICATION -> TransferErrorCategory.AUTHENTICATION
            BootstrapRejectionCategory.PROTOCOL -> TransferErrorCategory.PROTOCOL
        }
        listener.onState(ReceiveServerState.Failed(domainCategory, code))
    }

    private fun readRecord(input: InputStream, prefix: ByteArray? = null): TransferWireRecord? {
        val headerBytes = if (prefix == null) {
            readExactly(input, dev.swiftshare.domain.TRANSFER_HEADER_SIZE) ?: return null
        } else {
            require(prefix.size == 4)
            prefix + (readExactly(input, dev.swiftshare.domain.TRANSFER_HEADER_SIZE - 4) ?: throw EOFException("truncated record header"))
        }
        val header = TransferRecordHeader.decode(headerBytes)
        val payload = readExactly(input, header.payloadLength)
            ?: throw EOFException("truncated payload")
        return TransferWireRecord(header, payload)
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

    private class ActiveSession(
        val socket: SSLSocket,
        private val output: OutputStream,
        val sessionId: UUID,
        private val negotiated: NegotiatedSessionParameters,
        val peerIdHex: String,
    ) {
        val cancelled = AtomicBoolean(false)
        private val writeLock = Any()

        fun send(type: TransferRecordType, payload: ByteArray) = synchronized(writeLock) {
            output.write(TransferRecordHeader(
                type,
                payload.size,
                sessionId,
                protocolMajor = negotiated.version.major,
                protocolMinor = negotiated.version.minor,
            ).encode())
            output.write(payload)
            output.flush()
        }

        fun sendTerminalFailure(status: WireTerminalStatus, category: TransferErrorCategory, code: String) {
            runCatching {
                send(
                    TransferRecordType.TERMINAL_RESULT,
                    TransferControlCodec.encode(TransferWireTerminalResult(status, category, code)),
                )
            }
        }
    }

    private class ReceiverCancelled : Exception()
    private class ReceiverUnpaired : Exception()
    private class ReceiverIntegrityFailure(message: String) : Exception(message)

    companion object {
        private const val APPROVAL_TIMEOUT_SECONDS = 120L
        private const val CONNECT_TIMEOUT_MILLIS = 15_000
        private const val HELLO_TIMEOUT_MILLIS = 10_000
        private const val IDLE_TIMEOUT_MILLIS = 60_000
        private const val CONNECTION_WORKERS = 4
        private const val CONNECTION_QUEUE_CAPACITY = 4
        private const val HANDSHAKE_WORKERS = 4
        private const val HANDSHAKE_QUEUE_CAPACITY = 8
        private const val PROBE_TIMEOUT_MILLIS = 3_000
    }
}
