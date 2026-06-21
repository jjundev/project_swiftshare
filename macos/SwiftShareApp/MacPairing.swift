import AppKit
import Combine
import CryptoKit
import Darwin
import Foundation
import Network
import Security
import SwiftShareDomain

enum MacPairingViewState: Equatable {
    case idle
    case waiting(qr: String, expiresAt: Date)
    case confirming(name: String, platform: String, code: String, keyID: String)
    case waitingForAndroid
    case paired(name: String)
    case failed(String)
}

@MainActor
final class MacPairingModel: ObservableObject {
    @Published private(set) var state: MacPairingViewState = .idle
    @Published private(set) var peers: [MacStoredPeer] = []
    private var task: Task<Void, Never>?
    private var approval: CheckedContinuation<Bool, Never>?
    private let repository = MacTrustRepository.shared

    init() {
        if (try? MacDevelopmentIdentityStore().loadIdentity()) == nil {
            Task { try? await repository.clear(); await MainActor.run { self.refreshPeers() } }
        } else { refreshPeers() }
    }

    func start() {
        task?.cancel()
        task = Task {
            do {
                let service = MacPairingService(repository: repository)
                let peer = try await service.run(
                    host: try Self.localIPv4Address(),
                    onQR: { [weak self] payload in
                        let qr = try payload.encodedURI()
                        await MainActor.run { self?.state = .waiting(qr: qr, expiresAt: payload.expiresAt) }
                    },
                    confirm: { [weak self] descriptor, code in
                        await withCheckedContinuation { continuation in
                            Task { @MainActor in
                                guard let self else { continuation.resume(returning: false); return }
                                self.approval = continuation
                                self.state = .confirming(
                                    name: descriptor.displayName, platform: descriptor.platform,
                                    code: code, keyID: descriptor.keyID.hexString
                                )
                            }
                        }
                    },
                    waitingForRemote: { [weak self] in await MainActor.run { self?.state = .waitingForAndroid } }
                )
                state = .paired(name: peer.displayName); refreshPeers()
            } catch is CancellationError { state = .idle }
            catch { state = .failed((error as? PairingError)?.rawValue ?? error.localizedDescription) }
        }
    }

    func approve() { approval?.resume(returning: true); approval = nil }
    func reject() { approval?.resume(returning: false); approval = nil }
    func cancel() { approval?.resume(returning: false); approval = nil; task?.cancel(); task = nil; state = .idle }
    func unpair(_ peer: MacStoredPeer, onRevoked: @escaping @MainActor () -> Void = {}) {
        Task {
            do {
                guard try await repository.remove(id: peer.id) else { return }
                onRevoked()
                refreshPeers()
            } catch {
                state = .failed("peer_storage_error")
            }
        }
    }
    func setApprovalPolicy(_ peer: MacStoredPeer, autoAccept: Bool) {
        Task {
            do {
                _ = try await repository.setApprovalPolicy(
                    id: peer.id,
                    policy: autoAccept ? .autoAccept : .requireApproval
                )
                refreshPeers()
            } catch {
                state = .failed("peer_storage_error")
            }
        }
    }
    func refreshPeers() {
        Task {
            do { peers = try await repository.peers() }
            catch { state = .failed("peer_storage_error") }
        }
    }

    private static func localIPv4Address() throws -> String {
        var pointer: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&pointer) == 0, let first = pointer else { throw PairingError.invalidQR }
        defer { freeifaddrs(pointer) }
        var current: UnsafeMutablePointer<ifaddrs>? = first
        var fallback: String?
        while let item = current {
            defer { current = item.pointee.ifa_next }
            guard item.pointee.ifa_addr.pointee.sa_family == UInt8(AF_INET) else { continue }
            let name = String(cString: item.pointee.ifa_name)
            guard name != "lo0" else { continue }
            var address = [CChar](repeating: 0, count: Int(NI_MAXHOST))
            let result = getnameinfo(item.pointee.ifa_addr, socklen_t(item.pointee.ifa_addr.pointee.sa_len), &address, socklen_t(address.count), nil, 0, NI_NUMERICHOST)
            guard result == 0 else { continue }
            let value = String(cString: address)
            if name == "en0" { return value }
            fallback = fallback ?? value
        }
        guard let fallback else { throw PairingError.invalidQR }
        return fallback
    }
}

final class MacPairingService: @unchecked Sendable {
    private let identityStore = MacDevelopmentIdentityStore()
    private let repository: MacTrustRepository
    private let queue = DispatchQueue(label: "dev.swiftshare.macos.pairing")
    init(repository: MacTrustRepository) { self.repository = repository }

