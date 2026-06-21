import Foundation

public enum PairingRecordType: UInt8, Sendable {
    case clientStart = 1, serverChallenge, clientProof, serverProof, clientDecision, commitReceipt, commitAck, error
}

public struct PairingWireDevice: Equatable, Sendable {
    public let certificateDER: Data
    public let canonicalSPKI: Data
    public let displayName: String
    public let platform: String
    public init(certificateDER: Data, canonicalSPKI: Data, displayName: String, platform: String) {
        self.certificateDER = certificateDER; self.canonicalSPKI = canonicalSPKI
        self.displayName = displayName; self.platform = platform
    }
    public var descriptor: PairingDeviceDescriptor { get throws { try PairingDeviceDescriptor(canonicalSPKI: canonicalSPKI, displayName: displayName, platform: platform) } }
}

public enum PairingWireMessage: Equatable, Sendable {
    case clientStart(sessionID: UUID, token: Data, device: PairingWireDevice, nonce: Data)
    case serverChallenge(device: PairingWireDevice, nonce: Data)
    case clientProof(signature: Data)
    case serverProof(signature: Data)
    case clientDecision(accepted: Bool, signature: Data)
    case commitReceipt(sessionID: UUID, transcriptSHA256: Data, macKeyID: Data, androidKeyID: Data, committedAt: Date, signature: Data)
    case commitAck(receiptSHA256: Data)
    case error(code: String)

    public var type: PairingRecordType {
        switch self {
        case .clientStart: .clientStart; case .serverChallenge: .serverChallenge
        case .clientProof: .clientProof; case .serverProof: .serverProof
        case .clientDecision: .clientDecision; case .commitReceipt: .commitReceipt
        case .commitAck: .commitAck; case .error: .error
        }
    }
}

public enum PairingWireCodec {
    public static func encode(_ message: PairingWireMessage) -> Data {
        var w = PairingMessageWriter()
        switch message {
        case .clientStart(let id, let token, let device, let nonce):
            w.bytes(1, id.pairingWireBytes); w.bytes(2, token); w.bytes(3, encodeDevice(device)); w.bytes(4, nonce)
        case .serverChallenge(let device, let nonce):
            w.bytes(1, encodeDevice(device)); w.bytes(2, nonce)
        case .clientProof(let signature), .serverProof(let signature): w.bytes(1, signature)
        case .clientDecision(let accepted, let signature): w.variable(1, accepted ? 1 : 0); w.bytes(2, signature)
        case .commitReceipt(let id, let transcript, let mac, let android, let committedAt, let signature):
            w.bytes(1, id.pairingWireBytes); w.bytes(2, transcript); w.bytes(3, mac); w.bytes(4, android)
            w.variable(5, UInt64(committedAt.timeIntervalSince1970)); w.bytes(6, signature)
        case .commitAck(let digest): w.bytes(1, digest)
        case .error(let code): w.string(1, code)
        }
        return w.data
    }

    public static func decode(type: PairingRecordType, data: Data) throws -> PairingWireMessage {
        guard data.count <= PairingLimits().maximumFrameBytes else { throw PairingError.oversized }
        var r = PairingMessageReader(data)
        var fields: [Int: Data] = [:]; var integers: [Int: UInt64] = [:]
        while let field = try r.next() {
            if field.wire == 0 { integers[field.number] = try r.variable(field) }
            else { fields[field.number] = try r.bytes(field) }
        }
        switch type {
        case .clientStart:
            return .clientStart(
                sessionID: try uuid(fields[1]), token: try required(fields[2]),
                device: try decodeDevice(required(fields[3])), nonce: try sized(fields[4], 32)
            )
        case .serverChallenge: return .serverChallenge(device: try decodeDevice(required(fields[1])), nonce: try sized(fields[2], 32))
        case .clientProof: return .clientProof(signature: try sized(fields[1], 64))
        case .serverProof: return .serverProof(signature: try sized(fields[1], 64))
        case .clientDecision: return .clientDecision(accepted: integers[1] == 1, signature: try sized(fields[2], 64))
        case .commitReceipt:
            return .commitReceipt(
                sessionID: try uuid(fields[1]), transcriptSHA256: try sized(fields[2], 32),
                macKeyID: try sized(fields[3], 32), androidKeyID: try sized(fields[4], 32),
                committedAt: Date(timeIntervalSince1970: TimeInterval(integers[5] ?? 0)), signature: try sized(fields[6], 64)
            )
        case .commitAck: return .commitAck(receiptSHA256: try sized(fields[1], 32))
        case .error:
            guard let value = fields[1], let code = String(data: value, encoding: .utf8), code.utf8.count <= 128 else { throw PairingError.invalidTranscript }
            return .error(code: code)
        }
    }

