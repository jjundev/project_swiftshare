import Foundation

public let transferHeaderSize = 32
public let transferChunkPreludeSize = 64
public let transferChunkTarget = 4 * 1024 * 1024
public let transferControlLimit = 256 * 1024
public let transferRecordLimit = 16 * 1024 * 1024
public let transferBootstrapLimit = 64 * 1024

public enum TransferProtocolError: Error, Equatable, Sendable {
    case violation(String)
}

public enum TransferRecordType: UInt8, Equatable, Sendable {
    case manifest = 1
    case approval = 2
    case progress = 3
    case senderFinished = 4
    case cancel = 5
    case terminalResult = 6
    case chunk = 7
    case sessionHello = 240
    case sessionDecision = 241

    init(code: UInt8) throws {
        guard let value = Self(rawValue: code) else {
            throw TransferProtocolError.violation("unknown_record_type")
        }
        self = value
    }
}

public struct TransferRecordHeader: Equatable, Sendable {
    public let type: TransferRecordType
    public let payloadLength: Int
    public let sessionID: UUID
    public let protocolMajor: UInt16
    public let protocolMinor: UInt16

    public init(
        type: TransferRecordType,
        payloadLength: Int,
        sessionID: UUID,
        protocolMajor: UInt16 = 1,
        protocolMinor: UInt16 = 0
    ) {
        self.type = type
        self.payloadLength = payloadLength
        self.sessionID = sessionID
        self.protocolMajor = protocolMajor
        self.protocolMinor = protocolMinor
    }

    public func encoded() throws -> Data {
        try Self.validate(type: type, payloadLength: payloadLength)
        if type.isBootstrap, protocolMajor != 0 || protocolMinor != 0 {
            throw TransferProtocolError.violation("invalid_bootstrap_version")
        }
        var data = Data([0x53, 0x53, 0x48, 0x52, 0x01, type.rawValue])
        data.appendBigEndian(UInt16(0))
        data.appendBigEndian(protocolMajor)
        data.appendBigEndian(protocolMinor)
        data.appendBigEndian(UInt32(payloadLength))
        data.append(contentsOf: sessionID.transferBytes)
        return data
    }

    public static func decode(_ data: Data) throws -> TransferRecordHeader {
        guard data.count == transferHeaderSize else {
            throw TransferProtocolError.violation("truncated_header")
        }
        var reader = TransferDataReader(data)
        guard try reader.readData(count: 4) == Data([0x53, 0x53, 0x48, 0x52]) else {
            throw TransferProtocolError.violation("invalid_magic")
        }
        guard try reader.readUInt8() == 1 else {
            throw TransferProtocolError.violation("unsupported_header_version")
        }
        let type = try TransferRecordType(code: reader.readUInt8())
        guard try reader.readUInt16() == 0 else {
            throw TransferProtocolError.violation("reserved_flags")
        }
        let major = try reader.readUInt16()
        let minor = try reader.readUInt16()
        if type.isBootstrap {
            guard major == 0, minor == 0 else {
                throw TransferProtocolError.violation("invalid_bootstrap_version")
            }
        } else if major != 1 || minor != 0 {
            throw TransferProtocolError.violation("incompatible_version")
        }
        let length = Int(try reader.readUInt32())
        try validate(type: type, payloadLength: length)
        let sessionID = try UUID(transferBytes: reader.readData(count: 16))
        return TransferRecordHeader(
            type: type,
            payloadLength: length,
            sessionID: sessionID,
            protocolMajor: major,
            protocolMinor: minor
        )
    }

    private static func validate(type: TransferRecordType, payloadLength: Int) throws {
        guard payloadLength >= 0, payloadLength <= transferRecordLimit else {
            throw TransferProtocolError.violation("oversized_record")
        }
        let typeLimit = if type.isBootstrap {
            transferBootstrapLimit
        } else if type == .chunk {
            transferChunkTarget + transferChunkPreludeSize
        } else {
            transferControlLimit
        }
        guard payloadLength <= typeLimit else {
            throw TransferProtocolError.violation("oversized_\(type.protocolName)")
        }
        if type == .chunk, payloadLength < transferChunkPreludeSize {
            throw TransferProtocolError.violation("truncated_chunk")
        }
    }
}

public struct TransferChunkPrelude: Equatable, Sendable {
    public let itemID: UUID
    public let chunkIndex: UInt32
    public let offset: UInt64
    public let dataLength: UInt32
    public let sha256: Data

    public init(itemID: UUID, chunkIndex: UInt32, offset: UInt64, dataLength: UInt32, sha256: Data) {
        precondition(dataLength <= transferChunkTarget)
        precondition(sha256.count == 32)
        self.itemID = itemID
        self.chunkIndex = chunkIndex
        self.offset = offset
        self.dataLength = dataLength
        self.sha256 = sha256
    }

    public func encoded() -> Data {
        var data = Data(itemID.transferBytes)
        data.appendBigEndian(chunkIndex)
        data.appendBigEndian(offset)
        data.appendBigEndian(dataLength)
        data.append(sha256)
        return data
    }

