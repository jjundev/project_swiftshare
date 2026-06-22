import Combine
import CryptoKit
import AVFoundation
import AppKit
import Foundation
import Network
import Security
import SwiftUI
import SwiftShareDomain

@MainActor
final class MacPeerConnectionModule: ObservableObject, AuthenticatedPeerRouteProviding, AuthenticatedPeerRouteAuthenticating {
    static let shared = MacPeerConnectionModule()

    @Published private(set) var onlinePeerIDs: Set<String> = []
    @Published private(set) var discoveryError: String?

    private struct Route { let id: String; let endpoint: NWEndpoint; let rotatingID: Data }
    private let repository = MacTrustRepository.shared
    private let identityStore = MacDevelopmentIdentityStore()
    private let queue = DispatchQueue(label: "dev.swiftshare.macos.lan-discovery")
    private var browser: NWBrowser?
    private var routesByPeer: [String: [String: Route]] = [:]
    private var peerByRoute: [String: String] = [:]
    private var endpointByCandidateID: [String: NWEndpoint] = [:]

    private init() { startBrowsing() }

    func routes(peerID: Data, endpointHint: String?) async throws -> [AuthenticatedPeerRoute] {
        let peerKey = peerID.hexString
        let peer = try await repository.peer(id: peerKey)
        guard let peer else { throw MacTransferError.remoteFailure("peer_unpaired") }
        if let endpointHint, !endpointHint.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            let decoded = try EndpointQRCodec.decodeURI(endpointHint.trimmingCharacters(in: .whitespacesAndNewlines))
            guard decoded.0.peerKeyID == peerID else { throw MacTransferError.remoteFailure("endpoint_qr_peer_mismatch") }
            let key = try P256.Signing.PublicKey(derRepresentation: peer.canonicalSPKI)
            let signature = try P256.Signing.ECDSASignature(rawRepresentation: decoded.1.signatureP1363)
            guard key.isValidSignature(signature, for: EndpointQRCodec.signingDigest(bodyBytes: decoded.1.bodyBytes)) else {
                throw MacTransferError.remoteFailure("endpoint_qr_signature_invalid")
            }
            return decoded.0.addresses.enumerated().compactMap { index, address in
                let endpoint: NWEndpoint
                switch address.family {
                case .ipv4:
                    guard let value = IPv4Address(address.bytes) else { return nil }
                    endpoint = .hostPort(host: .ipv4(value), port: .init(rawValue: decoded.0.port)!)
                case .ipv6:
                    guard let value = IPv6Address(address.bytes) else { return nil }
                    endpoint = .hostPort(host: .ipv6(value), port: .init(rawValue: decoded.0.port)!)
                }
                let id = "qr|\(peerKey)|\(index)|\(endpoint.debugDescription)"
                endpointByCandidateID[id] = endpoint
                return AuthenticatedPeerRoute(id: id, endpointTicket: decoded.0.ticket)
            }
        }

