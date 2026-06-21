import AppKit
import Combine
import CryptoKit
import Foundation
import Network
import Security
import SwiftShareDomain

struct PreparedMacFile: Sendable {
    let url: URL
    let itemID: UUID
    let displayName: String
    let byteCount: UInt64
    let sha256: Data
    let modificationDate: Date?
}

struct MacTransferCompletion: Sendable {
    let artifactID: String
}

enum MacTransferError: LocalizedError {
    case invalidFile(String)
    case identityUnavailable
    case invalidPin
    case connection(String)
    case peerUnavailable
    case rejected(String)
    case remoteFailure(String)
    case busy
    case incompatibleVersion
    case protocolFailure(String)
    case sourceChanged
    case timeout(String)

    var errorDescription: String? {
        switch self {
        case .invalidFile(let value): value
        case .identityUnavailable: "Provision the Mac development identity before sending."
        case .invalidPin: "The Android SPKI pin must contain 64 hexadecimal characters."
        case .connection(let value): "Connection failed: \(value)"
        case .peerUnavailable: "The Android device is not currently available. Open SwiftShare or enable Receive Mode."
        case .rejected(let value): value.isEmpty ? "The Android receiver declined the transfer." : value
        case .remoteFailure(let value): "Android reported: \(value)"
        case .busy: "The other device is handling another transfer. Try again when it finishes."
        case .incompatibleVersion: "SwiftShare versions are incompatible. Update SwiftShare on both devices."
        case .protocolFailure(let value): "Protocol error: \(value)"
        case .sourceChanged: "The selected file changed after review. Select it again."
        case .timeout(let phase): "Timed out while \(phase)."
        }
    }
}

final class MacDevelopmentIdentityStore: @unchecked Sendable {
    static let commonName = "SwiftShare Mac Development"

    func loadIdentity() throws -> SecIdentity {
        let query: [CFString: Any] = [
            kSecClass: kSecClassIdentity,
            kSecReturnRef: true,
            kSecMatchLimit: kSecMatchLimitAll,
        ]
        var result: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        guard status == errSecSuccess else { throw MacTransferError.identityUnavailable }
        let identities: [SecIdentity]
        if let array = result as? [SecIdentity] {
            identities = array
        } else if let identity = result as! SecIdentity? {
            identities = [identity]
        } else {
            identities = []
        }
        for identity in identities {
            var certificate: SecCertificate?
            guard SecIdentityCopyCertificate(identity, &certificate) == errSecSuccess,
                  let certificate
            else { continue }
            var name: CFString?
            SecCertificateCopyCommonName(certificate, &name)
            if name as String? == Self.commonName { return identity }
        }
        throw MacTransferError.identityUnavailable
    }

    func spkiPin(for identity: SecIdentity) throws -> Data {
        var certificate: SecCertificate?
        guard SecIdentityCopyCertificate(identity, &certificate) == errSecSuccess,
              let certificate,
              let key = SecCertificateCopyKey(certificate)
        else { throw MacTransferError.identityUnavailable }
        return try Self.spkiPin(for: key)
    }

    func certificateData(for identity: SecIdentity) throws -> Data {
        var certificate: SecCertificate?
        guard SecIdentityCopyCertificate(identity, &certificate) == errSecSuccess, let certificate else {
            throw MacTransferError.identityUnavailable
        }
        return SecCertificateCopyData(certificate) as Data
    }

    func canonicalSPKI(for identity: SecIdentity) throws -> Data {
        var certificate: SecCertificate?
        guard SecIdentityCopyCertificate(identity, &certificate) == errSecSuccess,
              let certificate, let key = SecCertificateCopyKey(certificate)
        else { throw MacTransferError.identityUnavailable }
        return try Self.canonicalSPKI(for: key)
    }

