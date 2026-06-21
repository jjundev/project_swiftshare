import CryptoKit
import Foundation

public let pairingProtocolMajor: UInt16 = 1
public let pairingProtocolMinor: UInt16 = 0

public struct PairingLimits: Equatable, Sendable {
    public let maximumQRBytes: Int
    public let maximumFrameBytes: Int
    public let failureBudget: Int
    public let admissionDuration: TimeInterval
    public let confirmationDuration: TimeInterval
    public let recoveryDuration: TimeInterval

    public init(
        maximumQRBytes: Int = 2_048,
        maximumFrameBytes: Int = 65_536,
        failureBudget: Int = 3,
        admissionDuration: TimeInterval = 60,
        confirmationDuration: TimeInterval = 120,
        recoveryDuration: TimeInterval = 600
    ) {
        self.maximumQRBytes = maximumQRBytes
        self.maximumFrameBytes = maximumFrameBytes
        self.failureBudget = failureBudget
        self.admissionDuration = admissionDuration
        self.confirmationDuration = confirmationDuration
        self.recoveryDuration = recoveryDuration
    }
}

public struct PairingQRPayload: Equatable, Sendable {
    public let protocolMajor: UInt16
    public let protocolMinor: UInt16
    public let sessionID: UUID
    public let host: String
    public let port: UInt16
    public let certificateSHA256: Data
    public let token: Data
    public let expiresAt: Date

    public init(
        protocolMajor: UInt16 = pairingProtocolMajor,
        protocolMinor: UInt16 = pairingProtocolMinor,
        sessionID: UUID,
        host: String,
        port: UInt16,
        certificateSHA256: Data,
        token: Data,
        expiresAt: Date
    ) throws {
        guard protocolMajor == pairingProtocolMajor,
              !host.isEmpty,
              host.utf8.count <= 255,
              certificateSHA256.count == 32,
              token.count == 32
        else { throw PairingError.invalidQR }
        self.protocolMajor = protocolMajor
        self.protocolMinor = protocolMinor
        self.sessionID = sessionID
        self.host = host
        self.port = port
        self.certificateSHA256 = certificateSHA256
        self.token = token
        self.expiresAt = expiresAt
    }

    public func encodedURI(limits: PairingLimits = PairingLimits()) throws -> String {
        var encoder = PairingProtoWriter()
        encoder.variable(field: 1, value: UInt64(protocolMajor))
        encoder.variable(field: 2, value: UInt64(protocolMinor))
        encoder.bytes(field: 3, value: sessionID.pairingBytes)
        encoder.string(field: 4, value: host)
        encoder.variable(field: 5, value: UInt64(port))
        encoder.bytes(field: 6, value: certificateSHA256)
        encoder.bytes(field: 7, value: token)
        encoder.variable(field: 8, value: UInt64(expiresAt.timeIntervalSince1970))
        let encoded = encoder.data
        guard encoded.count <= limits.maximumQRBytes else { throw PairingError.oversized }
        return "swiftshare://pair/v1/" + encoded.base64URLEncodedString()
    }

    public static func decode(
        _ uri: String,
        now: Date,
        limits: PairingLimits = PairingLimits()
    ) throws -> PairingQRPayload {
        let prefix = "swiftshare://pair/v1/"
        guard uri.hasPrefix(prefix),
              let data = Data(base64URL: String(uri.dropFirst(prefix.count))),
              data.count <= limits.maximumQRBytes
        else { throw PairingError.invalidQR }
        var reader = PairingProtoReader(data)
        var major: UInt16?
        var minor: UInt16 = 0
        var session: UUID?
        var host: String?
        var port: UInt16?
        var fingerprint: Data?
        var token: Data?
        var expiry: Date?
        while let field = try reader.nextField() {
            switch field.number {
            case 1: major = UInt16(try reader.variable(field))
            case 2: minor = UInt16(try reader.variable(field))
            case 3: session = UUID(pairingBytes: try reader.bytes(field))
            case 4: host = String(data: try reader.bytes(field), encoding: .utf8)
            case 5: port = UInt16(try reader.variable(field))
            case 6: fingerprint = try reader.bytes(field)
            case 7: token = try reader.bytes(field)
            case 8: expiry = Date(timeIntervalSince1970: TimeInterval(try reader.variable(field)))
            default: try reader.skip(field)
            }
        }
        guard let major, let session, let host, let port, let fingerprint, let token, let expiry,
              expiry > now
        else { throw PairingError.invalidQR }
        return try PairingQRPayload(
            protocolMajor: major,
            protocolMinor: minor,
            sessionID: session,
            host: host,
            port: port,
            certificateSHA256: fingerprint,
            token: token,
            expiresAt: expiry
        )
    }
}

public struct PairingDeviceDescriptor: Equatable, Sendable {
    public let canonicalSPKI: Data
    public let displayName: String
    public let platform: String

