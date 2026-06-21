import CryptoKit
import Foundation

public let discoverySchemaMajor: UInt16 = 1
public let discoverySchemaMinor: UInt16 = 0

public struct DiscoveryAdvertisement: Equatable, Sendable {
    public let rotatingID: Data
    public init(rotatingID: Data) {
        precondition(rotatingID.count == 16)
        self.rotatingID = rotatingID
    }
    public var instanceName: String { "ss-" + rotatingID.discoveryBase64URL }
    public var txt: [String: String] { ["v": "1", "rid": rotatingID.discoveryBase64URL] }
}

public enum EndpointAddressFamily: UInt8, Equatable, Sendable { case ipv4 = 4; case ipv6 = 6 }

public struct EndpointAddress: Equatable, Sendable {
    public let family: EndpointAddressFamily
    public let bytes: Data
    public init(family: EndpointAddressFamily, bytes: Data) {
        precondition(bytes.count == (family == .ipv4 ? 4 : 16))
        self.family = family; self.bytes = bytes
    }
}

public struct EndpointQRBody: Equatable, Sendable {
    public let peerKeyID: Data
    public let port: UInt16
    public let expiresAt: Date
    public let ticket: Data
    public let addresses: [EndpointAddress]
    public init(peerKeyID: Data, port: UInt16, expiresAt: Date, ticket: Data, addresses: [EndpointAddress]) {
        precondition(peerKeyID.count == 32 && ticket.count == 32 && !addresses.isEmpty && addresses.count <= 8)
        self.peerKeyID = peerKeyID; self.port = port; self.expiresAt = expiresAt; self.ticket = ticket; self.addresses = addresses
    }
}

public struct EndpointQREnvelope: Equatable, Sendable {
    public let bodyBytes: Data
    public let signatureP1363: Data
    public init(bodyBytes: Data, signatureP1363: Data) {
        precondition(bodyBytes.count <= 1024 && signatureP1363.count == 64)
        self.bodyBytes = bodyBytes; self.signatureP1363 = signatureP1363
    }
}

public enum EndpointQRCodec {
    private static let prefix = "swiftshare://connect/v1/"
    private static let bodyMagic = Data("SSEQ".utf8)
    private static let envelopeMagic = Data("SSEC".utf8)
    private static let domain = Data("SwiftShare-Endpoint-QR-v1".utf8)

    public static func encodeBody(_ body: EndpointQRBody) -> Data {
        var writer = DiscoveryFixedWriter()
        writer.data(bodyMagic); writer.u16(discoverySchemaMajor); writer.u16(discoverySchemaMinor)
        writer.data(body.peerKeyID); writer.u16(body.port); writer.u64(UInt64(body.expiresAt.timeIntervalSince1970))
        writer.data(body.ticket); writer.u8(UInt8(body.addresses.count))
        for address in body.addresses {
            writer.u8(address.family.rawValue); writer.u8(UInt8(address.bytes.count)); writer.data(address.bytes)
        }
        return writer.value
    }

    public static func decodeBody(_ data: Data) throws -> EndpointQRBody {
        var reader = DiscoveryFixedReader(data)
        guard try reader.data(4) == bodyMagic,
              try reader.u16() == discoverySchemaMajor,
              try reader.u16() == discoverySchemaMinor
        else { throw DiscoveryContractError.invalidQR }
        let peer = try reader.data(32); let port = try reader.u16(); let expiry = try reader.u64()
        let ticket = try reader.data(32); let count = Int(try reader.u8())
        guard port > 0, (1 ... 8).contains(count) else { throw DiscoveryContractError.invalidQR }
        var addresses: [EndpointAddress] = []
        for _ in 0 ..< count {
            guard let family = EndpointAddressFamily(rawValue: try reader.u8()) else { throw DiscoveryContractError.invalidQR }
            let length = Int(try reader.u8()); guard length == (family == .ipv4 ? 4 : 16) else { throw DiscoveryContractError.invalidQR }
            addresses.append(EndpointAddress(family: family, bytes: try reader.data(length)))
        }
        guard reader.finished else { throw DiscoveryContractError.invalidQR }
        return EndpointQRBody(peerKeyID: peer, port: port, expiresAt: Date(timeIntervalSince1970: TimeInterval(expiry)), ticket: ticket, addresses: addresses)
    }