    func signP1363(_ digest: Data, identity: SecIdentity) throws -> Data {
        guard digest.count == 32 else { throw PairingError.invalidTranscript }
        var key: SecKey?
        guard SecIdentityCopyPrivateKey(identity, &key) == errSecSuccess, let key else {
            throw MacTransferError.identityUnavailable
        }
        var error: Unmanaged<CFError>?
        guard let der = SecKeyCreateSignature(key, .ecdsaSignatureDigestX962SHA256, digest as CFData, &error) as Data? else {
            _ = error?.takeRetainedValue(); throw PairingError.invalidProof
        }
        return try derEcdsaToP1363(der)
    }

    static func spkiPin(for key: SecKey) throws -> Data {
        Data(SHA256.hash(data: try canonicalSPKI(for: key)))
    }

    static func canonicalSPKI(for key: SecKey) throws -> Data {
        var error: Unmanaged<CFError>?
        guard let external = SecKeyCopyExternalRepresentation(key, &error) as Data? else {
            _ = error?.takeRetainedValue()
            throw MacTransferError.invalidPin
        }
        // P-256 SubjectPublicKeyInfo prefix followed by the ANSI X9.63 point.
        let prefix = try "3059301306072a8648ce3d020106082a8648ce3d030107034200".transferHexData
        guard external.count == 65, external.first == 0x04 else { throw MacTransferError.invalidPin }
        return prefix + external
    }
}

func verifyP1363(certificateDER: Data, digest: Data, signature: Data) -> Bool {
    guard digest.count == 32, signature.count == 64,
          let certificate = SecCertificateCreateWithData(nil, certificateDER as CFData),
          let key = SecCertificateCopyKey(certificate),
          let der = try? p1363ToDerEcdsa(signature)
    else { return false }
    var error: Unmanaged<CFError>?
    let valid = SecKeyVerifySignature(key, .ecdsaSignatureDigestX962SHA256, digest as CFData, der as CFData, &error)
    _ = error?.takeRetainedValue()
    return valid
}

private func derEcdsaToP1363(_ der: Data) throws -> Data {
    let bytes = [UInt8](der); guard bytes.count >= 8, bytes[0] == 0x30 else { throw PairingError.invalidProof }
    var index = 2; guard bytes[index] == 0x02 else { throw PairingError.invalidProof }; index += 1
    let rLength = Int(bytes[index]); index += 1; guard index + rLength < bytes.count else { throw PairingError.invalidProof }
    let r = Array(bytes[index..<index+rLength]); index += rLength
    guard bytes[index] == 0x02 else { throw PairingError.invalidProof }; index += 1
    let sLength = Int(bytes[index]); index += 1; guard index + sLength <= bytes.count else { throw PairingError.invalidProof }
    let s = Array(bytes[index..<index+sLength])
    func normalized(_ value: [UInt8]) throws -> Data {
        let stripped = Array(value.drop(while: { $0 == 0 })); guard stripped.count <= 32 else { throw PairingError.invalidProof }
        return Data(repeating: 0, count: 32 - stripped.count) + Data(stripped)
    }
    return try normalized(r) + normalized(s)
}

private func p1363ToDerEcdsa(_ value: Data) throws -> Data {
    guard value.count == 64 else { throw PairingError.invalidProof }
    func integer(_ bytes: Data) -> Data {
        var body = Data(bytes.drop(while: { $0 == 0 })); if body.isEmpty { body = Data([0]) }
        if body.first! & 0x80 != 0 { body.insert(0, at: 0) }
        return Data([0x02, UInt8(body.count)]) + body
    }
    let r = integer(value.prefix(32)); let s = integer(value.suffix(32))
    return Data([0x30, UInt8(r.count + s.count)]) + r + s
}

final class NWTLSRecordChannel: @unchecked Sendable {
    private let connection: NWConnection
    private let queue = DispatchQueue(label: "dev.swiftshare.macos.fixed-endpoint")

