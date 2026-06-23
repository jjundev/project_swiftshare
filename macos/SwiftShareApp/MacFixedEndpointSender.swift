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
        }
    }

    static func message(for failure: TransferFailure) -> String {
        let error: MacTransferError = switch failure.category {
        case .discovery: .peerUnavailable
        case .authentication where failure.code == "identity_unavailable": .identityUnavailable
        case .authentication: .remoteFailure(failure.code)
        case .approval: .rejected(failure.code)
        case .permission, .storage, .integrity: .remoteFailure(failure.code)
        case .network: .connection(failure.code)
        case .protocol: .protocolFailure(failure.code)
        case .source: .sourceChanged
        case .busy: .busy
        case .incompatibleVersion: .incompatibleVersion
        case .cancelled: .remoteFailure(failure.code)
        }
        return error.localizedDescription
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
    private let sessionLock = NSLock()
    private var outboundSession: OutboundTransferSession?

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
        _ files: [PreparedMacFile],
        peerID: String,
        endpointQR: String?,
        event: @escaping @Sendable (TransferSessionEvent) async -> Void
    ) async -> RoleTransferOutcome {
        let totalBytes = files.reduce(UInt64(0)) { $0 + $1.byteCount }
        do {
            guard !files.isEmpty else { throw MacTransferError.invalidFile("Select at least one file to send.") }
            let pin = try peerID.transferHexData
            guard pin.count == 32 else { throw MacTransferError.invalidPin }
            let session = try session()
            return await session.execute(
                OutboundTransferIntent(peerID: pin, endpointHint: endpointQR, payloads: files),
                events: MacTransferEventSink(handler: event)
            )
        } catch {
            await event(TransferSessionEvent(phase: .failed, totalBytes: totalBytes, totalItems: files.count))
            let failure: TransferFailure
            switch error as? MacTransferError {
            case .invalidPin:
                failure = TransferFailure(category: .authentication, code: "invalid_peer_identity")
            case .identityUnavailable:
                failure = TransferFailure(category: .authentication, code: "identity_unavailable")
            default:
                failure = TransferFailure(category: .protocol, code: "session_initialization_failed")
            }
            return RoleTransferOutcome(
                result: .failed,
                failure: failure
            )
        }
    }

    /// Normalizes a native selection (multi-file panel or drag-and-drop) into a bounded
    /// batch: regular files are prepared and hashed; unreadable, folder, or symlink sources
    /// are rejected up front and reported so the receiver never sees them.
    func prepareBatch(_ urls: [URL]) async -> (accepted: [PreparedMacFile], rejected: [String]) {
        var accepted: [PreparedMacFile] = []
        var rejected: [String] = []
        for url in urls.prefix(transferMaxBatchItems) {
            do {
                accepted.append(try await prepare(url))
            } catch {
                rejected.append(url.lastPathComponent)
            }
        }
        if urls.count > transferMaxBatchItems {
            rejected.append("\(urls.count - transferMaxBatchItems) more over the \(transferMaxBatchItems)-item limit")
        }
        return (accepted, rejected)
    }

    func cancelActive(reasonCode: String, peerID: String? = nil) {
        let session = sessionLock.withLock { outboundSession }
        let pin = peerID.flatMap { try? $0.transferHexData }
        Task {
            await session?.cancel(reasonCode: reasonCode, peerID: pin)
        }
    }

    private func session() throws -> OutboundTransferSession {
        try sessionLock.withLock {
            if let outboundSession { return outboundSession }
            let value = OutboundTransferSession(
                scheduler: try MacSharedTransferScheduler.shared.value(),
                locator: MacOutboundPeerLocator()
            )
            outboundSession = value
            return value
        }
    }
}

private struct MacTransferEventSink: TransferEventSink {
    let handler: @Sendable (TransferSessionEvent) async -> Void
    func emit(_ event: TransferSessionEvent) async { await handler(event) }
}

private actor MacOutboundPeerLocator: OutboundAuthenticatedPeerLocating {
    private var location: AuthenticatedPeerLocationModule?

    func locate(peerID: Data, endpointHint: String?) async throws -> OutboundAuthenticatedPeerLocation {
        let module: AuthenticatedPeerLocationModule
        if let location {
            module = location
        } else {
            let adapter = await MainActor.run { MacPeerConnectionModule.shared }
            let value = AuthenticatedPeerLocationModule(routes: adapter, authenticator: adapter)
            location = value
            module = value
        }
        return try await module.locate(peerID: peerID, endpointHint: endpointHint)
    }
}

extension NWTLSRecordChannel: OutboundTransferRecordChannel {
    func close() async { cancel() }
}

extension PreparedMacFile: OutboundTransferPayload {
    var mediaType: String { "application/octet-stream" }

    func validateForTransfer() async throws {
        let access = url.startAccessingSecurityScopedResource()
        defer { if access { url.stopAccessingSecurityScopedResource() } }
        let values = try url.resourceValues(forKeys: [
            .isRegularFileKey, .isSymbolicLinkKey, .fileSizeKey, .contentModificationDateKey,
        ])
        guard values.isRegularFile == true,
              values.isSymbolicLink != true,
              values.fileSize.map(UInt64.init) == byteCount,
              values.contentModificationDate == modificationDate
        else { throw MacTransferError.sourceChanged }
    }

