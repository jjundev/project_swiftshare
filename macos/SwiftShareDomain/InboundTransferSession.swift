import CryptoKit
import Foundation

public protocol InboundTransferRecordChannel: Sendable {
    func receiveRecord() async throws -> TransferWireRecord
    func sendRecord(
        type: TransferRecordType,
        payload: Data,
        sessionID: UUID,
        protocolMajor: UInt16,
        protocolMinor: UInt16
    ) async throws
    func close() async
}

public struct InboundAuthorizedPeer: Equatable, Sendable {
    public let id: String
    public let displayName: String
    public let approvalPolicy: PeerApprovalPolicy

    public init(id: String, displayName: String, approvalPolicy: PeerApprovalPolicy) {
        self.id = id
        self.displayName = displayName
        self.approvalPolicy = approvalPolicy
    }
}

public protocol InboundPeerAuthorizing: Sendable {
    func currentPeer() async -> InboundAuthorizedPeer?
}

public protocol InboundTransferReservation: Sendable {
    var displayName: String { get }
    func write(_ data: Data) async throws
    func finishWriting() async throws
    func commit() async throws -> CommittedArtifact
    func abort() async
}

public protocol InboundTransferDestination: Sendable {
    func preflight() async throws
    func reserve(displayName: String, mediaType: String, sessionID: UUID, itemID: UUID) async throws
        -> any InboundTransferReservation
}

public struct InboundTransferSummary: Equatable, Sendable {
    public let authenticatedSender: String
    public let displayName: String
    public let byteCount: UInt64
    public let itemCount: Int
    public let totalBytes: UInt64

    public init(
        authenticatedSender: String,
        displayName: String,
        byteCount: UInt64,
        itemCount: Int = 1,
        totalBytes: UInt64? = nil
    ) {
        self.authenticatedSender = authenticatedSender
        self.displayName = displayName
        self.byteCount = byteCount
        self.itemCount = itemCount
        self.totalBytes = totalBytes ?? byteCount
    }
}

public enum InboundApprovalDecision: Equatable, Sendable {
    case accepted
    case rejected
    case timedOut
}

public protocol InboundApprovalGateway: Sendable {
    func awaitDecision(_ summary: InboundTransferSummary) async -> InboundApprovalDecision
    func cancel() async
}

public struct InboundTransferSessionEvent: Equatable, Sendable {
    public let phase: TransferActivityPhase
    public let summary: InboundTransferSummary
    public let verifiedBytes: UInt64
    public let artifact: CommittedArtifact?
    public let failure: TransferFailure?
    public let completedItems: Int
    public let totalItems: Int
    public let currentItemName: String

    public init(
        phase: TransferActivityPhase,
        summary: InboundTransferSummary,
        verifiedBytes: UInt64 = 0,
        artifact: CommittedArtifact? = nil,
        failure: TransferFailure? = nil,
        completedItems: Int = 0,
        totalItems: Int = 1,
        currentItemName: String = ""
    ) {
        self.phase = phase
        self.summary = summary
        self.verifiedBytes = verifiedBytes
        self.artifact = artifact
        self.failure = failure
        self.completedItems = completedItems
        self.totalItems = totalItems
        self.currentItemName = currentItemName
    }
}

public protocol InboundTransferEventSink: Sendable {
    func emit(_ event: InboundTransferSessionEvent) async
}

public struct InboundAuthenticatedConnection: Sendable {
    public let peerID: Data
    public let peerIDKey: String
    public let channel: any InboundTransferRecordChannel
    public let authorizer: any InboundPeerAuthorizing

    public init(
        peerID: Data,
        peerIDKey: String,
        channel: any InboundTransferRecordChannel,
        authorizer: any InboundPeerAuthorizing
    ) {
        precondition(peerID.count == 32)
        self.peerID = peerID
        self.peerIDKey = peerIDKey
        self.channel = channel
        self.authorizer = authorizer
    }
}

public struct InboundTransferTimeoutPolicy: Equatable, Sendable {
    public let negotiation: Duration
    public let approval: Duration
    public let idle: Duration

    public init(
        negotiation: Duration = .seconds(10),
        approval: Duration = .seconds(120),
        idle: Duration = .seconds(60)
    ) {
        self.negotiation = negotiation
        self.approval = approval
        self.idle = idle
    }
}