    public static func decode(_ data: Data) throws -> TransferChunkPrelude {
        guard data.count == transferChunkPreludeSize else {
            throw TransferProtocolError.violation("invalid_chunk_prelude")
        }
        var reader = TransferDataReader(data)
        let itemID = try UUID(transferBytes: reader.readData(count: 16))
        let index = try reader.readUInt32()
        let offset = try reader.readUInt64()
        let length = try reader.readUInt32()
        let digest = try reader.readData(count: 32)
        guard length <= transferChunkTarget else {
            throw TransferProtocolError.violation("invalid_chunk_bounds")
        }
        return TransferChunkPrelude(
            itemID: itemID,
            chunkIndex: index,
            offset: offset,
            dataLength: length,
            sha256: digest
        )
    }
}

public struct TransferWireRecord: Equatable, Sendable {
    public let header: TransferRecordHeader
    public let payload: Data

    public init(header: TransferRecordHeader, payload: Data) {
        self.header = header
        self.payload = payload
    }
}

public struct IncrementalTransferRecordParser: Sendable {
    private var pending = Data()

    public init() {}

    public mutating func feed(_ fragment: Data) throws -> [TransferWireRecord] {
        pending.append(fragment)
        var result: [TransferWireRecord] = []
        var consumed = 0
        while pending.count - consumed >= transferHeaderSize {
            let headerData = pending.subdata(in: consumed..<(consumed + transferHeaderSize))
            let header = try TransferRecordHeader.decode(headerData)
            let total = transferHeaderSize + header.payloadLength
            guard pending.count - consumed >= total else { break }
            let payloadStart = consumed + transferHeaderSize
            result.append(
                TransferWireRecord(
                    header: header,
                    payload: pending.subdata(in: payloadStart..<(payloadStart + header.payloadLength))
                )
            )
            consumed += total
        }
        if consumed > 0 { pending.removeFirst(consumed) }
        guard pending.count <= transferHeaderSize + transferRecordLimit else {
            throw TransferProtocolError.violation("oversized_buffer")
        }
        return result
    }

    public func finish() throws {
        guard pending.isEmpty else {
            throw TransferProtocolError.violation("truncated_record")
        }
    }
}

public struct TransferFileEntry: Equatable, Sendable {
    public let itemID: UUID
    public let displayName: String
    public let byteCount: UInt64
    public let sha256: Data
    public let mediaType: String
    public let chunkSize: UInt32

    public init(
        itemID: UUID,
        displayName: String,
        byteCount: UInt64,
        sha256: Data,
        mediaType: String,
        chunkSize: UInt32
    ) {
        self.itemID = itemID
        self.displayName = displayName
        self.byteCount = byteCount
        self.sha256 = sha256
        self.mediaType = mediaType
        self.chunkSize = chunkSize
    }
}

public enum TransferProgressPhase: UInt64, Equatable, Sendable {
    case transferring = 1
    case verifying = 2
    case committing = 3
}

public enum WireTerminalStatus: UInt64, Equatable, Sendable {
    case completed = 1
    case rejected = 2
    case cancelled = 3
    case failed = 4
}

public enum TransferErrorCategory: UInt64, Equatable, Sendable {
    case discovery = 1
    case authentication = 2
    case approval = 3
    case permission = 4
    case network = 5
    case `protocol` = 6
    case source = 7
    case storage = 8
    case integrity = 9
    case busy = 10
    case incompatibleVersion = 11
    case cancelled = 12
}

public enum TransferControlMessage: Equatable, Sendable {
    case manifest([TransferFileEntry])
    case approval(accepted: Bool, reasonCode: String)
    case progress(itemID: UUID, verifiedBytes: UInt64, verifiedChunks: UInt32, phase: TransferProgressPhase)
    case senderFinished(itemID: UUID, byteCount: UInt64, sha256: Data)
    case cancel(reasonCode: String)
    case terminalResult(
        status: WireTerminalStatus,
        errorCategory: TransferErrorCategory?,
        errorCode: String,
        committedArtifactID: String
    )
}

public enum TransferControlCodec {
    public static func encode(_ message: TransferControlMessage) throws -> Data {
        var writer = ProtoWriter()
        switch message {
        case .manifest(let entries):
            for entry in entries { writer.bytes(field: 1, value: try encode(entry)) }
        case .approval(let accepted, let reason):
            if accepted { writer.variable(field: 1, value: 1) }
            if !reason.isEmpty { writer.string(field: 2, value: reason) }
        case .progress(let itemID, let verifiedBytes, let verifiedChunks, let phase):
            writer.bytes(field: 1, value: Data(itemID.transferBytes))
            writer.variable(field: 2, value: verifiedBytes)
            writer.variable(field: 3, value: UInt64(verifiedChunks))
            writer.variable(field: 4, value: phase.rawValue)
        case .senderFinished(let itemID, let byteCount, let digest):
            writer.bytes(field: 1, value: Data(itemID.transferBytes))
            writer.variable(field: 2, value: byteCount)
            writer.bytes(field: 3, value: digest)
        case .cancel(let reason):
            writer.string(field: 1, value: reason)
        case .terminalResult(let status, let category, let code, let artifact):
            writer.variable(field: 1, value: status.rawValue)
            if let category { writer.variable(field: 2, value: category.rawValue) }
            if !code.isEmpty { writer.string(field: 3, value: code) }
            if !artifact.isEmpty { writer.string(field: 4, value: artifact) }
        }
        return writer.data
    }

