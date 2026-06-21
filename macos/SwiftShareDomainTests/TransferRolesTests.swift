import Foundation
import Testing
import SwiftShareDomain

@Suite("Role-specific Transfer Sessions")
struct TransferRolesTests {
    @Test("Outbound completion exposes only the receiver committed artifact")
    func outboundCompletion() async {
        let events = RoleEventRecorder()
        let connection = RoleConnectionStub(
            approval: .approval(accepted: true, reasonCode: ""),
            remote: RemoteTransferResult(status: .completed, committedArtifactID: "content://media/42")
        )
        let coordinator = makeOutbound(peer: Self.peer, connection: connection, events: events)

        let outcome = await coordinator.execute(Self.request)

        #expect(outcome.result == .completed)
        #expect(outcome.committedArtifacts == [CommittedArtifact(id: "content://media/42")])
        #expect(await events.phases == [.preparing, .connecting, .awaitingApproval, .transferring, .completed])
    }

    @Test("An outbound unpaired Peer is rejected before connection")
    func outboundUnpaired() async {
        let events = RoleEventRecorder()
        let connector = RoleConnectorSpy(connection: RoleConnectionStub())
        let coordinator = OutboundTransferSessionCoordinator(
            identityStore: RoleIdentityStore(),
            peerDirectory: RolePeerDirectory(peer: nil),
            endpointResolver: RoleEndpointResolver(),
            connector: connector,
            deadlineScheduler: ImmediateRoleDeadlineScheduler(),
            cancellation: NeverCancelled(),
            events: events
        )

        let outcome = await coordinator.execute(Self.request)

        #expect(outcome.result == .rejected)
        #expect(await connector.calls == 0)
        #expect(await events.phases == [.preparing, .rejected])
    }

    @Test("Inbound rejection allocates no destination")
    func inboundRejection() async {
        let receiver = RoleReceiverSpy()
        let coordinator = InboundTransferSessionCoordinator(
            peerDirectory: RolePeerDirectory(peer: Self.peer),
            approvalGateway: RoleApprovalStub(approved: false),
            receiver: receiver,
            deadlineScheduler: ImmediateRoleDeadlineScheduler(),
            cancellation: NeverCancelled(),
            events: RoleEventRecorder()
        )

        let outcome = await coordinator.execute(
            InboundTransferContext(request: Self.request, authenticatedPeerID: Self.peer.id)
        )

        #expect(outcome.result == .rejected)
        #expect(outcome.committedArtifacts.isEmpty)
        #expect(await receiver.calls == 0)
    }

    @Test("Inbound permission failure stays typed and uncommitted")
    func inboundPermissionFailure() async {
        let receiver = RoleReceiverSpy(
            error: TransferExecutionError(category: .permission, code: "downloads_permission_lost")
        )
        let coordinator = InboundTransferSessionCoordinator(
            peerDirectory: RolePeerDirectory(peer: Self.peer),
            approvalGateway: RoleApprovalStub(approved: true),
            receiver: receiver,
            deadlineScheduler: ImmediateRoleDeadlineScheduler(),
            cancellation: NeverCancelled(),
            events: RoleEventRecorder()
        )

        let outcome = await coordinator.execute(
            InboundTransferContext(request: Self.request, authenticatedPeerID: Self.peer.id)
        )

        #expect(outcome.result == .failed)
        #expect(outcome.failure?.category == .permission)
        #expect(outcome.committedArtifacts.isEmpty)
    }

    @Test("Inbound auto-accept bypasses the manual approval gateway")
    func inboundAutoAccept() async {
        let approval = RoleApprovalSpy(approved: false)
        let receiver = RoleReceiverSpy()
        let coordinator = InboundTransferSessionCoordinator(
            peerDirectory: RolePeerDirectory(peer: Self.peer),
            approvalPolicyResolver: RolePolicyStub(policy: .autoAccept),
            approvalGateway: approval,
            receiver: receiver,
            deadlineScheduler: ImmediateRoleDeadlineScheduler(),
            cancellation: NeverCancelled(),
            events: RoleEventRecorder()
        )

        let outcome = await coordinator.execute(
            InboundTransferContext(request: Self.request, authenticatedPeerID: Self.peer.id)
        )

        #expect(outcome.result == .completed)
        #expect(await approval.calls == 0)
        #expect(await receiver.calls == 1)
    }

