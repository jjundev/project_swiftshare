package dev.swiftshare.domain

import java.io.IOException
import java.io.OutputStream
import java.security.MessageDigest
import java.util.UUID

interface InboundTransferRecordChannel {
    fun receiveRecord(timeoutMillis: Int, prefix: ByteArray? = null): TransferWireRecord?

    fun sendRecord(
        type: TransferRecordType,
        payload: ByteArray,
        sessionId: UUID,
        protocolMajor: Int,
        protocolMinor: Int,
    )

    fun close()
}

data class InboundAuthorizedPeer(
    val id: String,
    val displayName: String,
    val approvalPolicy: PeerApprovalPolicy,
)

fun interface InboundPeerAuthorizing {
    fun currentPeer(): InboundAuthorizedPeer?
}

interface InboundEndpointTicketAuthorizing {
    fun claim(ticket: ByteArray, peerId: String, sessionId: UUID): Boolean
    fun consume()
}

fun interface InboundTransferAdmitting {
    fun admit(peerId: ByteArray, sessionId: UUID): SessionAdmission
}

interface InboundTransferReservation {
    val displayName: String
    fun openOutput(): OutputStream
    fun commit(): CommittedArtifact
    fun abort()
}

fun interface InboundTransferDestination {
    fun reserve(displayName: String, mediaType: String): InboundTransferReservation
}

data class InboundTransferSummary(
    val authenticatedSender: String,
    val displayName: String,
    val byteCount: Long,
    val itemCount: Int = 1,
    val totalBytes: Long = byteCount,
)

enum class InboundApprovalDecision {
    ACCEPTED,
    REJECTED,
    TIMED_OUT,
}

interface InboundApprovalGateway {
    fun awaitDecision(summary: InboundTransferSummary, timeoutMillis: Long): InboundApprovalDecision
    fun cancel()
}

data class InboundTransferSessionEvent(
    val phase: TransferActivityPhase,
    val summary: InboundTransferSummary,
    val verifiedBytes: Long = 0,
    val artifact: CommittedArtifact? = null,
    val failure: TransferFailure? = null,
    val completedItems: Int = 0,
    val totalItems: Int = 1,
    val currentItemName: String = "",
)

fun interface InboundTransferEventSink {
    fun emit(event: InboundTransferSessionEvent)
}

data class InboundAuthenticatedConnection(
    val peerId: ByteArray,
    val peerIdKey: String,
    val firstRecordPrefix: ByteArray,
    val channel: InboundTransferRecordChannel,
    val authorizer: InboundPeerAuthorizing,
) {
    init {
        require(peerId.size == 32)
        require(firstRecordPrefix.size == 4)
    }
}

data class InboundTransferTimeoutPolicy(
    val negotiationMillis: Int = 10_000,
    val approvalMillis: Long = 120_000,
    val idleMillis: Int = 60_000,
)

