import CryptoKit
import Foundation

public protocol OutboundTransferRecordChannel: Sendable {
    func sendRecord(
        type: TransferRecordType,
        payload: Data,
        sessionID: UUID,
        protocolMajor: UInt16,
        protocolMinor: UInt16
    ) async throws

    func receiveRecord() async throws -> TransferWireRecord
    func close() async
}

public struct OutboundAuthenticatedPeerLocation: Sendable {
    public let channel: any OutboundTransferRecordChannel
    public let endpointTicket: Data?

    public init(channel: any OutboundTransferRecordChannel, endpointTicket: Data? = nil) {
        precondition(endpointTicket == nil || endpointTicket?.count == 32)
        self.channel = channel
        self.endpointTicket = endpointTicket
    }
}

public protocol OutboundAuthenticatedPeerLocating: Sendable {
    func locate(peerID: Data, endpointHint: String?) async throws -> OutboundAuthenticatedPeerLocation
}

public protocol OutboundTransferPayloadStream: Sendable {
    func read(upToCount count: Int) async throws -> Data?
    func close() async
}

public protocol OutboundTransferPayload: Sendable {
    var itemID: UUID { get }
    var displayName: String { get }
    var byteCount: UInt64 { get }
    var sha256: Data { get }
    var mediaType: String { get }

    func validateForTransfer() async throws
    func openStream() async throws -> any OutboundTransferPayloadStream
}

public struct OutboundTransferIntent: Sendable {
    public let peerID: Data
    public let endpointHint: String?
    public let payloads: [any OutboundTransferPayload]

    public init(peerID: Data, endpointHint: String? = nil, payloads: [any OutboundTransferPayload]) {
        precondition(peerID.count == 32)
        precondition(!payloads.isEmpty && payloads.count <= transferMaxBatchItems)
        self.peerID = peerID
        self.endpointHint = endpointHint
        self.payloads = payloads
    }

    /// Convenience for a single-item Transfer Session.
    public init(peerID: Data, endpointHint: String? = nil, payload: any OutboundTransferPayload) {
        self.init(peerID: peerID, endpointHint: endpointHint, payloads: [payload])
    }
}

public struct OutboundTransferTimeoutPolicy: Equatable, Sendable {
    public let locating: Duration
    public let negotiation: Duration
    public let approval: Duration
    public let idle: Duration

    public init(
        locating: Duration = .seconds(5),
        negotiation: Duration = .seconds(10),
        approval: Duration = .seconds(120),
        idle: Duration = .seconds(60)
    ) {
        self.locating = locating
        self.negotiation = negotiation
        self.approval = approval
        self.idle = idle
    }
}

public enum TransferDeadlineError: Error, Equatable, Sendable {
    case timeout
}

public struct ContinuousTransferDeadlineScheduler: TransferDeadlineScheduling {
    public init() {}

    public func run<T: Sendable>(
        timeout: Duration,
        operation: @escaping @Sendable () async throws -> T
    ) async throws -> T {
        try await withThrowingTaskGroup(of: T.self) { group in
            group.addTask(operation: operation)
            group.addTask {
                try await Task.sleep(for: timeout)
                throw TransferDeadlineError.timeout
            }
            guard let value = try await group.next() else { throw TransferDeadlineError.timeout }
            group.cancelAll()
            return value
        }
    }
}