    private func makeOutbound(
        peer: PairedPeer?,
        connection: any OutboundTransferConnection,
        events: RoleEventRecorder
    ) -> OutboundTransferSessionCoordinator {
        OutboundTransferSessionCoordinator(
            identityStore: RoleIdentityStore(),
            peerDirectory: RolePeerDirectory(peer: peer),
            endpointResolver: RoleEndpointResolver(),
            connector: RoleConnectorSpy(connection: connection),
            deadlineScheduler: ImmediateRoleDeadlineScheduler(),
            cancellation: NeverCancelled(),
            events: events
        )
    }

    private static let peer = PairedPeer(id: "android", displayName: "Android")
    private static let request = TransferSessionRequest(
        id: UUID(uuidString: "00000000-0000-0000-0000-000000000001")!,
        peerID: "android",
        payloads: [PayloadDescriptor(id: "file-1", byteCount: 42)]
    )
}

private struct RoleIdentityStore: IdentityStoring {
    func localDeviceIdentity() async throws -> DeviceIdentity { DeviceIdentity(id: "mac") }
}

private struct RolePeerDirectory: PeerDirectory {
    let peer: PairedPeer?
    func pairedPeer(id: String) async throws -> PairedPeer? { peer?.id == id ? peer : nil }
}

private struct RoleEndpointResolver: PeerEndpointResolving {
    func endpoint(for peer: PairedPeer) async throws -> PeerEndpoint {
        PeerEndpoint(host: "127.0.0.1", port: 8443)
    }
}

private struct ImmediateRoleDeadlineScheduler: TransferDeadlineScheduling {
    func run<T: Sendable>(
        timeout: Duration,
        operation: @escaping @Sendable () async throws -> T
    ) async throws -> T { try await operation() }
}

private struct NeverCancelled: TransferCancellationChecking {
    func isCancelled() async -> Bool { false }
}

private actor RoleEventRecorder: TransferEventSink {
    private var values: [TransferSessionEvent] = []
    var phases: [TransferActivityPhase] { values.map(\.phase) }
    func emit(_ event: TransferSessionEvent) { values.append(event) }
}

private actor RoleConnectorSpy: OutboundTransferConnecting {
    let connection: any OutboundTransferConnection
    private(set) var calls = 0

    init(connection: any OutboundTransferConnection) { self.connection = connection }

    func connect(_ context: OutboundConnectionContext) async throws -> any OutboundTransferConnection {
        calls += 1
        return connection
    }
}

private actor RoleConnectionStub: OutboundTransferConnection {
    let approval: TransferControlMessage
    let remote: RemoteTransferResult

    init(
        approval: TransferControlMessage = .approval(accepted: true, reasonCode: ""),
        remote: RemoteTransferResult = RemoteTransferResult(status: .completed)
    ) {
        self.approval = approval
        self.remote = remote
    }

    func awaitApproval() async throws -> TransferControlMessage { approval }
    func stream(
        events: any TransferEventSink,
        cancellation: any TransferCancellationChecking,
        deadlineScheduler: any TransferDeadlineScheduling,
        idleTimeout: Duration
    ) async throws -> RemoteTransferResult { remote }
    func cancel(reasonCode: String) async {}
}

private struct RoleApprovalStub: IncomingApprovalGateway {
    let approved: Bool
    func requestApproval(peer: PairedPeer, request: TransferSessionRequest) async throws -> Bool { approved }
}

private struct RolePolicyStub: PeerApprovalPolicyResolving {
    let policy: PeerApprovalPolicy
    func policy(for peer: PairedPeer) async throws -> PeerApprovalPolicy { policy }
}

private actor RoleApprovalSpy: IncomingApprovalGateway {
    let approved: Bool
    private(set) var calls = 0
    init(approved: Bool) { self.approved = approved }
    func requestApproval(peer: PairedPeer, request: TransferSessionRequest) async throws -> Bool {
        calls += 1
        return approved
    }
}

private actor RoleReceiverSpy: InboundTransferReceiving {
    let error: TransferExecutionError?
    private(set) var calls = 0

    init(error: TransferExecutionError? = nil) { self.error = error }

    func receive(
        peer: PairedPeer,
        request: TransferSessionRequest,
        events: any TransferEventSink,
        cancellation: any TransferCancellationChecking,
        deadlineScheduler: any TransferDeadlineScheduling,
        idleTimeout: Duration
    ) async throws -> CommittedArtifact {
        calls += 1
        if let error { throw error }
        return CommittedArtifact(id: "content://media/42")
    }
}