public struct LatestOperationGeneration: Equatable, Sendable {
    private var value: UInt64 = 0

    public init() {}

    public mutating func begin() -> UInt64 {
        value &+= 1
        return value
    }

    public func isCurrent(_ candidate: UInt64) -> Bool { candidate == value }
}

public enum DeterministicDestinationName {
    public static func candidate(
        original: String,
        suffix: Int,
        maximumUTF8Bytes: Int = 255
    ) throws -> String {
        try InboundTransferSession.validateBasename(original)
        guard suffix >= 0, maximumUTF8Bytes > 0 else {
            throw InboundTransferFailure.permission("invalid_destination_name")
        }
        if suffix == 0 {
            guard original.utf8.count <= maximumUTF8Bytes else {
                throw InboundTransferFailure.permission("invalid_destination_name")
            }
            return original
        }
        let value = original as NSString
        let extensionValue = value.pathExtension
        let stem = extensionValue.isEmpty ? original : value.deletingPathExtension
        let ending = " (\(suffix))" + (extensionValue.isEmpty ? "" : ".\(extensionValue)")
        let maximumStemBytes = maximumUTF8Bytes - ending.utf8.count
        guard maximumStemBytes > 0 else {
            throw InboundTransferFailure.permission("invalid_destination_name")
        }
        var bounded = ""
        for character in stem {
            let next = bounded + String(character)
            if next.utf8.count > maximumStemBytes { break }
            bounded = next
        }
        guard !bounded.isEmpty else {
            throw InboundTransferFailure.permission("invalid_destination_name")
        }
        let result = bounded + ending
        try InboundTransferSession.validateBasename(result)
        return result
    }
}