    convenience init(host: String, port: UInt16, identity: SecIdentity, peerSPKIPin: Data) throws {
        guard let networkPort = NWEndpoint.Port(rawValue: port), peerSPKIPin.count == 32 else {
            throw MacTransferError.invalidPin
        }
        try self.init(endpoint: .hostPort(host: NWEndpoint.Host(host), port: networkPort), identity: identity, peerSPKIPin: peerSPKIPin)
    }

    init(endpoint: NWEndpoint, identity: SecIdentity, peerSPKIPin: Data) throws {
        guard peerSPKIPin.count == 32 else { throw MacTransferError.invalidPin }
        let tls = NWProtocolTLS.Options()
        sec_protocol_options_set_min_tls_protocol_version(tls.securityProtocolOptions, .TLSv13)
        sec_protocol_options_set_max_tls_protocol_version(tls.securityProtocolOptions, .TLSv13)
        guard let localIdentity = sec_identity_create(identity) else {
            throw MacTransferError.identityUnavailable
        }
        sec_protocol_options_set_local_identity(tls.securityProtocolOptions, localIdentity)
        let verifyQueue = queue
        sec_protocol_options_set_verify_block(
            tls.securityProtocolOptions,
            { _, trust, complete in
                let trustRef = sec_trust_copy_ref(trust).takeRetainedValue()
                guard let chain = SecTrustCopyCertificateChain(trustRef) as? [SecCertificate],
                      let certificate = chain.first,
                      let key = SecCertificateCopyKey(certificate)
                else {
                    complete(false)
                    return
                }
                SecTrustSetAnchorCertificates(trustRef, [certificate] as CFArray)
                SecTrustSetAnchorCertificatesOnly(trustRef, true)
                var trustError: CFError?
                let valid = SecTrustEvaluateWithError(trustRef, &trustError)
                let pin = try? MacDevelopmentIdentityStore.spkiPin(for: key)
                complete(valid && pin == peerSPKIPin)
            },
            verifyQueue
        )
        let parameters = NWParameters(tls: tls, tcp: NWProtocolTCP.Options())
        connection = NWConnection(to: endpoint, using: parameters)
    }

    func connect() async throws {
        try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
                let gate = ContinuationGate<Void>(continuation)
                connection.stateUpdateHandler = { state in
                    switch state {
                    case .ready:
                        gate.resume(returning: ())
                    case .failed(let error), .waiting(let error):
                        gate.resume(throwing: MacTransferError.connection(error.localizedDescription))
                    case .cancelled:
                        gate.resume(throwing: CancellationError())
                    default:
                        break
                    }
                }
                connection.start(queue: queue)
            }
        } onCancel: {
            self.connection.cancel()
        }
    }

    func send(_ data: Data) async throws {
        try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
                connection.send(content: data, completion: .contentProcessed { error in
                    if let error {
                        continuation.resume(throwing: MacTransferError.connection(error.localizedDescription))
                    } else {
                        continuation.resume()
                    }
                })
            }
        } onCancel: {
            self.connection.cancel()
        }
    }

    func sendRecord(
        type: TransferRecordType,
        payload: Data,
        sessionID: UUID,
        protocolMajor: UInt16 = 1,
        protocolMinor: UInt16 = 0
    ) async throws {
        try await send(TransferRecordHeader(
            type: type,
            payloadLength: payload.count,
            sessionID: sessionID,
            protocolMajor: protocolMajor,
            protocolMinor: protocolMinor
        ).encoded())
        if !payload.isEmpty { try await send(payload) }
    }

    func receiveRecord() async throws -> TransferWireRecord {
        let headerData = try await receiveExactly(transferHeaderSize)
        let header = try TransferRecordHeader.decode(headerData)
        let payload = try await receiveExactly(header.payloadLength)
        return TransferWireRecord(header: header, payload: payload)
    }

    func cancel() { connection.cancel() }

    private func receiveExactly(_ count: Int) async throws -> Data {
        var result = Data()
        while result.count < count {
            let remaining = count - result.count
            let next = try await withTaskCancellationHandler {
                try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Data, Error>) in
                    connection.receive(minimumIncompleteLength: 1, maximumLength: remaining) {
                        data, _, complete, error in
                        if let error {
                            continuation.resume(throwing: MacTransferError.connection(error.localizedDescription))
                        } else if let data, !data.isEmpty {
                            continuation.resume(returning: data)
                        } else if complete {
                            continuation.resume(throwing: MacTransferError.connection("connection closed"))
                        } else {
                            continuation.resume(throwing: MacTransferError.connection("empty network read"))
                        }
                    }
                }
            } onCancel: {
                self.connection.cancel()
            }
            result.append(next)
        }
        return result
    }
}