    public init(canonicalSPKI: Data, displayName: String, platform: String) throws {
        let normalized = displayName.precomposedStringWithCanonicalMapping
        guard !canonicalSPKI.isEmpty,
              !normalized.isEmpty,
              normalized.utf8.count <= 128,
              platform.utf8.count <= 32,
              !normalized.unicodeScalars.contains(where: { $0.properties.isBidiControl || $0.value < 0x20 })
        else { throw PairingError.invalidIdentity }
        self.canonicalSPKI = canonicalSPKI
        self.displayName = normalized
        self.platform = platform
    }

    public var keyID: Data { Data(SHA256.hash(data: canonicalSPKI)) }
}

public enum PairingRole: String, Sendable { case mac, android }

public struct PairingTranscript: Equatable, Sendable {
    public let digest: Data

    public init(
        sessionID: UUID,
        token: Data,
        certificateSHA256: Data,
        clientNonce: Data,
        serverNonce: Data,
        mac: PairingDeviceDescriptor,
        android: PairingDeviceDescriptor
    ) throws {
        guard token.count == 32,
              certificateSHA256.count == 32,
              clientNonce.count == 32,
              serverNonce.count == 32
        else { throw PairingError.invalidTranscript }
        var input = Data("SwiftShare-Pairing-v1".utf8)
        input.append(sessionID.pairingBytes)
        input.append(Data(SHA256.hash(data: token)))
        input.append(certificateSHA256)
        input.append(clientNonce)
        input.append(serverNonce)
        for value in [mac.canonicalSPKI, Data(mac.displayName.utf8), Data(mac.platform.utf8),
                      android.canonicalSPKI, Data(android.displayName.utf8), Data(android.platform.utf8)] {
            var length = UInt32(value.count).bigEndian
            input.append(Data(bytes: &length, count: 4))
            input.append(value)
        }
        digest = Data(SHA256.hash(data: input))
    }

    public func proofDigest(for role: PairingRole) -> Data {
        Data(SHA256.hash(data: Data("SwiftShare-Pairing-v1-proof-\(role.rawValue)".utf8) + digest))
    }

    public var comparisonCode: String {
        var counter: UInt32 = 0
        let upper = UInt32.max - (UInt32.max % 1_000_000)
        while true {
            var value = counter.bigEndian
            let candidate = Data(SHA256.hash(
                data: Data("SwiftShare-Pairing-v1-code".utf8) + digest + Data(bytes: &value, count: 4)
            )).prefix(4).reduce(UInt32(0)) { ($0 << 8) | UInt32($1) }
            if candidate < upper { return String(format: "%06u", candidate % 1_000_000) }
            counter &+= 1
        }
    }
}

public struct PairingPeerRecord: Codable, Equatable, Sendable {
    public let keyIDHex: String
    public let canonicalSPKIBase64: String
    public let displayName: String
    public let platform: String
    public let pairedAt: Date

    public init(descriptor: PairingDeviceDescriptor, pairedAt: Date) {
        keyIDHex = descriptor.keyID.hexString
        canonicalSPKIBase64 = descriptor.canonicalSPKI.base64EncodedString()
        displayName = descriptor.displayName
        platform = descriptor.platform
        self.pairedAt = pairedAt
    }
}

public enum PairingSessionPhase: Equatable, Sendable {
    case accepting
    case claimed(candidateKeyID: Data, confirmationDeadline: Date)
    case committed(candidateKeyID: Data, recoveryDeadline: Date)
    case invalidated(PairingError)
}

public actor PairingSession {
    public let payload: PairingQRPayload
    private let limits: PairingLimits
    private(set) public var phase: PairingSessionPhase = .accepting
    private var failures = 0

    public init(payload: PairingQRPayload, limits: PairingLimits = PairingLimits()) {
        self.payload = payload
        self.limits = limits
    }

    public func admit(sessionID: UUID, token: Data, now: Date) throws {
        guard case .accepting = phase else { throw PairingError.alreadyClaimed }
        guard now < payload.expiresAt else {
            phase = .invalidated(.expired)
            throw PairingError.expired
        }
        guard sessionID == payload.sessionID,
              token.count == 32,
              constantTimeEqual(token, payload.token)
        else {
            try fail(.invalidToken)
            throw PairingError.invalidToken
        }
    }

    public func claim(candidateKeyID: Data, proofIsValid: Bool, now: Date) throws {
        guard case .accepting = phase else { throw PairingError.alreadyClaimed }
        guard proofIsValid else {
            try fail(.invalidProof)
            throw PairingError.invalidProof
        }
        phase = .claimed(
            candidateKeyID: candidateKeyID,
            confirmationDeadline: now.addingTimeInterval(limits.confirmationDuration)
        )
    }

    public func requireConfirmation(candidateKeyID: Data, now: Date) throws {
        guard case .claimed(let expected, let deadline) = phase,
              constantTimeEqual(expected, candidateKeyID)
        else { throw PairingError.alreadyClaimed }
        guard now < deadline else {
            phase = .invalidated(.expired)
            throw PairingError.expired
        }
    }

    public func commit(candidateKeyID: Data, now: Date) throws {
        try requireConfirmation(candidateKeyID: candidateKeyID, now: now)
        phase = .committed(
            candidateKeyID: candidateKeyID,
            recoveryDeadline: now.addingTimeInterval(limits.recoveryDuration)
        )
    }

    public func cancel() { phase = .invalidated(.cancelled) }

    private func fail(_ error: PairingError) throws {
        failures += 1
        if failures >= limits.failureBudget {
            phase = .invalidated(.failureBudgetExhausted)
            throw PairingError.failureBudgetExhausted
        }
    }
}

