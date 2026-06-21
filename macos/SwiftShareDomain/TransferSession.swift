import Foundation

public struct DeviceIdentity: Equatable, Sendable {
    public let id: String

    public init(id: String) {
        self.id = id
    }
}

public struct PairedPeer: Equatable, Sendable {
    public let id: String
    public let displayName: String

    public init(id: String, displayName: String) {
        self.id = id
        self.displayName = displayName
    }
}

public struct PeerEndpoint: Equatable, Sendable {
    public let host: String
    public let port: UInt16

    public init(host: String, port: UInt16) {
        self.host = host
        self.port = port
    }
}

public struct PayloadDescriptor: Equatable, Sendable {
    public let id: String
    public let byteCount: UInt64

    public init(id: String, byteCount: UInt64) {
        self.id = id
        self.byteCount = byteCount
    }
}

public struct CommittedArtifact: Equatable, Sendable {
    public let id: String

    public init(id: String) {
        self.id = id
    }
}

public enum PeerApprovalPolicy: String, Codable, Equatable, Sendable {
    case requireApproval = "require_approval"
    case autoAccept = "auto_accept"
}

public enum TransferTerminalResult: String, Codable, Equatable, Sendable {
    case completed
    case rejected
    case cancelled
    case failed
}

public struct TransferSessionRequest: Equatable, Sendable {
    public let id: UUID
    public let peerID: String
    public let payloads: [PayloadDescriptor]

    public init(
        id: UUID,
        peerID: String,
        payloads: [PayloadDescriptor]
    ) {
        self.id = id
        self.peerID = peerID
        self.payloads = payloads
    }
}

public struct TransferSessionOutcome: Equatable, Sendable {
    public let result: TransferTerminalResult
    public let committedArtifacts: [CommittedArtifact]
    public let startedAt: Date
    public let endedAt: Date

    public init(
        result: TransferTerminalResult,
        committedArtifacts: [CommittedArtifact],
        startedAt: Date,
        endedAt: Date
    ) {
        self.result = result
        self.committedArtifacts = committedArtifacts
        self.startedAt = startedAt
        self.endedAt = endedAt
    }
}

public protocol IdentityStoring: Sendable {
    func localDeviceIdentity() async throws -> DeviceIdentity
}

public protocol PeerDirectory: Sendable {
    func pairedPeer(id: String) async throws -> PairedPeer?
}

public protocol PeerEndpointResolving: Sendable {
    func endpoint(for peer: PairedPeer) async throws -> PeerEndpoint
}

public protocol TransferTransport: Sendable {
    func perform(
        request: TransferSessionRequest,
        localDevice: DeviceIdentity,
        peer: PairedPeer,
        endpoint: PeerEndpoint
    ) async throws -> TransferTerminalResult
}

public protocol DestinationStorage: Sendable {
    func committedArtifacts(for sessionID: UUID) async throws -> [CommittedArtifact]
}

public protocol TransferClock: Sendable {
    func now() async -> Date
}

public struct TransferSession: Sendable {
    private let identityStore: any IdentityStoring
    private let peerDirectory: any PeerDirectory
    private let endpointResolver: any PeerEndpointResolving
    private let transport: any TransferTransport
    private let destinationStorage: any DestinationStorage
    private let clock: any TransferClock

    public init(
        identityStore: any IdentityStoring,
        peerDirectory: any PeerDirectory,
        endpointResolver: any PeerEndpointResolving,
        transport: any TransferTransport,
        destinationStorage: any DestinationStorage,
        clock: any TransferClock
    ) {
        self.identityStore = identityStore
        self.peerDirectory = peerDirectory
        self.endpointResolver = endpointResolver
        self.transport = transport
        self.destinationStorage = destinationStorage
        self.clock = clock
    }