final class MacFixedEndpointSender: @unchecked Sendable {
    private let identityStore = MacDevelopmentIdentityStore()
    private let activeLock = NSLock()
    private var activePair: (channel: NWTLSRecordChannel, sessionID: UUID, version: NegotiatedProtocolVersion?, peerID: String)?
    private var scheduler: TransferSessionScheduler?

    func localSPKIPin() throws -> String {
        try identityStore.spkiPin(for: identityStore.loadIdentity()).hexString
    }

    func prepare(_ url: URL) async throws -> PreparedMacFile {
        try await Task.detached(priority: .userInitiated) {
            let access = url.startAccessingSecurityScopedResource()
            defer { if access { url.stopAccessingSecurityScopedResource() } }
            let values = try url.resourceValues(forKeys: [
                .isRegularFileKey, .isSymbolicLinkKey, .fileSizeKey, .contentModificationDateKey,
            ])
            guard values.isRegularFile == true, values.isSymbolicLink != true, let size = values.fileSize, size >= 0 else {
                throw MacTransferError.invalidFile("Select one regular file; folders and symbolic links are not supported.")
            }
            let handle = try FileHandle(forReadingFrom: url)
            defer { try? handle.close() }
            var hasher = SHA256()
            while let data = try handle.read(upToCount: 1024 * 1024), !data.isEmpty {
                try Task.checkCancellation()
                hasher.update(data: data)
            }
            return PreparedMacFile(
                url: url,
                itemID: UUID(),
                displayName: url.lastPathComponent,
                byteCount: UInt64(size),
                sha256: Data(hasher.finalize()),
                modificationDate: values.contentModificationDate
            )
        }.value
    }