    private static func encodeDevice(_ device: PairingWireDevice) -> Data {
        var w = PairingMessageWriter(); w.bytes(1, device.certificateDER); w.bytes(2, device.canonicalSPKI)
        w.string(3, device.displayName); w.string(4, device.platform); return w.data
    }
    private static func decodeDevice(_ data: Data) throws -> PairingWireDevice {
        var r = PairingMessageReader(data); var values: [Int: Data] = [:]
        while let field = try r.next() { guard field.wire == 2 else { throw PairingError.invalidTranscript }; values[field.number] = try r.bytes(field) }
        guard let cert = values[1], cert.count <= 8_192, let spki = values[2], spki.count <= 512,
              let nameData = values[3], let name = String(data: nameData, encoding: .utf8),
              let platformData = values[4], let platform = String(data: platformData, encoding: .utf8)
        else { throw PairingError.invalidIdentity }
        _ = try PairingDeviceDescriptor(canonicalSPKI: spki, displayName: name, platform: platform)
        return PairingWireDevice(certificateDER: cert, canonicalSPKI: spki, displayName: name, platform: platform)
    }
    private static func required(_ value: Data?) throws -> Data { guard let value else { throw PairingError.invalidTranscript }; return value }
    private static func sized(_ value: Data?, _ size: Int) throws -> Data { let value = try required(value); guard value.count == size else { throw PairingError.invalidTranscript }; return value }
    private static func uuid(_ value: Data?) throws -> UUID { guard let result = UUID(pairingWireBytes: try sized(value, 16)) else { throw PairingError.invalidTranscript }; return result }
}

private extension UUID {
    var pairingWireBytes: Data { var value = uuid; return withUnsafeBytes(of: &value) { Data($0) } }
    init?(pairingWireBytes: Data) { guard pairingWireBytes.count == 16 else { return nil }; var v: uuid_t = (0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0); _ = withUnsafeMutableBytes(of: &v) { pairingWireBytes.copyBytes(to: $0) }; self.init(uuid: v) }
}

private struct PairingMessageWriter {
    var data = Data()
    mutating func variable(_ field: Int, _ value: UInt64) { varint(UInt64(field << 3)); varint(value) }
    mutating func bytes(_ field: Int, _ value: Data) { varint(UInt64(field << 3 | 2)); varint(UInt64(value.count)); data.append(value) }
    mutating func string(_ field: Int, _ value: String) { bytes(field, Data(value.utf8)) }
    private mutating func varint(_ input: UInt64) { var v = input; while v >= 128 { data.append(UInt8(v & 127) | 128); v >>= 7 }; data.append(UInt8(v)) }
}
private struct PairingMessageReader {
    struct Field { let number: Int; let wire: UInt64 }
    let data: Data; var index = 0
    init(_ data: Data) { self.data = data }
    mutating func next() throws -> Field? { guard index < data.count else { return nil }; let tag = try varint(); let f = Field(number: Int(tag >> 3), wire: tag & 7); guard f.number > 0, f.wire == 0 || f.wire == 2 else { throw PairingError.invalidTranscript }; return f }
    mutating func variable(_ field: Field) throws -> UInt64 { guard field.wire == 0 else { throw PairingError.invalidTranscript }; return try varint() }
    mutating func bytes(_ field: Field) throws -> Data { guard field.wire == 2 else { throw PairingError.invalidTranscript }; let count = Int(try varint()); guard count >= 0, index + count <= data.count else { throw PairingError.invalidTranscript }; defer { index += count }; return data.subdata(in: index..<index+count) }
    private mutating func varint() throws -> UInt64 { var value: UInt64 = 0; for shift in stride(from: 0, through: 63, by: 7) { guard index < data.count else { throw PairingError.invalidTranscript }; let b = data[index]; index += 1; value |= UInt64(b & 127) << UInt64(shift); if b & 128 == 0 { return value } }; throw PairingError.invalidTranscript }
}
