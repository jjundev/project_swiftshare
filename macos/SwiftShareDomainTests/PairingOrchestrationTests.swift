import CryptoKit
import Foundation
import Testing
import SwiftShareDomain

@Suite("Production Pairing Session")
struct PairingOrchestrationTests {
    @Test("Pairing completes only after durable trust and CommitAck")
    func completedConversation() async throws {
        let fixture = Fixture()

        let outcome = try await fixture.session.execute(host: "192.0.2.10")

        #expect(outcome.peer == fixture.remoteDescriptor)
        #expect(await fixture.committer.committedPeers == [fixture.remoteDescriptor])
        #expect(await fixture.confirmation.requests.count == 1)
        #expect(await fixture.events.phases == ["qr", "waiting", "completed"])
        #expect(await fixture.listener.channel?.sentTypes == [
            .serverChallenge, .serverProof, .commitReceipt,
        ])
        #expect(await fixture.listener.closed)
        #expect(await fixture.listener.channel?.closed == true)
    }

    @Test("Local rejection sends a terminal error without durable trust")
    func localRejection() async {
        let fixture = Fixture(localAccepted: false)

        await #expect(throws: PairingError.cancelled) {
            try await fixture.session.execute(host: "192.0.2.10")
        }

        #expect(await fixture.committer.committedPeers.isEmpty)
        #expect(await fixture.listener.channel?.sentTypes.last == .error)
    }

    @Test("An invalid remote proof never asks the user or commits")
    func invalidProof() async {
        let fixture = Fixture(proofIsValid: false)

        await #expect(throws: PairingError.invalidProof) {
            try await fixture.session.execute(host: "192.0.2.10")
        }

        #expect(await fixture.confirmation.requests.isEmpty)
        #expect(await fixture.committer.committedPeers.isEmpty)
    }

    @Test("A lost CommitAck remains failed after local durable trust")
    func lostCommitAck() async {
        let fixture = Fixture(validCommitAck: false)

        await #expect(throws: PairingError.invalidTranscript) {
            try await fixture.session.execute(host: "192.0.2.10")
        }

        #expect(await fixture.committer.committedPeers == [fixture.remoteDescriptor])
        #expect(await fixture.events.phases == ["qr", "waiting"])
    }

    private final class Fixture: @unchecked Sendable {
        let remoteDescriptor: PairingDeviceDescriptor
        let listener: PairingListenerFake
        let cryptography: PairingCryptographyFake
        let committer = PairingCommitterSpy()
        let confirmation: PairingConfirmationFake
        let events = PairingEventRecorder()
        let session: PairingResponderSession

        init(
            localAccepted: Bool = true,
            proofIsValid: Bool = true,
            validCommitAck: Bool = true
        ) {
            let localDescriptor = try! PairingDeviceDescriptor(
                canonicalSPKI: Data(repeating: 1, count: 91),
                displayName: "Mac",
                platform: "macos"
            )
            remoteDescriptor = try! PairingDeviceDescriptor(
                canonicalSPKI: Data(repeating: 2, count: 91),
                displayName: "Pixel",
                platform: "android"
            )
            let localWire = PairingWireDevice(
                certificateDER: Data(repeating: 3, count: 128),
                canonicalSPKI: localDescriptor.canonicalSPKI,
                displayName: localDescriptor.displayName,
                platform: localDescriptor.platform
            )
            let remoteWire = PairingWireDevice(
                certificateDER: Data(repeating: 4, count: 128),
                canonicalSPKI: remoteDescriptor.canonicalSPKI,
                displayName: remoteDescriptor.displayName,
                platform: remoteDescriptor.platform
            )
            listener = PairingListenerFake(remoteWire: remoteWire, validCommitAck: validCommitAck)
            cryptography = PairingCryptographyFake(
                identity: PairingResponderIdentity(
                    device: localWire,
                    certificateSHA256: Data(repeating: 5, count: 32)
                ),
                remote: remoteDescriptor,
                proofIsValid: proofIsValid
            )
            confirmation = PairingConfirmationFake(accepted: localAccepted)
            session = PairingResponderSession(
                listening: PairingListeningFake(listener: listener),
                cryptography: cryptography,
                committer: committer,
                confirmation: confirmation,
                events: events,
                clock: FixedPairingClock(),
                random: FixedPairingRandom()
            )
        }
    }
}