    public static func decode(type: TransferRecordType, data: Data) throws -> TransferControlMessage {
        guard type != .chunk else { throw TransferProtocolError.violation("chunk_is_not_control") }
        guard data.count <= transferControlLimit else { throw TransferProtocolError.violation("oversized_control") }
        var reader = ProtoReader(data)
        switch type {
        case .manifest: return try decodeManifest(&reader)
        case .approval: return try decodeApproval(&reader)
        case .progress: return try decodeProgress(&reader)
        case .senderFinished: return try decodeFinished(&reader)
        case .cancel: return try decodeCancel(&reader)
        case .terminalResult: return try decodeTerminal(&reader)
        case .chunk: throw TransferProtocolError.violation("chunk_is_not_control")
        case .sessionHello, .sessionDecision:
            throw TransferProtocolError.violation("bootstrap_is_not_v1_control")
        }
    }

    private static func encode(_ entry: TransferFileEntry) throws -> Data {
        guard entry.sha256.count == 32, entry.chunkSize > 0, entry.chunkSize <= transferChunkTarget else {
            throw TransferProtocolError.violation("invalid_manifest_entry")
        }
        var writer = ProtoWriter()
        writer.bytes(field: 1, value: Data(entry.itemID.transferBytes))
        writer.string(field: 2, value: entry.displayName)
        writer.variable(field: 3, value: entry.byteCount)
        writer.bytes(field: 4, value: entry.sha256)
        if !entry.mediaType.isEmpty { writer.string(field: 5, value: entry.mediaType) }
        writer.variable(field: 6, value: UInt64(entry.chunkSize))
        return writer.data
    }

    private static func decodeManifest(_ reader: inout ProtoReader) throws -> TransferControlMessage {
        var entries: [TransferFileEntry] = []
        while let (field, wire) = try reader.nextField() {
            if field == 1, wire == 2 {
                var entryReader = ProtoReader(try reader.bytes())
                entries.append(try decodeEntry(&entryReader))
            } else { try reader.skip(wire: wire) }
        }
        guard entries.count == 1 else { throw TransferProtocolError.violation("manifest_requires_one_file") }
        return .manifest(entries)
    }

    private static func decodeEntry(_ reader: inout ProtoReader) throws -> TransferFileEntry {
        var id: UUID?
        var name = ""
        var count: UInt64 = 0
        var digest = Data()
        var media = ""
        var chunkSize: UInt32 = 0
        while let (field, wire) = try reader.nextField() {
            switch field {
            case 1: id = try UUID(transferBytes: reader.bytes())
            case 2: name = try reader.string()
            case 3: count = try reader.variable()
            case 4: digest = try reader.bytes()
            case 5: media = try reader.string()
            case 6:
                let value = try reader.variable()
                guard value <= UInt32.max else { throw TransferProtocolError.violation("invalid_manifest_entry") }
                chunkSize = UInt32(value)
            default: try reader.skip(wire: wire)
            }
        }
        guard let id, !name.isEmpty, digest.count == 32, chunkSize > 0, chunkSize <= transferChunkTarget else {
            throw TransferProtocolError.violation("invalid_manifest_entry")
        }
        return TransferFileEntry(
            itemID: id,
            displayName: name,
            byteCount: count,
            sha256: digest,
            mediaType: media,
            chunkSize: chunkSize
        )
    }

    private static func decodeApproval(_ reader: inout ProtoReader) throws -> TransferControlMessage {
        var accepted = false
        var reason = ""
        while let (field, wire) = try reader.nextField() {
            switch field {
            case 1: accepted = try reader.variable() != 0
            case 2: reason = try reader.string()
            default: try reader.skip(wire: wire)
            }
        }
        return .approval(accepted: accepted, reasonCode: reason)
    }

    private static func decodeProgress(_ reader: inout ProtoReader) throws -> TransferControlMessage {
        var id: UUID?
        var bytes: UInt64 = 0
        var chunks: UInt32 = 0
        var phase: TransferProgressPhase?
        while let (field, wire) = try reader.nextField() {
            switch field {
            case 1: id = try UUID(transferBytes: reader.bytes())
            case 2: bytes = try reader.variable()
            case 3:
                let value = try reader.variable()
                guard value <= UInt32.max else { throw TransferProtocolError.violation("invalid_progress") }
                chunks = UInt32(value)
            case 4: phase = TransferProgressPhase(rawValue: try reader.variable())
            default: try reader.skip(wire: wire)
            }
        }
        guard let id, let phase else { throw TransferProtocolError.violation("invalid_progress") }
        return .progress(itemID: id, verifiedBytes: bytes, verifiedChunks: chunks, phase: phase)
    }

    private static func decodeFinished(_ reader: inout ProtoReader) throws -> TransferControlMessage {
        var id: UUID?
        var count: UInt64 = 0
        var digest = Data()
        while let (field, wire) = try reader.nextField() {
            switch field {
            case 1: id = try UUID(transferBytes: reader.bytes())
            case 2: count = try reader.variable()
            case 3: digest = try reader.bytes()
            default: try reader.skip(wire: wire)
            }
        }
        guard let id, digest.count == 32 else { throw TransferProtocolError.violation("invalid_file_digest") }
        return .senderFinished(itemID: id, byteCount: count, sha256: digest)
    }

    private static func decodeCancel(_ reader: inout ProtoReader) throws -> TransferControlMessage {
        var reason = ""
        while let (field, wire) = try reader.nextField() {
            if field == 1 { reason = try reader.string() } else { try reader.skip(wire: wire) }
        }
        return .cancel(reasonCode: reason)
    }

