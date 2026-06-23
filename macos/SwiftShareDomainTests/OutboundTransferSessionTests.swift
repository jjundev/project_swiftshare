import CryptoKit
import Foundation
import Testing
import SwiftShareDomain

@Suite("Production Outbound Transfer Session")
struct OutboundTransferSessionTests {
    @Test("The production conversation reaches receiver Commit through one interface")
    func completedConversation() async throws {
        let channel = ScriptedOutboundChannel(mode: .completed)
        let locator = OutboundLocatorStub(channel: channel)
        let scheduler = TransferSessionScheduler(localDeviceID: Self.localID)
        let events = OutboundEventRecorder()
        let payload = OutboundPayloadStub(bytes: Data("abc".utf8))
        let session = OutboundTransferSession(
            scheduler: scheduler,
            locator: locator,
            deadlines: ImmediateOutboundDeadlineScheduler()
        )

        let outcome = await session.execute(
            OutboundTransferIntent(peerID: Self.peerID, payload: payload),
            events: events
        )

        #expect(outcome.result == .completed)
        #expect(outcome.committedArtifacts == [CommittedArtifact(id: "content://media/42")])
        #expect(outcome.failure == nil)
        #expect(await channel.sentTypes == [.sessionHello, .manifest, .chunk, .senderFinished])
        #expect(await events.phases == [
            .connecting, .awaitingApproval, .transferring, .transferring, .verifying, .completed,
        ])
        #expect(await locator.calls == 1)
        #expect(await payload.probe.openCalls == 1)
        #expect(await payload.probe.streamClosed)
        #expect(await scheduler.hasActiveSession == false)
    }

    @Test("An unavailable Authenticated Peer Location is a typed terminal outcome")
    func unavailablePeer() async {
        let scheduler = TransferSessionScheduler(localDeviceID: Self.localID)
        let events = OutboundEventRecorder()
        let session = OutboundTransferSession(
            scheduler: scheduler,
            locator: FailingOutboundLocator(),
            deadlines: ImmediateOutboundDeadlineScheduler()
        )

        let outcome = await session.execute(
            OutboundTransferIntent(peerID: Self.peerID, payload: OutboundPayloadStub()),
            events: events
        )

        #expect(outcome.result == .failed)
        #expect(outcome.failure == TransferFailure(category: .discovery, code: "peer_unavailable"))
        #expect(await events.phases == [.connecting, .failed])
        #expect(await scheduler.hasActiveSession == false)
    }

    @Test("Receiver rejection never opens the Payload stream")
    func rejectedBeforeStreaming() async {
        let channel = ScriptedOutboundChannel(mode: .rejected)
        let payload = OutboundPayloadStub()
        let events = OutboundEventRecorder()
        let session = OutboundTransferSession(
            scheduler: TransferSessionScheduler(localDeviceID: Self.localID),
            locator: OutboundLocatorStub(channel: channel),
            deadlines: ImmediateOutboundDeadlineScheduler()
        )

        let outcome = await session.execute(
            OutboundTransferIntent(peerID: Self.peerID, payload: payload),
            events: events
        )

        #expect(outcome.result == .rejected)
        #expect(outcome.failure == TransferFailure(category: .approval, code: "user_rejected"))
        #expect(await payload.probe.openCalls == 0)
        #expect(await channel.sentTypes == [.sessionHello, .manifest])
        #expect(await events.phases == [.connecting, .awaitingApproval, .rejected])
    }

    @Test("Peer revocation owns cancellation, channel close, outcome, and lease release")
    func peerRevocationCancellation() async {
        let channel = ApprovalBlockingOutboundChannel()
        let scheduler = TransferSessionScheduler(localDeviceID: Self.localID)
        let events = OutboundEventRecorder()
        let session = OutboundTransferSession(
            scheduler: scheduler,
            locator: OutboundLocatorStub(channel: channel),
            deadlines: ImmediateOutboundDeadlineScheduler()
        )
        let execution = Task {
            await session.execute(
                OutboundTransferIntent(peerID: Self.peerID, payload: OutboundPayloadStub()),
                events: events
            )
        }
        while !(await channel.isAwaitingApproval) { await Task.yield() }

        await session.cancel(reasonCode: "peer_unpaired", peerID: Self.peerID)
        let outcome = await execution.value

        #expect(outcome.result == .cancelled)
        #expect(outcome.failure == TransferFailure(category: .authentication, code: "peer_unpaired"))
        #expect(await channel.sentTypes == [.sessionHello, .manifest, .cancel])
        #expect(await channel.isClosed)
        #expect(await scheduler.hasActiveSession == false)
        #expect(await events.phases == [.connecting, .awaitingApproval, .cancelled])
    }

    @Test("A multi-item batch streams each item and acknowledges per item")
    func multiItemBatch() async throws {
        let channel = BatchScriptedOutboundChannel()
        let events = OutboundEventRecorder()
        let first = OutboundPayloadStub(itemID: UUID(uuidString: "00000000-0000-0000-0000-0000000000a1")!, bytes: Data("ab".utf8))
        let second = OutboundPayloadStub(itemID: UUID(uuidString: "00000000-0000-0000-0000-0000000000a2")!, bytes: Data("cd".utf8))
        let session = OutboundTransferSession(
            scheduler: TransferSessionScheduler(localDeviceID: Self.localID),
            locator: OutboundLocatorStub(channel: channel),
            deadlines: ImmediateOutboundDeadlineScheduler()
        )

        let outcome = await session.execute(
            OutboundTransferIntent(peerID: Self.peerID, payloads: [first, second]),
            events: events
        )

        #expect(outcome.result == .completed)
        #expect(await channel.sentTypes == [
            .sessionHello, .manifest, .chunk, .senderFinished, .chunk, .senderFinished,
        ])
        #expect(await events.phases == [
            .connecting, .awaitingApproval,
            .transferring, .transferring, .verifying,
            .transferring, .transferring, .verifying,
            .completed,
        ])
        let last = try #require(await events.last)
        #expect(last.completedItems == 2)
        #expect(last.totalItems == 2)
        #expect(last.totalBytes == 4)
        #expect(last.verifiedBytes == 4)
        #expect(await first.probe.openCalls == 1)
        #expect(await second.probe.openCalls == 1)
    }

    private static let localID = Data(repeating: 0x11, count: 32)
    private static let peerID = Data(repeating: 0x22, count: 32)
}