    func openStream() async throws -> any OutboundTransferPayloadStream {
        try MacFilePayloadStream(url: url)
    }
}

private actor MacFilePayloadStream: OutboundTransferPayloadStream {
    private let url: URL
    private let handle: FileHandle
    private let securityScope: Bool
    private var closed = false

    init(url: URL) throws {
        self.url = url
        securityScope = url.startAccessingSecurityScopedResource()
        do {
            handle = try FileHandle(forReadingFrom: url)
        } catch {
            if securityScope { url.stopAccessingSecurityScopedResource() }
            throw error
        }
    }

    func read(upToCount count: Int) throws -> Data? { try handle.read(upToCount: count) }

    func close() {
        guard !closed else { return }
        closed = true
        try? handle.close()
        if securityScope { url.stopAccessingSecurityScopedResource() }
    }
}

@MainActor
final class MacTransferModel: ObservableObject {
    @Published var endpointQR = ""
    @Published private(set) var selectedPeerID: String?
    @Published private(set) var selectedPeerName: String?
    @Published private(set) var onlinePeerIDs: Set<String> = []
    @Published private(set) var localPin = ""
    @Published private(set) var preparedFiles: [PreparedMacFile] = []
    @Published private(set) var rejectedItems: [String] = []
    @Published private(set) var phase: TransferActivityPhase?
    @Published private(set) var verifiedBytes: UInt64 = 0
    @Published private(set) var totalBytes: UInt64 = 0
    @Published private(set) var completedItems = 0
    @Published private(set) var totalItems = 0
    @Published private(set) var currentItemName = ""
    @Published private(set) var statusMessage = "Drag files here or choose files to begin."
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
        !preparedFiles.isEmpty && selectedPeerID != nil && task == nil
    }

    var itemCount: Int { preparedFiles.count }
    var batchByteCount: UInt64 { preparedFiles.reduce(0) { $0 + $1.byteCount } }
    var destinationLabel: String { selectedPeerName ?? "Not selected" }
    func isOnline(_ peerID: String) -> Bool { onlinePeerIDs.contains(peerID) }
    func select(peer: MacStoredPeer) { selectedPeerID = peer.id; selectedPeerName = peer.displayName }

    /// Multi-file picker entry point (US16).
    func selectFiles() {
        let panel = NSOpenPanel()
        panel.canChooseFiles = true
        panel.canChooseDirectories = false
        panel.allowsMultipleSelection = true
        guard panel.runModal() == .OK, !panel.urls.isEmpty else { return }
        prepare(urls: panel.urls)
    }

    /// Drag-and-drop entry point (US15).
    func dropFiles(_ urls: [URL]) {
        guard !urls.isEmpty else { return }
        prepare(urls: urls)
    }

    private func prepare(urls: [URL]) {
        guard task == nil else { return }
        task?.cancel()
        phase = .preparing
        statusMessage = "Preparing and hashing…"
        errorMessage = nil
        task = Task {
            defer { task = nil }
            let (accepted, rejected) = await sender.prepareBatch(urls)
            preparedFiles = accepted
            rejectedItems = rejected
            totalBytes = batchByteCount
            totalItems = accepted.count
            completedItems = 0
            verifiedBytes = 0
            phase = nil
            statusMessage = accepted.isEmpty
                ? "No supported files in that selection."
                : "Review the destination and \(accepted.count) item(s), then send."
        }
    }

    func clearSelection() {
        guard task == nil else { return }
        preparedFiles = []
        rejectedItems = []
        totalBytes = 0
        totalItems = 0
        phase = nil
        statusMessage = "Drag files here or choose files to begin."
    }

    func send() {
        guard !preparedFiles.isEmpty, let peerID = selectedPeerID else { return }
        let files = preparedFiles
        errorMessage = nil
        task = Task {
            defer { task = nil }
            let outcome = await sender.send(
                files,
                peerID: peerID,
                endpointQR: endpointQR.isEmpty ? nil : endpointQR
            ) { [weak self] event in
                await MainActor.run {
                    self?.phase = event.phase
                    self?.verifiedBytes = event.verifiedBytes
                    self?.totalBytes = event.totalBytes
                    self?.completedItems = event.completedItems
                    self?.totalItems = event.totalItems
                    if !event.currentItemName.isEmpty { self?.currentItemName = event.currentItemName }
                    self?.statusMessage = event.phase.userLabel
                }
            }
            switch outcome.result {
            case .completed:
                break
            case .cancelled:
                phase = .cancelled
                statusMessage = "Cancelled"
            case .rejected, .failed:
                errorMessage = outcome.failure.map(MacTransferError.message(for:)) ?? "Transfer failed"
            }
        }
    }

    func cancel() {
        if phase == .preparing { task?.cancel() }
        else { sender.cancelActive(reasonCode: "user_cancelled") }
    }
    func unpairActive() { sender.cancelActive(reasonCode: "peer_unpaired") }
    func unpair(peerID: String) {
        connections.invalidate(peerID: peerID)
        sender.cancelActive(reasonCode: "peer_unpaired", peerID: peerID)
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