    public static func signingDigest(bodyBytes: Data) -> Data { Data(SHA256.hash(data: domain + bodyBytes)) }

    public static func encodeURI(_ envelope: EndpointQREnvelope) -> String {
        var writer = DiscoveryFixedWriter(); writer.data(envelopeMagic); writer.u16(UInt16(envelope.bodyBytes.count))
        writer.data(envelope.bodyBytes); writer.data(envelope.signatureP1363)
        return prefix + writer.value.discoveryBase64URL
    }

    public static func decodeURI(_ uri: String, now: Date = Date()) throws -> (EndpointQRBody, EndpointQREnvelope) {
        guard uri.hasPrefix(prefix), let raw = Data(discoveryBase64URL: String(uri.dropFirst(prefix.count))) else {
            throw DiscoveryContractError.invalidQR
        }
        var reader = DiscoveryFixedReader(raw)
        guard try reader.data(4) == envelopeMagic else { throw DiscoveryContractError.invalidQR }
        let bodyBytes = try reader.data(Int(try reader.u16())); let signature = try reader.data(64)
        guard reader.finished else { throw DiscoveryContractError.invalidQR }
        let body = try decodeBody(bodyBytes); guard body.expiresAt > now else { throw DiscoveryContractError.expired }
        return (body, EndpointQREnvelope(bodyBytes: bodyBytes, signatureP1363: signature))
    }
}

public struct DiscoveryProbe: Equatable, Sendable {
    public let rotatingID: Data
    public let nonce: Data
    public init(rotatingID: Data, nonce: Data) {
        precondition(rotatingID.count == 16 && nonce.count == 16)
        self.rotatingID = rotatingID; self.nonce = nonce
    }
}

public enum DiscoveryProbeCodec {
    public static let magic = Data("SSDR".utf8)
    public static func encodeRequest(_ value: DiscoveryProbe) -> Data { magic + Data([1, 0]) + value.rotatingID + value.nonce }
    public static func decodeRequest(_ data: Data) throws -> DiscoveryProbe {
        guard data.count == 38, data.prefix(4) == magic, data[4] == 1, data[5] == 0 else { throw DiscoveryContractError.invalidProbe }
        return DiscoveryProbe(rotatingID: data.subdata(in: 6 ..< 22), nonce: data.subdata(in: 22 ..< 38))
    }
    public static func encodeReply(_ value: DiscoveryProbe, available: Bool) -> Data {
        magic + Data([1, 0, available ? 1 : 0]) + value.rotatingID + value.nonce
    }
    public static func decodeReply(_ data: Data) throws -> (DiscoveryProbe, Bool) {
        guard data.count == 39, data.prefix(4) == magic, data[4] == 1, data[5] == 0 else { throw DiscoveryContractError.invalidProbe }
        return (DiscoveryProbe(rotatingID: data.subdata(in: 7 ..< 23), nonce: data.subdata(in: 23 ..< 39)), data[6] == 1)
    }
}

public enum PeerAvailability: Equatable, Sendable { case offline, locating, authenticating, online, incompatible, needsQR }
public struct PeerRouteState: Equatable, Sendable {
    public let routes: Set<String>; public let availability: PeerAvailability
    public init(routes: Set<String> = [], availability: PeerAvailability = .offline) { self.routes = routes; self.availability = availability }
}
public enum PeerRouteEvent: Equatable, Sendable {
    case candidate(String), authenticating(String), authenticated(String), lost(String), incompatible, discoveryUnavailable, reset
}
public func reducePeerRoute(_ state: PeerRouteState, _ event: PeerRouteEvent) -> PeerRouteState {
    switch event {
    case .candidate: PeerRouteState(routes: state.routes, availability: state.routes.isEmpty ? .locating : .online)
    case .authenticating: PeerRouteState(routes: state.routes, availability: state.routes.isEmpty ? .authenticating : .online)
    case .authenticated(let id): PeerRouteState(routes: state.routes.union([id]), availability: .online)
    case .lost(let id):
        PeerRouteState(routes: state.routes.subtracting([id]), availability: state.routes.subtracting([id]).isEmpty ? .offline : .online)
    case .incompatible: PeerRouteState(routes: state.routes, availability: .incompatible)
    case .discoveryUnavailable: PeerRouteState(routes: state.routes, availability: state.routes.isEmpty ? .needsQR : .online)
    case .reset: PeerRouteState()
    }
}