        let automaticRoutes = routesByPeer[peerKey].map { Array($0.values) } ?? []
        return automaticRoutes
            .sorted { $0.id < $1.id }
            .map { route in
                let id = "bonjour|\(peerKey)|\(route.id)"
                endpointByCandidateID[id] = route.endpoint
                return AuthenticatedPeerRoute(id: id)
            }
    }

    func authenticate(
        peerID: Data,
        route: AuthenticatedPeerRoute
    ) async throws -> any OutboundTransferRecordChannel {
        let peerKey = peerID.hexString
        guard try await repository.peer(id: peerKey) != nil else {
            throw MacTransferError.remoteFailure("peer_unpaired")
        }
        guard let endpoint = endpointByCandidateID[route.id] else {
            throw MacTransferError.connection("Peer route is stale")
        }
        let identity = UnsafeSendableIdentity(try identityStore.loadIdentity())
        let channel = try NWTLSRecordChannel(endpoint: endpoint, identity: identity.value, peerSPKIPin: peerID)
        try await channel.connect()
        return channel
    }

    func invalidate(peerID: String) {
        let routeIDs = routesByPeer[peerID].map { Array($0.keys) } ?? []
        for routeID in routeIDs { endpointByCandidateID["bonjour|\(peerID)|\(routeID)"] = nil }
        endpointByCandidateID = endpointByCandidateID.filter { !$0.key.hasPrefix("qr|\(peerID)|") }
        routesByPeer[peerID] = nil
        peerByRoute = peerByRoute.filter { $0.value != peerID }
        onlinePeerIDs.remove(peerID)
    }

    private func startBrowsing() {
        let value = NWBrowser(for: .bonjourWithTXTRecord(type: "_swiftshare._tcp", domain: nil), using: .tcp)
        value.stateUpdateHandler = { [weak self] state in
            Task { @MainActor in
                switch state {
                case .failed(let error), .waiting(let error): self?.discoveryError = error.localizedDescription
                case .ready: self?.discoveryError = nil
                default: break
                }
            }
        }
        value.browseResultsChangedHandler = { [weak self] results, changes in
            Task { @MainActor in await self?.apply(results: results, changes: changes) }
        }
        browser = value
        value.start(queue: queue)
    }

    private func apply(results: Set<NWBrowser.Result>, changes: Set<NWBrowser.Result.Change>) async {
        for change in changes {
            if case .removed(let result) = change { remove(routeID: routeID(result)) }
        }
        for result in results {
            let id = routeID(result)
            if peerByRoute[id] != nil { continue }
            guard case .bonjour(let txt) = result.metadata,
                  txt["v"] == "1", let encoded = txt["rid"], let rotatingID = Data(discoveryBase64URL: encoded), rotatingID.count == 16
            else { continue }
            do {
                let peers = try await repository.peers()
                let pins = try peers.map { try $0.keyIDHex.transferHexData }
                guard !pins.isEmpty else { continue }
                let identity = try identityStore.loadIdentity()
                let probe = DiscoveryProbe(rotatingID: rotatingID, nonce: Data.random(count: 16))
                let resolved = try await MacDiscoveryProbeConnection(endpoint: result.endpoint, identity: identity, acceptedPins: pins).perform(probe)
                guard resolved.available, resolved.probe == probe else { continue }
                let peerID = resolved.peerID.hexString
                guard peers.contains(where: { $0.keyIDHex == peerID }) else { continue }
                let route = Route(id: id, endpoint: result.endpoint, rotatingID: rotatingID)
                routesByPeer[peerID, default: [:]][id] = route; peerByRoute[id] = peerID; onlinePeerIDs.insert(peerID)
            } catch { /* Anonymous/unpaired/stale services stay hidden. */ }
        }
    }

    private func remove(routeID: String) {
        guard let peerID = peerByRoute.removeValue(forKey: routeID) else { return }
        endpointByCandidateID["bonjour|\(peerID)|\(routeID)"] = nil
        routesByPeer[peerID]?[routeID] = nil
        if routesByPeer[peerID]?.isEmpty != false { routesByPeer[peerID] = nil; onlinePeerIDs.remove(peerID) }
    }

    private func routeID(_ result: NWBrowser.Result) -> String {
        result.endpoint.debugDescription + "|" + result.interfaces.map(\.name).sorted().joined(separator: ",")
    }
}

private final class MacDiscoveryProbeConnection: @unchecked Sendable {
    private let connection: NWConnection
    private let queue = DispatchQueue(label: "dev.swiftshare.macos.discovery-probe")
    private let matched = LockedPin()