    func run(
        host: String,
        onQR: @escaping @Sendable (PairingQRPayload) async throws -> Void,
        confirm: @escaping @Sendable (PairingDeviceDescriptor, String) async -> Bool,
        waitingForRemote: @escaping @Sendable () async -> Void
    ) async throws -> MacStoredPeer {
        let identity = try identityStore.loadIdentity()
        let certificateDER = try identityStore.certificateData(for: identity)
        let spki = try identityStore.canonicalSPKI(for: identity)
        let tls = NWProtocolTLS.Options()
        sec_protocol_options_set_min_tls_protocol_version(tls.securityProtocolOptions, .TLSv13)
        sec_protocol_options_set_max_tls_protocol_version(tls.securityProtocolOptions, .TLSv13)
        guard let localIdentity = sec_identity_create(identity) else { throw MacTransferError.identityUnavailable }
        sec_protocol_options_set_local_identity(tls.securityProtocolOptions, localIdentity)
        let listener = try NWListener(using: NWParameters(tls: tls, tcp: NWProtocolTCP.Options()))
        let connections = AsyncStream<NWConnection> { continuation in
            listener.newConnectionHandler = { continuation.yield($0) }
            continuation.onTermination = { _ in listener.cancel() }
        }
        try await waitUntilReady(listener)
        guard let port = listener.port?.rawValue else { throw PairingError.invalidQR }
        let token = Data((0..<32).map { _ in UInt8.random(in: .min ... .max) })
        let payload = try PairingQRPayload(
            sessionID: UUID(), host: host, port: port,
            certificateSHA256: Data(SHA256.hash(data: certificateDER)), token: token,
            expiresAt: Date().addingTimeInterval(PairingLimits().admissionDuration)
        )
        let session = PairingSession(payload: payload)
        try await onQR(payload)
        guard let connection = await connections.first(where: { _ in true }) else { throw PairingError.cancelled }
        defer { listener.cancel(); connection.cancel() }
        let channel = MacPairingChannel(connection: connection, queue: queue)
        try await channel.connect()
        guard case .clientStart(let sessionID, let presentedToken, let androidWire, let clientNonce) = try await channel.receive() else {
            throw PairingError.invalidTranscript
        }
        try await session.admit(sessionID: sessionID, token: presentedToken, now: Date())
        let androidDescriptor = try androidWire.descriptor
        guard certificateSPKI(androidWire.certificateDER) == androidDescriptor.canonicalSPKI else { throw PairingError.invalidIdentity }
        let macDescriptor = try PairingDeviceDescriptor(
            canonicalSPKI: spki,
            displayName: String((Host.current().localizedName ?? "Mac").prefix(128)),
            platform: "macos"
        )
        let macWire = PairingWireDevice(certificateDER: certificateDER, canonicalSPKI: spki, displayName: macDescriptor.displayName, platform: macDescriptor.platform)
        let serverNonce = Data((0..<32).map { _ in UInt8.random(in: .min ... .max) })
        try await channel.send(.serverChallenge(device: macWire, nonce: serverNonce))
        let transcript = try PairingTranscript(
            sessionID: payload.sessionID, token: token, certificateSHA256: payload.certificateSHA256,
            clientNonce: clientNonce, serverNonce: serverNonce, mac: macDescriptor, android: androidDescriptor
        )
        guard case .clientProof(let clientSignature) = try await channel.receive(),
              verifyP1363(certificateDER: androidWire.certificateDER, digest: transcript.proofDigest(for: .android), signature: clientSignature)
        else { throw PairingError.invalidProof }
        try await session.claim(candidateKeyID: androidDescriptor.keyID, proofIsValid: true, now: Date())
        try await channel.send(.serverProof(signature: try identityStore.signP1363(transcript.proofDigest(for: .mac), identity: identity)))
        let localAccepted = await confirm(androidDescriptor, transcript.comparisonCode)
        guard case .clientDecision(let remoteAccepted, let decisionSignature) = try await channel.receive() else { throw PairingError.invalidTranscript }
        let remoteDecisionDigest = macDecisionDigest(transcript: transcript.digest, accepted: remoteAccepted)
        guard verifyP1363(certificateDER: androidWire.certificateDER, digest: remoteDecisionDigest, signature: decisionSignature) else { throw PairingError.invalidProof }
        guard localAccepted, remoteAccepted else { await session.cancel(); try await channel.send(.error(code: "user_rejected")); throw PairingError.cancelled }
        await waitingForRemote()
        try await session.requireConfirmation(candidateKeyID: androidDescriptor.keyID, now: Date())
        let peer = try await repository.upsert(androidDescriptor)
        try await session.commit(candidateKeyID: androidDescriptor.keyID, now: Date())
        let committedAt = Date()
        let unsigned = PairingWireMessage.commitReceipt(
            sessionID: payload.sessionID, transcriptSHA256: transcript.digest,
            macKeyID: macDescriptor.keyID, androidKeyID: androidDescriptor.keyID,
            committedAt: committedAt, signature: Data(repeating: 0, count: 64)
        )
        guard case .commitReceipt(let rid, let td, let mk, let ak, let date, _) = unsigned else { throw PairingError.invalidTranscript }
        let digest = macReceiptDigest(sessionID: rid, transcript: td, mac: mk, android: ak, date: date)
        let receipt = PairingWireMessage.commitReceipt(
            sessionID: rid, transcriptSHA256: td, macKeyID: mk, androidKeyID: ak,
            committedAt: date, signature: try identityStore.signP1363(digest, identity: identity)
        )
        try await channel.send(receipt)
        guard case .commitAck(let ack) = try await channel.receive(),
              ack == Data(SHA256.hash(data: PairingWireCodec.encode(receipt)))
        else { throw PairingError.invalidTranscript }
        return peer
    }

