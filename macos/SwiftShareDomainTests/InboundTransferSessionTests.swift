import CryptoKit
import Foundation
import Testing
import SwiftShareDomain

@Suite("Production Inbound Transfer Session")
struct InboundTransferSessionTests {
    @Test("Only the latest restart generation may publish availability")
    func latestRestartGeneration() {
        var generation = LatestOperationGeneration()
        let first = generation.begin()
        let second = generation.begin()

        #expect(!generation.isCurrent(first))
        #expect(generation.isCurrent(second))
    }

    @Test("Destination conflicts use a bounded deterministic suffix")
    func destinationSuffix() throws {
        #expect(try DeterministicDestinationName.candidate(original: "report.txt", suffix: 0) == "report.txt")
        #expect(try DeterministicDestinationName.candidate(original: "report.txt", suffix: 2) == "report (2).txt")
        let long = String(repeating: "가", count: 80) + ".txt"
        let candidate = try DeterministicDestinationName.candidate(original: long, suffix: 9999)
        #expect(candidate.utf8.count <= 255)
        #expect(candidate.hasSuffix(" (9999).txt"))
    }

    @Test("Verified bytes are committed and the wire artifact remains private")
    func completedConversation() async throws {
        let bytes = Data("abc".utf8)
        let channel = InboundScriptedChannel(bytes: bytes)
        let destination = InboundDestinationStub()
        let events = InboundEventRecorder()
        let scheduler = TransferSessionScheduler(localDeviceID: Data(repeating: 0x11, count: 32))
        let session = InboundTransferSession(
            scheduler: scheduler,
            approvalGateway: InboundApprovalStub(.accepted),
            destination: destination,
            deadlines: InboundImmediateDeadline(),
            events: events
        )

        let outcome = await session.execute(InboundAuthenticatedConnection(
            peerID: Data(repeating: 0x22, count: 32),
            peerIDKey: String(repeating: "22", count: 32),
            channel: channel,
            authorizer: InboundAuthorizerStub(policy: .autoAccept)
        ))

        #expect(outcome.result == .completed)
        #expect(outcome.committedArtifacts == [CommittedArtifact(id: "local-artifact")])
        #expect(await destination.bytes == bytes)
        #expect(await channel.completedArtifactID == "")
        #expect(await scheduler.hasActiveSession == false)
        #expect(await events.phases == [.transferring, .transferring, .verifying, .committing, .completed])
    }

    @Test("Unsafe sender names fail before destination allocation")
    func unsafeName() async throws {
        let channel = InboundScriptedChannel(bytes: Data("abc".utf8), displayName: "../escape.txt")
        let destination = InboundDestinationStub()
        let session = InboundTransferSession(
            scheduler: TransferSessionScheduler(localDeviceID: Data(repeating: 0x11, count: 32)),
            approvalGateway: InboundApprovalStub(.accepted),
            destination: destination,
            deadlines: InboundImmediateDeadline(),
            events: InboundEventRecorder()
        )

        let outcome = await session.execute(InboundAuthenticatedConnection(
            peerID: Data(repeating: 0x22, count: 32),
            peerIDKey: String(repeating: "22", count: 32),
            channel: channel,
            authorizer: InboundAuthorizerStub(policy: .autoAccept)
        ))

        #expect(outcome.result == .failed)
        #expect(outcome.failure == TransferFailure(category: .permission, code: "invalid_destination_name"))
        #expect(await destination.reserveCalls == 0)
    }

    @Test("Final integrity mismatch aborts without Commit")
    func integrityMismatch() async throws {
        let channel = InboundScriptedChannel(bytes: Data("abc".utf8), chunkBytes: Data("abd".utf8))
        let destination = InboundDestinationStub()
        let session = InboundTransferSession(
            scheduler: TransferSessionScheduler(localDeviceID: Data(repeating: 0x11, count: 32)),
            approvalGateway: InboundApprovalStub(.accepted),
            destination: destination,
            deadlines: InboundImmediateDeadline(),
            events: InboundEventRecorder()
        )

        let outcome = await session.execute(InboundAuthenticatedConnection(
            peerID: Data(repeating: 0x22, count: 32),
            peerIDKey: String(repeating: "22", count: 32),
            channel: channel,
            authorizer: InboundAuthorizerStub(policy: .autoAccept)
        ))

        #expect(outcome.result == .failed)
        #expect(outcome.failure == TransferFailure(category: .integrity, code: "file_sha256_mismatch"))
        #expect(outcome.committedArtifacts.isEmpty)
        #expect(await destination.commitCalls == 0)
    }

