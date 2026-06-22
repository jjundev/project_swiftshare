import CryptoKit
import Foundation

public protocol PairingResponderChannel: Sendable {
    func send(_ message: PairingWireMessage) async throws
    func receive() async throws -> PairingWireMessage
    func close() async
}

public protocol PairingResponderListener: Sendable {
    func accept(payload: PairingQRPayload) async throws -> any PairingResponderChannel
    func close() async
}

public struct PairingResponderAdmission: Sendable {
    public let port: UInt16
    public let listener: any PairingResponderListener

    public init(port: UInt16, listener: any PairingResponderListener) {
        precondition(port > 0)
        self.port = port
        self.listener = listener
    }
}

public protocol PairingResponderListening: Sendable {
    func listen() async throws -> PairingResponderAdmission
}

public struct PairingResponderIdentity: Sendable {
    public let device: PairingWireDevice
    public let certificateSHA256: Data

    public init(device: PairingWireDevice, certificateSHA256: Data) {
        precondition(certificateSHA256.count == 32)
        self.device = device
        self.certificateSHA256 = certificateSHA256
    }
}

public protocol PairingResponderCryptography: Sendable {
    func localIdentity() async throws -> PairingResponderIdentity
    func validateRemote(_ device: PairingWireDevice) async throws -> PairingDeviceDescriptor
    func sign(_ digest: Data) async throws -> Data
    func verify(device: PairingWireDevice, digest: Data, signature: Data) async -> Bool
}

public protocol PairingPeerCommitting: Sendable {
    func commit(_ peer: PairingDeviceDescriptor, at date: Date) async throws
}

public struct PairingConfirmationRequest: Equatable, Sendable {
    public let peer: PairingDeviceDescriptor
    public let comparisonCode: String

    public init(peer: PairingDeviceDescriptor, comparisonCode: String) {
        self.peer = peer
        self.comparisonCode = comparisonCode
    }
}

public protocol PairingConfirming: Sendable {
    func confirm(_ request: PairingConfirmationRequest) async -> Bool
}

public enum PairingExecutionEvent: Equatable, Sendable {
    case qrIssued(PairingQRPayload)
    case waitingForRemoteCommit
    case completed(PairingDeviceDescriptor)
}

public protocol PairingExecutionEventSink: Sendable {
    func emit(_ event: PairingExecutionEvent) async throws
}

public struct PairingExecutionOutcome: Equatable, Sendable {
    public let peer: PairingDeviceDescriptor
    public init(peer: PairingDeviceDescriptor) { self.peer = peer }
}

public protocol PairingExecutionClock: Sendable {
    func now() -> Date
}

public protocol PairingRandomGenerating: Sendable {
    func uuid() -> UUID
    func data(count: Int) -> Data
}

public struct SystemPairingExecutionClock: PairingExecutionClock {
    public init() {}
    public func now() -> Date { Date() }
}

public struct SystemPairingRandomGenerator: PairingRandomGenerating {
    public init() {}
    public func uuid() -> UUID { UUID() }
    public func data(count: Int) -> Data {
        var generator = SystemRandomNumberGenerator()
        return Data((0 ..< count).map { _ in UInt8.random(in: .min ... .max, using: &generator) })
    }
}