    func send(
        _ file: PreparedMacFile,
        host: String,
        port: UInt16 = 8443,
        androidSPKIPinHex: String,
        connectedChannel: NWTLSRecordChannel? = nil,
        endpointTicket: Data? = nil,
        connectionResolver: (@Sendable () async throws -> MacConnectedPeer)? = nil,
        event: @escaping @Sendable (TransferSessionEvent) async -> Void
    ) async throws -> MacTransferCompletion {
        let pin = try androidSPKIPinHex.transferHexData
        guard pin.count == 32 else { throw MacTransferError.invalidPin }
        let identity = try identityStore.loadIdentity()
        let localDeviceID = try identityStore.spkiPin(for: identity)
        let scheduler = activeLock.withLock {
            if let scheduler = self.scheduler { return scheduler }
            let value = TransferSessionScheduler(localDeviceID: localDeviceID)
            self.scheduler = value
            return value
        }
        let sessionID = UUID()
        guard let lease = await scheduler.reserveOutbound(peerID: pin, sessionID: sessionID) else {
            throw MacTransferError.busy
        }
        do {
            let resolved: MacConnectedPeer?
            if let connectionResolver {
                do {
                    resolved = try await withTransferTimeout(.seconds(5), phase: "locating the Android receiver") {
                        try await connectionResolver()
                    }
                } catch is CancellationError {
                    throw CancellationError()
                } catch {
                    throw MacTransferError.peerUnavailable
                }
            } else {
                resolved = nil
            }
            let channel = try resolved?.channel ?? connectedChannel ?? NWTLSRecordChannel(host: host, port: port, identity: identity, peerSPKIPin: pin)
            let effectiveTicket = resolved?.endpointTicket ?? endpointTicket
            activeLock.withLock { activePair = (channel, sessionID, nil, androidSPKIPinHex) }
            let completion = try await withTaskCancellationHandler {
            let access = file.url.startAccessingSecurityScopedResource()
            defer {
                if access { file.url.stopAccessingSecurityScopedResource() }
                channel.cancel()
                activeLock.withLock { activePair = nil }
            }
            try validate(file)
            await event(file.event(.connecting))
            if connectedChannel == nil && resolved == nil {
                try await withTransferTimeout(.seconds(15), phase: "connecting") {
                    try await channel.connect()
                }
            }
            let hello = SessionHello(offer: .default, endpointTicket: effectiveTicket)
            try await withTransferTimeout(.seconds(10), phase: "sending session capabilities") {
                try await channel.sendRecord(
                    type: .sessionHello,
                    payload: SessionBootstrapCodec.encode(hello),
                    sessionID: sessionID,
                    protocolMajor: 0,
                    protocolMinor: 0
                )
            }
            let decisionRecord = try await withTransferTimeout(.seconds(10), phase: "negotiating session capabilities") {
                try await channel.receiveRecord()
            }
            guard decisionRecord.header.sessionID == sessionID,
                  decisionRecord.header.type == .sessionDecision,
                  decisionRecord.header.protocolMajor == 0,
                  decisionRecord.header.protocolMinor == 0
            else { throw MacTransferError.protocolFailure("session decision required") }
            let decision = try SessionBootstrapCodec.decodeDecision(decisionRecord.payload)
            guard decision.accepted else {
                switch decision.rejectionCategory {
                case .busy: throw MacTransferError.busy
                case .incompatibleVersion, .capability:
                    throw MacTransferError.incompatibleVersion
                default: throw MacTransferError.remoteFailure(decision.rejectionCode)
                }
            }
            let negotiated = try SessionNegotiator.validate(hello: hello, decision: decision)
            guard await scheduler.markOutboundAccepted(token: lease.token) else {
                throw MacTransferError.busy
            }
            activeLock.withLock { activePair = (channel, sessionID, negotiated.version, androidSPKIPinHex) }
            let manifest = TransferControlMessage.manifest([
                TransferFileEntry(
                    itemID: file.itemID,
                    displayName: file.displayName,
                    byteCount: file.byteCount,
                    sha256: file.sha256,
                    mediaType: "application/octet-stream",
                    chunkSize: UInt32(negotiated.chunkSize)
                )
            ])
            try await channel.sendRecord(
                type: .manifest,
                payload: TransferControlCodec.encode(manifest),
                sessionID: sessionID,
                protocolMajor: negotiated.version.major,
                protocolMinor: negotiated.version.minor
            )
            await event(file.event(.awaitingApproval))
            let approvalRecord = try await withTransferTimeout(.seconds(120), phase: "waiting for approval") {
                try await channel.receiveRecord()
            }
            if approvalRecord.header.type == .terminalResult {
                try throwTerminal(approvalRecord)
            }
            try requireSession(approvalRecord, sessionID: sessionID, type: .approval, version: negotiated.version)
            guard case .approval(let accepted, _) = try TransferControlCodec.decode(
                type: .approval,
                data: approvalRecord.payload
            ), accepted else {
                throw MacTransferError.rejected(
                    (try? TransferControlCodec.decode(type: .approval, data: approvalRecord.payload).approvalReason) ?? ""
                )
            }

            try validate(file)
            let handle = try FileHandle(forReadingFrom: file.url)
            defer { try? handle.close() }
            var offset: UInt64 = 0
            var chunkIndex: UInt32 = 0
            while let bytes = try handle.read(upToCount: negotiated.chunkSize), !bytes.isEmpty {
                try Task.checkCancellation()
                let digest = Data(SHA256.hash(data: bytes))
                let prelude = TransferChunkPrelude(
                    itemID: file.itemID,
                    chunkIndex: chunkIndex,
                    offset: offset,
                    dataLength: UInt32(bytes.count),
                    sha256: digest
                )
                try await channel.sendRecord(
                    type: .chunk,
                    payload: prelude.encoded() + bytes,
                    sessionID: sessionID,
                    protocolMajor: negotiated.version.major,
                    protocolMinor: negotiated.version.minor
                )
                let progressRecord = try await withTransferTimeout(.seconds(60), phase: "waiting for verified progress") {
                    try await channel.receiveRecord()
                }
                if progressRecord.header.type == .terminalResult {
                    try throwTerminal(progressRecord)
                }
                try requireSession(progressRecord, sessionID: sessionID, type: .progress, version: negotiated.version)
                guard case .progress(let itemID, let verified, _, _) = try TransferControlCodec.decode(
                    type: .progress,
                    data: progressRecord.payload
                ), itemID == file.itemID else {
                    throw MacTransferError.protocolFailure("invalid progress acknowledgement")
                }
                offset += UInt64(bytes.count)
                chunkIndex += 1
                guard verified == offset else {
                    throw MacTransferError.protocolFailure("non-cumulative progress acknowledgement")
                }
                await event(file.event(.transferring, transferred: offset, verified: verified))
            }
            guard offset == file.byteCount else { throw MacTransferError.sourceChanged }
            try await channel.sendRecord(
                type: .senderFinished,
                payload: TransferControlCodec.encode(
                    .senderFinished(itemID: file.itemID, byteCount: file.byteCount, sha256: file.sha256)
                ),
                sessionID: sessionID,
                protocolMajor: negotiated.version.major,
                protocolMinor: negotiated.version.minor
            )
            await event(file.event(.verifying, transferred: offset, verified: offset))
            while true {
                let record = try await withTransferTimeout(.seconds(60), phase: "waiting for the terminal result") {
                    try await channel.receiveRecord()
                }
                guard record.header.sessionID == sessionID,
                      record.header.protocolMajor == negotiated.version.major,
                      record.header.protocolMinor == negotiated.version.minor else {
                    throw MacTransferError.protocolFailure("session ID mismatch")
                }
                if record.header.type == .cancel { throw CancellationError() }
                guard record.header.type == .terminalResult else { continue }
                guard case .terminalResult(let status, _, let code, let artifact) = try TransferControlCodec.decode(
                    type: .terminalResult,
                    data: record.payload
                ) else { throw MacTransferError.protocolFailure("invalid terminal result") }
                switch status {
                case .completed:
                    await event(file.event(.completed, transferred: offset, verified: offset))
                    return MacTransferCompletion(artifactID: artifact)
                case .rejected:
                    throw MacTransferError.rejected(code)
                case .cancelled:
                    throw CancellationError()
                case .failed:
                    throw MacTransferError.remoteFailure(code)
                }
            }
            } onCancel: {
                channel.cancel()
            }
            await scheduler.release(token: lease.token)
            return completion
        } catch {
            await scheduler.release(token: lease.token)
            throw error
        }
    }