    @Test("A multi-item batch commits each item and reports all artifacts")
    func multiItemBatch() async throws {
        let items: [(String, Data)] = [("first.txt", Data("ab".utf8)), ("second.txt", Data("cd".utf8))]
        let channel = InboundBatchScriptedChannel(items: items)
        let destination = InboundDestinationStub()
        let events = InboundEventRecorder()
        let session = InboundTransferSession(
            scheduler: TransferSessionScheduler(localDeviceID: Data(repeating: 0x11, count: 32)),
            approvalGateway: InboundApprovalStub(.accepted),
            destination: destination,
            deadlines: InboundImmediateDeadline(),
            events: events
        )

        let outcome = await session.execute(InboundAuthenticatedConnection(
            peerID: Data(repeating: 0x22, count: 32),
            peerIDKey: String(repeating: "22", count: 32),
            channel: channel,
            authorizer: InboundAuthorizerStub(policy: .autoAccept)
        ))

        #expect(outcome.result == .completed)
        #expect(outcome.committedArtifacts.count == 2)
        #expect(await destination.reserveCalls == 2)
        #expect(await destination.commitCalls == 2)
        #expect(await destination.bytes == Data("abcd".utf8))
        // Non-final item is acknowledged with ItemCommitted; the batch ends with one TerminalResult.
        #expect(await channel.sentTypes.filter { $0 == .itemCommitted }.count == 1)
        #expect(await channel.sentTypes.filter { $0 == .terminalResult }.count == 1)
        #expect(await channel.completedArtifactID == "")
        let last = try #require(await events.last)
        #expect(last.completedItems == 2)
        #expect(last.totalItems == 2)
    }

    @Test("Zero-length Chunk is rejected instead of extending the session")
    func zeroLengthChunk() async throws {
        let channel = InboundScriptedChannel(bytes: Data())
        let session = InboundTransferSession(
            scheduler: TransferSessionScheduler(localDeviceID: Data(repeating: 0x11, count: 32)),
            approvalGateway: InboundApprovalStub(.accepted),
            destination: InboundDestinationStub(),
            deadlines: InboundImmediateDeadline(),
            events: InboundEventRecorder()
        )

        let outcome = await session.execute(InboundAuthenticatedConnection(
            peerID: Data(repeating: 0x22, count: 32),
            peerIDKey: String(repeating: "22", count: 32),
            channel: channel,
            authorizer: InboundAuthorizerStub(policy: .autoAccept)
        ))

        #expect(outcome.result == .failed)
        #expect(outcome.failure == TransferFailure(category: .protocol, code: "empty_chunk"))
    }
}

private actor InboundScriptedChannel: InboundTransferRecordChannel {
    private let bytes: Data
    private let chunkBytes: Data
    private let displayName: String
    private let sessionID = UUID(uuidString: "00000000-0000-0000-0000-000000000077")!
    private let itemID = UUID(uuidString: "00000000-0000-0000-0000-000000000042")!
    private var receiveIndex = 0
    private var sent: [(TransferRecordType, Data)] = []

    init(bytes: Data, displayName: String = "report.txt", chunkBytes: Data? = nil) {
        self.bytes = bytes
        self.chunkBytes = chunkBytes ?? bytes
        self.displayName = displayName
    }

    var completedArtifactID: String? {
        guard let terminal = sent.last(where: { $0.0 == .terminalResult }),
              let decoded = try? TransferControlCodec.decode(type: .terminalResult, data: terminal.1),
              case .terminalResult(.completed, _, _, let artifact) = decoded
        else { return nil }
        return artifact
    }

    func receiveRecord() throws -> TransferWireRecord {
        defer { receiveIndex += 1 }
        switch receiveIndex {
        case 0:
            return record(.sessionHello, SessionBootstrapCodec.encode(SessionHello(offer: .default)), major: 0)
        case 1:
            return try record(.manifest, TransferControlCodec.encode(.manifest([
                TransferFileEntry(
                    itemID: itemID,
                    displayName: displayName,
                    byteCount: UInt64(bytes.count),
                    sha256: Data(SHA256.hash(data: bytes)),
                    mediaType: "text/plain",
                    chunkSize: UInt32(transferChunkTarget)
                ),
            ])))
        case 2:
            let prelude = TransferChunkPrelude(
                itemID: itemID,
                chunkIndex: 0,
                offset: 0,
                dataLength: UInt32(chunkBytes.count),
                sha256: Data(SHA256.hash(data: chunkBytes))
            )
            return record(.chunk, prelude.encoded() + chunkBytes)
        default:
            return try record(.senderFinished, TransferControlCodec.encode(.senderFinished(
                itemID: itemID,
                byteCount: UInt64(bytes.count),
                sha256: Data(SHA256.hash(data: bytes))
            )))
        }
    }

    func sendRecord(
        type: TransferRecordType,
        payload: Data,
        sessionID: UUID,
        protocolMajor: UInt16,
        protocolMinor: UInt16
    ) {
        sent.append((type, payload))
    }

    func close() {}

    private func record(
        _ type: TransferRecordType,
        _ payload: Data,
        major: UInt16 = 1
    ) -> TransferWireRecord {
        TransferWireRecord(
            header: TransferRecordHeader(
                type: type,
                payloadLength: payload.count,
                sessionID: sessionID,
                protocolMajor: major,
                protocolMinor: 0
            ),
            payload: payload
        )
    }
}