    private static func decodeTerminal(_ reader: inout ProtoReader) throws -> TransferControlMessage {
        var status: WireTerminalStatus?
        var category: TransferErrorCategory?
        var code = ""
        var artifact = ""
        while let (field, wire) = try reader.nextField() {
            switch field {
            case 1: status = WireTerminalStatus(rawValue: try reader.variable())
            case 2: category = TransferErrorCategory(rawValue: try reader.variable())
            case 3: code = try reader.string()
            case 4: artifact = try reader.string()
            default: try reader.skip(wire: wire)
            }
        }
        guard let status else { throw TransferProtocolError.violation("invalid_terminal_status") }
        return .terminalResult(
            status: status,
            errorCategory: category,
            errorCode: code,
            committedArtifactID: artifact
        )
    }
}

public let minimumNegotiatedChunkSize = 64 * 1024

public struct ProtocolVersionRange: Equatable, Sendable {
    public let major: UInt16
    public let minimumMinor: UInt16
    public let maximumMinor: UInt16

    public init(major: UInt16, minimumMinor: UInt16, maximumMinor: UInt16) {
        precondition(major > 0 && minimumMinor <= maximumMinor)
        self.major = major
        self.minimumMinor = minimumMinor
        self.maximumMinor = maximumMinor
    }

    public func contains(_ version: NegotiatedProtocolVersion) -> Bool {
        version.major == major && version.minor >= minimumMinor && version.minor <= maximumMinor
    }
}

public struct NegotiatedProtocolVersion: Equatable, Sendable {
    public let major: UInt16
    public let minor: UInt16

    public init(major: UInt16, minor: UInt16) {
        precondition(major > 0)
        self.major = major
        self.minor = minor
    }
}

public struct SessionCapabilityOffer: Equatable, Sendable {
    public let name: String
    public let supportedVersions: [ProtocolVersionRange]
    public let requiredVersions: [ProtocolVersionRange]