private struct PairingListeningFake: PairingResponderListening {
    let listener: PairingListenerFake
    func listen() async throws -> PairingResponderAdmission {
        PairingResponderAdmission(port: 8443, listener: listener)
    }
}

private actor PairingListenerFake: PairingResponderListener {
    let remoteWire: PairingWireDevice
    let validCommitAck: Bool
    private(set) var channel: PairingChannelFake?
    private(set) var closed = false

    init(remoteWire: PairingWireDevice, validCommitAck: Bool) {
        self.remoteWire = remoteWire
        self.validCommitAck = validCommitAck
    }

    func accept(payload: PairingQRPayload) async throws -> any PairingResponderChannel {
        let value = PairingChannelFake(payload: payload, remoteWire: remoteWire, validCommitAck: validCommitAck)
        channel = value
        return value
    }

    func close() async { closed = true }
}

private actor PairingChannelFake: PairingResponderChannel {
    let payload: PairingQRPayload
    let remoteWire: PairingWireDevice
    let validCommitAck: Bool
    private var receiveIndex = 0
    private var sent: [PairingWireMessage] = []
    private(set) var closed = false

    init(payload: PairingQRPayload, remoteWire: PairingWireDevice, validCommitAck: Bool) {
        self.payload = payload
        self.remoteWire = remoteWire
        self.validCommitAck = validCommitAck
    }

    var sentTypes: [PairingRecordType] { sent.map(\.type) }

    func send(_ message: PairingWireMessage) async throws { sent.append(message) }

    func receive() async throws -> PairingWireMessage {
        defer { receiveIndex += 1 }
        switch receiveIndex {
        case 0:
            return .clientStart(
                sessionID: payload.sessionID,
                token: payload.token,
                device: remoteWire,
                nonce: Data(repeating: 6, count: 32)
            )
        case 1:
            return .clientProof(signature: Data(repeating: 7, count: 64))
        case 2:
            return .clientDecision(accepted: true, signature: Data(repeating: 8, count: 64))
        default:
            guard let receipt = sent.last, receipt.type == .commitReceipt else {
                throw PairingError.invalidTranscript
            }
            let digest = Data(SHA256.hash(data: PairingWireCodec.encode(receipt)))
            return .commitAck(receiptSHA256: validCommitAck ? digest : Data(repeating: 0, count: 32))
        }
    }

    func close() async { closed = true }
}

private actor PairingCryptographyFake: PairingResponderCryptography {
    let identity: PairingResponderIdentity
    let remote: PairingDeviceDescriptor
    let proofIsValid: Bool

    init(identity: PairingResponderIdentity, remote: PairingDeviceDescriptor, proofIsValid: Bool) {
        self.identity = identity
        self.remote = remote
        self.proofIsValid = proofIsValid
    }

    func localIdentity() async throws -> PairingResponderIdentity { identity }
    func validateRemote(_ device: PairingWireDevice) async throws -> PairingDeviceDescriptor { remote }
    func sign(_ digest: Data) async throws -> Data { Data(repeating: digest.first ?? 0, count: 64) }
    func verify(device: PairingWireDevice, digest: Data, signature: Data) async -> Bool { proofIsValid }
}

private actor PairingCommitterSpy: PairingPeerCommitting {
    private(set) var committedPeers: [PairingDeviceDescriptor] = []
    func commit(_ peer: PairingDeviceDescriptor, at date: Date) async throws { committedPeers.append(peer) }
}

private actor PairingConfirmationFake: PairingConfirming {
    let accepted: Bool
    private(set) var requests: [PairingConfirmationRequest] = []
    init(accepted: Bool) { self.accepted = accepted }
    func confirm(_ request: PairingConfirmationRequest) async -> Bool {
        requests.append(request)
        return accepted
    }
}

private actor PairingEventRecorder: PairingExecutionEventSink {
    private(set) var phases: [String] = []
    func emit(_ event: PairingExecutionEvent) async throws {
        switch event {
        case .qrIssued: phases.append("qr")
        case .waitingForRemoteCommit: phases.append("waiting")
        case .completed: phases.append("completed")
        }
    }
}

private struct FixedPairingClock: PairingExecutionClock {
    func now() -> Date { Date(timeIntervalSince1970: 1_000) }
}

private struct FixedPairingRandom: PairingRandomGenerating {
    func uuid() -> UUID { UUID(uuidString: "00000000-0000-0000-0000-000000000077")! }
    func data(count: Int) -> Data { Data(repeating: 9, count: count) }
}
