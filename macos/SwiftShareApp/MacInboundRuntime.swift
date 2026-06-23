import AppKit
import Combine
import CryptoKit
import Dispatch
import Foundation
import Network
import Security
import SwiftShareDomain

final class MacSharedTransferScheduler: @unchecked Sendable {
    static let shared = MacSharedTransferScheduler()
    private let lock = NSLock()
    private var scheduler: TransferSessionScheduler?

    func value() throws -> TransferSessionScheduler {
        try lock.withLock {
            if let scheduler { return scheduler }
            let identityStore = MacDevelopmentIdentityStore()
            let identity = try identityStore.loadIdentity()
            let value = TransferSessionScheduler(localDeviceID: try identityStore.spkiPin(for: identity))
            scheduler = value
            return value
        }
    }
}

@MainActor
final class MacInboundRuntime: ObservableObject {
    static let shared = MacInboundRuntime()

    @Published private(set) var destinationName = "Not configured"
    @Published private(set) var availabilityMessage = "Choose a destination to receive from Android."
    @Published private(set) var incomingSummary: InboundTransferSummary?
    @Published private(set) var incomingPhase: TransferActivityPhase?
    @Published private(set) var verifiedBytes: UInt64 = 0
    @Published private(set) var errorMessage: String?
    @Published private(set) var committedPath: String?

    private let bookmarks = MacDestinationBookmarkStore.shared
    private let approval = MacInboundApprovalGateway()
    private var server: MacInboundServer?
    private var startingServer: MacInboundServer?
    private var restartGeneration = LatestOperationGeneration()

    private init() {
        destinationName = bookmarks.displayName() ?? "Not configured"
        Task { await restart() }
    }