public actor OutboundTransferSession {
    private struct CurrentExecution {
        let sessionID: UUID
        let peerID: Data
        var locationTask: Task<OutboundAuthenticatedPeerLocation, Error>?
        var channel: (any OutboundTransferRecordChannel)?
        var version: NegotiatedProtocolVersion?
        var cancellationReason: String?
        var committedItems = 0
        var committedBytes: UInt64 = 0
        var committedArtifacts: [CommittedArtifact] = []
    }

    private let scheduler: TransferSessionScheduler
    private let locator: any OutboundAuthenticatedPeerLocating
    private let deadlines: any TransferDeadlineScheduling
    private let timeouts: OutboundTransferTimeoutPolicy
    private var current: CurrentExecution?

    public init(
        scheduler: TransferSessionScheduler,
        locator: any OutboundAuthenticatedPeerLocating,
        deadlines: any TransferDeadlineScheduling = ContinuousTransferDeadlineScheduler(),
        timeouts: OutboundTransferTimeoutPolicy = OutboundTransferTimeoutPolicy()
    ) {
        self.scheduler = scheduler
        self.locator = locator
        self.deadlines = deadlines
        self.timeouts = timeouts
    }

    public func execute(
        _ intent: OutboundTransferIntent,
        events: any TransferEventSink
    ) async -> RoleTransferOutcome {
        let sessionID = UUID()
        let totalBytes = intent.payloads.reduce(UInt64(0)) { $0 + $1.byteCount }
        let totalItems = intent.payloads.count
        guard let lease = await scheduler.reserveOutbound(peerID: intent.peerID, sessionID: sessionID) else {
            return await finish(
                result: .failed,
                phase: .failed,
                failure: TransferFailure(category: .busy, code: "device_busy"),
                totalBytes: totalBytes,
                totalItems: totalItems,
                events: events
            )
        }

        current = CurrentExecution(sessionID: sessionID, peerID: intent.peerID)
        let outcome: RoleTransferOutcome
        do {
            outcome = try await run(intent, sessionID: sessionID, lease: lease, events: events)
        } catch {
            let committedArtifacts = current?.committedArtifacts ?? []
            let committedItems = current?.committedItems ?? 0
            let committedBytes = current?.committedBytes ?? 0
            if let reason = current?.cancellationReason ?? ((error is CancellationError) ? "user_cancelled" : nil) {
                outcome = await finishCancellation(
                    reason: reason,
                    totalBytes: totalBytes,
                    totalItems: totalItems,
                    committedArtifacts: committedArtifacts,
                    committedItems: committedItems,
                    committedBytes: committedBytes,
                    events: events
                )
            } else if let failure = error as? TransferExecutionError {
                outcome = await finish(
                    result: .failed,
                    phase: .failed,
                    failure: TransferFailure(category: failure.category, code: failure.code),
                    committedArtifacts: committedArtifacts,
                    totalBytes: totalBytes,
                    totalItems: totalItems,
                    committedItems: committedItems,
                    committedBytes: committedBytes,
                    events: events
                )
            } else {
                outcome = await finish(
                    result: .failed,
                    phase: .failed,
                    failure: TransferFailure(category: .protocol, code: "unexpected_failure"),
                    committedArtifacts: committedArtifacts,
                    totalBytes: totalBytes,
                    totalItems: totalItems,
                    committedItems: committedItems,
                    committedBytes: committedBytes,
                    events: events
                )
            }
        }

        if let channel = current?.channel { await channel.close() }
        current = nil
        await scheduler.release(token: lease.token)
        return outcome
    }

    public func cancel(reasonCode: String, peerID: Data? = nil) async {
        guard var execution = current, peerID == nil || peerID == execution.peerID else { return }
        execution.cancellationReason = reasonCode
        execution.locationTask?.cancel()
        current = execution
        guard let channel = execution.channel else { return }
        if let version = execution.version {
            try? await channel.sendRecord(
                type: .cancel,
                payload: TransferControlCodec.encode(.cancel(reasonCode: reasonCode)),
                sessionID: execution.sessionID,
                protocolMajor: version.major,
                protocolMinor: version.minor
            )
        }
        await channel.close()
    }

    private func run(
        _ intent: OutboundTransferIntent,
        sessionID: UUID,
        lease: SessionLease,
        events: any TransferEventSink
    ) async throws -> RoleTransferOutcome {
        let payloads = intent.payloads
        let totalItems = payloads.count
        let totalBytes = payloads.reduce(UInt64(0)) { $0 + $1.byteCount }
        await events.emit(event(.connecting, totalBytes: totalBytes, totalItems: totalItems))
        for payload in payloads { try await validate(payload) }

        let locator = self.locator
        let deadlines = self.deadlines
        let timeout = timeouts.locating
        let task = Task {
            try await deadlines.run(timeout: timeout) {
                try await locator.locate(peerID: intent.peerID, endpointHint: intent.endpointHint)
            }
        }
        current?.locationTask = task
        let location: OutboundAuthenticatedPeerLocation
        do {
            location = try await task.value
        } catch is CancellationError {
            throw CancellationError()
        } catch {
            throw TransferExecutionError(category: .discovery, code: "peer_unavailable")
        }
        try throwIfCancelled()
        current?.locationTask = nil
        current?.channel = location.channel

        let hello = SessionHello(offer: .default, endpointTicket: location.endpointTicket)
        try await timed(
            timeout: timeouts.negotiation,
            category: .network,
            timeoutCode: "negotiation_timeout"
        ) {
            try await location.channel.sendRecord(
                type: .sessionHello,
                payload: SessionBootstrapCodec.encode(hello),
                sessionID: sessionID,
                protocolMajor: 0,
                protocolMinor: 0
            )
        }
        let decisionRecord = try await timed(
            timeout: timeouts.negotiation,
            category: .network,
            timeoutCode: "negotiation_timeout"
        ) {
            try await location.channel.receiveRecord()
        }
        guard decisionRecord.header.sessionID == sessionID,
              decisionRecord.header.type == .sessionDecision,
              decisionRecord.header.protocolMajor == 0,
              decisionRecord.header.protocolMinor == 0
        else { throw TransferExecutionError(category: .protocol, code: "session_decision_required") }

        let decision: SessionDecision
        do {
            decision = try SessionBootstrapCodec.decodeDecision(decisionRecord.payload)
        } catch {
            throw TransferExecutionError(category: .protocol, code: "invalid_session_decision")
        }
        guard decision.accepted else {
            let failure = bootstrapFailure(decision)
            let result: TransferTerminalResult = decision.rejectionCategory == .busy ? .failed : .rejected
            return await finish(
                result: result,
                phase: result == .rejected ? .rejected : .failed,
                failure: failure,
                totalBytes: totalBytes,
                totalItems: totalItems,
                events: events
            )
        }

        let negotiated: NegotiatedSessionParameters
        do {
            negotiated = try SessionNegotiator.validate(hello: hello, decision: decision)
        } catch {
            throw TransferExecutionError(category: .protocol, code: "invalid_session_selection")
        }
        guard await scheduler.markOutboundAccepted(token: lease.token) else {
            throw TransferExecutionError(category: .busy, code: "session_lease_lost")
        }
        current?.version = negotiated.version
        try throwIfCancelled()

        let manifest = TransferControlMessage.manifest(payloads.map { payload in
            TransferFileEntry(
                itemID: payload.itemID,
                displayName: payload.displayName,
                byteCount: payload.byteCount,
                sha256: payload.sha256,
                mediaType: payload.mediaType,
                chunkSize: UInt32(negotiated.chunkSize)
            )
        })
        try await send(
            channel: location.channel,
            type: .manifest,
            payload: try encode(manifest),
            sessionID: sessionID,
            version: negotiated.version
        )

        await events.emit(event(.awaitingApproval, totalBytes: totalBytes, totalItems: totalItems))
        let approvalRecord = try await timed(
            timeout: timeouts.approval,
            category: .approval,
            timeoutCode: "approval_timeout"
        ) {
            try await location.channel.receiveRecord()
        }
        if approvalRecord.header.type == .terminalResult {
            return try await remoteTerminal(
                approvalRecord,
                sessionID: sessionID,
                version: negotiated.version,
                totalBytes: totalBytes,
                totalItems: totalItems,
                committedItems: 0,
                committedBytes: 0,
                committedArtifacts: [],
                events: events,
                allowCompletion: false
            )
        }
        try require(approvalRecord, sessionID: sessionID, type: .approval, version: negotiated.version)
        let approval = try decode(type: .approval, data: approvalRecord.payload)
        guard case .approval(let accepted, let reason) = approval else {
            throw TransferExecutionError(category: .protocol, code: "invalid_approval")
        }
        guard accepted else {
            return await finish(
                result: .rejected,
                phase: .rejected,
                failure: TransferFailure(category: .approval, code: reason.isEmpty ? "user_rejected" : reason),
                totalBytes: totalBytes,
                totalItems: totalItems,
                events: events
            )
        }

        // Stream each item in manifest order. Already-committed items are retained on
        // failure; the receiver acknowledges every non-final item with ItemCommitted and
        // closes the batch with one session TerminalResult after the final item.
        var committedItems = 0
        var committedBytes: UInt64 = 0
        var committedArtifacts: [CommittedArtifact] = []
        for (index, payload) in payloads.enumerated() {
            let isLast = index == payloads.count - 1
            try await validate(payload)
            let stream: any OutboundTransferPayloadStream
            do {
                stream = try await payload.openStream()
            } catch {
                throw TransferExecutionError(category: .source, code: "source_changed")
            }

            var offset: UInt64 = 0
            var chunkIndex: UInt32 = 0
            await events.emit(event(
                .transferring,
                totalBytes: totalBytes,
                totalItems: totalItems,
                committedItems: committedItems,
                committedBytes: committedBytes,
                currentItemName: payload.displayName
            ))
            do {
                while let bytes = try await stream.read(upToCount: negotiated.chunkSize), !bytes.isEmpty {
                    try Task.checkCancellation()
                    try throwIfCancelled()
                    let prelude = TransferChunkPrelude(
                        itemID: payload.itemID,
                        chunkIndex: chunkIndex,
                        offset: offset,
                        dataLength: UInt32(bytes.count),
                        sha256: Data(SHA256.hash(data: bytes))
                    )
                    try await send(
                        channel: location.channel,
                        type: .chunk,
                        payload: prelude.encoded() + bytes,
                        sessionID: sessionID,
                        version: negotiated.version
                    )
                    let progressRecord = try await timed(
                        timeout: timeouts.idle,
                        category: .network,
                        timeoutCode: "progress_timeout"
                    ) {
                        try await location.channel.receiveRecord()
                    }
                    if progressRecord.header.type == .terminalResult {
                        await stream.close()
                        return try await remoteTerminal(
                            progressRecord,
                            sessionID: sessionID,
                            version: negotiated.version,
                            totalBytes: totalBytes,
                            totalItems: totalItems,
                            committedItems: committedItems,
                            committedBytes: committedBytes,
                            committedArtifacts: committedArtifacts,
                            events: events,
                            allowCompletion: false
                        )
                    }
                    try require(progressRecord, sessionID: sessionID, type: .progress, version: negotiated.version)
                    let progress = try decode(type: .progress, data: progressRecord.payload)
                    guard case .progress(let itemID, let verified, _, _) = progress,
                          itemID == payload.itemID,
                          verified == offset + UInt64(bytes.count)
                    else { throw TransferExecutionError(category: .protocol, code: "invalid_progress") }
                    offset = verified
                    chunkIndex += 1
                    await events.emit(event(
                        .transferring,
                        totalBytes: totalBytes,
                        totalItems: totalItems,
                        committedItems: committedItems,
                        committedBytes: committedBytes,
                        itemTransferred: offset,
                        currentItemName: payload.displayName
                    ))
                }
                await stream.close()
            } catch {
                await stream.close()
                if error is CancellationError { throw error }
                if let failure = error as? TransferExecutionError { throw failure }
                throw TransferExecutionError(category: .source, code: "source_read_failed")
            }

            guard offset == payload.byteCount else {
                throw TransferExecutionError(category: .source, code: "source_changed")
            }
            try await send(
                channel: location.channel,
                type: .senderFinished,
                payload: try encode(.senderFinished(itemID: payload.itemID, byteCount: payload.byteCount, sha256: payload.sha256)),
                sessionID: sessionID,
                version: negotiated.version
            )
            await events.emit(event(
                .verifying,
                totalBytes: totalBytes,
                totalItems: totalItems,
                committedItems: committedItems,
                committedBytes: committedBytes,
                itemTransferred: offset,
                currentItemName: payload.displayName
            ))

            // Await this item's acknowledgement: ItemCommitted (more items follow) or the
            // session TerminalResult (final item, or early termination).
            acknowledgement: while true {
                let record = try await timed(
                    timeout: timeouts.idle,
                    category: .network,
                    timeoutCode: "terminal_timeout"
                ) {
                    try await location.channel.receiveRecord()
                }
                switch record.header.type {
                case .cancel:
                    throw CancellationError()
                case .terminalResult:
                    return try await remoteTerminal(
                        record,
                        sessionID: sessionID,
                        version: negotiated.version,
                        totalBytes: totalBytes,
                        totalItems: totalItems,
                        committedItems: committedItems,
                        committedBytes: committedBytes,
                        committedArtifacts: committedArtifacts,
                        events: events,
                        allowCompletion: isLast
                    )
                case .itemCommitted:
                    guard !isLast else {
                        throw TransferExecutionError(category: .protocol, code: "unexpected_item_committed")
                    }
                    try require(record, sessionID: sessionID, type: .itemCommitted, version: negotiated.version)
                    let message = try decode(type: .itemCommitted, data: record.payload)
                    guard case .itemCommitted(let itemID, let artifact) = message, itemID == payload.itemID else {
                        throw TransferExecutionError(category: .protocol, code: "invalid_item_committed")
                    }
                    committedItems += 1
                    committedBytes += payload.byteCount
                    if !artifact.isEmpty { committedArtifacts.append(CommittedArtifact(id: artifact)) }
                    current?.committedItems = committedItems
                    current?.committedBytes = committedBytes
                    current?.committedArtifacts = committedArtifacts
                    break acknowledgement
                default:
                    continue acknowledgement
                }
            }
        }
        throw TransferExecutionError(category: .protocol, code: "missing_terminal")
    }

    private func remoteTerminal(
        _ record: TransferWireRecord,
        sessionID: UUID,
        version: NegotiatedProtocolVersion,
        totalBytes: UInt64,
        totalItems: Int,
        committedItems: Int,
        committedBytes: UInt64,
        committedArtifacts: [CommittedArtifact],
        events: any TransferEventSink,
        allowCompletion: Bool
    ) async throws -> RoleTransferOutcome {
        try require(record, sessionID: sessionID, type: .terminalResult, version: version)
        let message = try decode(type: .terminalResult, data: record.payload)
        guard case .terminalResult(let status, let category, let code, let artifactID) = message else {
            throw TransferExecutionError(category: .protocol, code: "invalid_terminal_result")
        }
        switch status {
        case .completed where allowCompletion:
            var artifacts = committedArtifacts
            if !artifactID.isEmpty { artifacts.append(CommittedArtifact(id: artifactID)) }
            return await finish(
                result: .completed,
                phase: .completed,
                committedArtifacts: artifacts,
                totalBytes: totalBytes,
                totalItems: totalItems,
                committedItems: totalItems,
                committedBytes: totalBytes,
                events: events
            )
        case .completed:
            throw TransferExecutionError(category: .protocol, code: "completion_before_commit")
        case .rejected:
            return await finish(
                result: .rejected,
                phase: .rejected,
                failure: TransferFailure(category: category ?? .approval, code: code),
                committedArtifacts: committedArtifacts,
                totalBytes: totalBytes,
                totalItems: totalItems,
                committedItems: committedItems,
                committedBytes: committedBytes,
                events: events
            )
        case .cancelled:
            return await finish(
                result: .cancelled,
                phase: .cancelled,
                failure: TransferFailure(category: category ?? .cancelled, code: code),
                committedArtifacts: committedArtifacts,
                totalBytes: totalBytes,
                totalItems: totalItems,
                committedItems: committedItems,
                committedBytes: committedBytes,
                events: events
            )
        case .failed:
            return await finish(
                result: .failed,
                phase: .failed,
                failure: TransferFailure(category: category ?? .protocol, code: code),
                committedArtifacts: committedArtifacts,
                totalBytes: totalBytes,
                totalItems: totalItems,
                committedItems: committedItems,
                committedBytes: committedBytes,
                events: events
            )
        }
    }

    private func finishCancellation(
        reason: String,
        totalBytes: UInt64,
        totalItems: Int,
        committedArtifacts: [CommittedArtifact],
        committedItems: Int,
        committedBytes: UInt64,
        events: any TransferEventSink
    ) async -> RoleTransferOutcome {
        let category: TransferErrorCategory = reason == "peer_unpaired" ? .authentication : .cancelled
        return await finish(
            result: .cancelled,
            phase: .cancelled,
            failure: TransferFailure(category: category, code: reason),
            committedArtifacts: committedArtifacts,
            totalBytes: totalBytes,
            totalItems: totalItems,
            committedItems: committedItems,
            committedBytes: committedBytes,
            events: events
        )
    }

    private func finish(
        result: TransferTerminalResult,
        phase: TransferActivityPhase,
        failure: TransferFailure? = nil,
        committedArtifacts: [CommittedArtifact] = [],
        totalBytes: UInt64,
        totalItems: Int,
        committedItems: Int = 0,
        committedBytes: UInt64 = 0,
        events: any TransferEventSink
    ) async -> RoleTransferOutcome {
        let completed = phase == .completed
        await events.emit(event(
            phase,
            totalBytes: totalBytes,
            totalItems: totalItems,
            committedItems: completed ? totalItems : committedItems,
            committedBytes: completed ? totalBytes : committedBytes
        ))
        return RoleTransferOutcome(result: result, committedArtifacts: committedArtifacts, failure: failure)
    }

    private func event(
        _ phase: TransferActivityPhase,
        totalBytes: UInt64,
        totalItems: Int,
        committedItems: Int = 0,
        committedBytes: UInt64 = 0,
        itemTransferred: UInt64 = 0,
        currentItemName: String = ""
    ) -> TransferSessionEvent {
        TransferSessionEvent(
            phase: phase,
            transferredBytes: committedBytes + itemTransferred,
            verifiedBytes: committedBytes + itemTransferred,
            totalBytes: totalBytes,
            completedItems: committedItems,
            totalItems: totalItems,
            currentItemName: currentItemName
        )
    }

    private func timed<T: Sendable>(
        timeout: Duration,
        category: TransferErrorCategory,
        timeoutCode: String,
        operation: @escaping @Sendable () async throws -> T
    ) async throws -> T {
        do {
            return try await deadlines.run(timeout: timeout, operation: operation)
        } catch is CancellationError {
            throw CancellationError()
        } catch TransferDeadlineError.timeout {
            throw TransferExecutionError(category: category, code: timeoutCode)
        } catch let failure as TransferExecutionError {
            throw failure
        } catch {
            throw TransferExecutionError(category: .network, code: "connection_failed")
        }
    }

    private func send(
        channel: any OutboundTransferRecordChannel,
        type: TransferRecordType,
        payload: Data,
        sessionID: UUID,
        version: NegotiatedProtocolVersion
    ) async throws {
        do {
            try await channel.sendRecord(
                type: type,
                payload: payload,
                sessionID: sessionID,
                protocolMajor: version.major,
                protocolMinor: version.minor
            )
        } catch is CancellationError {
            throw CancellationError()
        } catch {
            throw TransferExecutionError(category: .network, code: "connection_failed")
        }
    }

    private func validate(_ payload: any OutboundTransferPayload) async throws {
        do {
            try await payload.validateForTransfer()
        } catch {
            throw TransferExecutionError(category: .source, code: "source_changed")
        }
    }

    private func encode(_ message: TransferControlMessage) throws -> Data {
        do { return try TransferControlCodec.encode(message) }
        catch { throw TransferExecutionError(category: .protocol, code: "control_encode_failed") }
    }

    private func decode(type: TransferRecordType, data: Data) throws -> TransferControlMessage {
        do { return try TransferControlCodec.decode(type: type, data: data) }
        catch { throw TransferExecutionError(category: .protocol, code: "control_decode_failed") }
    }

    private func require(
        _ record: TransferWireRecord,
        sessionID: UUID,
        type: TransferRecordType,
        version: NegotiatedProtocolVersion
    ) throws {
        guard record.header.sessionID == sessionID,
              record.header.type == type,
              record.header.protocolMajor == version.major,
              record.header.protocolMinor == version.minor
        else { throw TransferExecutionError(category: .protocol, code: "unexpected_record") }
    }

    private func throwIfCancelled() throws {
        if current?.cancellationReason != nil || Task.isCancelled { throw CancellationError() }
    }

    private func bootstrapFailure(_ decision: SessionDecision) -> TransferFailure {
        switch decision.rejectionCategory {
        case .busy: TransferFailure(category: .busy, code: decision.rejectionCode.isEmpty ? "device_busy" : decision.rejectionCode)
        case .incompatibleVersion, .capability:
            TransferFailure(category: .incompatibleVersion, code: decision.rejectionCode.isEmpty ? "incompatible_version" : decision.rejectionCode)
        case .authentication:
            TransferFailure(category: .authentication, code: decision.rejectionCode.isEmpty ? "authentication_rejected" : decision.rejectionCode)
        case .protocol, .none:
            TransferFailure(category: .protocol, code: decision.rejectionCode.isEmpty ? "negotiation_rejected" : decision.rejectionCode)
        }
    }
}