    public func execute(_ request: TransferSessionRequest) async -> TransferSessionOutcome {
        let startedAt = await clock.now()

        do {
            let localDevice = try await identityStore.localDeviceIdentity()
            guard let peer = try await peerDirectory.pairedPeer(id: request.peerID) else {
                return TransferSessionOutcome(
                    result: .rejected,
                    committedArtifacts: [],
                    startedAt: startedAt,
                    endedAt: await clock.now()
                )
            }

            let endpoint = try await endpointResolver.endpoint(for: peer)
            let result = try await transport.perform(
                request: request,
                localDevice: localDevice,
                peer: peer,
                endpoint: endpoint
            )
            let artifacts: [CommittedArtifact] = if result == .completed {
                try await destinationStorage.committedArtifacts(for: request.id)
            } else {
                []
            }

            return TransferSessionOutcome(
                result: result,
                committedArtifacts: artifacts,
                startedAt: startedAt,
                endedAt: await clock.now()
            )
        } catch {
            return TransferSessionOutcome(
                result: .failed,
                committedArtifacts: [],
                startedAt: startedAt,
                endedAt: await clock.now()
            )
        }
    }
}

public enum SessionLeasePhase: Equatable, Sendable {
    case outboundPreDecision
    case activeOutbound
    case activeInbound
    case lostDraining
}

public struct SessionLease: Equatable, Sendable {
    public let token: UUID
    public let sessionID: UUID
    public let peerID: Data
    public let phase: SessionLeasePhase

    public init(token: UUID = UUID(), sessionID: UUID, peerID: Data, phase: SessionLeasePhase) {
        precondition(peerID.count == 32)
        self.token = token
        self.sessionID = sessionID
        self.peerID = peerID
        self.phase = phase
    }
}

public enum SessionAdmission: Equatable, Sendable {
    case admitted(lease: SessionLease, preemptedOutboundToken: UUID?)
    case rejected(code: String)
}

public actor TransferSessionScheduler {
    private let localDeviceID: Data
    private var active: SessionLease?
    private var draining: Set<UUID> = []

    public init(localDeviceID: Data) {
        precondition(localDeviceID.count == 32)
        self.localDeviceID = localDeviceID
    }

    public func reserveOutbound(peerID: Data, sessionID: UUID) -> SessionLease? {
        precondition(peerID.count == 32)
        guard active == nil else { return nil }
        let lease = SessionLease(sessionID: sessionID, peerID: peerID, phase: .outboundPreDecision)
        active = lease
        return lease
    }

    @discardableResult
    public func markOutboundAccepted(token: UUID) -> Bool {
        guard let current = active, current.token == token, current.phase == .outboundPreDecision else { return false }
        active = SessionLease(token: current.token, sessionID: current.sessionID, peerID: current.peerID, phase: .activeOutbound)
        return true
    }

    public func admitInbound(peerID: Data, sessionID: UUID) -> SessionAdmission {
        precondition(peerID.count == 32)
        guard peerID != localDeviceID else { return .rejected(code: "identity_collision") }
        guard let current = active else {
            let lease = SessionLease(sessionID: sessionID, peerID: peerID, phase: .activeInbound)
            active = lease
            return .admitted(lease: lease, preemptedOutboundToken: nil)
        }
        if current.phase == .outboundPreDecision, current.peerID == peerID {
            if localDeviceID.lexicographicallyPrecedes(peerID) {
                return .rejected(code: "simultaneous_initiation_lost")
            }
            draining.insert(current.token)
            let lease = SessionLease(sessionID: sessionID, peerID: peerID, phase: .activeInbound)
            active = lease
            return .admitted(lease: lease, preemptedOutboundToken: current.token)
        }
        return .rejected(code: "device_busy")
    }

    public func phase(token: UUID) -> SessionLeasePhase? {
        if active?.token == token { return active?.phase }
        if draining.contains(token) { return .lostDraining }
        return nil
    }

    public func release(token: UUID) {
        if active?.token == token { active = nil }
        draining.remove(token)
    }

    public var hasActiveSession: Bool { active != nil }
}