/// Replies contextually to whatever the sender last transmitted so it can drive a
/// batch of any length: decision → approval → per-chunk progress → per-item ack.
private actor BatchScriptedOutboundChannel: OutboundTransferRecordChannel {
    struct Sent: Sendable { let type: TransferRecordType; let payload: Data; let sessionID: UUID }
    private var sent: [Sent] = []
    private var manifestItemIDs: [UUID] = []

    var sentTypes: [TransferRecordType] { sent.map(\.type) }

    func sendRecord(
        type: TransferRecordType,
        payload: Data,
        sessionID: UUID,
        protocolMajor: UInt16,
        protocolMinor: UInt16
    ) {
        sent.append(Sent(type: type, payload: payload, sessionID: sessionID))
    }

    func receiveRecord() throws -> TransferWireRecord {
        let sessionID = try #require(sent.first?.sessionID)
        let last = try #require(sent.last)
        switch last.type {
        case .sessionHello:
            return record(.sessionDecision, SessionBootstrapCodec.encode(.accepted(SessionOffer.default.negotiatedForTests)), sessionID, major: 0)
        case .manifest:
            if case .manifest(let entries) = try TransferControlCodec.decode(type: .manifest, data: last.payload) {
                manifestItemIDs = entries.map(\.itemID)
            }
            return try record(.approval, TransferControlCodec.encode(.approval(accepted: true, reasonCode: "")), sessionID)
        case .chunk:
            let prelude = try TransferChunkPrelude.decode(last.payload.prefix(transferChunkPreludeSize))
            return try record(.progress, TransferControlCodec.encode(.progress(
                itemID: prelude.itemID,
                verifiedBytes: prelude.offset + UInt64(prelude.dataLength),
                verifiedChunks: prelude.chunkIndex + 1,
                phase: .transferring
            )), sessionID)
        case .senderFinished:
            let finished = try TransferControlCodec.decode(type: .senderFinished, data: last.payload)
            guard case .senderFinished(let itemID, _, _) = finished else {
                throw TransferProtocolError.violation("unexpected")
            }
            if itemID == manifestItemIDs.last {
                return try record(.terminalResult, TransferControlCodec.encode(.terminalResult(
                    status: .completed, errorCategory: nil, errorCode: "", committedArtifactID: ""
                )), sessionID)
            }
            return try record(.itemCommitted, TransferControlCodec.encode(.itemCommitted(
                itemID: itemID, committedArtifactID: ""
            )), sessionID)
        default:
            throw TransferProtocolError.violation("unexpected")
        }
    }

    func close() async {}

    private func record(
        _ type: TransferRecordType,
        _ payload: Data,
        _ sessionID: UUID,
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

private actor OutboundPayloadProbe {
    var openCalls = 0
    var streamClosed = false
    func opened() { openCalls += 1 }
    func closed() { streamClosed = true }
}

private struct OutboundPayloadStub: OutboundTransferPayload {
    let itemID: UUID
    let displayName = "report.txt"
    let byteCount: UInt64
    let sha256: Data
    let mediaType = "text/plain"
    let bytes: Data
    let probe = OutboundPayloadProbe()

    init(itemID: UUID = UUID(uuidString: "00000000-0000-0000-0000-000000000042")!, bytes: Data = Data("abc".utf8)) {
        self.itemID = itemID
        self.bytes = bytes
        byteCount = UInt64(bytes.count)
        sha256 = Data(SHA256.hash(data: bytes))
    }

    func validateForTransfer() async throws {}
    func openStream() async throws -> any OutboundTransferPayloadStream {
        await probe.opened()
        return OutboundPayloadStreamStub(bytes: bytes, probe: probe)
    }
}

private actor OutboundPayloadStreamStub: OutboundTransferPayloadStream {
    private var bytes: Data?
    private let probe: OutboundPayloadProbe
    init(bytes: Data, probe: OutboundPayloadProbe) { self.bytes = bytes; self.probe = probe }
    func read(upToCount count: Int) -> Data? { defer { bytes = nil }; return bytes }
    func close() async { await probe.closed() }
}

private actor OutboundLocatorStub: OutboundAuthenticatedPeerLocating {
    private let channel: any OutboundTransferRecordChannel
    private(set) var calls = 0
    init(channel: any OutboundTransferRecordChannel) { self.channel = channel }
    func locate(peerID: Data, endpointHint: String?) -> OutboundAuthenticatedPeerLocation {
        calls += 1
        return OutboundAuthenticatedPeerLocation(channel: channel)
    }
}

private struct FailingOutboundLocator: OutboundAuthenticatedPeerLocating {
    func locate(peerID: Data, endpointHint: String?) async throws -> OutboundAuthenticatedPeerLocation {
        throw TransferExecutionError(category: .discovery, code: "route_missing")
    }
}

private struct ImmediateOutboundDeadlineScheduler: TransferDeadlineScheduling {
    func run<T: Sendable>(
        timeout: Duration,
        operation: @escaping @Sendable () async throws -> T
    ) async throws -> T { try await operation() }
}

private actor OutboundEventRecorder: TransferEventSink {
    private var events: [TransferSessionEvent] = []
    var phases: [TransferActivityPhase] { events.map(\.phase) }
    var last: TransferSessionEvent? { events.last }
    func emit(_ event: TransferSessionEvent) { events.append(event) }
}

private actor ScriptedOutboundChannel: OutboundTransferRecordChannel {
    enum Mode { case completed, rejected }
    struct Sent: Sendable { let type: TransferRecordType; let payload: Data; let sessionID: UUID }

    private let mode: Mode
    private var sent: [Sent] = []
    private var receiveIndex = 0
    init(mode: Mode) { self.mode = mode }

    var sentTypes: [TransferRecordType] { sent.map(\.type) }

    func sendRecord(
        type: TransferRecordType,
        payload: Data,
        sessionID: UUID,
        protocolMajor: UInt16,
        protocolMinor: UInt16
    ) {
        sent.append(Sent(type: type, payload: payload, sessionID: sessionID))
    }

    func receiveRecord() throws -> TransferWireRecord {
        let sessionID = try #require(sent.first?.sessionID)
        defer { receiveIndex += 1 }
        switch receiveIndex {
        case 0:
            return try record(
                type: .sessionDecision,
                payload: SessionBootstrapCodec.encode(.accepted(SessionOffer.default.negotiatedForTests)),
                sessionID: sessionID,
                major: 0,
                minor: 0
            )
        case 1:
            return try record(
                type: .approval,
                payload: TransferControlCodec.encode(.approval(
                    accepted: mode == .completed,
                    reasonCode: mode == .completed ? "" : "user_rejected"
                )),
                sessionID: sessionID
            )
        case 2:
            let chunk = try #require(sent.last { $0.type == .chunk })
            let prelude = try TransferChunkPrelude.decode(chunk.payload.prefix(transferChunkPreludeSize))
            return try record(
                type: .progress,
                payload: TransferControlCodec.encode(.progress(
                    itemID: prelude.itemID,
                    verifiedBytes: prelude.offset + UInt64(prelude.dataLength),
                    verifiedChunks: prelude.chunkIndex + 1,
                    phase: .transferring
                )),
                sessionID: sessionID
            )
        default:
            return try record(
                type: .terminalResult,
                payload: TransferControlCodec.encode(.terminalResult(
                    status: .completed,
                    errorCategory: nil,
                    errorCode: "",
                    committedArtifactID: "content://media/42"
                )),
                sessionID: sessionID
            )
        }
    }

    func close() async {}

    private func record(
        type: TransferRecordType,
        payload: Data,
        sessionID: UUID,
        major: UInt16 = 1,
        minor: UInt16 = 0
    ) throws -> TransferWireRecord {
        TransferWireRecord(
            header: TransferRecordHeader(
                type: type,
                payloadLength: payload.count,
                sessionID: sessionID,
                protocolMajor: major,
                protocolMinor: minor
            ),
            payload: payload
        )
    }
}

private actor ApprovalBlockingOutboundChannel: OutboundTransferRecordChannel {
    struct Sent: Sendable { let type: TransferRecordType; let payload: Data; let sessionID: UUID }
    private var sent: [Sent] = []
    private var receiveIndex = 0
    private var approval: CheckedContinuation<TransferWireRecord, Error>?
    private(set) var isClosed = false

    var sentTypes: [TransferRecordType] { sent.map(\.type) }
    var isAwaitingApproval: Bool { approval != nil }

    func sendRecord(
        type: TransferRecordType,
        payload: Data,
        sessionID: UUID,
        protocolMajor: UInt16,
        protocolMinor: UInt16
    ) {
        sent.append(Sent(type: type, payload: payload, sessionID: sessionID))
    }

    func receiveRecord() async throws -> TransferWireRecord {
        let sessionID = try #require(sent.first?.sessionID)
        if receiveIndex == 0 {
            receiveIndex += 1
            let payload = SessionBootstrapCodec.encode(.accepted(SessionOffer.default.negotiatedForTests))
            return TransferWireRecord(
                header: TransferRecordHeader(
                    type: .sessionDecision,
                    payloadLength: payload.count,
                    sessionID: sessionID,
                    protocolMajor: 0,
                    protocolMinor: 0
                ),
                payload: payload
            )
        }
        return try await withCheckedThrowingContinuation { approval = $0 }
    }

    func close() {
        isClosed = true
        approval?.resume(throwing: CancellationError())
        approval = nil
    }
}

private extension SessionOffer {
    var negotiatedForTests: NegotiatedSessionParameters {
        NegotiatedSessionParameters(
            version: NegotiatedProtocolVersion(major: 1, minor: 0),
            capabilities: ["file_transfer"],
            chunkSize: min(maximumChunkSize, transferChunkTarget)
        )
    }
}
