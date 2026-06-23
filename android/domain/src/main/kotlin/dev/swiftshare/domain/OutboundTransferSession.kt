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
    val payload: OutboundTransferPayload,
) {
    init { require(peerId.size == 32) }
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
    )

    private val lock = Any()
    private var current: Execution? = null

    suspend fun execute(intent: OutboundTransferIntent, events: TransferEventSink): RoleTransferOutcome {
        val sessionId = UUID.randomUUID()
        val lease = scheduler.reserveOutbound(intent.peerId, sessionId)
            ?: return finish(
                TransferTerminalResult.FAILED,
                TransferActivityPhase.FAILED,
                intent.payload,
                events,
                failure = TransferFailure(TransferErrorCategory.BUSY, "device_busy"),
            )
        synchronized(lock) { current = Execution(sessionId, intent.peerId.copyOf()) }

        val outcome = try {
            run(intent, lease, events)
        } catch (error: OutboundCancelled) {
            finishCancellation(error.reason, intent.payload, events)
        } catch (error: TransferExecutionException) {
            finish(
                TransferTerminalResult.FAILED,
                TransferActivityPhase.FAILED,
                intent.payload,
                events,
                failure = TransferFailure(error.category, error.errorCode),
            )
        } catch (_: Exception) {
            finish(
                TransferTerminalResult.FAILED,
                TransferActivityPhase.FAILED,
                intent.payload,
                events,
                failure = TransferFailure(TransferErrorCategory.PROTOCOL, "unexpected_failure"),
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
        val payload = intent.payload
        events.emit(event(TransferActivityPhase.CONNECTING, payload))
        validate(payload)
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
                payload,
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
                    listOf(
                        TransferFileEntry(
                            payload.itemId,
                            payload.displayName,
                            payload.byteCount,
                            payload.sha256,
                            payload.mediaType,
                            negotiated.chunkSize,
                        ),
                    ),
                ),
            ),
            negotiated.version,
        )
        events.emit(event(TransferActivityPhase.AWAITING_APPROVAL, payload))
        val approvalRecord = timed(timeouts.approvalMillis, TransferErrorCategory.APPROVAL, "approval_timeout") {
            location.channel.receiveRecord()
        }
        if (approvalRecord.header.type == TransferRecordType.TERMINAL_RESULT) {
            return remoteTerminal(approvalRecord, payload, 0, negotiated.version, events, false)
        }
        requireRecord(approvalRecord, TransferRecordType.APPROVAL, negotiated.version)
        val approval = TransferControlCodec.decode(TransferRecordType.APPROVAL, approvalRecord.payload) as? TransferApproval
            ?: throw TransferExecutionException(TransferErrorCategory.PROTOCOL, "invalid_approval")
        if (!approval.accepted) return finish(
            TransferTerminalResult.REJECTED,
            TransferActivityPhase.REJECTED,
            payload,
            events,
            failure = TransferFailure(
                TransferErrorCategory.APPROVAL,
                approval.reasonCode.ifEmpty { "user_rejected" },
            ),
        )

        validate(payload)
        val stream = try { payload.openStream() } catch (_: Exception) {
            throw TransferExecutionException(TransferErrorCategory.SOURCE, "source_changed")
        }
        var offset = 0L
        var chunkIndex = 0L
        val streamedDigest = MessageDigest.getInstance("SHA-256")
        events.emit(event(TransferActivityPhase.TRANSFERRING, payload))
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
                send(
                    location.channel,
                    TransferRecordType.CHUNK,
                    prelude.encode() + bytes,
                    negotiated.version,
                )
                val progressRecord = timed(timeouts.idleMillis, TransferErrorCategory.NETWORK, "progress_timeout") {
                    location.channel.receiveRecord()
                }
                if (progressRecord.header.type == TransferRecordType.TERMINAL_RESULT) {
                    return remoteTerminal(progressRecord, payload, offset, negotiated.version, events, false)
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
                events.emit(event(TransferActivityPhase.TRANSFERRING, payload, offset, offset))
            }
        } catch (error: Exception) {
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
                    sessionId,
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
        events.emit(event(TransferActivityPhase.VERIFYING, payload, offset, offset))
        while (true) {
            val record = timed(timeouts.idleMillis, TransferErrorCategory.NETWORK, "terminal_timeout") {
                location.channel.receiveRecord()
            }
            if (record.header.type == TransferRecordType.CANCEL) throw OutboundCancelled("receiver_cancelled")
            if (record.header.type == TransferRecordType.TERMINAL_RESULT) {
                return remoteTerminal(record, payload, offset, negotiated.version, events, true)
            }
        }
    }

    private suspend fun remoteTerminal(
        record: TransferWireRecord,
        payload: OutboundTransferPayload,
        transferredBytes: Long,
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
                finish(
                    TransferTerminalResult.COMPLETED,
                    TransferActivityPhase.COMPLETED,
                    payload,
                    events,
                    transferredBytes,
                    transferredBytes,
                    terminal.committedArtifactId.takeIf(String::isNotEmpty),
                )
            }
            WireTerminalStatus.REJECTED -> finish(
                TransferTerminalResult.REJECTED,
                TransferActivityPhase.REJECTED,
                payload,
                events,
                failure = TransferFailure(terminal.errorCategory ?: TransferErrorCategory.APPROVAL, terminal.errorCode),
            )
            WireTerminalStatus.CANCELLED -> finish(
                TransferTerminalResult.CANCELLED,
                TransferActivityPhase.CANCELLED,
                payload,
                events,
                failure = TransferFailure(terminal.errorCategory ?: TransferErrorCategory.CANCELLED, terminal.errorCode),
            )
            WireTerminalStatus.FAILED -> finish(
                TransferTerminalResult.FAILED,
                TransferActivityPhase.FAILED,
                payload,
                events,
                failure = TransferFailure(terminal.errorCategory ?: TransferErrorCategory.PROTOCOL, terminal.errorCode),
            )
        }
    }

    private suspend fun finishCancellation(
        reason: String,
        payload: OutboundTransferPayload,
        events: TransferEventSink,
    ): RoleTransferOutcome = finish(
        TransferTerminalResult.CANCELLED,
        TransferActivityPhase.CANCELLED,
        payload,
        events,
        failure = TransferFailure(
            if (reason == "peer_unpaired") TransferErrorCategory.AUTHENTICATION else TransferErrorCategory.CANCELLED,
            reason,
        ),
    )

    private suspend fun finish(
        result: TransferTerminalResult,
        phase: TransferActivityPhase,
        payload: OutboundTransferPayload,
        events: TransferEventSink,
        transferred: Long = 0,
        verified: Long = 0,
        artifactId: String? = null,
        failure: TransferFailure? = null,
    ): RoleTransferOutcome {
        events.emit(event(phase, payload, transferred, verified))
        return RoleTransferOutcome(
            result,
            if (result == TransferTerminalResult.COMPLETED && artifactId != null) {
                listOf(CommittedArtifact(artifactId))
            } else emptyList(),
            failure,
        )
    }

    private fun event(
        phase: TransferActivityPhase,
        payload: OutboundTransferPayload,
        transferred: Long = 0,
        verified: Long = 0,
    ) = TransferSessionEvent(
        phase,
        transferred,
        verified,
        payload.byteCount,
        if (phase == TransferActivityPhase.COMPLETED) 1 else 0,
        1,
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