    init(endpoint: NWEndpoint, identity: SecIdentity, acceptedPins: [Data]) throws {
        let tls = NWProtocolTLS.Options()
        sec_protocol_options_set_min_tls_protocol_version(tls.securityProtocolOptions, .TLSv13)
        sec_protocol_options_set_max_tls_protocol_version(tls.securityProtocolOptions, .TLSv13)
        guard let localIdentity = sec_identity_create(identity) else { throw MacTransferError.identityUnavailable }
        sec_protocol_options_set_local_identity(tls.securityProtocolOptions, localIdentity)
        let accepted = acceptedPins
        let matched = self.matched
        sec_protocol_options_set_verify_block(tls.securityProtocolOptions, { _, trust, complete in
            let trustRef = sec_trust_copy_ref(trust).takeRetainedValue()
            guard let chain = SecTrustCopyCertificateChain(trustRef) as? [SecCertificate],
                  let certificate = chain.first, let key = SecCertificateCopyKey(certificate),
                  let pin = try? MacDevelopmentIdentityStore.spkiPin(for: key), accepted.contains(pin)
            else { complete(false); return }
            SecTrustSetAnchorCertificates(trustRef, [certificate] as CFArray); SecTrustSetAnchorCertificatesOnly(trustRef, true)
            var error: CFError?; let valid = SecTrustEvaluateWithError(trustRef, &error)
            if valid { matched.set(pin) }; complete(valid)
        }, queue)
        connection = NWConnection(to: endpoint, using: NWParameters(tls: tls, tcp: NWProtocolTCP.Options()))
    }

    func perform(_ probe: DiscoveryProbe) async throws -> (probe: DiscoveryProbe, available: Bool, peerID: Data) {
        defer { connection.cancel() }
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            let gate = ProbeContinuationGate<Void>(continuation)
            connection.stateUpdateHandler = { state in
                switch state { case .ready: gate.resume(returning: ()); case .failed(let e), .waiting(let e): gate.resume(throwing: e); default: break }
            }
            connection.start(queue: queue)
        }
        try await send(DiscoveryProbeCodec.encodeRequest(probe))
        let reply = try await receiveExactly(39)
        let decoded = try DiscoveryProbeCodec.decodeReply(reply)
        guard let peerID = matched.get() else { throw MacTransferError.connection("Discovery peer authentication failed") }
        return (decoded.0, decoded.1, peerID)
    }

    private func send(_ data: Data) async throws {
        try await withCheckedThrowingContinuation { continuation in
            connection.send(content: data, completion: .contentProcessed { error in error.map { continuation.resume(throwing: $0) } ?? continuation.resume() })
        }
    }
    private func receiveExactly(_ count: Int) async throws -> Data {
        var value = Data()
        while value.count < count {
            let remaining = count - value.count
            let next = try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Data, Error>) in
                connection.receive(minimumIncompleteLength: 1, maximumLength: remaining) { data, _, complete, error in
                    if let error { continuation.resume(throwing: error) }
                    else if let data, !data.isEmpty { continuation.resume(returning: data) }
                    else { continuation.resume(throwing: MacTransferError.connection(complete ? "Probe closed" : "Empty probe response")) }
                }
            }
            value.append(next)
        }
        return value
    }
}

private final class LockedPin: @unchecked Sendable {
    private let lock = NSLock(); private var value: Data?
    func set(_ value: Data) { lock.withLock { self.value = value } }
    func get() -> Data? { lock.withLock { value } }
}

private struct UnsafeSendableIdentity: @unchecked Sendable { let value: SecIdentity; init(_ value: SecIdentity) { self.value = value } }

private extension Data {
    static func random(count: Int) -> Data { Data((0 ..< count).map { _ in UInt8.random(in: .min ... .max) }) }
    init?(discoveryBase64URL: String) {
        var value = discoveryBase64URL.replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")
        value += String(repeating: "=", count: (4 - value.count % 4) % 4); self.init(base64Encoded: value)
    }
}

private extension String {
    var transferHexData: Data {
        get throws {
            guard count.isMultiple(of: 2) else { throw MacTransferError.invalidPin }
            var value = Data(); var index = startIndex
            while index < endIndex {
                let next = self.index(index, offsetBy: 2)
                guard let byte = UInt8(self[index ..< next], radix: 16) else { throw MacTransferError.invalidPin }
                value.append(byte); index = next
            }
            return value
        }
    }
}