public actor PairingResponderSession {
    private let listening: any PairingResponderListening
    private let cryptography: any PairingResponderCryptography
    private let committer: any PairingPeerCommitting
    private let confirmation: any PairingConfirming
    private let events: any PairingExecutionEventSink
    private let clock: any PairingExecutionClock
    private let random: any PairingRandomGenerating
    private let limits: PairingLimits

    public init(
        listening: any PairingResponderListening,
        cryptography: any PairingResponderCryptography,
        committer: any PairingPeerCommitting,
        confirmation: any PairingConfirming,
        events: any PairingExecutionEventSink,
        clock: any PairingExecutionClock = SystemPairingExecutionClock(),
        random: any PairingRandomGenerating = SystemPairingRandomGenerator(),
        limits: PairingLimits = PairingLimits()
    ) {
        self.listening = listening
        self.cryptography = cryptography
        self.committer = committer
        self.confirmation = confirmation
        self.events = events
        self.clock = clock
        self.random = random
        self.limits = limits
    }

    public func execute(host: String) async throws -> PairingExecutionOutcome {
        let identity = try await cryptography.localIdentity()
        let localDescriptor = try identity.device.descriptor
        let admission = try await listening.listen()
        let payload = try PairingQRPayload(
            sessionID: random.uuid(),
            host: host,
            port: admission.port,
            certificateSHA256: identity.certificateSHA256,
            token: random.data(count: 32),
            expiresAt: clock.now().addingTimeInterval(limits.admissionDuration)
        )
        let state = PairingSession(payload: payload, limits: limits)
        try await events.emit(.qrIssued(payload))

        let channel: any PairingResponderChannel
        do {
            channel = try await admission.listener.accept(payload: payload)
        } catch {
            await admission.listener.close()
            throw error
        }
        do {
            let outcome = try await run(
                channel: channel,
                payload: payload,
                state: state,
                identity: identity,
                localDescriptor: localDescriptor
            )
            await channel.close()
            await admission.listener.close()
            return outcome
        } catch {
            await channel.close()
            await admission.listener.close()
            throw error
        }
    }

    private func run(
        channel: any PairingResponderChannel,
        payload: PairingQRPayload,
        state: PairingSession,
        identity: PairingResponderIdentity,
        localDescriptor: PairingDeviceDescriptor
    ) async throws -> PairingExecutionOutcome {
        guard case .clientStart(let sessionID, let token, let remoteWire, let clientNonce) = try await channel.receive()
        else { throw PairingError.invalidTranscript }
        try await state.admit(sessionID: sessionID, token: token, now: clock.now())
        let remoteDescriptor = try await cryptography.validateRemote(remoteWire)
        let serverNonce = random.data(count: 32)
        try await channel.send(.serverChallenge(device: identity.device, nonce: serverNonce))
        let transcript = try PairingTranscript(
            sessionID: payload.sessionID,
            token: payload.token,
            certificateSHA256: payload.certificateSHA256,
            clientNonce: clientNonce,
            serverNonce: serverNonce,
            mac: localDescriptor,
            android: remoteDescriptor
        )

        guard case .clientProof(let remoteProof) = try await channel.receive(),
              await cryptography.verify(
                device: remoteWire,
                digest: transcript.proofDigest(for: .android),
                signature: remoteProof
              )
        else { throw PairingError.invalidProof }
        try await state.claim(candidateKeyID: remoteDescriptor.keyID, proofIsValid: true, now: clock.now())
        try await channel.send(.serverProof(signature: try await cryptography.sign(transcript.proofDigest(for: .mac))))

        let localAccepted = await confirmation.confirm(
            PairingConfirmationRequest(peer: remoteDescriptor, comparisonCode: transcript.comparisonCode)
        )
        guard case .clientDecision(let remoteAccepted, let remoteSignature) = try await channel.receive()
        else { throw PairingError.invalidTranscript }
        let remoteDecision = pairingDecisionDigest(transcript: transcript.digest, accepted: remoteAccepted)
        guard await cryptography.verify(device: remoteWire, digest: remoteDecision, signature: remoteSignature)
        else { throw PairingError.invalidProof }
        guard localAccepted, remoteAccepted else {
            await state.cancel()
            try await channel.send(.error(code: "user_rejected"))
            throw PairingError.cancelled
        }

        try await events.emit(.waitingForRemoteCommit)
        try await state.requireConfirmation(candidateKeyID: remoteDescriptor.keyID, now: clock.now())
        let committedAt = clock.now()
        do {
            try await committer.commit(remoteDescriptor, at: committedAt)
        } catch {
            throw PairingError.storage
        }
        try await state.commit(candidateKeyID: remoteDescriptor.keyID, now: committedAt)

        let receiptDigest = pairingReceiptDigest(
            sessionID: payload.sessionID,
            transcript: transcript.digest,
            mac: localDescriptor.keyID,
            android: remoteDescriptor.keyID,
            date: committedAt
        )
        let receipt = PairingWireMessage.commitReceipt(
            sessionID: payload.sessionID,
            transcriptSHA256: transcript.digest,
            macKeyID: localDescriptor.keyID,
            androidKeyID: remoteDescriptor.keyID,
            committedAt: committedAt,
            signature: try await cryptography.sign(receiptDigest)
        )
        try await channel.send(receipt)
        guard case .commitAck(let receivedDigest) = try await channel.receive(),
              receivedDigest == Data(SHA256.hash(data: PairingWireCodec.encode(receipt)))
        else { throw PairingError.invalidTranscript }

        try await events.emit(.completed(remoteDescriptor))
        return PairingExecutionOutcome(peer: remoteDescriptor)
    }
}

public func pairingDecisionDigest(transcript: Data, accepted: Bool) -> Data {
    Data(SHA256.hash(
        data: Data("SwiftShare-Pairing-v1-decision-\(accepted ? "accept" : "reject")".utf8) + transcript
    ))
}

public func pairingReceiptDigest(
    sessionID: UUID,
    transcript: Data,
    mac: Data,
    android: Data,
    date: Date
) -> Data {
    var seconds = UInt64(date.timeIntervalSince1970).bigEndian
    var uuid = sessionID.uuid
    return Data(SHA256.hash(
        data: Data("SwiftShare-Pairing-v1-receipt".utf8)
            + Data(bytes: &uuid, count: 16)
            + transcript
            + mac
            + android
            + Data(bytes: &seconds, count: 8)
    ))
}