    func send(
        _ file: PreparedMacFile,
        connection: MacConnectedPeer,
        event: @escaping @Sendable (TransferSessionEvent) async -> Void
    ) async throws -> MacTransferCompletion {
        try await send(
            file,
            host: "",
            androidSPKIPinHex: connection.peerID,
            connectedChannel: connection.channel,
            endpointTicket: connection.endpointTicket,
            event: event
        )
    }

    func send(
        _ file: PreparedMacFile,
        peerID: String,
        endpointQR: String?,
        event: @escaping @Sendable (TransferSessionEvent) async -> Void
    ) async throws -> MacTransferCompletion {
        try await send(
            file,
            host: "",
            androidSPKIPinHex: peerID,
            connectionResolver: {
                try await MacPeerConnectionModule.shared.connect(peerID: peerID, endpointQR: endpointQR)
            },
            event: event
        )
    }

    func unpairActive(peerID: String? = nil) {
        guard let active = activeLock.withLock({ activePair }) else { return }
        if let peerID, active.peerID != peerID { return }
        Task.detached {
            if let version = active.version {
                try? await active.channel.sendRecord(
                    type: .cancel,
                    payload: TransferControlCodec.encode(.cancel(reasonCode: "peer_unpaired")),
                    sessionID: active.sessionID,
                    protocolMajor: version.major,
                    protocolMinor: version.minor
                )
            }
            active.channel.cancel()
        }
    }

