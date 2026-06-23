import Foundation
import Testing
import SwiftShareDomain

@Suite("Transfer wire v1")
struct TransferWireTests {
    @Test("Swift matches the fixed record header and Chunk prelude vectors")
    func fixedLayoutVectors() throws {
        let fixture = try Self.fixture
        let controls = try #require(fixture["control_messages"] as? [String: String])
        let sessionIDString = try #require(fixture["session_id"] as? String)
        let itemIDString = try #require(fixture["item_id"] as? String)
        let header = TransferRecordHeader(
            type: .manifest,
            payloadLength: try #require(controls["manifest_hex"]).count / 2,
            sessionID: try #require(UUID(uuidString: sessionIDString))
        )
        let expectedHeader = try #require(fixture["record_header_hex"] as? String).transferHexData
        #expect(try header.encoded() == expectedHeader)
        #expect(try TransferRecordHeader.decode(expectedHeader) == header)

        let prelude = TransferChunkPrelude(
            itemID: try #require(UUID(uuidString: itemIDString)),
            chunkIndex: 3,
            offset: 12 * 1024 * 1024,
            dataLength: 3,
            sha256: try #require(fixture["sha256_hex"] as? String).transferHexData
        )
        let expectedPrelude = try #require(fixture["chunk_prelude_hex"] as? String).transferHexData
        #expect(prelude.encoded() == expectedPrelude)
        #expect(try TransferChunkPrelude.decode(expectedPrelude) == prelude)
    }

    @Test("Swift decodes and re-encodes every Protobuf control vector semantically")
    func controlVectors() throws {
        let fixture = try Self.fixture
        let controls = try #require(fixture["control_messages"] as? [String: String])
        let itemIDString = try #require(fixture["item_id"] as? String)
        let itemID = try #require(UUID(uuidString: itemIDString))
        let digest = try #require(fixture["sha256_hex"] as? String).transferHexData
        let cases: [(TransferRecordType, String, TransferControlMessage)] = [
            (
                .manifest,
                "manifest_hex",
                .manifest([
                    TransferFileEntry(
                        itemID: itemID,
                        displayName: "report.txt",
                        byteCount: 3,
                        sha256: digest,
                        mediaType: "text/plain",
                        chunkSize: UInt32(transferChunkTarget)
                    )
                ])
            ),
            (.approval, "approval_hex", .approval(accepted: true, reasonCode: "")),
            (
                .progress,
                "progress_hex",
                .progress(itemID: itemID, verifiedBytes: 3, verifiedChunks: 1, phase: .verifying)
            ),
            (
                .senderFinished,
                "sender_finished_hex",
                .senderFinished(itemID: itemID, byteCount: 3, sha256: digest)
            ),
            (.cancel, "cancel_hex", .cancel(reasonCode: "user_cancelled")),
            (
                .terminalResult,
                "terminal_result_hex",
                .terminalResult(
                    status: .completed,
                    errorCategory: nil,
                    errorCode: "",
                    committedArtifactID: "content://media/42"
                )
            ),
        ]

        for (type, key, expected) in cases {
            let bytes = try #require(controls[key]).transferHexData
            #expect(try TransferControlCodec.decode(type: type, data: bytes) == expected)
            let encoded = try TransferControlCodec.encode(expected)
            #expect(try TransferControlCodec.decode(type: type, data: encoded) == expected)
        }
    }

    @Test("Swift matches the batch manifest and ItemCommitted golden vectors")
    func batchVectors() throws {
        let fixture = try Self.fixture
        let controls = try #require(fixture["control_messages"] as? [String: String])
        let item1String = try #require(fixture["item_id"] as? String)
        let item2String = try #require(fixture["item_id_2"] as? String)
        let item1 = try #require(UUID(uuidString: item1String))
        let item2 = try #require(UUID(uuidString: item2String))
        let digest = try #require(fixture["sha256_hex"] as? String).transferHexData

        let batch = TransferControlMessage.manifest([
            TransferFileEntry(itemID: item1, displayName: "report.txt", byteCount: 3, sha256: digest, mediaType: "text/plain", chunkSize: UInt32(transferChunkTarget)),
            TransferFileEntry(itemID: item2, displayName: "second.txt", byteCount: 5, sha256: digest, mediaType: "text/plain", chunkSize: UInt32(transferChunkTarget)),
        ])
        let batchBytes = try #require(controls["manifest_batch_hex"]).transferHexData
        #expect(try TransferControlCodec.encode(batch) == batchBytes)
        #expect(try TransferControlCodec.decode(type: .manifest, data: batchBytes) == batch)

        let committed = TransferControlMessage.itemCommitted(itemID: item1, committedArtifactID: "content://media/42")
        let committedBytes = try #require(controls["item_committed_hex"]).transferHexData
        #expect(try TransferControlCodec.encode(committed) == committedBytes)
        #expect(try TransferControlCodec.decode(type: .itemCommitted, data: committedBytes) == committed)
    }