private extension Data { var hexString: String { map { String(format: "%02x", $0) }.joined() } }

struct EndpointQRScannerView: View {
    let onCode: (String) -> Void
    @StateObject private var scanner = EndpointQRScanner()
    var body: some View {
        VStack(spacing: 12) {
            Text("Scan Android endpoint QR").font(.headline)
            EndpointCameraPreview(session: scanner.session).frame(width: 420, height: 280).clipShape(RoundedRectangle(cornerRadius: 10))
            Text(scanner.message).font(.caption).foregroundStyle(.secondary)
        }
        .padding()
        .onAppear { scanner.onCode = onCode; scanner.start() }
        .onDisappear { scanner.stop() }
    }
}

private final class EndpointQRScanner: NSObject, ObservableObject, AVCaptureMetadataOutputObjectsDelegate, @unchecked Sendable {
    let session = AVCaptureSession()
    @Published var message = "Point the Mac camera at the QR shown on Android."
    var onCode: ((String) -> Void)?

    func start() {
        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized: configure()
        case .notDetermined:
            AVCaptureDevice.requestAccess(for: .video) { [weak self] allowed in DispatchQueue.main.async { allowed ? self?.configure() : (self?.message = "Camera access was denied. Paste the endpoint URI instead.") } }
        default: message = "Camera access is unavailable. Paste the endpoint URI instead."
        }
    }
    func stop() { if session.isRunning { session.stopRunning() } }
    private func configure() {
        guard session.inputs.isEmpty else { if !session.isRunning { session.startRunning() }; return }
        guard let camera = AVCaptureDevice.default(for: .video), let input = try? AVCaptureDeviceInput(device: camera) else {
            message = "No camera is available. Paste the endpoint URI instead."; return
        }
        let output = AVCaptureMetadataOutput()
        guard session.canAddInput(input), session.canAddOutput(output) else { message = "Camera setup failed."; return }
        session.addInput(input); session.addOutput(output); output.setMetadataObjectsDelegate(self, queue: .main); output.metadataObjectTypes = [.qr]
        DispatchQueue.global(qos: .userInitiated).async { [weak self] in self?.session.startRunning() }
    }
    func metadataOutput(_ output: AVCaptureMetadataOutput, didOutput metadataObjects: [AVMetadataObject], from connection: AVCaptureConnection) {
        guard let value = (metadataObjects.first as? AVMetadataMachineReadableCodeObject)?.stringValue,
              value.hasPrefix("swiftshare://connect/v1/") else { return }
        stop(); onCode?(value)
    }
}

private struct EndpointCameraPreview: NSViewRepresentable {
    let session: AVCaptureSession
    func makeNSView(context: Context) -> EndpointPreviewNSView { let view = EndpointPreviewNSView(); view.preview.session = session; return view }
    func updateNSView(_ nsView: EndpointPreviewNSView, context: Context) { nsView.preview.session = session }
}
private final class EndpointPreviewNSView: NSView {
    let preview = AVCaptureVideoPreviewLayer()
    override init(frame frameRect: NSRect) { super.init(frame: frameRect); wantsLayer = true; layer = preview; preview.videoGravity = .resizeAspectFill }
    required init?(coder: NSCoder) { nil }
    override func layout() { super.layout(); preview.frame = bounds }
}

private final class ProbeContinuationGate<Value: Sendable>: @unchecked Sendable {
    private let lock = NSLock(); private var continuation: CheckedContinuation<Value, Error>?
    init(_ continuation: CheckedContinuation<Value, Error>) { self.continuation = continuation }
    func resume(returning value: Value) { lock.withLock { continuation?.resume(returning: value); continuation = nil } }
    func resume(throwing error: Error) { lock.withLock { continuation?.resume(throwing: error); continuation = nil } }
}