public actor InboundTransferSession {
    private final class Execution: @unchecked Sendable {
        let connection: InboundAuthenticatedConnection
        var sessionID: UUID?
        var version: NegotiatedProtocolVersion?
        var lease: SessionLease?
        var reservation: (any InboundTransferReservation)?
        var summary: InboundTransferSummary?
        var cancellationReason: String?
        var committed = false
        var committedArtifacts: [CommittedArtifact] = []

        init(connection: InboundAuthenticatedConnection) { self.connection = connection }
    }

    private let scheduler: TransferSessionScheduler
    private let approvalGateway: any InboundApprovalGateway
    private let destination: any InboundTransferDestination
    private let deadlines: any TransferDeadlineScheduling
    private let events: any InboundTransferEventSink
    private let timeouts: InboundTransferTimeoutPolicy
    private var current: Execution?

    public init(
        scheduler: TransferSessionScheduler,
        approvalGateway: any InboundApprovalGateway,
        destination: any InboundTransferDestination,
        deadlines: any TransferDeadlineScheduling = ContinuousTransferDeadlineScheduler(),
        events: any InboundTransferEventSink,
        timeouts: InboundTransferTimeoutPolicy = InboundTransferTimeoutPolicy()
    ) {
        self.scheduler = scheduler
        self.approvalGateway = approvalGateway
        self.destination = destination
        self.deadlines = deadlines
        self.events = events
        self.timeouts = timeouts
    }

    public func execute(_ connection: InboundAuthenticatedConnection) async -> RoleTransferOutcome {
        let execution = Execution(connection: connection)
        do {
            let outcome = try await run(execution)
            await cleanup(execution)
            return outcome
        } catch let failure as InboundTransferFailure {
            let outcome = await finishFailure(execution, failure: failure)
            await cleanup(execution)
            return outcome
        } catch {
            let reason = execution.cancellationReason
            let failure = if let reason {
                InboundTransferFailure(
                    result: .cancelled,
                    phase: .cancelled,
                    category: reason == "peer_unpaired" ? .authentication : .cancelled,
                    code: reason
                )
            } else {
                InboundTransferFailure(
                    result: .failed,
                    phase: .failed,
                    category: .network,
                    code: "connection_failed"
                )
            }
            let outcome = await finishFailure(execution, failure: failure)
            await cleanup(execution)
            return outcome
        }
    }

    public func cancel(reasonCode: String, peerIDKey: String? = nil) async {
        guard let execution = current,
              peerIDKey == nil || peerIDKey == execution.connection.peerIDKey,
              !execution.committed
        else { return }
        execution.cancellationReason = reasonCode
        await approvalGateway.cancel()
        if let sessionID = execution.sessionID, let version = execution.version {
            try? await execution.connection.channel.sendRecord(
                type: .cancel,
                payload: TransferControlCodec.encode(.cancel(reasonCode: reasonCode)),
                sessionID: sessionID,
                protocolMajor: version.major,
                protocolMinor: version.minor
            )
        }
        await execution.connection.channel.close()
    }

    public var hasActiveTransfer: Bool { current != nil }

    private func run(_ execution: Execution) async throws -> RoleTransferOutcome {
        let helloRecord = try await timed(timeouts.negotiation, category: .network, code: "negotiation_timeout") {
            try await execution.connection.channel.receiveRecord()
        }
        execution.sessionID = helloRecord.header.sessionID
        guard helloRecord.header.type == .sessionHello,
              helloRecord.header.protocolMajor == 0,
              helloRecord.header.protocolMinor == 0
        else { return try await rejectBootstrap(execution, category: .protocol, code: "negotiation_required") }

        let hello: SessionHello
        do { hello = try SessionBootstrapCodec.decodeHello(helloRecord.payload) }
        catch { return try await rejectBootstrap(execution, category: .protocol, code: "malformed_hello") }
        guard hello.endpointTicket == nil else {
            return try await rejectBootstrap(execution, category: .authentication, code: "invalid_endpoint_ticket")
        }
        let negotiated: NegotiatedSessionParameters
        switch SessionNegotiator.negotiate(sender: hello.offer, receiver: .default) {
        case .accepted(let parameters): negotiated = parameters
        case .rejected(let category, let code):
            return try await rejectBootstrap(execution, category: category, code: code)
        }
        guard await execution.connection.authorizer.currentPeer() != nil else {
            return try await rejectBootstrap(execution, category: .authentication, code: "trust_revoked")
        }
        let lease: SessionLease
        switch await scheduler.admitInbound(
            peerID: execution.connection.peerID,
            sessionID: helloRecord.header.sessionID
        ) {
        case .admitted(let value, _): lease = value
        case .rejected(let code):
            return try await rejectBootstrap(
                execution,
                category: code == "identity_collision" ? .authentication : .busy,
                code: code
            )
        }
        execution.lease = lease
        current = execution
        try await execution.connection.channel.sendRecord(
            type: .sessionDecision,
            payload: SessionBootstrapCodec.encode(.accepted(negotiated)),
            sessionID: helloRecord.header.sessionID,
            protocolMajor: 0,
            protocolMinor: 0
        )
        execution.version = negotiated.version
        try throwIfCancelled(execution)

        let manifestRecord = try await timed(timeouts.idle, category: .network, code: "manifest_timeout") {
            try await execution.connection.channel.receiveRecord()
        }
        try requireRecord(execution, manifestRecord, type: .manifest)
        let message = try TransferControlCodec.decode(type: .manifest, data: manifestRecord.payload)
        guard case .manifest(let entries) = message, !entries.isEmpty else {
            throw InboundTransferFailure.protocol("empty_manifest")
        }
        let totalBytes = entries.reduce(UInt64(0)) { $0 + $1.byteCount }
        guard totalBytes <= transferMaxBatchBytes else {
            throw InboundTransferFailure.protocol("manifest_too_large")
        }
        for entry in entries {
            guard entry.chunkSize == negotiated.chunkSize else {
                throw InboundTransferFailure.protocol("negotiated_chunk_size_mismatch")
            }
            try Self.validateBasename(entry.displayName)
        }
        do { try await destination.preflight() }
        catch let error as InboundTransferFailure { throw error }
        catch { throw InboundTransferFailure.permission("destination_permission_required") }
        guard let peer = await execution.connection.authorizer.currentPeer() else {
            throw InboundTransferFailure.cancelled("peer_unpaired", category: .authentication)
        }
        let totalItems = entries.count
        let summary = InboundTransferSummary(
            authenticatedSender: peer.displayName,
            displayName: entries[0].displayName,
            byteCount: entries[0].byteCount,
            itemCount: totalItems,
            totalBytes: totalBytes
        )
        execution.summary = summary

        let approval: InboundApprovalDecision
        if peer.approvalPolicy == .autoAccept {
            approval = .accepted
        } else {
            await events.emit(InboundTransferSessionEvent(phase: .awaitingApproval, summary: summary, totalItems: totalItems))
            approval = try await timed(timeouts.approval, category: .approval, code: "approval_timeout") {
                await self.approvalGateway.awaitDecision(summary)
            }
        }
        try throwIfCancelled(execution)
        let accepted = approval == .accepted
        let rejectionCode = switch approval {
        case .accepted: ""
        case .rejected: "user_rejected"
        case .timedOut: "approval_timeout"
        }
        try await send(
            execution,
            type: .approval,
            payload: TransferControlCodec.encode(.approval(accepted: accepted, reasonCode: rejectionCode))
        )
        guard accepted else {
            let failure = TransferFailure(category: .approval, code: rejectionCode)
            await events.emit(InboundTransferSessionEvent(
                phase: .rejected,
                summary: summary,
                failure: failure,
                totalItems: totalItems
            ))
            return RoleTransferOutcome(result: .rejected, failure: failure)
        }

        // Receive each item in manifest order. Every committed item is retained even if a
        // later item fails; the in-flight Staging Reservation is the only one aborted.
        var committedBytes: UInt64 = 0
        for (index, entry) in entries.enumerated() {
            let isLast = index == entries.count - 1
            let reservation: any InboundTransferReservation
            do {
                reservation = try await destination.reserve(
                    displayName: entry.displayName,
                    mediaType: entry.mediaType,
                    sessionID: helloRecord.header.sessionID,
                    itemID: entry.itemID
                )
            } catch let error as InboundTransferFailure { throw error }
            catch { throw InboundTransferFailure.storage("pending_open_failed") }
            execution.reservation = reservation
            var fileDigest = SHA256()
            var expectedIndex: UInt32 = 0
            var expectedOffset: UInt64 = 0
            await events.emit(InboundTransferSessionEvent(
                phase: .transferring,
                summary: summary,
                verifiedBytes: committedBytes,
                completedItems: index,
                totalItems: totalItems,
                currentItemName: entry.displayName
            ))

            item: while true {
                try throwIfCancelled(execution)
                let record = try await timed(timeouts.idle, category: .network, code: "idle_timeout") {
                    try await execution.connection.channel.receiveRecord()
                }
                try requireRecord(execution, record, type: record.header.type)
                switch record.header.type {
                case .chunk:
                    let prelude = try TransferChunkPrelude.decode(record.payload.prefix(transferChunkPreludeSize))
                    let data = record.payload.dropFirst(transferChunkPreludeSize)
                    guard prelude.itemID == entry.itemID else { throw InboundTransferFailure.protocol("item_id_mismatch") }
                    guard prelude.chunkIndex == expectedIndex, prelude.offset == expectedOffset else {
                        throw InboundTransferFailure.protocol("out_of_order_chunk")
                    }
                    guard Int(prelude.dataLength) == data.count else {
                        throw InboundTransferFailure.protocol("chunk_length_mismatch")
                    }
                    guard !data.isEmpty else { throw InboundTransferFailure.protocol("empty_chunk") }
                    guard expectedOffset < entry.byteCount else {
                        throw InboundTransferFailure.protocol("chunk_after_file_end")
                    }
                    let remaining = entry.byteCount - expectedOffset
                    let expectedLength = min(UInt64(negotiated.chunkSize), remaining)
                    guard UInt64(data.count) == expectedLength else {
                        throw InboundTransferFailure.protocol("negotiated_chunk_length_mismatch")
                    }
                    guard Data(SHA256.hash(data: data)) == prelude.sha256 else {
                        throw InboundTransferFailure.integrity("chunk_sha256_mismatch")
                    }
                    do { try await reservation.write(Data(data)) }
                    catch { throw InboundTransferFailure.storage("pending_write_failed") }
                    fileDigest.update(data: data)
                    expectedOffset += UInt64(data.count)
                    expectedIndex += 1
                    await events.emit(InboundTransferSessionEvent(
                        phase: .transferring,
                        summary: summary,
                        verifiedBytes: committedBytes + expectedOffset,
                        completedItems: index,
                        totalItems: totalItems,
                        currentItemName: entry.displayName
                    ))
                    try await send(
                        execution,
                        type: .progress,
                        payload: TransferControlCodec.encode(.progress(
                            itemID: entry.itemID,
                            verifiedBytes: expectedOffset,
                            verifiedChunks: expectedIndex,
                            phase: .transferring
                        ))
                    )

                case .senderFinished:
                    let finished = try TransferControlCodec.decode(type: .senderFinished, data: record.payload)
                    guard case .senderFinished(let itemID, let byteCount, let digest) = finished else {
                        throw InboundTransferFailure.protocol("invalid_sender_finished")
                    }
                    do { try await reservation.finishWriting() }
                    catch { throw InboundTransferFailure.storage("pending_write_failed") }
                    await events.emit(InboundTransferSessionEvent(
                        phase: .verifying,
                        summary: summary,
                        verifiedBytes: committedBytes + expectedOffset,
                        completedItems: index,
                        totalItems: totalItems,
                        currentItemName: entry.displayName
                    ))
                    guard itemID == entry.itemID,
                          byteCount == entry.byteCount,
                          expectedOffset == entry.byteCount
                    else { throw InboundTransferFailure.integrity("file_length_mismatch") }
                    guard digest == entry.sha256,
                          Data(fileDigest.finalize()) == entry.sha256
                    else { throw InboundTransferFailure.integrity("file_sha256_mismatch") }
                    try throwIfCancelled(execution)
                    await events.emit(InboundTransferSessionEvent(
                        phase: .committing,
                        summary: summary,
                        verifiedBytes: committedBytes + expectedOffset,
                        completedItems: index,
                        totalItems: totalItems,
                        currentItemName: entry.displayName
                    ))
                    let artifact: CommittedArtifact
                    do { artifact = try await reservation.commit() }
                    catch { throw InboundTransferFailure.storage("destination_commit_failed") }
                    execution.committedArtifacts.append(artifact)
                    execution.reservation = nil
                    committedBytes += entry.byteCount
                    if isLast {
                        execution.committed = true
                        try? await send(
                            execution,
                            type: .terminalResult,
                            payload: TransferControlCodec.encode(.terminalResult(
                                status: .completed,
                                errorCategory: nil,
                                errorCode: "",
                                committedArtifactID: ""
                            ))
                        )
                        await events.emit(InboundTransferSessionEvent(
                            phase: .completed,
                            summary: summary,
                            verifiedBytes: committedBytes,
                            artifact: artifact,
                            completedItems: totalItems,
                            totalItems: totalItems
                        ))
                        return RoleTransferOutcome(result: .completed, committedArtifacts: execution.committedArtifacts)
                    }
                    try await send(
                        execution,
                        type: .itemCommitted,
                        payload: TransferControlCodec.encode(.itemCommitted(itemID: entry.itemID, committedArtifactID: ""))
                    )
                    break item

                case .cancel:
                    let cancel = try TransferControlCodec.decode(type: .cancel, data: record.payload)
                    guard case .cancel(let reason) = cancel else {
                        throw InboundTransferFailure.protocol("invalid_cancel")
                    }
                    let category: TransferErrorCategory = reason == "peer_unpaired" ? .authentication :
                        (reason == "source_changed" ? .source : .cancelled)
                    throw InboundTransferFailure.cancelled(reason.isEmpty ? "sender_cancelled" : reason, category: category)

                default:
                    throw InboundTransferFailure.protocol("unexpected_record")
                }
            }
        }
        throw InboundTransferFailure.protocol("missing_sender_finished")
    }

    private func rejectBootstrap(
        _ execution: Execution,
        category: BootstrapRejectionCategory,
        code: String
    ) async throws -> RoleTransferOutcome {
        guard let sessionID = execution.sessionID else {
            throw InboundTransferFailure.protocol(code)
        }
        try await execution.connection.channel.sendRecord(
            type: .sessionDecision,
            payload: SessionBootstrapCodec.encode(.rejected(category, code: code)),
            sessionID: sessionID,
            protocolMajor: 0,
            protocolMinor: 0
        )
        let domainCategory: TransferErrorCategory = switch category {
        case .incompatibleVersion, .capability: .incompatibleVersion
        case .busy: .busy
        case .authentication: .authentication
        case .protocol: .protocol
        }
        return RoleTransferOutcome(
            result: category == .busy ? .failed : .rejected,
            failure: TransferFailure(category: domainCategory, code: code)
        )
    }

    private func finishFailure(
        _ execution: Execution,
        failure: InboundTransferFailure
    ) async -> RoleTransferOutcome {
        if execution.version != nil {
            try? await send(
                execution,
                type: .terminalResult,
                payload: TransferControlCodec.encode(.terminalResult(
                    status: failure.result == .cancelled ? .cancelled :
                        (failure.result == .rejected ? .rejected : .failed),
                    errorCategory: failure.category,
                    errorCode: failure.code,
                    committedArtifactID: ""
                ))
            )
        }
        if let summary = execution.summary {
            await events.emit(InboundTransferSessionEvent(
                phase: failure.phase,
                summary: summary,
                failure: TransferFailure(category: failure.category, code: failure.code),
                completedItems: execution.committedArtifacts.count,
                totalItems: summary.itemCount
            ))
        }
        return RoleTransferOutcome(
            result: failure.result,
            committedArtifacts: execution.committedArtifacts,
            failure: TransferFailure(category: failure.category, code: failure.code)
        )
    }

    private func cleanup(_ execution: Execution) async {
        if !execution.committed { await execution.reservation?.abort() }
        if let lease = execution.lease { await scheduler.release(token: lease.token) }
        if current === execution { current = nil }
        await execution.connection.channel.close()
    }

    private func send(_ execution: Execution, type: TransferRecordType, payload: Data) async throws {
        guard let sessionID = execution.sessionID, let version = execution.version else {
            throw InboundTransferFailure.protocol("session_not_negotiated")
        }
        try await execution.connection.channel.sendRecord(
            type: type,
            payload: payload,
            sessionID: sessionID,
            protocolMajor: version.major,
            protocolMinor: version.minor
        )
    }

    private func requireRecord(
        _ execution: Execution,
        _ record: TransferWireRecord,
        type: TransferRecordType
    ) throws {
        guard let sessionID = execution.sessionID, let version = execution.version,
              record.header.sessionID == sessionID,
              record.header.type == type,
              record.header.protocolMajor == version.major,
              record.header.protocolMinor == version.minor
        else { throw InboundTransferFailure.protocol("unexpected_record") }
    }

    private func throwIfCancelled(_ execution: Execution) throws {
        if let reason = execution.cancellationReason {
            let category: TransferErrorCategory = reason == "peer_unpaired" ? .authentication : .cancelled
            throw InboundTransferFailure.cancelled(reason, category: category)
        }
    }

    private func timed<T: Sendable>(
        _ timeout: Duration,
        category: TransferErrorCategory,
        code: String,
        operation: @escaping @Sendable () async throws -> T
    ) async throws -> T {
        do { return try await deadlines.run(timeout: timeout, operation: operation) }
        catch let failure as InboundTransferFailure { throw failure }
        catch TransferDeadlineError.timeout { throw InboundTransferFailure(result: .failed, phase: .failed, category: category, code: code) }
        catch { throw error }
    }

    public static func validateBasename(_ name: String) throws {
        guard !name.isEmpty,
              name != ".", name != "..",
              !name.contains("/"), !name.contains("\0"),
              !name.hasPrefix("/"),
              name.utf8.count <= 255
        else { throw InboundTransferFailure.permission("invalid_destination_name") }
    }
}

public struct InboundTransferFailure: Error, Equatable, Sendable {
    public let result: TransferTerminalResult
    public let phase: TransferActivityPhase
    public let category: TransferErrorCategory
    public let code: String

    public init(
        result: TransferTerminalResult,
        phase: TransferActivityPhase,
        category: TransferErrorCategory,
        code: String
    ) {
        self.result = result
        self.phase = phase
        self.category = category
        self.code = code
    }

    public static func `protocol`(_ code: String) -> Self {
        Self(result: .failed, phase: .failed, category: .protocol, code: code)
    }
    public static func permission(_ code: String) -> Self {
        Self(result: .failed, phase: .failed, category: .permission, code: code)
    }
    public static func storage(_ code: String) -> Self {
        Self(result: .failed, phase: .failed, category: .storage, code: code)
    }
    public static func integrity(_ code: String) -> Self {
        Self(result: .failed, phase: .failed, category: .integrity, code: code)
    }
    public static func cancelled(_ code: String, category: TransferErrorCategory = .cancelled) -> Self {
        Self(result: .cancelled, phase: .cancelled, category: category, code: code)
    }
}