    func chooseDestination() {
        let panel = NSOpenPanel()
        panel.canChooseFiles = false
        panel.canChooseDirectories = true
        panel.allowsMultipleSelection = false
        panel.prompt = "Choose destination"
        guard panel.runModal() == .OK, let folder = panel.url else { return }
        do {
            try bookmarks.save(folder)
            destinationName = folder.lastPathComponent
            errorMessage = nil
            Task { await restart() }
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    func approve() { Task { await approval.resolve(.accepted) } }
    func reject() { Task { await approval.resolve(.rejected) } }
    func cancel() { Task { await server?.cancel() } }

    func unpair(peerID: String) { Task { await server?.unpair(peerID: peerID) } }

    func restart() async {
        let generation = restartGeneration.begin()
        let priorServer = server
        let priorStartingServer = startingServer
        server = nil
        startingServer = nil
        await priorStartingServer?.stop()
        if priorServer !== priorStartingServer { await priorServer?.stop() }
        guard restartGeneration.isCurrent(generation) else { return }

        var candidate: MacInboundServer?
        do {
            try await MacReceiveDestination(bookmarks: bookmarks).preflight()
            let value = try await MacInboundServer(
                scheduler: MacSharedTransferScheduler.shared.value(),
                approval: approval,
                destination: MacReceiveDestination(bookmarks: bookmarks),
                eventHandler: { [weak self] event in
                    await MainActor.run { self?.apply(event) }
                }
            )
            candidate = value
            guard restartGeneration.isCurrent(generation) else { return }
            startingServer = value
            try await value.start()
            guard restartGeneration.isCurrent(generation) else {
                if startingServer === value { startingServer = nil }
                await value.stop()
                return
            }
            startingServer = nil
            server = value
            availabilityMessage = "Available for Android transfers on the LAN."
            errorMessage = nil
        } catch {
            await candidate?.stop()
            guard restartGeneration.isCurrent(generation) else { return }
            if startingServer === candidate { startingServer = nil }
            server = nil
            availabilityMessage = "Unavailable until the destination is repaired."
            errorMessage = error.localizedDescription
        }
    }

    private func apply(_ event: InboundTransferSessionEvent) {
        incomingSummary = event.summary
        incomingPhase = event.phase
        verifiedBytes = event.verifiedBytes
        errorMessage = event.failure.map { "\($0.category): \($0.code)" }
        if event.phase == .completed { committedPath = event.artifact?.id }
        if event.failure?.category == .permission {
            Task { await restart() }
        }
    }
}

private actor MacInboundApprovalGateway: InboundApprovalGateway {
    private var continuation: CheckedContinuation<InboundApprovalDecision, Never>?

    func awaitDecision(_ summary: InboundTransferSummary) async -> InboundApprovalDecision {
        await withTaskCancellationHandler {
            await withCheckedContinuation { continuation = $0 }
        } onCancel: {
            Task { await self.resolve(.timedOut) }
        }
    }

    func resolve(_ decision: InboundApprovalDecision) {
        continuation?.resume(returning: decision)
        continuation = nil
    }

    func cancel() { resolve(.rejected) }
}

private struct MacInboundEventSink: InboundTransferEventSink {
    let handler: @Sendable (InboundTransferSessionEvent) async -> Void
    func emit(_ event: InboundTransferSessionEvent) async { await handler(event) }
}

private final class MacInboundServer: @unchecked Sendable {
    private let identityStore = MacDevelopmentIdentityStore()
    private let repository = MacTrustRepository.shared
    private let queue = DispatchQueue(label: "dev.swiftshare.macos.inbound")
    private let scheduler: TransferSessionScheduler
    private let approval: MacInboundApprovalGateway
    private let destination: MacReceiveDestination
    private let eventHandler: @Sendable (InboundTransferSessionEvent) async -> Void
    private var listener: NWListener?
    private var service: NetService?
    private var rotatingID = Data()
    private var session: InboundTransferSession?

    init(
        scheduler: TransferSessionScheduler,
        approval: MacInboundApprovalGateway,
        destination: MacReceiveDestination,
        eventHandler: @escaping @Sendable (InboundTransferSessionEvent) async -> Void
    ) async throws {
        self.scheduler = scheduler
        self.approval = approval
        self.destination = destination
        self.eventHandler = eventHandler
    }

    func start() async throws {
        let peers = try await repository.peers()
        guard !peers.isEmpty else { throw MacTransferError.remoteFailure("no_paired_peer") }
        let allowedPins = try peers.map { try $0.keyIDHex.transferHexData }
        let identity = try identityStore.loadIdentity()
        let tls = NWProtocolTLS.Options()
        sec_protocol_options_set_min_tls_protocol_version(tls.securityProtocolOptions, .TLSv13)
        sec_protocol_options_set_max_tls_protocol_version(tls.securityProtocolOptions, .TLSv13)
        sec_protocol_options_set_peer_authentication_required(tls.securityProtocolOptions, true)
        guard let localIdentity = sec_identity_create(identity) else { throw MacTransferError.identityUnavailable }
        sec_protocol_options_set_local_identity(tls.securityProtocolOptions, localIdentity)
        let verifyQueue = queue
        sec_protocol_options_set_verify_block(tls.securityProtocolOptions, { _, trust, complete in
            let trustRef = sec_trust_copy_ref(trust).takeRetainedValue()
            guard let chain = SecTrustCopyCertificateChain(trustRef) as? [SecCertificate],
                  let certificate = chain.first,
                  let key = SecCertificateCopyKey(certificate),
                  let pin = try? MacDevelopmentIdentityStore.spkiPin(for: key),
                  allowedPins.contains(pin)
            else { complete(false); return }
            SecTrustSetAnchorCertificates(trustRef, [certificate] as CFArray)
            SecTrustSetAnchorCertificatesOnly(trustRef, true)
            var error: CFError?
            complete(SecTrustEvaluateWithError(trustRef, &error))
        }, verifyQueue)
        let value = try NWListener(using: NWParameters(tls: tls, tcp: NWProtocolTCP.Options()))
        listener = value
        value.newConnectionHandler = { [weak self] connection in self?.accept(connection) }
        do {
            try await withCheckedThrowingContinuation { continuation in
                let gate = MacInboundContinuationGate<Void>(continuation)
                value.stateUpdateHandler = { state in
                    switch state {
                    case .ready: gate.resume(())
                    case .failed(let error): gate.fail(error)
                    case .cancelled: gate.fail(MacTransferError.connection("listener cancelled"))
                    default: break
                    }
                }
                value.start(queue: queue)
            }
        } catch {
            value.cancel()
            if listener === value { listener = nil }
            throw error
        }
        guard let port = value.port?.rawValue else { throw MacTransferError.connection("listener missing port") }
        rotatingID = Data((0..<16).map { _ in UInt8.random(in: .min ... .max) })
        let advertisement = DiscoveryAdvertisement(rotatingID: rotatingID)
        let service = NetService(domain: "local.", type: "_swiftshare._tcp.", name: advertisement.instanceName, port: Int32(port))
        let txt = NetService.data(fromTXTRecord: Dictionary(uniqueKeysWithValues: advertisement.txt.map {
            ($0.key, Data($0.value.utf8))
        }))
        service.setTXTRecord(txt)
        service.publish()
        self.service = service
        session = InboundTransferSession(
            scheduler: scheduler,
            approvalGateway: approval,
            destination: destination,
            events: MacInboundEventSink(handler: eventHandler)
        )
    }

    func stop() async {
        service?.stop()
        service = nil
        listener?.cancel()
        listener = nil
        if let session { await session.cancel(reasonCode: "receiver_cancelled") }
    }

    func cancel() async { await session?.cancel(reasonCode: "receiver_cancelled") }
    func unpair(peerID: String) async { await session?.cancel(reasonCode: "peer_unpaired", peerIDKey: peerID) }

    private func accept(_ connection: NWConnection) {
        let channel = MacInboundNWChannel(connection: connection, queue: queue)
        Task {
            do {
                try await channel.connect()
                let pin = try channel.peerSPKIPin()
                let first = try await channel.receiveExactly(4)
                if first == DiscoveryProbeCodec.magic {
                    let probe = try DiscoveryProbeCodec.decodeRequest(first + (try await channel.receiveExactly(34)))
                    try await channel.sendRaw(DiscoveryProbeCodec.encodeReply(
                        probe,
                        available: probe.rotatingID == rotatingID
                    ))
                    await channel.close()
                    return
                }
                let peerKey = pin.hexString
                guard try await repository.peer(id: peerKey) != nil else { throw MacTransferError.remoteFailure("peer_unpaired") }
                guard let session else { throw MacTransferError.connection("receiver unavailable") }
                _ = await session.execute(InboundAuthenticatedConnection(
                    peerID: pin,
                    peerIDKey: peerKey,
                    channel: channel.withPrefix(first),
                    authorizer: MacInboundPeerAuthorizer(peerID: peerKey, repository: repository)
                ))
            } catch {
                await channel.close()
            }
        }
    }
}

private struct MacInboundPeerAuthorizer: InboundPeerAuthorizing {
    let peerID: String
    let repository: MacTrustRepository
    func currentPeer() async -> InboundAuthorizedPeer? {
        guard let peer = try? await repository.peer(id: peerID) else { return nil }
        return InboundAuthorizedPeer(
            id: peer.id,
            displayName: peer.displayName,
            approvalPolicy: peer.approvalPolicy
        )
    }
}

private final class MacInboundNWChannel: InboundTransferRecordChannel, @unchecked Sendable {
    private let connection: NWConnection
    private let queue: DispatchQueue
    private let lock = NSLock()
    private var prefix = Data()

    init(connection: NWConnection, queue: DispatchQueue) {
        self.connection = connection
        self.queue = queue
    }

    func withPrefix(_ value: Data) -> MacInboundNWChannel {
        lock.withLock { prefix = value }
        return self
    }

    func connect() async throws {
        try await withCheckedThrowingContinuation { continuation in
            let gate = MacInboundContinuationGate<Void>(continuation)
            connection.stateUpdateHandler = { state in
                switch state {
                case .ready: gate.resume(())
                case .failed(let error), .waiting(let error): gate.fail(error)
                case .cancelled: gate.fail(CancellationError())
                default: break
                }
            }
            connection.start(queue: queue)
        }
    }

    func peerSPKIPin() throws -> Data {
        guard let metadata = connection.metadata(definition: NWProtocolTLS.definition) as? NWProtocolTLS.Metadata,
              let key = sec_protocol_metadata_copy_peer_public_key(metadata.securityProtocolMetadata)
        else { throw MacTransferError.connection("missing peer identity") }
        let representation = Data(key as DispatchData)
        guard !representation.isEmpty else { throw MacTransferError.connection("empty peer identity") }
        let canonical: Data
        if representation.count == 65 {
            canonical = try "3059301306072a8648ce3d020106082a8648ce3d030107034200".transferHexData + representation
        } else {
            canonical = representation
        }
        return Data(SHA256.hash(data: canonical))
    }

    func receiveRecord() async throws -> TransferWireRecord {
        let initial = lock.withLock { let value = prefix; prefix = Data(); return value }
        let header = initial + (try await receiveExactly(transferHeaderSize - initial.count))
        let decoded = try TransferRecordHeader.decode(header)
        return TransferWireRecord(header: decoded, payload: try await receiveExactly(decoded.payloadLength))
    }

    func sendRecord(
        type: TransferRecordType,
        payload: Data,
        sessionID: UUID,
        protocolMajor: UInt16,
        protocolMinor: UInt16
    ) async throws {
        let header = try TransferRecordHeader(
            type: type,
            payloadLength: payload.count,
            sessionID: sessionID,
            protocolMajor: protocolMajor,
            protocolMinor: protocolMinor
        ).encoded()
        try await sendRaw(header + payload)
    }

    func sendRaw(_ data: Data) async throws {
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            connection.send(content: data, completion: .contentProcessed { error in
                if let error { continuation.resume(throwing: error) } else { continuation.resume() }
            })
        }
    }

    func receiveExactly(_ count: Int) async throws -> Data {
        var result = Data()
        while result.count < count {
            let next = try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Data, Error>) in
                connection.receive(minimumIncompleteLength: 1, maximumLength: count - result.count) {
                    data, _, complete, error in
                    if let error { continuation.resume(throwing: error) }
                    else if let data, !data.isEmpty { continuation.resume(returning: data) }
                    else { continuation.resume(throwing: MacTransferError.connection(complete ? "closed" : "empty read")) }
                }
            }
            result.append(next)
        }
        return result
    }

    func close() async { connection.cancel() }
}

private final class MacInboundContinuationGate<Value: Sendable>: @unchecked Sendable {
    private let lock = NSLock()
    private var continuation: CheckedContinuation<Value, Error>?
    init(_ continuation: CheckedContinuation<Value, Error>) { self.continuation = continuation }
    func resume(_ value: Value) { lock.withLock { continuation?.resume(returning: value); continuation = nil } }
    func fail(_ error: Error) { lock.withLock { continuation?.resume(throwing: error); continuation = nil } }
}

private extension String {
    var transferHexData: Data {
        get throws {
            guard count.isMultiple(of: 2) else { throw MacTransferError.invalidPin }
            var result = Data()
            var index = startIndex
            while index < endIndex {
                let next = self.index(index, offsetBy: 2)
                guard let byte = UInt8(self[index ..< next], radix: 16) else {
                    throw MacTransferError.invalidPin
                }
                result.append(byte)
                index = next
            }
            return result
        }
    }
}

private extension Data {
    var hexString: String { map { String(format: "%02x", $0) }.joined() }
}
