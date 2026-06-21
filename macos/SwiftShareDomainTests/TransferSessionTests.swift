import Foundation
import Testing
import SwiftShareDomain

@Suite("Transfer Session acceptance seam")
struct TransferSessionTests {
    @Test("An unpaired Peer is rejected before endpoint resolution or transport")
    func rejectsUnpairedPeer() async {
        let transport = TransportSpy(result: .completed)
        let resolver = EndpointResolverSpy()
        let session = makeSession(
            pairedPeer: nil,
            resolver: resolver,
            transport: transport,
            artifacts: [CommittedArtifact(id: "should-not-appear")]
        )

        let outcome = await session.execute(Self.request)

        #expect(outcome.result == .rejected)
        #expect(outcome.committedArtifacts.isEmpty)
        #expect(await resolver.callCount == 0)
        #expect(await transport.callCount == 0)
    }

    @Test("Only completed sessions expose committed artifacts")
    func exposesArtifactsAfterCompletion() async {
        let artifact = CommittedArtifact(id: "artifact-1")
        let transport = TransportSpy(result: .completed)
        let session = makeSession(
            pairedPeer: PairedPeer(id: "android", displayName: "Android"),
            resolver: EndpointResolverSpy(),
            transport: transport,
            artifacts: [artifact]
        )

        let outcome = await session.execute(Self.request)

        #expect(outcome.result == .completed)
        #expect(outcome.committedArtifacts == [artifact])
        #expect(await transport.callCount == 1)
    }

    @Test("Rejected sessions expose no destination artifacts")
    func hidesArtifactsAfterRejection() async {
        let storage = DestinationStorageSpy(artifacts: [CommittedArtifact(id: "hidden")])
        let session = TransferSession(
            identityStore: IdentityStoreStub(),
            peerDirectory: PeerDirectoryStub(peer: PairedPeer(id: "android", displayName: "Android")),
            endpointResolver: EndpointResolverSpy(),
            transport: TransportSpy(result: .rejected),
            destinationStorage: storage,
            clock: ClockStub()
        )

        let outcome = await session.execute(Self.request)

        #expect(outcome.result == .rejected)
        #expect(outcome.committedArtifacts.isEmpty)
        #expect(await storage.callCount == 0)
    }

    private static let request = TransferSessionRequest(
        id: UUID(uuidString: "00000000-0000-0000-0000-000000000001")!,
        peerID: "android",
        payloads: [PayloadDescriptor(id: "file-1", byteCount: 42)]
    )

    private func makeSession(
        pairedPeer: PairedPeer?,
        resolver: EndpointResolverSpy,
        transport: TransportSpy,
        artifacts: [CommittedArtifact]
    ) -> TransferSession {
        TransferSession(
            identityStore: IdentityStoreStub(),
            peerDirectory: PeerDirectoryStub(peer: pairedPeer),
            endpointResolver: resolver,
            transport: transport,
            destinationStorage: DestinationStorageSpy(artifacts: artifacts),
            clock: ClockStub()
        )
    }
}

private struct IdentityStoreStub: IdentityStoring {
    func localDeviceIdentity() async throws -> DeviceIdentity {
        DeviceIdentity(id: "mac")
    }
}

private struct PeerDirectoryStub: PeerDirectory {
    let peer: PairedPeer?

    func pairedPeer(id: String) async throws -> PairedPeer? {
        peer?.id == id ? peer : nil
    }
}

private actor EndpointResolverSpy: PeerEndpointResolving {
    private(set) var callCount = 0

    func endpoint(for peer: PairedPeer) async throws -> PeerEndpoint {
        callCount += 1
        return PeerEndpoint(host: "127.0.0.1", port: 8443)
    }
}

private actor TransportSpy: TransferTransport {
    private let result: TransferTerminalResult
    private(set) var callCount = 0

    init(result: TransferTerminalResult) {
        self.result = result
    }

    func perform(
        request: TransferSessionRequest,
        localDevice: DeviceIdentity,
        peer: PairedPeer,
        endpoint: PeerEndpoint
    ) async throws -> TransferTerminalResult {
        callCount += 1
        return result
    }
}

private actor DestinationStorageSpy: DestinationStorage {
    private let artifacts: [CommittedArtifact]
    private(set) var callCount = 0

    init(artifacts: [CommittedArtifact]) {
        self.artifacts = artifacts
    }

    func committedArtifacts(for sessionID: UUID) async throws -> [CommittedArtifact] {
        callCount += 1
        return artifacts
    }
}

private actor ClockStub: TransferClock {
    private var tick: TimeInterval = 0

    func now() async -> Date {
        defer { tick += 1 }
        return Date(timeIntervalSince1970: tick)
    }
}