    private func validate(_ file: PreparedMacFile) throws {
        let values = try file.url.resourceValues(forKeys: [.isRegularFileKey, .isSymbolicLinkKey, .fileSizeKey, .contentModificationDateKey])
        guard values.isRegularFile == true,
              values.isSymbolicLink != true,
              values.fileSize.map(UInt64.init) == file.byteCount,
              values.contentModificationDate == file.modificationDate
        else { throw MacTransferError.sourceChanged }
    }

    private func requireSession(
        _ record: TransferWireRecord,
        sessionID: UUID,
        type: TransferRecordType,
        version: NegotiatedProtocolVersion
    ) throws {
        guard record.header.sessionID == sessionID,
              record.header.type == type,
              record.header.protocolMajor == version.major,
              record.header.protocolMinor == version.minor else {
            throw MacTransferError.protocolFailure("unexpected \(record.header.type) record")
        }
    }

    private func throwTerminal(_ record: TransferWireRecord) throws -> Never {
        guard case .terminalResult(let status, _, let code, _) = try TransferControlCodec.decode(
            type: .terminalResult,
            data: record.payload
        ) else { throw MacTransferError.protocolFailure("invalid early terminal result") }
        switch status {
        case .completed: throw MacTransferError.protocolFailure("completion before Commit")
        case .rejected: throw MacTransferError.rejected(code)
        case .cancelled where code == "peer_unpaired": throw MacTransferError.remoteFailure("peer_unpaired")
        case .cancelled: throw CancellationError()
        case .failed: throw MacTransferError.remoteFailure(code)
        }
    }
}

@MainActor
final class MacTransferModel: ObservableObject {
    @Published var endpointQR = ""
    @Published private(set) var selectedPeerID: String?
    @Published private(set) var selectedPeerName: String?
    @Published private(set) var onlinePeerIDs: Set<String> = []
    @Published private(set) var localPin = ""
    @Published private(set) var preparedFile: PreparedMacFile?
    @Published private(set) var phase: TransferActivityPhase?
    @Published private(set) var verifiedBytes: UInt64 = 0
    @Published private(set) var statusMessage = "Select one file to begin."
    @Published private(set) var errorMessage: String?

    private let sender = MacFixedEndpointSender()
    private let connections = MacPeerConnectionModule.shared
    private var task: Task<Void, Never>?
    private var cancellables: Set<AnyCancellable> = []

    init() {
        localPin = (try? sender.localSPKIPin()) ?? "Run scripts/provision-macos-dev-identity"
        connections.$onlinePeerIDs.sink { [weak self] in self?.onlinePeerIDs = $0 }.store(in: &cancellables)
    }

    var canSend: Bool {
        preparedFile != nil && selectedPeerID != nil && task == nil
    }

    var destinationLabel: String { selectedPeerName ?? "Not selected" }
    func isOnline(_ peerID: String) -> Bool { onlinePeerIDs.contains(peerID) }
    func select(peer: MacStoredPeer) { selectedPeerID = peer.id; selectedPeerName = peer.displayName }