class InboundTransferSession(
    private val scheduler: TransferSessionScheduler,
    private val endpointTickets: InboundEndpointTicketAuthorizing,
    private val approvalGateway: InboundApprovalGateway,
    private val destination: InboundTransferDestination,
    private val events: InboundTransferEventSink,
    private val admission: InboundTransferAdmitting = InboundTransferAdmitting(scheduler::admitInbound),
    private val timeouts: InboundTransferTimeoutPolicy = InboundTransferTimeoutPolicy(),
) {
    private data class Execution(
        val connection: InboundAuthenticatedConnection,
        var sessionId: UUID? = null,
        var version: NegotiatedProtocolVersion? = null,
        var lease: SessionLease? = null,
        var reservation: InboundTransferReservation? = null,
        var summary: InboundTransferSummary? = null,
        var cancellationReason: String? = null,
        var visible: Boolean = false,
        var committed: Boolean = false,
        val committedArtifacts: MutableList<CommittedArtifact> = mutableListOf(),
    )

    private val lock = Any()
    private var current: Execution? = null

    fun execute(connection: InboundAuthenticatedConnection): RoleTransferOutcome {
        val execution = Execution(connection)
        var output: OutputStream? = null
        return try {
            run(execution) { opened -> output = opened }
        } catch (error: InboundCancellation) {
            finishCancellation(execution, error.reason)
        } catch (error: InboundAuthenticationFailure) {
            finishFailure(
                execution,
                TransferTerminalResult.CANCELLED,
                TransferActivityPhase.CANCELLED,
                TransferErrorCategory.AUTHENTICATION,
                error.message ?: "peer_unpaired",
            )
        } catch (error: InboundIntegrityFailure) {
            finishFailure(
                execution,
                TransferTerminalResult.FAILED,
                TransferActivityPhase.FAILED,
                TransferErrorCategory.INTEGRITY,
                error.message ?: "integrity_failed",
            )
        } catch (error: InboundStorageFailure) {
            finishFailure(
                execution,
                TransferTerminalResult.FAILED,
                TransferActivityPhase.FAILED,
                TransferErrorCategory.STORAGE,
                error.message ?: "storage_failed",
            )
        } catch (error: TransferProtocolException) {
            finishFailure(
                execution,
                TransferTerminalResult.FAILED,
                TransferActivityPhase.FAILED,
                TransferErrorCategory.PROTOCOL,
                error.protocolCode,
            )
        } catch (error: IOException) {
            val reason = synchronized(lock) { execution.cancellationReason }
            if (reason != null) finishCancellation(execution, reason) else finishFailure(
                execution,
                TransferTerminalResult.FAILED,
                TransferActivityPhase.FAILED,
                TransferErrorCategory.NETWORK,
                "connection_failed",
            )
        } catch (error: Exception) {
            val reason = synchronized(lock) { execution.cancellationReason }
            if (reason != null) finishCancellation(execution, reason) else finishFailure(
                execution,
                TransferTerminalResult.FAILED,
                TransferActivityPhase.FAILED,
                TransferErrorCategory.STORAGE,
                error.message ?: "storage_failed",
            )
        } finally {
            runCatching { output?.close() }
            if (!execution.committed) execution.reservation?.let { runCatching(it::abort) }
            execution.lease?.let { scheduler.release(it.token) }
            synchronized(lock) {
                if (current === execution) current = null
            }
            runCatching(connection.channel::close)
        }
    }

    fun cancel(reasonCode: String, peerIdKey: String? = null) {
        val execution = synchronized(lock) {
            val value = current ?: return
            if (peerIdKey != null && value.connection.peerIdKey != peerIdKey) return
            if (value.committed) return
            value.cancellationReason = reasonCode
            value
        }
        approvalGateway.cancel()
        val sessionId = execution.sessionId
        val version = execution.version
        if (sessionId != null && version != null) {
            runCatching {
                execution.connection.channel.sendRecord(
                    TransferRecordType.CANCEL,
                    TransferControlCodec.encode(TransferCancel(reasonCode)),
                    sessionId,
                    version.major,
                    version.minor,
                )
            }
        }
        runCatching(execution.connection.channel::close)
    }

    fun hasActiveTransfer(): Boolean = synchronized(lock) { current != null }

    private fun run(execution: Execution, openedOutput: (OutputStream) -> Unit): RoleTransferOutcome {
        val connection = execution.connection
        val helloRecord = connection.channel.receiveRecord(
            timeouts.negotiationMillis,
            connection.firstRecordPrefix,
        ) ?: throw TransferProtocolException("missing_session_hello")
        execution.sessionId = helloRecord.header.sessionId
        if (helloRecord.header.type != TransferRecordType.SESSION_HELLO) {
            return rejectBootstrap(execution, BootstrapRejectionCategory.PROTOCOL, "negotiation_required")
        }
        val hello = try {
            SessionBootstrapCodec.decodeHello(helloRecord.payload)
        } catch (_: Exception) {
            return rejectBootstrap(execution, BootstrapRejectionCategory.PROTOCOL, "malformed_hello")
        }
        val negotiated = when (val result = SessionNegotiator.negotiate(hello.offer, SessionOffer.DEFAULT)) {
            is NegotiationResult.Accepted -> result.parameters
            is NegotiationResult.Rejected -> return rejectBootstrap(execution, result.category, result.code)
        }
        val ticket = hello.endpointTicket
        if (ticket != null && !endpointTickets.claim(ticket, connection.peerIdKey, helloRecord.header.sessionId)) {
            return rejectBootstrap(execution, BootstrapRejectionCategory.AUTHENTICATION, "invalid_endpoint_ticket")
        }
        connection.authorizer.currentPeer()
            ?: return rejectBootstrap(execution, BootstrapRejectionCategory.AUTHENTICATION, "trust_revoked")
        val lease = when (val result = admission.admit(connection.peerId, helloRecord.header.sessionId)) {
            is SessionAdmission.Admitted -> result.lease
            is SessionAdmission.Rejected -> {
                val category = if (result.code == "identity_collision") {
                    BootstrapRejectionCategory.AUTHENTICATION
                } else {
                    BootstrapRejectionCategory.BUSY
                }
                return rejectBootstrap(execution, category, result.code)
            }
        }
        execution.lease = lease
        synchronized(lock) { current = execution }
        if (ticket != null) endpointTickets.consume()
        sendBootstrapDecision(
            execution,
            SessionDecision.accepted(SessionOffer.DEFAULT, negotiated),
        )
        execution.version = negotiated.version
        throwIfCancelled(execution)

        val manifestRecord = connection.channel.receiveRecord(timeouts.idleMillis)
            ?: throw TransferProtocolException("missing_manifest")
        requireRecord(execution, manifestRecord, TransferRecordType.MANIFEST)
        val manifest = decodeControl(TransferRecordType.MANIFEST, manifestRecord.payload) as? TransferManifest
            ?: throw TransferProtocolException("invalid_manifest")
        if (manifest.entries.isEmpty()) throw TransferProtocolException("empty_manifest")
        val totalBytes = manifest.entries.sumOf { it.byteCount }
        if (totalBytes > TRANSFER_MAX_BATCH_BYTES) throw TransferProtocolException("manifest_too_large")
        manifest.entries.forEach { entry ->
            if (entry.chunkSize != negotiated.chunkSize) throw TransferProtocolException("negotiated_chunk_size_mismatch")
        }
        val totalItems = manifest.entries.size
        val peer = connection.authorizer.currentPeer() ?: throw InboundAuthenticationFailure("peer_unpaired")
        val summary = InboundTransferSummary(
            peer.displayName,
            manifest.entries.first().displayName,
            manifest.entries.first().byteCount,
            totalItems,
            totalBytes,
        )
        execution.summary = summary
        execution.visible = true

        val decision = if (peer.approvalPolicy == PeerApprovalPolicy.AUTO_ACCEPT) {
            InboundApprovalDecision.ACCEPTED
        } else {
            emit(execution, summary, TransferActivityPhase.AWAITING_APPROVAL, totalItems = totalItems)
            approvalGateway.awaitDecision(summary, timeouts.approvalMillis)
        }
        throwIfCancelled(execution)
        val accepted = decision == InboundApprovalDecision.ACCEPTED
        val rejectionCode = when (decision) {
            InboundApprovalDecision.ACCEPTED -> ""
            InboundApprovalDecision.REJECTED -> "user_rejected"
            InboundApprovalDecision.TIMED_OUT -> "approval_timeout"
        }
        send(
            execution,
            TransferRecordType.APPROVAL,
            TransferControlCodec.encode(TransferApproval(accepted, rejectionCode)),
        )
        if (!accepted) {
            val failure = TransferFailure(TransferErrorCategory.APPROVAL, rejectionCode)
            emit(execution, summary, TransferActivityPhase.REJECTED, failure = failure, totalItems = totalItems)
            return RoleTransferOutcome(TransferTerminalResult.REJECTED, failure = failure)
        }

        // Receive each item in manifest order. Already-committed items are retained even
        // if a later item fails; only the in-flight Staging Reservation is aborted.
        var committedBytes = 0L
        manifest.entries.forEachIndexed { index, entry ->
            val isLast = index == manifest.entries.lastIndex
            val reservation = destination.reserve(entry.displayName, entry.mediaType)
            execution.reservation = reservation
            val pendingOutput = try {
                reservation.openOutput()
            } catch (_: IOException) {
                throw InboundStorageFailure("pending_open_failed")
            }
            openedOutput(pendingOutput)
            val fileDigest = MessageDigest.getInstance("SHA-256")
            var expectedIndex = 0L
            var expectedOffset = 0L
            emit(
                execution, summary, TransferActivityPhase.TRANSFERRING, committedBytes,
                completedItems = index, totalItems = totalItems, currentItemName = entry.displayName,
            )

            item@ while (true) {
                throwIfCancelled(execution)
                val record = connection.channel.receiveRecord(timeouts.idleMillis)
                    ?: throw IOException("connection_closed")
                requireRecord(execution, record, record.header.type)
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
                        if (!MessageDigest.isEqual(MessageDigest.getInstance("SHA-256").digest(data), prelude.sha256)) {
                            throw InboundIntegrityFailure("chunk_sha256_mismatch")
                        }
                        if (expectedOffset + data.size > entry.byteCount) throw InboundIntegrityFailure("byte_count_exceeded")
                        try {
                            pendingOutput.write(data)
                        } catch (_: IOException) {
                            throw InboundStorageFailure("pending_write_failed")
                        }
                        fileDigest.update(data)
                        expectedOffset += data.size
                        expectedIndex += 1
                        emit(
                            execution, summary, TransferActivityPhase.TRANSFERRING, committedBytes + expectedOffset,
                            completedItems = index, totalItems = totalItems, currentItemName = entry.displayName,
                        )
                        send(
                            execution,
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
                        val finished = decodeControl(
                            TransferRecordType.SENDER_FINISHED,
                            record.payload,
                        ) as? TransferSenderFinished ?: throw TransferProtocolException("invalid_sender_finished")
                        try {
                            pendingOutput.flush()
                            pendingOutput.close()
                        } catch (_: IOException) {
                            throw InboundStorageFailure("pending_write_failed")
                        }
                        emit(
                            execution, summary, TransferActivityPhase.VERIFYING, committedBytes + expectedOffset,
                            completedItems = index, totalItems = totalItems, currentItemName = entry.displayName,
                        )
                        if (finished.itemId != entry.itemId ||
                            finished.byteCount != entry.byteCount ||
                            expectedOffset != entry.byteCount
                        ) {
                            throw InboundIntegrityFailure("file_length_mismatch")
                        }
                        if (!MessageDigest.isEqual(finished.sha256, entry.sha256) ||
                            !MessageDigest.isEqual(fileDigest.digest(), entry.sha256)
                        ) {
                            throw InboundIntegrityFailure("file_sha256_mismatch")
                        }
                        emit(
                            execution, summary, TransferActivityPhase.COMMITTING, committedBytes + expectedOffset,
                            completedItems = index, totalItems = totalItems, currentItemName = entry.displayName,
                        )
                        val artifact = synchronized(lock) {
                            execution.cancellationReason?.let { throw InboundCancellation(it) }
                            reservation.commit().also {
                                if (isLast) execution.committed = true
                            }
                        }
                        execution.committedArtifacts += artifact
                        execution.reservation = null
                        committedBytes += entry.byteCount
                        if (isLast) {
                            runCatching {
                                send(
                                    execution,
                                    TransferRecordType.TERMINAL_RESULT,
                                    TransferControlCodec.encode(
                                        TransferWireTerminalResult(
                                            WireTerminalStatus.COMPLETED,
                                            committedArtifactId = "",
                                        ),
                                    ),
                                )
                            }
                            emit(
                                execution, summary, TransferActivityPhase.COMPLETED, committedBytes, artifact,
                                completedItems = totalItems, totalItems = totalItems,
                            )
                            return RoleTransferOutcome(
                                TransferTerminalResult.COMPLETED,
                                execution.committedArtifacts.toList(),
                            )
                        }
                        send(
                            execution,
                            TransferRecordType.ITEM_COMMITTED,
                            TransferControlCodec.encode(TransferItemCommitted(entry.itemId, "")),
                        )
                        break@item
                    }

                    TransferRecordType.CANCEL -> {
                        val cancel = decodeControl(TransferRecordType.CANCEL, record.payload) as? TransferCancel
                            ?: throw TransferProtocolException("invalid_cancel")
                        throw InboundCancellation(cancel.reasonCode.ifEmpty { "sender_cancelled" })
                    }

                    else -> throw TransferProtocolException("unexpected_${record.header.type.name.lowercase()}")
                }
            }
        }
        throw TransferProtocolException("missing_sender_finished")
    }

    private fun rejectBootstrap(
        execution: Execution,
        category: BootstrapRejectionCategory,
        code: String,
    ): RoleTransferOutcome {
        sendBootstrapDecision(execution, SessionDecision.rejected(SessionOffer.DEFAULT, category, code))
        val domainCategory = when (category) {
            BootstrapRejectionCategory.INCOMPATIBLE_VERSION,
            BootstrapRejectionCategory.CAPABILITY,
            -> TransferErrorCategory.INCOMPATIBLE_VERSION
            BootstrapRejectionCategory.BUSY -> TransferErrorCategory.BUSY
            BootstrapRejectionCategory.AUTHENTICATION -> TransferErrorCategory.AUTHENTICATION
            BootstrapRejectionCategory.PROTOCOL -> TransferErrorCategory.PROTOCOL
        }
        return failed(domainCategory, code)
    }

    private fun sendBootstrapDecision(execution: Execution, decision: SessionDecision) {
        execution.connection.channel.sendRecord(
            TransferRecordType.SESSION_DECISION,
            SessionBootstrapCodec.encode(decision),
            requireNotNull(execution.sessionId),
            0,
            0,
        )
    }

    private fun send(execution: Execution, type: TransferRecordType, payload: ByteArray) {
        val version = requireNotNull(execution.version)
        execution.connection.channel.sendRecord(
            type,
            payload,
            requireNotNull(execution.sessionId),
            version.major,
            version.minor,
        )
    }

    private fun requireRecord(
        execution: Execution,
        record: TransferWireRecord,
        type: TransferRecordType,
    ) {
        val version = requireNotNull(execution.version)
        if (record.header.sessionId != execution.sessionId ||
            record.header.type != type ||
            record.header.protocolMajor != version.major ||
            record.header.protocolMinor != version.minor
        ) {
            throw TransferProtocolException("unexpected_record")
        }
    }

    private fun decodeControl(type: TransferRecordType, payload: ByteArray): TransferControlMessage = try {
        TransferControlCodec.decode(type, payload)
    } catch (error: TransferProtocolException) {
        throw error
    } catch (_: Exception) {
        throw TransferProtocolException("control_decode_failed")
    }

    private fun throwIfCancelled(execution: Execution) {
        val reason = synchronized(lock) { execution.cancellationReason }
        if (reason != null) throw InboundCancellation(reason)
    }

    private fun finishCancellation(execution: Execution, reason: String): RoleTransferOutcome {
        val category = if (reason == "peer_unpaired") {
            TransferErrorCategory.AUTHENTICATION
        } else {
            TransferErrorCategory.CANCELLED
        }
        return finishFailure(
            execution,
            TransferTerminalResult.CANCELLED,
            TransferActivityPhase.CANCELLED,
            category,
            reason,
        )
    }

    private fun finishFailure(
        execution: Execution,
        result: TransferTerminalResult,
        phase: TransferActivityPhase,
        category: TransferErrorCategory,
        code: String,
    ): RoleTransferOutcome {
        if (execution.version != null) {
            runCatching {
                send(
                    execution,
                    TransferRecordType.TERMINAL_RESULT,
                    TransferControlCodec.encode(
                        TransferWireTerminalResult(
                            status = when (result) {
                                TransferTerminalResult.CANCELLED -> WireTerminalStatus.CANCELLED
                                TransferTerminalResult.REJECTED -> WireTerminalStatus.REJECTED
                                else -> WireTerminalStatus.FAILED
                            },
                            errorCategory = category,
                            errorCode = code,
                        ),
                    ),
                )
            }
        }
        val failure = TransferFailure(category, code)
        execution.summary?.let {
            emit(
                execution, it, phase, failure = failure,
                completedItems = execution.committedArtifacts.size, totalItems = it.itemCount,
            )
        }
        return RoleTransferOutcome(result, execution.committedArtifacts.toList(), failure)
    }

    private fun emit(
        execution: Execution,
        summary: InboundTransferSummary,
        phase: TransferActivityPhase,
        verifiedBytes: Long = 0,
        artifact: CommittedArtifact? = null,
        failure: TransferFailure? = null,
        completedItems: Int = 0,
        totalItems: Int = 1,
        currentItemName: String = "",
    ) {
        if (execution.visible) {
            events.emit(
                InboundTransferSessionEvent(
                    phase, summary, verifiedBytes, artifact, failure,
                    completedItems, totalItems, currentItemName,
                ),
            )
        }
    }

    private fun failed(category: TransferErrorCategory, code: String) = RoleTransferOutcome(
        TransferTerminalResult.FAILED,
        failure = TransferFailure(category, code),
    )

    private class InboundCancellation(val reason: String) : Exception(reason)
    private class InboundAuthenticationFailure(message: String) : Exception(message)
    private class InboundIntegrityFailure(message: String) : Exception(message)
    private class InboundStorageFailure(message: String) : Exception(message)
}