public enum ConnectivityDiagnostic: String, Equatable, Sendable {
    case localNetworkPermissionDenied = "local_network_permission_denied"
    case wifiUnavailable = "wifi_unavailable"
    case vpnInterferenceSuspected = "vpn_interference_suspected"
    case automaticDiscoveryUnavailable = "automatic_discovery_unavailable"
    case clientIsolationSuspected = "client_isolation_suspected"
    case firewallSuspected = "firewall_suspected"
    case endpointStaleOrUnreachable = "endpoint_stale_or_unreachable"
}
public struct ConnectivityEvidence: Equatable, Sendable {
    public var permissionDenied = false; public var hasLANInterface = true; public var vpnActive = false
    public var automaticDiscoveryFailed = false; public var signedQRForSamePeer = false
    public var authenticatedUnicastSucceeded = false; public var sameSubnetTimedOut = false; public var connectionRefused = false
    public init() {}
}
public func classifyConnectivity(_ value: ConnectivityEvidence) -> ConnectivityDiagnostic {
    if value.permissionDenied { return .localNetworkPermissionDenied }
    if !value.hasLANInterface { return .wifiUnavailable }
    if value.vpnActive { return .vpnInterferenceSuspected }
    if value.automaticDiscoveryFailed && value.signedQRForSamePeer && value.authenticatedUnicastSucceeded { return .automaticDiscoveryUnavailable }
    if value.automaticDiscoveryFailed && value.signedQRForSamePeer && value.sameSubnetTimedOut { return .clientIsolationSuspected }
    if value.connectionRefused { return .firewallSuspected }
    return .endpointStaleOrUnreachable
}

public enum DiscoveryContractError: Error { case invalidQR, expired, invalidProbe }

private struct DiscoveryFixedWriter {
    private(set) var value = Data()
    mutating func data(_ data: Data) { value.append(data) }
    mutating func u8(_ input: UInt8) { value.append(input) }
    mutating func u16(_ input: UInt16) { var v = input.bigEndian; value.append(Data(bytes: &v, count: 2)) }
    mutating func u64(_ input: UInt64) { var v = input.bigEndian; value.append(Data(bytes: &v, count: 8)) }
}
private struct DiscoveryFixedReader {
    let bytes: Data; var index = 0; var finished: Bool { index == bytes.count }
    init(_ bytes: Data) { self.bytes = bytes }
    mutating func data(_ count: Int) throws -> Data { guard count >= 0, index + count <= bytes.count else { throw DiscoveryContractError.invalidQR }; defer { index += count }; return bytes.subdata(in: index ..< index + count) }
    mutating func u8() throws -> UInt8 { try data(1)[0] }
    mutating func u16() throws -> UInt16 { try data(2).reduce(UInt16(0)) { ($0 << 8) | UInt16($1) } }
    mutating func u64() throws -> UInt64 { try data(8).reduce(UInt64(0)) { ($0 << 8) | UInt64($1) } }
}
private extension Data {
    var discoveryBase64URL: String { base64EncodedString().replacingOccurrences(of: "+", with: "-").replacingOccurrences(of: "/", with: "_").replacingOccurrences(of: "=", with: "") }
    init?(discoveryBase64URL: String) {
        var value = discoveryBase64URL.replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")
        value += String(repeating: "=", count: (4 - value.count % 4) % 4); self.init(base64Encoded: value)
    }
}
