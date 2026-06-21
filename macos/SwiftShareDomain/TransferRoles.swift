import Foundation

public enum TransferActivityPhase: Equatable, Sendable {
    case preparing
    case connecting
    case awaitingApproval
    case transferring
    case verifying
    case committing
    case completed
    case rejected
    case cancelled
    case failed
}

public struct TransferSessionEvent: Equatable, Sendable {
    public let phase: TransferActivityPhase
    public let transferredBytes: UInt64
    public let verifiedBytes: UInt64
    public let totalBytes: UInt64
    public let completedItems: Int
    public let totalItems: Int

    public init(
        phase: TransferActivityPhase,
        transferredBytes: UInt64 = 0,
        verifiedBytes: UInt64 = 0,
        totalBytes: UInt64 = 0,
        completedItems: Int = 0,
        totalItems: Int = 1
    ) {
        self.phase = phase
        self.transferredBytes = transferredBytes
        self.verifiedBytes = verifiedBytes
        self.totalBytes = totalBytes
        self.completedItems = completedItems
        self.totalItems = totalItems
    }
}

public struct TransferFailure: Equatable, Sendable {
    public let category: TransferErrorCategory
    public let code: String

    public init(category: TransferErrorCategory, code: String) {
        self.category = category
        self.code = code
    }
}

public struct RoleTransferOutcome: Equatable, Sendable {
    public let result: TransferTerminalResult
    public let committedArtifacts: [CommittedArtifact]
    public let failure: TransferFailure?

    public init(
        result: TransferTerminalResult,
        committedArtifacts: [CommittedArtifact] = [],
        failure: TransferFailure? = nil
    ) {
        self.result = result
        self.committedArtifacts = committedArtifacts
        self.failure = failure
    }
}

public struct TransferTimeoutPolicy: Equatable, Sendable {
    public let connect: Duration
    public let approval: Duration
    public let idle: Duration

    public init(
        connect: Duration = .seconds(15),
        approval: Duration = .seconds(120),
        idle: Duration = .seconds(60)
    ) {
        self.connect = connect
        self.approval = approval
        self.idle = idle
    }
}

public protocol TransferEventSink: Sendable {
    func emit(_ event: TransferSessionEvent) async
}

public protocol TransferCancellationChecking: Sendable {
    func isCancelled() async -> Bool
}

public protocol TransferDeadlineScheduling: Sendable {
    func run<T: Sendable>(
        timeout: Duration,
        operation: @escaping @Sendable () async throws -> T
    ) async throws -> T
}

public struct OutboundConnectionContext: Sendable {
    public let request: TransferSessionRequest
    public let localDevice: DeviceIdentity
    public let peer: PairedPeer
    public let endpoint: PeerEndpoint

    public init(
        request: TransferSessionRequest,
        localDevice: DeviceIdentity,
        peer: PairedPeer,
        endpoint: PeerEndpoint
    ) {
        self.request = request
        self.localDevice = localDevice
        self.peer = peer
        self.endpoint = endpoint
    }
}

public struct RemoteTransferResult: Equatable, Sendable {
    public let status: WireTerminalStatus
    public let committedArtifactID: String
    public let failure: TransferFailure?

    public init(
        status: WireTerminalStatus,
        committedArtifactID: String = "",
        failure: TransferFailure? = nil
    ) {
        self.status = status
        self.committedArtifactID = committedArtifactID
        self.failure = failure
    }
}

public protocol OutboundTransferConnection: Sendable {
    func awaitApproval() async throws -> TransferControlMessage

    func stream(
        events: any TransferEventSink,
        cancellation: any TransferCancellationChecking,
        deadlineScheduler: any TransferDeadlineScheduling,
        idleTimeout: Duration
    ) async throws -> RemoteTransferResult

    func cancel(reasonCode: String) async
}

public protocol OutboundTransferConnecting: Sendable {
    func connect(_ context: OutboundConnectionContext) async throws -> any OutboundTransferConnection
}

public struct OutboundTransferSessionCoordinator: Sendable {
    private let identityStore: any IdentityStoring
    private let peerDirectory: any PeerDirectory
    private let endpointResolver: any PeerEndpointResolving
    private let connector: any OutboundTransferConnecting
    private let deadlineScheduler: any TransferDeadlineScheduling
    private let cancellation: any TransferCancellationChecking
    private let events: any TransferEventSink
    private let timeouts: TransferTimeoutPolicy