    func selectFile() {
        let panel = NSOpenPanel()
        panel.canChooseFiles = true
        panel.canChooseDirectories = false
        panel.allowsMultipleSelection = false
        guard panel.runModal() == .OK, let url = panel.url else { return }
        task?.cancel()
        phase = .preparing
        statusMessage = "Preparing and hashing…"
        errorMessage = nil
        task = Task {
            defer { task = nil }
            do {
                preparedFile = try await sender.prepare(url)
                phase = nil
                statusMessage = "Review the destination and payload, then send."
            } catch {
                phase = .failed
                errorMessage = error.localizedDescription
                statusMessage = "Preparation failed"
            }
        }
    }

    func send() {
        guard let file = preparedFile, let peerID = selectedPeerID else { return }
        errorMessage = nil
        task = Task {
            defer { task = nil }
            do {
                _ = try await sender.send(file, peerID: peerID, endpointQR: endpointQR.isEmpty ? nil : endpointQR) { [weak self] event in
                    await MainActor.run {
                        self?.phase = event.phase
                        self?.verifiedBytes = event.verifiedBytes
                        self?.statusMessage = event.phase.userLabel
                    }
                }
            } catch is CancellationError {
                phase = .cancelled
                statusMessage = "Cancelled"
            } catch {
                phase = .failed
                errorMessage = error.localizedDescription
                statusMessage = "Transfer failed"
            }
        }
    }

    func cancel() { task?.cancel() }
    func unpairActive() { sender.unpairActive() }
    func unpair(peerID: String) {
        connections.invalidate(peerID: peerID)
        sender.unpairActive(peerID: peerID)
        if selectedPeerID == peerID { selectedPeerID = nil; selectedPeerName = nil; endpointQR = "" }
    }
}

private final class ContinuationGate<Value: Sendable>: @unchecked Sendable {
    private let lock = NSLock()
    private var continuation: CheckedContinuation<Value, Error>?

    init(_ continuation: CheckedContinuation<Value, Error>) { self.continuation = continuation }

    func resume(returning value: Value) {
        lock.withLock {
            continuation?.resume(returning: value)
            continuation = nil
        }
    }

    func resume(throwing error: Error) {
        lock.withLock {
            continuation?.resume(throwing: error)
            continuation = nil
        }
    }
}

private func withTransferTimeout<T: Sendable>(
    _ duration: Duration,
    phase: String,
    operation: @escaping @Sendable () async throws -> T
) async throws -> T {
    try await withThrowingTaskGroup(of: T.self) { group in
        group.addTask(operation: operation)
        group.addTask {
            try await Task.sleep(for: duration)
            throw MacTransferError.timeout(phase)
        }
        guard let result = try await group.next() else {
            throw MacTransferError.timeout(phase)
        }
        group.cancelAll()
        return result
    }
}

private extension PreparedMacFile {
    func event(
        _ phase: TransferActivityPhase,
        transferred: UInt64 = 0,
        verified: UInt64 = 0
    ) -> TransferSessionEvent {
        TransferSessionEvent(
            phase: phase,
            transferredBytes: transferred,
            verifiedBytes: verified,
            totalBytes: byteCount,
            completedItems: phase == .completed ? 1 : 0,
            totalItems: 1
        )
    }
}

private extension TransferControlMessage {
    var approvalReason: String {
        if case .approval(_, let reason) = self { return reason }
        return ""
    }
}

private extension TransferActivityPhase {
    var userLabel: String {
        switch self {
        case .preparing: "Preparing"
        case .connecting: "Connecting"
        case .awaitingApproval: "Awaiting approval"
        case .transferring: "Transferring"
        case .verifying: "Verifying"
        case .committing: "Committing"
        case .completed: "Completed"
        case .rejected: "Rejected"
        case .cancelled: "Cancelled"
        case .failed: "Failed"
        }
    }
}

private extension Data {
    var hexString: String { map { String(format: "%02x", $0) }.joined() }
}
