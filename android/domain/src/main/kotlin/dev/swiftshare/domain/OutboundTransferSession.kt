package dev.swiftshare.domain

import java.security.MessageDigest
import java.util.UUID

interface OutboundTransferRecordChannel {
    suspend fun sendRecord(
        type: TransferRecordType,
        payload: ByteArray,
        sessionId: UUID,
        protocolMajor: Int,
        protocolMinor: Int,
    )

    suspend fun receiveRecord(): TransferWireRecord
    suspend fun close()
}

data class OutboundAuthenticatedPeerLocation(
    val channel: OutboundTransferRecordChannel,
    val endpointTicket: ByteArray? = null,
) {
    init { require(endpointTicket == null || endpointTicket.size == ENDPOINT_TICKET_BYTES) }
}

fun interface OutboundAuthenticatedPeerLocating {
    suspend fun locate(peerId: ByteArray): OutboundAuthenticatedPeerLocation
}

interface OutboundTransferPayloadStream {
    suspend fun read(upToCount: Int): ByteArray?
    suspend fun close()
}

interface OutboundTransferPayload {
    val itemId: UUID
    val displayName: String
    val byteCount: Long
    val sha256: ByteArray
    val mediaType: String

    suspend fun validateForTransfer()
    suspend fun openStream(): OutboundTransferPayloadStream
}

data class OutboundTransferIntent(
    val peerId: ByteArray,
    val payloads: List<OutboundTransferPayload>,
) {
    init {
        require(peerId.size == 32)
        require(payloads.isNotEmpty() && payloads.size <= TRANSFER_MAX_BATCH_ITEMS)
    }

    /** Convenience for a single-item Transfer Session. */
    constructor(peerId: ByteArray, payload: OutboundTransferPayload) : this(peerId, listOf(payload))
}

data class ProductionOutboundTimeoutPolicy(
    val locatingMillis: Long = 5_000,
    val negotiationMillis: Long = 10_000,
    val approvalMillis: Long = 120_000,
    val idleMillis: Long = 60_000,
)