    public init(
        identityStore: any IdentityStoring,
        peerDirectory: any PeerDirectory,
        endpointResolver: any PeerEndpointResolving,
        connector: any OutboundTransferConnecting,
        deadlineScheduler: any TransferDeadlineScheduling,
        cancellation: any TransferCancellationChecking,
        events: any TransferEventSink,
        timeouts: TransferTimeoutPolicy = TransferTimeoutPolicy()
    ) {
        self.identityStore = identityStore
        self.peerDirectory = peerDirectory
        self.endpointResolver = endpointResolver
        self.connector = connector
        self.deadlineScheduler = deadlineScheduler
        self.cancellation = cancellation
        self.events = events
        self.timeouts = timeouts
    }

    public func execute(_ request: TransferSessionRequest) async -> RoleTransferOutcome {
        do {
            await events.emit(event(.preparing, request: request))
            guard let peer = try await peerDirectory.pairedPeer(id: request.peerID) else {
                return await terminal(.rejected, phase: .rejected, request: request)
            }
            let localDevice = try await identityStore.localDeviceIdentity()
            let endpoint = try await endpointResolver.endpoint(for: peer)
            await events.emit(event(.connecting, request: request))
            let context = OutboundConnectionContext(
                request: request,
                localDevice: localDevice,
                peer: peer,
                endpoint: endpoint
            )
            let connection = try await deadlineScheduler.run(timeout: timeouts.connect) {
                try await connector.connect(context)
            }
            if await cancellation.isCancelled() {
                await connection.cancel(reasonCode: "user_cancelled")
                return await terminal(.cancelled, phase: .cancelled, request: request)
            }
            await events.emit(event(.awaitingApproval, request: request))
            let approval = try await deadlineScheduler.run(timeout: timeouts.approval) {
                try await connection.awaitApproval()
            }
            guard case .approval(let accepted, _) = approval, accepted else {
                return await terminal(.rejected, phase: .rejected, request: request)
            }
            await events.emit(event(.transferring, request: request))
            let remote = try await connection.stream(
                events: events,
                cancellation: cancellation,
                deadlineScheduler: deadlineScheduler,
                idleTimeout: timeouts.idle
            )
            let result = remote.status.domainResult
            await events.emit(event(result.activityPhase, request: request, verifiedBytes: request.totalBytes))
            let artifacts = result == .completed && !remote.committedArtifactID.isEmpty
                ? [CommittedArtifact(id: remote.committedArtifactID)]
                : []
            return RoleTransferOutcome(result: result, committedArtifacts: artifacts, failure: remote.failure)
        } catch let error as TransferExecutionError {
            await events.emit(event(.failed, request: request))
            return RoleTransferOutcome(
                result: .failed,
                failure: TransferFailure(category: error.category, code: error.code)
            )
        } catch {
            await events.emit(event(.failed, request: request))
            return RoleTransferOutcome(
                result: .failed,
                failure: TransferFailure(category: .protocol, code: "unexpected_failure")
            )
        }
    }

    private func terminal(
        _ result: TransferTerminalResult,
        phase: TransferActivityPhase,
        request: TransferSessionRequest
    ) async -> RoleTransferOutcome {
        await events.emit(event(phase, request: request))
        return RoleTransferOutcome(result: result)
    }
}

public struct InboundTransferContext: Sendable {
    public let request: TransferSessionRequest
    public let authenticatedPeerID: String

    public init(request: TransferSessionRequest, authenticatedPeerID: String) {
        self.request = request
        self.authenticatedPeerID = authenticatedPeerID
    }
}

public protocol IncomingApprovalGateway: Sendable {
    func requestApproval(peer: PairedPeer, request: TransferSessionRequest) async throws -> Bool
}

public protocol PeerApprovalPolicyResolving: Sendable {
    func policy(for peer: PairedPeer) async throws -> PeerApprovalPolicy
}

public struct RequireApprovalPolicyResolver: PeerApprovalPolicyResolving {
    public init() {}
    public func policy(for peer: PairedPeer) async throws -> PeerApprovalPolicy { .requireApproval }
}

public protocol InboundTransferReceiving: Sendable {
    func receive(
        peer: PairedPeer,
        request: TransferSessionRequest,
        events: any TransferEventSink,
        cancellation: any TransferCancellationChecking,
        deadlineScheduler: any TransferDeadlineScheduling,
        idleTimeout: Duration
    ) async throws -> CommittedArtifact
}