    public init(name: String, supportedVersions: [ProtocolVersionRange], requiredVersions: [ProtocolVersionRange] = []) {
        precondition(name.range(of: #"^[a-z][a-z0-9_.-]{0,63}$"#, options: .regularExpression) != nil)
        precondition(!supportedVersions.isEmpty && supportedVersions.count <= 8 && requiredVersions.count <= 8)
        precondition(requiredVersions.allSatisfy { required in supportedVersions.contains { $0.contains(range: required) } })
        self.name = name
        self.supportedVersions = supportedVersions
        self.requiredVersions = requiredVersions
    }

    public func supports(_ version: NegotiatedProtocolVersion) -> Bool { supportedVersions.contains { $0.contains(version) } }
    public func requires(_ version: NegotiatedProtocolVersion) -> Bool { requiredVersions.contains { $0.contains(version) } }
}

public struct SessionOffer: Equatable, Sendable {
    public let versions: [ProtocolVersionRange]
    public let capabilities: [SessionCapabilityOffer]
    public let minimumChunkSize: Int
    public let maximumChunkSize: Int

    public init(
        versions: [ProtocolVersionRange],
        capabilities: [SessionCapabilityOffer],
        minimumChunkSize: Int,
        maximumChunkSize: Int
    ) {
        precondition(!versions.isEmpty && versions.count <= 8)
        precondition(capabilities.count <= 32 && Set(capabilities.map(\.name)).count == capabilities.count)
        precondition(minimumChunkSize >= minimumNegotiatedChunkSize)
        precondition(maximumChunkSize >= minimumChunkSize && maximumChunkSize <= transferChunkTarget)
        self.versions = versions
        self.capabilities = capabilities
        self.minimumChunkSize = minimumChunkSize
        self.maximumChunkSize = maximumChunkSize
    }

    public static let `default` = SessionOffer(
        versions: [ProtocolVersionRange(major: 1, minimumMinor: 0, maximumMinor: 0)],
        capabilities: [
            SessionCapabilityOffer(
                name: "file_transfer",
                supportedVersions: [ProtocolVersionRange(major: 1, minimumMinor: 0, maximumMinor: 0)],
                requiredVersions: [ProtocolVersionRange(major: 1, minimumMinor: 0, maximumMinor: 0)]
            )
        ],
        minimumChunkSize: minimumNegotiatedChunkSize,
        maximumChunkSize: transferChunkTarget
    )
}

public struct NegotiatedSessionParameters: Equatable, Sendable {
    public let version: NegotiatedProtocolVersion
    public let capabilities: [String]
    public let chunkSize: Int

    public init(version: NegotiatedProtocolVersion, capabilities: [String], chunkSize: Int) {
        self.version = version
        self.capabilities = capabilities
        self.chunkSize = chunkSize
    }
}

public enum BootstrapRejectionCategory: UInt64, Equatable, Sendable {
    case incompatibleVersion = 1
    case capability = 2
    case busy = 3
    case `protocol` = 4
    case authentication = 5
}

public struct SessionHello: Equatable, Sendable {
    public let offer: SessionOffer
    public let endpointTicket: Data?
    public init(offer: SessionOffer, endpointTicket: Data? = nil) {
        precondition(endpointTicket == nil || endpointTicket?.count == 32)
        self.offer = offer
        self.endpointTicket = endpointTicket
    }
}

public struct SessionDecision: Equatable, Sendable {
    public let accepted: Bool
    public let receiverOffer: SessionOffer
    public let negotiated: NegotiatedSessionParameters?
    public let rejectionCategory: BootstrapRejectionCategory?
    public let rejectionCode: String

    public init(
        accepted: Bool,
        receiverOffer: SessionOffer,
        negotiated: NegotiatedSessionParameters? = nil,
        rejectionCategory: BootstrapRejectionCategory? = nil,
        rejectionCode: String = ""
    ) {
        precondition(accepted == (negotiated != nil))
        precondition(accepted || rejectionCategory != nil)
        precondition(rejectionCode.utf8.count <= 64 && rejectionCode.unicodeScalars.allSatisfy { $0.value >= 0x20 && $0.value <= 0x7e })
        self.accepted = accepted
        self.receiverOffer = receiverOffer
        self.negotiated = negotiated
        self.rejectionCategory = rejectionCategory
        self.rejectionCode = rejectionCode
    }

    public static func accepted(_ parameters: NegotiatedSessionParameters, receiverOffer: SessionOffer = .default) -> Self {
        Self(accepted: true, receiverOffer: receiverOffer, negotiated: parameters)
    }

    public static func rejected(_ category: BootstrapRejectionCategory, code: String, receiverOffer: SessionOffer = .default) -> Self {
        Self(accepted: false, receiverOffer: receiverOffer, rejectionCategory: category, rejectionCode: code)
    }
}

public enum NegotiationResult: Equatable, Sendable {
    case accepted(NegotiatedSessionParameters)
    case rejected(BootstrapRejectionCategory, String)
}

public enum SessionNegotiator {
    private static let knownCapabilities: Set<String> = ["file_transfer"]

    public static func negotiate(sender: SessionOffer, receiver: SessionOffer) -> NegotiationResult {
        let commonMajors = Set(sender.versions.map(\.major)).intersection(receiver.versions.map(\.major))
        guard let major = commonMajors.max() else { return .rejected(.incompatibleVersion, "no_common_major") }
        var selectedMinor: UInt16?
        for left in sender.versions where left.major == major {
            for right in receiver.versions where right.major == major {
                let minimum = max(left.minimumMinor, right.minimumMinor)
                let maximum = min(left.maximumMinor, right.maximumMinor)
                if minimum <= maximum { selectedMinor = max(selectedMinor ?? 0, maximum) }
            }
        }
        guard let minor = selectedMinor else { return .rejected(.incompatibleVersion, "no_common_minor") }
        let version = NegotiatedProtocolVersion(major: major, minor: minor)
        let senderSupported = Dictionary(uniqueKeysWithValues: sender.capabilities.filter { $0.supports(version) }.map { ($0.name, $0) })
        let receiverSupported = Dictionary(uniqueKeysWithValues: receiver.capabilities.filter { $0.supports(version) }.map { ($0.name, $0) })
        let selected = Set(senderSupported.keys).intersection(receiverSupported.keys).filter { knownCapabilities.contains($0) }.sorted()
        let required = Set((sender.capabilities + receiver.capabilities).filter { $0.requires(version) }.map(\.name))
        guard required.allSatisfy({ knownCapabilities.contains($0) && selected.contains($0) }) else {
            return .rejected(.capability, "required_capability_unsupported")
        }
        let minimumChunk = max(sender.minimumChunkSize, receiver.minimumChunkSize)
        let maximumChunk = min(sender.maximumChunkSize, receiver.maximumChunkSize)
        guard minimumChunk <= maximumChunk else { return .rejected(.capability, "no_common_chunk_size") }
        return .accepted(NegotiatedSessionParameters(version: version, capabilities: selected, chunkSize: maximumChunk))
    }

    public static func validate(hello: SessionHello, decision: SessionDecision) throws -> NegotiatedSessionParameters {
        guard decision.accepted, let actual = decision.negotiated else {
            throw TransferProtocolError.violation(decision.rejectionCode)
        }
        guard case .accepted(let expected) = negotiate(sender: hello.offer, receiver: decision.receiverOffer), expected == actual else {
            throw TransferProtocolError.violation("invalid_negotiation_selection")
        }
        return actual
    }
}

public enum SessionBootstrapCodec {
    public static func encode(_ hello: SessionHello) -> Data {
        var writer = ProtoWriter(); writer.bytes(field: 1, value: encode(hello.offer))
        if let ticket = hello.endpointTicket { writer.bytes(field: 2, value: ticket) }
        return writer.data
    }

    public static func decodeHello(_ data: Data) throws -> SessionHello {
        guard data.count <= transferBootstrapLimit else { throw TransferProtocolError.violation("oversized_bootstrap") }
        var reader = ProtoReader(data); var offer: SessionOffer?; var ticket: Data?
        while let (field, wire) = try reader.nextField() {
            if field == 1 {
                try requireWire(wire, 2); guard offer == nil else { throw duplicate() }
                offer = try decodeOffer(reader.bytes())
            } else if field == 2 {
                try requireWire(wire, 2); guard ticket == nil else { throw duplicate() }
                ticket = try reader.bytes(); guard ticket?.count == 32 else { throw TransferProtocolError.violation("invalid_endpoint_ticket") }
            } else { try reader.skip(wire: wire) }
        }
        guard let offer else { throw TransferProtocolError.violation("malformed_hello") }
        return SessionHello(offer: offer, endpointTicket: ticket)
    }

    public static func encode(_ decision: SessionDecision) -> Data {
        var writer = ProtoWriter()
        if decision.accepted { writer.variable(field: 1, value: 1) }
        writer.bytes(field: 2, value: encode(decision.receiverOffer))
        if let negotiated = decision.negotiated { writer.bytes(field: 3, value: encode(negotiated)) }
        if let category = decision.rejectionCategory { writer.variable(field: 4, value: category.rawValue) }
        if !decision.rejectionCode.isEmpty { writer.string(field: 5, value: decision.rejectionCode) }
        return writer.data
    }

    public static func decodeDecision(_ data: Data) throws -> SessionDecision {
        guard data.count <= transferBootstrapLimit else { throw TransferProtocolError.violation("oversized_bootstrap") }
        var reader = ProtoReader(data); var accepted = false; var seenAccepted = false
        var offer: SessionOffer?; var negotiated: NegotiatedSessionParameters?; var category: BootstrapRejectionCategory?
        var code = ""; var seenCode = false
        while let (field, wire) = try reader.nextField() {
            switch field {
            case 1:
                try requireWire(wire, 0); guard !seenAccepted else { throw duplicate() }
                seenAccepted = true; accepted = try reader.variable() != 0
            case 2:
                try requireWire(wire, 2); guard offer == nil else { throw duplicate() }
                offer = try decodeOffer(reader.bytes())
            case 3:
                try requireWire(wire, 2); guard negotiated == nil else { throw duplicate() }
                negotiated = try decodeParameters(reader.bytes())
            case 4:
                try requireWire(wire, 0); guard category == nil else { throw duplicate() }
                category = BootstrapRejectionCategory(rawValue: try reader.variable())
                guard category != nil else { throw TransferProtocolError.violation("invalid_rejection_category") }
            case 5:
                try requireWire(wire, 2); guard !seenCode else { throw duplicate() }
                seenCode = true; code = try reader.string()
            default: try reader.skip(wire: wire)
            }
        }
        guard let offer else { throw TransferProtocolError.violation("invalid_decision") }
        guard accepted == (negotiated != nil), accepted || category != nil else {
            throw TransferProtocolError.violation("invalid_decision")
        }
        guard code.utf8.count <= 64,
              code.unicodeScalars.allSatisfy({ $0.value >= 0x20 && $0.value <= 0x7e })
        else { throw TransferProtocolError.violation("invalid_rejection_code") }
        return SessionDecision(
            accepted: accepted,
            receiverOffer: offer,
            negotiated: negotiated,
            rejectionCategory: category,
            rejectionCode: code
        )
    }

    private static func encode(_ offer: SessionOffer) -> Data {
        var writer = ProtoWriter()
        for version in offer.versions { writer.bytes(field: 1, value: encode(version)) }
        for capability in offer.capabilities { writer.bytes(field: 2, value: encode(capability)) }
        writer.variable(field: 3, value: UInt64(offer.minimumChunkSize))
        writer.variable(field: 4, value: UInt64(offer.maximumChunkSize))
        return writer.data
    }

    private static func decodeOffer(_ data: Data) throws -> SessionOffer {
        var reader = ProtoReader(data); var versions: [ProtocolVersionRange] = []; var capabilities: [SessionCapabilityOffer] = []
        var minimum: Int?; var maximum: Int?
        while let (field, wire) = try reader.nextField() {
            switch field {
            case 1: try requireWire(wire, 2); versions.append(try decodeRange(reader.bytes()))
            case 2: try requireWire(wire, 2); capabilities.append(try decodeCapability(reader.bytes()))
            case 3:
                try requireWire(wire, 0); guard minimum == nil else { throw duplicate() }
                minimum = try boundedInt(reader.variable())
            case 4:
                try requireWire(wire, 0); guard maximum == nil else { throw duplicate() }
                maximum = try boundedInt(reader.variable())
            default: try reader.skip(wire: wire)
            }
        }
        guard !versions.isEmpty, versions.count <= 8, capabilities.count <= 32,
              Set(capabilities.map(\.name)).count == capabilities.count,
              let minimum, let maximum,
              minimum >= minimumNegotiatedChunkSize, maximum >= minimum, maximum <= transferChunkTarget
        else { throw TransferProtocolError.violation("invalid_session_offer") }
        return SessionOffer(versions: versions, capabilities: capabilities, minimumChunkSize: minimum, maximumChunkSize: maximum)
    }

    private static func encode(_ range: ProtocolVersionRange) -> Data {
        var writer = ProtoWriter(); writer.variable(field: 1, value: UInt64(range.major)); writer.variable(field: 2, value: UInt64(range.minimumMinor)); writer.variable(field: 3, value: UInt64(range.maximumMinor)); return writer.data
    }

    private static func decodeRange(_ data: Data) throws -> ProtocolVersionRange {
        var reader = ProtoReader(data); var major: UInt16?; var minimum: UInt16?; var maximum: UInt16?
        while let (field, wire) = try reader.nextField() {
            switch field {
            case 1:
                try requireWire(wire, 0); guard major == nil else { throw duplicate() }
                let value = try reader.variable(); guard value <= UInt16.max else { throw TransferProtocolError.violation("invalid_version_range") }; major = UInt16(value)
            case 2:
                try requireWire(wire, 0); guard minimum == nil else { throw duplicate() }
                let value = try reader.variable(); guard value <= UInt16.max else { throw TransferProtocolError.violation("invalid_version_range") }; minimum = UInt16(value)
            case 3:
                try requireWire(wire, 0); guard maximum == nil else { throw duplicate() }
                let value = try reader.variable(); guard value <= UInt16.max else { throw TransferProtocolError.violation("invalid_version_range") }; maximum = UInt16(value)
            default: try reader.skip(wire: wire)
            }
        }
        guard let major, major > 0, let minimum, let maximum, minimum <= maximum else {
            throw TransferProtocolError.violation("invalid_version_range")
        }
        return ProtocolVersionRange(major: major, minimumMinor: minimum, maximumMinor: maximum)
    }

    private static func encode(_ capability: SessionCapabilityOffer) -> Data {
        var writer = ProtoWriter(); writer.string(field: 1, value: capability.name)
        for version in capability.supportedVersions { writer.bytes(field: 2, value: encode(version)) }
        for version in capability.requiredVersions { writer.bytes(field: 3, value: encode(version)) }
        return writer.data
    }

    private static func decodeCapability(_ data: Data) throws -> SessionCapabilityOffer {
        var reader = ProtoReader(data); var name: String?; var supported: [ProtocolVersionRange] = []; var required: [ProtocolVersionRange] = []
        while let (field, wire) = try reader.nextField() {
            switch field {
            case 1: try requireWire(wire, 2); guard name == nil else { throw duplicate() }; name = try reader.string()
            case 2: try requireWire(wire, 2); supported.append(try decodeRange(reader.bytes()))
            case 3: try requireWire(wire, 2); required.append(try decodeRange(reader.bytes()))
            default: try reader.skip(wire: wire)
            }
        }
        guard let name, name.range(of: #"^[a-z][a-z0-9_.-]{0,63}$"#, options: .regularExpression) != nil,
              !supported.isEmpty, supported.count <= 8, required.count <= 8,
              required.allSatisfy({ value in supported.contains { $0.contains(range: value) } })
        else { throw TransferProtocolError.violation("invalid_capability_offer") }
        return SessionCapabilityOffer(name: name, supportedVersions: supported, requiredVersions: required)
    }

    private static func encode(_ parameters: NegotiatedSessionParameters) -> Data {
        var version = ProtoWriter(); version.variable(field: 1, value: UInt64(parameters.version.major)); version.variable(field: 2, value: UInt64(parameters.version.minor))
        var writer = ProtoWriter(); writer.bytes(field: 1, value: version.data)
        for capability in parameters.capabilities { writer.string(field: 2, value: capability) }
        writer.variable(field: 3, value: UInt64(parameters.chunkSize)); return writer.data
    }

    private static func decodeParameters(_ data: Data) throws -> NegotiatedSessionParameters {
        var reader = ProtoReader(data); var version: NegotiatedProtocolVersion?; var capabilities: [String] = []; var chunk: Int?
        while let (field, wire) = try reader.nextField() {
            switch field {
            case 1:
                try requireWire(wire, 2); guard version == nil else { throw duplicate() }
                var nested = ProtoReader(try reader.bytes()); var major: UInt16?; var minor: UInt16?
                while let (nestedField, nestedWire) = try nested.nextField() {
                    if nestedField == 1 {
                        try requireWire(nestedWire, 0); guard major == nil else { throw duplicate() }
                        let value = try nested.variable(); guard value <= UInt16.max else { throw TransferProtocolError.violation("invalid_negotiated_version") }; major = UInt16(value)
                    } else if nestedField == 2 {
                        try requireWire(nestedWire, 0); guard minor == nil else { throw duplicate() }
                        let value = try nested.variable(); guard value <= UInt16.max else { throw TransferProtocolError.violation("invalid_negotiated_version") }; minor = UInt16(value)
                    } else { try nested.skip(wire: nestedWire) }
                }
                guard let major, major > 0, let minor else { throw TransferProtocolError.violation("invalid_negotiated_version") }
                version = NegotiatedProtocolVersion(major: major, minor: minor)
            case 2: try requireWire(wire, 2); capabilities.append(try reader.string())
            case 3: try requireWire(wire, 0); guard chunk == nil else { throw duplicate() }; chunk = try boundedInt(reader.variable())
            default: try reader.skip(wire: wire)
            }
        }
        guard let version, let chunk, chunk >= minimumNegotiatedChunkSize, chunk <= transferChunkTarget,
              capabilities.count <= 32, Set(capabilities).count == capabilities.count,
              capabilities.allSatisfy({ $0.range(of: #"^[a-z][a-z0-9_.-]{0,63}$"#, options: .regularExpression) != nil })
        else { throw TransferProtocolError.violation("invalid_negotiated_parameters") }
        return NegotiatedSessionParameters(version: version, capabilities: capabilities, chunkSize: chunk)
    }

    private static func requireWire(_ actual: Int, _ expected: Int) throws {
        guard actual == expected else { throw TransferProtocolError.violation("invalid_known_field_wire_type") }
    }

    private static func duplicate() -> TransferProtocolError { .violation("duplicate_singular_field") }
    private static func boundedInt(_ value: UInt64) throws -> Int {
        guard value <= Int.max else { throw TransferProtocolError.violation("integer_overflow") }
        return Int(value)
    }
}

private extension ProtocolVersionRange {
    func contains(range: ProtocolVersionRange) -> Bool {
        major == range.major && minimumMinor <= range.minimumMinor && maximumMinor >= range.maximumMinor
    }
}

private struct ProtoWriter {
    private(set) var data = Data()

    mutating func variable(field: Int, value: UInt64) {
        appendVarint(UInt64(field << 3))
        appendVarint(value)
    }

    mutating func bytes(field: Int, value: Data) {
        appendVarint(UInt64((field << 3) | 2))
        appendVarint(UInt64(value.count))
        data.append(value)
    }

    mutating func string(field: Int, value: String) {
        bytes(field: field, value: Data(value.utf8))
    }

    private mutating func appendVarint(_ input: UInt64) {
        var value = input
        repeat {
            var byte = UInt8(value & 0x7f)
            value >>= 7
            if value != 0 { byte |= 0x80 }
            data.append(byte)
        } while value != 0
    }
}

private struct ProtoReader {
    private let input: Data
    private var position = 0

    init(_ input: Data) { self.input = input }

    mutating func nextField() throws -> (Int, Int)? {
        guard position < input.count else { return nil }
        let tag = try variable()
        let field = Int(tag >> 3)
        guard field != 0 else { throw TransferProtocolError.violation("invalid_protobuf_tag") }
        return (field, Int(tag & 7))
    }

    mutating func variable() throws -> UInt64 {
        var result: UInt64 = 0
        var shift: UInt64 = 0
        while shift < 64 {
            guard position < input.count else { throw TransferProtocolError.violation("truncated_protobuf") }
            let byte = input[position]
            position += 1
            result |= UInt64(byte & 0x7f) << shift
            if byte & 0x80 == 0 { return result }
            shift += 7
        }
        throw TransferProtocolError.violation("invalid_varint")
    }

    mutating func bytes() throws -> Data {
        let length = try variable()
        guard length <= Int.max else { throw TransferProtocolError.violation("truncated_protobuf") }
        let count = Int(length)
        guard position + count <= input.count else { throw TransferProtocolError.violation("truncated_protobuf") }
        let value = input.subdata(in: position..<(position + count))
        position += count
        return value
    }

    mutating func string() throws -> String {
        guard let value = String(data: try bytes(), encoding: .utf8) else {
            throw TransferProtocolError.violation("invalid_utf8")
        }
        return value
    }

    mutating func skip(wire: Int) throws {
        switch wire {
        case 0: _ = try variable()
        case 1: try advance(8)
        case 2:
            let length = try variable()
            guard length <= Int.max else { throw TransferProtocolError.violation("truncated_protobuf") }
            try advance(Int(length))
        case 5: try advance(4)
        default: throw TransferProtocolError.violation("unsupported_protobuf_wire_type")
        }
    }

    private mutating func advance(_ count: Int) throws {
        guard count >= 0, position + count <= input.count else {
            throw TransferProtocolError.violation("truncated_protobuf")
        }
        position += count
    }
}

private struct TransferDataReader {
    private let data: Data
    private var position = 0

    init(_ data: Data) { self.data = data }

    mutating func readUInt8() throws -> UInt8 {
        guard position < data.count else { throw TransferProtocolError.violation("truncated_record") }
        defer { position += 1 }
        return data[position]
    }

    mutating func readUInt16() throws -> UInt16 {
        let bytes = try readData(count: 2)
        return (UInt16(bytes[0]) << 8) | UInt16(bytes[1])
    }

    mutating func readUInt32() throws -> UInt32 {
        let bytes = try readData(count: 4)
        return bytes.reduce(UInt32(0)) { ($0 << 8) | UInt32($1) }
    }

    mutating func readUInt64() throws -> UInt64 {
        let bytes = try readData(count: 8)
        return bytes.reduce(UInt64(0)) { ($0 << 8) | UInt64($1) }
    }

    mutating func readData(count: Int) throws -> Data {
        guard count >= 0, position + count <= data.count else {
            throw TransferProtocolError.violation("truncated_record")
        }
        let result = data.subdata(in: position..<(position + count))
        position += count
        return result
    }
}

private extension TransferRecordType {
    var isBootstrap: Bool { self == .sessionHello || self == .sessionDecision }

    var protocolName: String {
        switch self {
        case .senderFinished: "sender_finished"
        case .terminalResult: "terminal_result"
        default: String(describing: self)
        }
    }
}

private extension Data {
    mutating func appendBigEndian<T: FixedWidthInteger>(_ input: T) {
        var value = input.bigEndian
        Swift.withUnsafeBytes(of: &value) { append(contentsOf: $0) }
    }
}

public extension UUID {
    var transferBytes: [UInt8] {
        let value = uuid
        return [
            value.0, value.1, value.2, value.3,
            value.4, value.5, value.6, value.7,
            value.8, value.9, value.10, value.11,
            value.12, value.13, value.14, value.15,
        ]
    }

    init(transferBytes data: Data) throws {
        guard data.count == 16 else { throw TransferProtocolError.violation("invalid_uuid") }
        let bytes = [UInt8](data)
        self.init(uuid: (
            bytes[0], bytes[1], bytes[2], bytes[3],
            bytes[4], bytes[5], bytes[6], bytes[7],
            bytes[8], bytes[9], bytes[10], bytes[11],
            bytes[12], bytes[13], bytes[14], bytes[15]
        ))
    }
}

public extension String {
    var transferHexData: Data {
        get throws {
            guard count.isMultiple(of: 2) else { throw TransferProtocolError.violation("odd_hex_length") }
            var result = Data()
            var index = startIndex
            while index < endIndex {
                let next = self.index(index, offsetBy: 2)
                guard let byte = UInt8(self[index..<next], radix: 16) else {
                    throw TransferProtocolError.violation("invalid_hex")
                }
                result.append(byte)
                index = next
            }
            return result
        }
    }
}