class ProductionOutboundTransferSession(
    private val scheduler: TransferSessionScheduler,
    private val locator: OutboundAuthenticatedPeerLocating,
    private val deadlines: TransferDeadlineScheduler,
    private val timeouts: ProductionOutboundTimeoutPolicy = ProductionOutboundTimeoutPolicy(),
) {
    private data class Execution(
        val sessionId: UUID,
        val peerId: ByteArray,
        var channel: OutboundTransferRecordChannel? = null,
        var version: NegotiatedProtocolVersion? = null,
        var cancellationReason: String? = null,
        var committedItems: Int = 0,
        var committedBytes: Long = 0,
        val committedArtifacts: MutableList<CommittedArtifact> = mutableListOf(),
    )

    private val lock = Any()
    private var current: Execution? = null

    suspend fun execute(intent: OutboundTransferIntent, events: TransferEventSink): RoleTransferOutcome {
        val sessionId = UUID.randomUUID()
        val totalBytes = intent.payloads.sumOf { it.byteCount }
        val totalItems = intent.payloads.size
        val lease = scheduler.reserveOutbound(intent.peerId, sessionId)
            ?: return finish(
                TransferTerminalResult.FAILED,
                TransferActivityPhase.FAILED,
                totalBytes,
                totalItems,
                events,
                failure = TransferFailure(TransferErrorCategory.BUSY, "device_busy"),
            )
        synchronized(lock) { current = Execution(sessionId, intent.peerId.copyOf()) }

        val progress = { synchronized(lock) { current } }
        val outcome = try {
            run(intent, lease, events)
        } catch (error: OutboundCancelled) {
            val state = progress()
            finishCancellation(
                error.reason, totalBytes, totalItems, events,
                state?.committedArtifacts?.toList() ?: emptyList(),
                state?.committedItems ?: 0, state?.committedBytes ?: 0,
            )
        } catch (error: TransferExecutionException) {
            val state = progress()
            finish(
                TransferTerminalResult.FAILED,
                TransferActivityPhase.FAILED,
                totalBytes,
                totalItems,
                events,
                failure = TransferFailure(error.category, error.errorCode),
                committedArtifacts = state?.committedArtifacts?.toList() ?: emptyList(),
                committedItems = state?.committedItems ?: 0,
                committedBytes = state?.committedBytes ?: 0,
            )
        } catch (_: Exception) {
            val state = progress()
            finish(
                TransferTerminalResult.FAILED,
                TransferActivityPhase.FAILED,
                totalBytes,
                totalItems,
                events,
                failure = TransferFailure(TransferErrorCategory.PROTOCOL, "unexpected_failure"),
                committedArtifacts = state?.committedArtifacts?.toList() ?: emptyList(),
                committedItems = state?.committedItems ?: 0,
                committedBytes = state?.committedBytes ?: 0,
            )
        }

        val channel = synchronized(lock) { current?.channel }
        runCatching { channel?.close() }
        synchronized(lock) { current = null }
        scheduler.release(lease.token)
        return outcome
    }

    suspend fun cancel(reasonCode: String, peerId: ByteArray? = null) {
        val execution = synchronized(lock) {
            val value = current ?: return
            if (peerId != null && !MessageDigest.isEqual(peerId, value.peerId)) return
            value.cancellationReason = reasonCode
            value
        }
        val channel = execution.channel ?: return
        val version = execution.version
        if (version != null) runCatching {
            channel.sendRecord(
                TransferRecordType.CANCEL,
                TransferControlCodec.encode(TransferCancel(reasonCode)),
                execution.sessionId,
                version.major,
                version.minor,
            )
        }
        runCatching { channel.close() }
    }

    private suspend fun run(
        intent: OutboundTransferIntent,
        lease: SessionLease,
        events: TransferEventSink,
    ): RoleTransferOutcome {
        val payloads = intent.payloads
        val totalItems = payloads.size
        val totalBytes = payloads.sumOf { it.byteCount }
        events.emit(event(TransferActivityPhase.CONNECTING, totalBytes, totalItems))
        payloads.forEach { validate(it) }
        val location = timed(timeouts.locatingMillis, TransferErrorCategory.DISCOVERY, "peer_unavailable") {
            locator.locate(intent.peerId)
        }
        throwIfCancelled()
        synchronized(lock) { current?.channel = location.channel }

        val hello = SessionHello(SessionOffer.DEFAULT, location.endpointTicket)
        timed(timeouts.negotiationMillis, TransferErrorCategory.NETWORK, "negotiation_timeout") {
            location.channel.sendRecord(
                TransferRecordType.SESSION_HELLO,
                SessionBootstrapCodec.encode(hello),
                currentSessionId(),
                0,
                0,
            )
        }
        val decisionRecord = timed(timeouts.negotiationMillis, TransferErrorCategory.NETWORK, "negotiation_timeout") {
            location.channel.receiveRecord()
        }
        val sessionId = currentSessionId()
        if (decisionRecord.header.sessionId != sessionId ||
            decisionRecord.header.type != TransferRecordType.SESSION_DECISION ||
            decisionRecord.header.protocolMajor != 0 || decisionRecord.header.protocolMinor != 0
        ) throw TransferExecutionException(TransferErrorCategory.PROTOCOL, "session_decision_required")
        val decision = try {
            SessionBootstrapCodec.decodeDecision(decisionRecord.payload)
        } catch (_: Exception) {
            throw TransferExecutionException(TransferErrorCategory.PROTOCOL, "invalid_session_decision")
        }
        if (!decision.accepted) {
            val failure = bootstrapFailure(decision)
            val result = if (failure.category == TransferErrorCategory.BUSY) {
                TransferTerminalResult.FAILED
            } else TransferTerminalResult.REJECTED
            return finish(
                result,
                if (result == TransferTerminalResult.REJECTED) TransferActivityPhase.REJECTED else TransferActivityPhase.FAILED,
                totalBytes,
                totalItems,
                events,
                failure = failure,
            )
        }
        val negotiated = try {
            SessionNegotiator.validate(hello, decision)
        } catch (_: Exception) {
            throw TransferExecutionException(TransferErrorCategory.PROTOCOL, "invalid_session_selection")
        }
        if (!scheduler.markOutboundAccepted(lease.token)) {
            throw TransferExecutionException(TransferErrorCategory.BUSY, "session_lease_lost")
        }
        synchronized(lock) { current?.version = negotiated.version }
        throwIfCancelled()

        send(
            location.channel,
            TransferRecordType.MANIFEST,
            TransferControlCodec.encode(
                TransferManifest(
                    payloads.map {
                        TransferFileEntry(
                            it.itemId,
                            it.displayName,
                            it.byteCount,
                            it.sha256,
                            it.mediaType,
                            negotiated.chunkSize,
                        )
                    },
                ),
            ),
            negotiated.version,
        )
        events.emit(event(TransferActivityPhase.AWAITING_APPROVAL, totalBytes, totalItems))
        val approvalRecord = timed(timeouts.approvalMillis, TransferErrorCategory.APPROVAL, "approval_timeout") {
            location.channel.receiveRecord()
        }
        if (approvalRecord.header.type == TransferRecordType.TERMINAL_RESULT) {
            return remoteTerminal(approvalRecord, totalBytes, totalItems, 0, 0, emptyList(), negotiated.version, events, false)
        }
        requireRecord(approvalRecord, TransferRecordType.APPROVAL, negotiated.version)
        val approval = TransferControlCodec.decode(TransferRecordType.APPROVAL, approvalRecord.payload) as? TransferApproval
            ?: throw TransferExecutionException(TransferErrorCategory.PROTOCOL, "invalid_approval")
        if (!approval.accepted) return finish(
            TransferTerminalResult.REJECTED,
            TransferActivityPhase.REJECTED,
            totalBytes,
            totalItems,
            events,
            failure = TransferFailure(
                TransferErrorCategory.APPROVAL,
                approval.reasonCode.ifEmpty { "user_rejected" },
            ),
        )

        // Stream each item in manifest order; the receiver acknowledges every non-final
        // item with ItemCommitted and closes the batch with one TerminalResult.
        var committedItems = 0
        var committedBytes = 0L
        val committedArtifacts = mutableListOf<CommittedArtifact>()
        payloads.forEachIndexed { index, payload ->
            val isLast = index == payloads.lastIndex
            validate(payload)
            val stream = try { payload.openStream() } catch (_: Exception) {
                throw TransferExecutionException(TransferErrorCategory.SOURCE, "source_changed")
            }
            var offset = 0L
            var chunkIndex = 0L
            val streamedDigest = MessageDigest.getInstance("SHA-256")
            events.emit(event(
                TransferActivityPhase.TRANSFERRING, totalBytes, totalItems,
                committedItems, committedBytes, currentItemName = payload.displayName,
            ))
            try {
                while (true) {
                    throwIfCancelled()
                    val bytes = stream.read(negotiated.chunkSize) ?: break
                    if (bytes.isEmpty()) continue
                    if (bytes.size > negotiated.chunkSize || offset + bytes.size > payload.byteCount) {
                        throw TransferExecutionException(TransferErrorCategory.SOURCE, "source_changed")
                    }
                    streamedDigest.update(bytes)
                    val prelude = TransferChunkPrelude(
                        payload.itemId,
                        chunkIndex,
                        offset,
                        bytes.size,
                        MessageDigest.getInstance("SHA-256").digest(bytes),
                    )
                    send(location.channel, TransferRecordType.CHUNK, prelude.encode() + bytes, negotiated.version)
                    val progressRecord = timed(timeouts.idleMillis, TransferErrorCategory.NETWORK, "progress_timeout") {
                        location.channel.receiveRecord()
                    }
                    if (progressRecord.header.type == TransferRecordType.TERMINAL_RESULT) {
                        runCatching { stream.close() }
                        return remoteTerminal(
                            progressRecord, totalBytes, totalItems, committedItems, committedBytes,
                            committedArtifacts.toList(), negotiated.version, events, false,
                        )
                    }
                    requireRecord(progressRecord, TransferRecordType.PROGRESS, negotiated.version)
                    val progress = TransferControlCodec.decode(TransferRecordType.PROGRESS, progressRecord.payload) as? TransferProgress
                        ?: throw TransferExecutionException(TransferErrorCategory.PROTOCOL, "invalid_progress")
                    val expected = offset + bytes.size
                    if (progress.itemId != payload.itemId || progress.verifiedBytes != expected) {
                        throw TransferExecutionException(TransferErrorCategory.PROTOCOL, "invalid_progress")
                    }
                    offset = expected
                    chunkIndex += 1
                    events.emit(event(
                        TransferActivityPhase.TRANSFERRING, totalBytes, totalItems,
                        committedItems, committedBytes, offset, payload.displayName,
                    ))
                }
            } catch (error: Exception) {
                runCatching { stream.close() }
                if (error is OutboundCancelled || error is TransferExecutionException) throw error
                throw TransferExecutionException(TransferErrorCategory.SOURCE, "source_read_failed")
            } finally {
                runCatching { stream.close() }
            }
            if (offset != payload.byteCount || !MessageDigest.isEqual(streamedDigest.digest(), payload.sha256)) {
                runCatching {
                    location.channel.sendRecord(
                        TransferRecordType.CANCEL,
                        TransferControlCodec.encode(TransferCancel("source_changed")),
                        currentSessionId(),
                        negotiated.version.major,
                        negotiated.version.minor,
                    )
                }
                throw TransferExecutionException(TransferErrorCategory.SOURCE, "source_changed")
            }
            send(
                location.channel,
                TransferRecordType.SENDER_FINISHED,
                TransferControlCodec.encode(TransferSenderFinished(payload.itemId, payload.byteCount, payload.sha256)),
                negotiated.version,
            )
            events.emit(event(
                TransferActivityPhase.VERIFYING, totalBytes, totalItems,
                committedItems, committedBytes, offset, payload.displayName,
            ))

            // Await this item's acknowledgement: ItemCommitted (advance) or TerminalResult.
            while (true) {
                val record = timed(timeouts.idleMillis, TransferErrorCategory.NETWORK, "terminal_timeout") {
                    location.channel.receiveRecord()
                }
                when (record.header.type) {
                    TransferRecordType.CANCEL -> throw OutboundCancelled("receiver_cancelled")
                    TransferRecordType.TERMINAL_RESULT -> return remoteTerminal(
                        record, totalBytes, totalItems, committedItems, committedBytes,
                        committedArtifacts.toList(), negotiated.version, events, isLast,
                    )
                    TransferRecordType.ITEM_COMMITTED -> {
                        if (isLast) throw TransferExecutionException(TransferErrorCategory.PROTOCOL, "unexpected_item_committed")
                        requireRecord(record, TransferRecordType.ITEM_COMMITTED, negotiated.version)
                        val committed = TransferControlCodec.decode(TransferRecordType.ITEM_COMMITTED, record.payload) as? TransferItemCommitted
                            ?: throw TransferExecutionException(TransferErrorCategory.PROTOCOL, "invalid_item_committed")
                        if (committed.itemId != payload.itemId) {
                            throw TransferExecutionException(TransferErrorCategory.PROTOCOL, "invalid_item_committed")
                        }
                        committedItems += 1
                        committedBytes += payload.byteCount
                        if (committed.committedArtifactId.isNotEmpty()) {
                            committedArtifacts += CommittedArtifact(committed.committedArtifactId)
                        }
                        synchronized(lock) {
                            current?.let {
                                it.committedItems = committedItems
                                it.committedBytes = committedBytes
                                it.committedArtifacts.clear()
                                it.committedArtifacts.addAll(committedArtifacts)
                            }
                        }
                        break
                    }
                    else -> Unit
                }
            }
        }
        throw TransferExecutionException(TransferErrorCategory.PROTOCOL, "missing_terminal")
    }

    private suspend fun remoteTerminal(
        record: TransferWireRecord,
        totalBytes: Long,
        totalItems: Int,
        committedItems: Int,
        committedBytes: Long,
        committedArtifacts: List<CommittedArtifact>,
        version: NegotiatedProtocolVersion,
        events: TransferEventSink,
        allowCompletion: Boolean,
    ): RoleTransferOutcome {
        requireRecord(record, TransferRecordType.TERMINAL_RESULT, version)
        val terminal = TransferControlCodec.decode(TransferRecordType.TERMINAL_RESULT, record.payload)
            as? TransferWireTerminalResult
            ?: throw TransferExecutionException(TransferErrorCategory.PROTOCOL, "invalid_terminal_result")
        return when (terminal.status) {
            WireTerminalStatus.COMPLETED -> {
                if (!allowCompletion) throw TransferExecutionException(TransferErrorCategory.PROTOCOL, "completion_before_commit")
                val artifacts = committedArtifacts.toMutableList()
                if (terminal.committedArtifactId.isNotEmpty()) artifacts += CommittedArtifact(terminal.committedArtifactId)
                finish(
                    TransferTerminalResult.COMPLETED,
                    TransferActivityPhase.COMPLETED,
                    totalBytes,
                    totalItems,
                    events,
                    committedArtifacts = artifacts,
                    committedItems = totalItems,
                    committedBytes = totalBytes,
                )
            }
            WireTerminalStatus.REJECTED -> finish(
                TransferTerminalResult.REJECTED,
                TransferActivityPhase.REJECTED,
                totalBytes,
                totalItems,
                events,
                failure = TransferFailure(terminal.errorCategory ?: TransferErrorCategory.APPROVAL, terminal.errorCode),
                committedArtifacts = committedArtifacts,
                committedItems = committedItems,
                committedBytes = committedBytes,
            )
            WireTerminalStatus.CANCELLED -> finish(
                TransferTerminalResult.CANCELLED,
                TransferActivityPhase.CANCELLED,
                totalBytes,
                totalItems,
                events,
                failure = TransferFailure(terminal.errorCategory ?: TransferErrorCategory.CANCELLED, terminal.errorCode),
                committedArtifacts = committedArtifacts,
                committedItems = committedItems,
                committedBytes = committedBytes,
            )
            WireTerminalStatus.FAILED -> finish(
                TransferTerminalResult.FAILED,
                TransferActivityPhase.FAILED,
                totalBytes,
                totalItems,
                events,
                failure = TransferFailure(terminal.errorCategory ?: TransferErrorCategory.PROTOCOL, terminal.errorCode),
                committedArtifacts = committedArtifacts,
                committedItems = committedItems,
                committedBytes = committedBytes,
            )
        }
    }

    private suspend fun finishCancellation(
        reason: String,
        totalBytes: Long,
        totalItems: Int,
        events: TransferEventSink,
        committedArtifacts: List<CommittedArtifact>,
        committedItems: Int,
        committedBytes: Long,
    ): RoleTransferOutcome = finish(
        TransferTerminalResult.CANCELLED,
        TransferActivityPhase.CANCELLED,
        totalBytes,
        totalItems,
        events,
        failure = TransferFailure(
            if (reason == "peer_unpaired") TransferErrorCategory.AUTHENTICATION else TransferErrorCategory.CANCELLED,
            reason,
        ),
        committedArtifacts = committedArtifacts,
        committedItems = committedItems,
        committedBytes = committedBytes,
    )

    private suspend fun finish(
        result: TransferTerminalResult,
        phase: TransferActivityPhase,
        totalBytes: Long,
        totalItems: Int,
        events: TransferEventSink,
        committedArtifacts: List<CommittedArtifact> = emptyList(),
        committedItems: Int = 0,
        committedBytes: Long = 0,
        failure: TransferFailure? = null,
    ): RoleTransferOutcome {
        val completed = phase == TransferActivityPhase.COMPLETED
        events.emit(event(
            phase, totalBytes, totalItems,
            if (completed) totalItems else committedItems,
            if (completed) totalBytes else committedBytes,
        ))
        return RoleTransferOutcome(result, committedArtifacts, failure)
    }

    private fun event(
        phase: TransferActivityPhase,
        totalBytes: Long,
        totalItems: Int,
        committedItems: Int = 0,
        committedBytes: Long = 0,
        itemTransferred: Long = 0,
        currentItemName: String = "",
    ) = TransferSessionEvent(
        phase = phase,
        transferredBytes = committedBytes + itemTransferred,
        verifiedBytes = committedBytes + itemTransferred,
        totalBytes = totalBytes,
        completedItems = committedItems,
        totalItems = totalItems,
        currentItemName = currentItemName,
    )

    private suspend fun <T> timed(
        timeoutMillis: Long,
        category: TransferErrorCategory,
        timeoutCode: String,
        operation: suspend () -> T,
    ): T = try {
        deadlines.run(timeoutMillis, operation)
    } catch (error: OutboundCancelled) {
        throw error
    } catch (error: TransferExecutionException) {
        throw error
    } catch (_: Exception) {
        throw TransferExecutionException(category, timeoutCode)
    }

    private suspend fun send(
        channel: OutboundTransferRecordChannel,
        type: TransferRecordType,
        payload: ByteArray,
        version: NegotiatedProtocolVersion,
    ) = try {
        channel.sendRecord(type, payload, currentSessionId(), version.major, version.minor)
    } catch (_: Exception) {
        throw TransferExecutionException(TransferErrorCategory.NETWORK, "connection_failed")
    }

    private suspend fun validate(payload: OutboundTransferPayload) = try {
        payload.validateForTransfer()
    } catch (_: Exception) {
        throw TransferExecutionException(TransferErrorCategory.SOURCE, "source_changed")
    }

    private fun requireRecord(
        record: TransferWireRecord,
        type: TransferRecordType,
        version: NegotiatedProtocolVersion,
    ) {
        if (record.header.sessionId != currentSessionId() || record.header.type != type ||
            record.header.protocolMajor != version.major || record.header.protocolMinor != version.minor
        ) throw TransferExecutionException(TransferErrorCategory.PROTOCOL, "unexpected_record")
    }

    private fun currentSessionId(): UUID = synchronized(lock) {
        current?.sessionId ?: throw OutboundCancelled("user_cancelled")
    }

    private fun throwIfCancelled() {
        synchronized(lock) { current?.cancellationReason }?.let { throw OutboundCancelled(it) }
    }

    private fun bootstrapFailure(decision: SessionDecision): TransferFailure = when (decision.rejectionCategory) {
        BootstrapRejectionCategory.BUSY -> TransferFailure(
            TransferErrorCategory.BUSY,
            decision.rejectionCode.ifEmpty { "device_busy" },
        )
        BootstrapRejectionCategory.INCOMPATIBLE_VERSION,
        BootstrapRejectionCategory.CAPABILITY,
        -> TransferFailure(
            TransferErrorCategory.INCOMPATIBLE_VERSION,
            decision.rejectionCode.ifEmpty { "incompatible_version" },
        )
        BootstrapRejectionCategory.AUTHENTICATION -> TransferFailure(
            TransferErrorCategory.AUTHENTICATION,
            decision.rejectionCode.ifEmpty { "authentication_failed" },
        )
        else -> TransferFailure(
            TransferErrorCategory.PROTOCOL,
            decision.rejectionCode.ifEmpty { "negotiation_failed" },
        )
    }
}

private class OutboundCancelled(val reason: String) : Exception(reason)