public enum PairingError: String, Error, Codable, Equatable, Sendable {
    case invalidQR = "invalid_qr"
    case oversized = "oversized"
    case expired = "expired"
    case invalidToken = "invalid_token"
    case invalidIdentity = "invalid_identity"
    case invalidTranscript = "invalid_transcript"
    case invalidProof = "invalid_proof"
    case alreadyClaimed = "already_claimed"
    case failureBudgetExhausted = "failure_budget_exhausted"
    case cancelled = "cancelled"
    case storage = "storage"
}

private func constantTimeEqual(_ lhs: Data, _ rhs: Data) -> Bool {
    guard lhs.count == rhs.count else { return false }
    return zip(lhs, rhs).reduce(UInt8(0)) { $0 | ($1.0 ^ $1.1) } == 0
}

private extension UUID {
    var pairingBytes: Data {
        var value = uuid
        return withUnsafeBytes(of: &value) { Data($0) }
    }

    init?(pairingBytes: Data) {
        guard pairingBytes.count == 16 else { return nil }
        var value: uuid_t = (0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)
        _ = withUnsafeMutableBytes(of: &value) { pairingBytes.copyBytes(to: $0) }
        self.init(uuid: value)
    }
}

private extension Data {
    func base64URLEncodedString() -> String {
        base64EncodedString().replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }

    init?(base64URL: String) {
        var value = base64URL.replacingOccurrences(of: "-", with: "+")
            .replacingOccurrences(of: "_", with: "/")
        value += String(repeating: "=", count: (4 - value.count % 4) % 4)
        self.init(base64Encoded: value)
    }

    var hexString: String { map { String(format: "%02x", $0) }.joined() }
}

private struct PairingProtoWriter {
    var data = Data()
    mutating func variable(field: Int, value: UInt64) { tag(field, 0); varint(value) }
    mutating func bytes(field: Int, value: Data) { tag(field, 2); varint(UInt64(value.count)); data.append(value) }
    mutating func string(field: Int, value: String) { bytes(field: field, value: Data(value.utf8)) }
    private mutating func tag(_ field: Int, _ wire: UInt64) { varint(UInt64(field << 3) | wire) }
    private mutating func varint(_ input: UInt64) {
        var value = input
        while value >= 0x80 { data.append(UInt8(value & 0x7f) | 0x80); value >>= 7 }
        data.append(UInt8(value))
    }
}

private struct PairingProtoReader {
    struct Field { let number: Int; let wire: UInt64 }
    let data: Data
    var index = 0
    init(_ data: Data) { self.data = data }
    mutating func nextField() throws -> Field? {
        guard index < data.count else { return nil }
        let tag = try readVarint()
        let field = Field(number: Int(tag >> 3), wire: tag & 7)
        guard field.number > 0, field.wire == 0 || field.wire == 2 else { throw PairingError.invalidQR }
        return field
    }
    mutating func variable(_ field: Field) throws -> UInt64 {
        guard field.wire == 0 else { throw PairingError.invalidQR }
        return try readVarint()
    }
    mutating func bytes(_ field: Field) throws -> Data {
        guard field.wire == 2 else { throw PairingError.invalidQR }
        let count = Int(try readVarint())
        guard count >= 0, index + count <= data.count else { throw PairingError.invalidQR }
        defer { index += count }
        return data.subdata(in: index ..< index + count)
    }
    mutating func skip(_ field: Field) throws { if field.wire == 0 { _ = try readVarint() } else { _ = try bytes(field) } }
    private mutating func readVarint() throws -> UInt64 {
        var value: UInt64 = 0
        for shift in stride(from: 0, through: 63, by: 7) {
            guard index < data.count else { throw PairingError.invalidQR }
            let byte = data[index]; index += 1
            value |= UInt64(byte & 0x7f) << UInt64(shift)
            if byte & 0x80 == 0 { return value }
        }
        throw PairingError.invalidQR
    }
}