private struct InboundAuthorizerStub: InboundPeerAuthorizing {
    let policy: PeerApprovalPolicy
    func currentPeer() -> InboundAuthorizedPeer? {
        InboundAuthorizedPeer(id: "peer", displayName: "Android", approvalPolicy: policy)
    }
}

private struct InboundApprovalStub: InboundApprovalGateway {
    let decision: InboundApprovalDecision
    init(_ decision: InboundApprovalDecision) { self.decision = decision }
    func awaitDecision(_ summary: InboundTransferSummary) -> InboundApprovalDecision { decision }
    func cancel() {}
}

private actor InboundDestinationStub: InboundTransferDestination {
    private(set) var bytes = Data()
    private(set) var reserveCalls = 0
    private(set) var commitCalls = 0

    func preflight() {}
    func reserve(displayName: String, mediaType: String, sessionID: UUID, itemID: UUID)
        -> any InboundTransferReservation {
        reserveCalls += 1
        return InboundReservationStub(destination: self, displayName: displayName)
    }

    func append(_ data: Data) { bytes.append(data) }
    func committed() { commitCalls += 1 }
}

private struct InboundReservationStub: InboundTransferReservation {
    let destination: InboundDestinationStub
    let displayName: String
    func write(_ data: Data) async { await destination.append(data) }
    func finishWriting() {}
    func commit() async -> CommittedArtifact {
        await destination.committed()
        return CommittedArtifact(id: "local-artifact")
    }
    func abort() {}
}

private struct InboundImmediateDeadline: TransferDeadlineScheduling {
    func run<T: Sendable>(
        timeout: Duration,
        operation: @escaping @Sendable () async throws -> T
    ) async throws -> T { try await operation() }
}

private actor InboundEventRecorder: InboundTransferEventSink {
    private var events: [InboundTransferSessionEvent] = []
    var phases: [TransferActivityPhase] { events.map(\.phase) }
    var last: InboundTransferSessionEvent? { events.last }
    func emit(_ event: InboundTransferSessionEvent) { events.append(event) }
}

/// Delivers a full batch conversation (hello, N-entry manifest, then per-item chunk +
/// SenderFinished) and records everything the receiver sends back.
private actor InboundBatchScriptedChannel: InboundTransferRecordChannel {
    private static let sessionID = UUID(uuidString: "00000000-0000-0000-0000-000000000099")!
    private var queue: [TransferWireRecord] = []
    private var index = 0
    private var sent: [(TransferRecordType, Data)] = []

    init(items: [(String, Data)]) {
        var entries: [TransferFileEntry] = []
        var perItem: [(UUID, Data)] = []
        for item in items {
            let id = UUID()
            entries.append(TransferFileEntry(
                itemID: id,
                displayName: item.0,
                byteCount: UInt64(item.1.count),
                sha256: Data(SHA256.hash(data: item.1)),
                mediaType: "text/plain",
                chunkSize: UInt32(transferChunkTarget)
            ))
            perItem.append((id, item.1))
        }
        var records = [
            Self.make(.sessionHello, SessionBootstrapCodec.encode(SessionHello(offer: .default)), major: 0),
            Self.make(.manifest, try! TransferControlCodec.encode(.manifest(entries))),
        ]
        for (id, bytes) in perItem {
            let prelude = TransferChunkPrelude(
                itemID: id, chunkIndex: 0, offset: 0,
                dataLength: UInt32(bytes.count), sha256: Data(SHA256.hash(data: bytes))
            )
            records.append(Self.make(.chunk, prelude.encoded() + bytes))
            records.append(Self.make(.senderFinished, try! TransferControlCodec.encode(.senderFinished(
                itemID: id, byteCount: UInt64(bytes.count), sha256: Data(SHA256.hash(data: bytes))
            ))))
        }
        queue = records
    }

    var sentTypes: [TransferRecordType] { sent.map(\.0) }
    var completedArtifactID: String? {
        guard let terminal = sent.last(where: { $0.0 == .terminalResult }),
              let decoded = try? TransferControlCodec.decode(type: .terminalResult, data: terminal.1),
              case .terminalResult(.completed, _, _, let artifact) = decoded
        else { return nil }
        return artifact
    }

    func receiveRecord() throws -> TransferWireRecord {
        guard index < queue.count else { throw TransferProtocolError.violation("exhausted") }
        defer { index += 1 }
        return queue[index]
    }

    func sendRecord(
        type: TransferRecordType,
        payload: Data,
        sessionID: UUID,
        protocolMajor: UInt16,
        protocolMinor: UInt16
    ) {
        sent.append((type, payload))
    }

    func close() {}

    private static func make(_ type: TransferRecordType, _ payload: Data, major: UInt16 = 1) -> TransferWireRecord {
        TransferWireRecord(
            header: TransferRecordHeader(
                type: type,
                payloadLength: payload.count,
                sessionID: sessionID,
                protocolMajor: major,
                protocolMinor: 0
            ),
            payload: payload
        )
    }
}