public struct InboundTransferSessionCoordinator: Sendable {
    private let peerDirectory: any PeerDirectory
    private let approvalPolicyResolver: any PeerApprovalPolicyResolving
    private let approvalGateway: any IncomingApprovalGateway
    private let receiver: any InboundTransferReceiving
    private let deadlineScheduler: any TransferDeadlineScheduling
    private let cancellation: any TransferCancellationChecking
    private let events: any TransferEventSink
    private let timeouts: TransferTimeoutPolicy

    public init(
        peerDirectory: any PeerDirectory,
        approvalPolicyResolver: any PeerApprovalPolicyResolving = RequireApprovalPolicyResolver(),
        approvalGateway: any IncomingApprovalGateway,
        receiver: any InboundTransferReceiving,
        deadlineScheduler: any TransferDeadlineScheduling,
        cancellation: any TransferCancellationChecking,
        events: any TransferEventSink,
        timeouts: TransferTimeoutPolicy = TransferTimeoutPolicy()
    ) {
        self.peerDirectory = peerDirectory
        self.approvalPolicyResolver = approvalPolicyResolver
        self.approvalGateway = approvalGateway
        self.receiver = receiver
        self.deadlineScheduler = deadlineScheduler
        self.cancellation = cancellation
        self.events = events
        self.timeouts = timeouts
    }

    public func execute(_ context: InboundTransferContext) async -> RoleTransferOutcome {
        let request = context.request
        do {
            guard let peer = try await peerDirectory.pairedPeer(id: context.authenticatedPeerID) else {
                return await terminal(.rejected, phase: .rejected, request: request)
            }
            await events.emit(event(.awaitingApproval, request: request))
            let approved = switch try await approvalPolicyResolver.policy(for: peer) {
            case .autoAccept: true
            case .requireApproval:
                try await deadlineScheduler.run(timeout: timeouts.approval) {
                    try await approvalGateway.requestApproval(peer: peer, request: request)
                }
            }
            guard approved else { return await terminal(.rejected, phase: .rejected, request: request) }
            guard !(await cancellation.isCancelled()) else {
                return await terminal(.cancelled, phase: .cancelled, request: request)
            }
            await events.emit(event(.transferring, request: request))
            let artifact = try await receiver.receive(
                peer: peer,
                request: request,
                events: events,
                cancellation: cancellation,
                deadlineScheduler: deadlineScheduler,
                idleTimeout: timeouts.idle
            )
            await events.emit(event(.completed, request: request, verifiedBytes: request.totalBytes))
            return RoleTransferOutcome(result: .completed, committedArtifacts: [artifact])
        } catch let error as TransferExecutionError {
            await events.emit(event(.failed, request: request))
            return RoleTransferOutcome(
                result: .failed,
                failure: TransferFailure(category: error.category, code: error.code)
            )
        } catch {
            await events.emit(event(.failed, request: request))
            return RoleTransferOutcome(
                result: .failed,
                failure: TransferFailure(category: .protocol, code: "unexpected_failure")
            )
        }
    }

    private func terminal(
        _ result: TransferTerminalResult,
        phase: TransferActivityPhase,
        request: TransferSessionRequest
    ) async -> RoleTransferOutcome {
        await events.emit(event(phase, request: request))
        return RoleTransferOutcome(result: result)
    }
}

public struct TransferExecutionError: Error, Equatable, Sendable {
    public let category: TransferErrorCategory
    public let code: String

    public init(category: TransferErrorCategory, code: String) {
        self.category = category
        self.code = code
    }
}

private func event(
    _ phase: TransferActivityPhase,
    request: TransferSessionRequest,
    verifiedBytes: UInt64 = 0
) -> TransferSessionEvent {
    TransferSessionEvent(
        phase: phase,
        verifiedBytes: verifiedBytes,
        totalBytes: request.totalBytes,
        completedItems: phase == .completed ? request.payloads.count : 0,
        totalItems: request.payloads.count
    )
}

private extension TransferSessionRequest {
    var totalBytes: UInt64 { payloads.reduce(0) { $0 + $1.byteCount } }
}

private extension WireTerminalStatus {
    var domainResult: TransferTerminalResult {
        switch self {
        case .completed: .completed
        case .rejected: .rejected
        case .cancelled: .cancelled
        case .failed: .failed
        }
    }
}

private extension TransferTerminalResult {
    var activityPhase: TransferActivityPhase {
        switch self {
        case .completed: .completed
        case .rejected: .rejected
        case .cancelled: .cancelled
        case .failed: .failed
        }
    }
}