    @Test("The parser accepts fragmentation and coalescing")
    func fragmentationAndCoalescing() throws {
        let fixture = try Self.fixture
        let controls = try #require(fixture["control_messages"] as? [String: String])
        let sessionIDString = try #require(fixture["session_id"] as? String)
        let sessionID = try #require(UUID(uuidString: sessionIDString))
        let approval = try #require(controls["approval_hex"]).transferHexData
        let cancel = try #require(controls["cancel_hex"]).transferHexData
        let first = try TransferRecordHeader(type: .approval, payloadLength: approval.count, sessionID: sessionID).encoded() + approval
        let second = try TransferRecordHeader(type: .cancel, payloadLength: cancel.count, sessionID: sessionID).encoded() + cancel
        let all = first + second
        var parser = IncrementalTransferRecordParser()

        #expect(try parser.feed(all.subdata(in: 0..<7)).isEmpty)
        #expect(try parser.feed(all.subdata(in: 7..<(first.count - 1))).isEmpty)
        let records = try parser.feed(all.subdata(in: (first.count - 1)..<all.count))

        #expect(records.map(\.header.type) == [.approval, .cancel])
        #expect(records[0].payload == approval)
        #expect(records[1].payload == cancel)
        try parser.finish()
    }

    @Test("Malformed headers, lengths, and truncation fail before allocation")
    func malformedRecords() throws {
        let fixture = try Self.fixture
        let header = try #require(fixture["record_header_hex"] as? String).transferHexData

        var badMagic = header
        badMagic[0] = 0
        try expectProtocol("invalid_magic") { try TransferRecordHeader.decode(badMagic) }

        var flags = header
        flags[7] = 1
        try expectProtocol("reserved_flags") { try TransferRecordHeader.decode(flags) }

        var unknown = header
        unknown[5] = 99
        try expectProtocol("unknown_record_type") { try TransferRecordHeader.decode(unknown) }

        var oversized = header
        let length = UInt32(transferControlLimit + 1)
        oversized[12] = UInt8((length >> 24) & 0xff)
        oversized[13] = UInt8((length >> 16) & 0xff)
        oversized[14] = UInt8((length >> 8) & 0xff)
        oversized[15] = UInt8(length & 0xff)
        try expectProtocol("oversized_manifest") { try TransferRecordHeader.decode(oversized) }

        var parser = IncrementalTransferRecordParser()
        _ = try parser.feed(header.subdata(in: 0..<12))
        try expectProtocol("truncated_record") { try parser.finish() }
    }

    @Test("Malformed Protobuf and invalid identifiers fail closed")
    func malformedControl() throws {
        try expectProtocol("truncated_protobuf") {
            try TransferControlCodec.decode(type: .approval, data: Data([0x12, 0x05, 0x41]))
        }
        try expectProtocol("invalid_uuid") {
            try TransferControlCodec.decode(type: .progress, data: Data([0x0a, 0x01, 0x00, 0x20, 0x01]))
        }
        try expectProtocol("chunk_is_not_control") {
            try TransferControlCodec.decode(type: .chunk, data: Data())
        }
    }

    private func expectProtocol<T>(_ code: String, _ operation: () throws -> T) throws {
        do {
            _ = try operation()
            Issue.record("Expected TransferProtocolError \(code)")
        } catch let TransferProtocolError.violation(actual) {
            #expect(actual == code)
        }
    }

    private static var fixture: [String: Any] {
        get throws {
            let url = URL(fileURLWithPath: #filePath)
                .deletingLastPathComponent()
                .deletingLastPathComponent()
                .deletingLastPathComponent()
                .appendingPathComponent("contracts/fixtures/transfer-v1.json")
            let object = try JSONSerialization.jsonObject(with: Data(contentsOf: url))
            return try #require(object as? [String: Any])
        }
    }
}