    private func waitUntilReady(_ listener: NWListener) async throws {
        try await withCheckedThrowingContinuation { continuation in
            let gate = PairingContinuationGate<Void>(continuation)
            listener.stateUpdateHandler = { state in
                switch state { case .ready: gate.resume(()); case .failed(let error): gate.fail(error); case .cancelled: gate.fail(PairingError.cancelled); default: break }
            }
            listener.start(queue: queue)
        }
    }
    private func certificateSPKI(_ der: Data) -> Data? {
        guard let certificate = SecCertificateCreateWithData(nil, der as CFData), let key = SecCertificateCopyKey(certificate) else { return nil }
        return try? MacDevelopmentIdentityStore.canonicalSPKI(for: key)
    }
}

private final class MacPairingChannel: @unchecked Sendable {
    let connection: NWConnection; let queue: DispatchQueue
    init(connection: NWConnection, queue: DispatchQueue) { self.connection = connection; self.queue = queue }
    func connect() async throws { try await withCheckedThrowingContinuation { continuation in let gate=PairingContinuationGate<Void>(continuation); connection.stateUpdateHandler={ state in switch state { case .ready: gate.resume(()); case .failed(let e): gate.fail(e); case .cancelled: gate.fail(PairingError.cancelled); default: break } }; connection.start(queue: queue) } }
    func send(_ message: PairingWireMessage) async throws { let payload=PairingWireCodec.encode(message); guard payload.count<=65_536 else{throw PairingError.oversized}; var length=UInt32(payload.count).bigEndian; let data=Data([message.type.rawValue])+Data(bytes:&length,count:4)+payload; try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in connection.send(content:data,completion:.contentProcessed{ error in if let error{continuation.resume(throwing:error)}else{continuation.resume()} }) } }
    func receive() async throws -> PairingWireMessage { let header=try await exactly(5); guard let type=PairingRecordType(rawValue:header[0]) else{throw PairingError.invalidTranscript}; let length=header.dropFirst().reduce(UInt32(0)){($0<<8)|UInt32($1)}; guard length<=65_536 else{throw PairingError.oversized}; return try PairingWireCodec.decode(type:type,data:try await exactly(Int(length))) }
    private func exactly(_ count:Int) async throws -> Data { var result=Data(); while result.count<count { let remaining=count-result.count; let next=try await withCheckedThrowingContinuation { (continuation:CheckedContinuation<Data,Error>) in connection.receive(minimumIncompleteLength:1,maximumLength:remaining){ data,_,complete,error in if let error{continuation.resume(throwing:error)}else if let data,!data.isEmpty{continuation.resume(returning:data)}else if complete{continuation.resume(throwing:PairingError.cancelled)}else{continuation.resume(throwing:PairingError.invalidTranscript)} } }; result.append(next) }; return result }
}

private final class PairingContinuationGate<Value: Sendable>: @unchecked Sendable {
    private let lock=NSLock(); private var continuation:CheckedContinuation<Value,Error>?
    init(_ continuation:CheckedContinuation<Value,Error>){self.continuation=continuation}
    func resume(_ value:Value){lock.withLock{continuation?.resume(returning:value);continuation=nil}}
    func fail(_ error:Error){lock.withLock{continuation?.resume(throwing:error);continuation=nil}}
}

private func macDecisionDigest(transcript:Data,accepted:Bool)->Data{Data(SHA256.hash(data:Data("SwiftShare-Pairing-v1-decision-\(accepted ? "accept":"reject")".utf8)+transcript))}
private func macReceiptDigest(sessionID:UUID,transcript:Data,mac:Data,android:Data,date:Date)->Data{var seconds=UInt64(date.timeIntervalSince1970).bigEndian;var uuid=sessionID.uuid;return Data(SHA256.hash(data:Data("SwiftShare-Pairing-v1-receipt".utf8)+Data(bytes:&uuid,count:16)+transcript+mac+android+Data(bytes:&seconds,count:8)))}

private extension Data { var hexString:String{map{String(format:"%02x",$0)}.joined()} }
