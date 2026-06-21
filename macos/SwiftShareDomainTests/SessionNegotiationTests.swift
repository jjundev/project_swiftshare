import Foundation
import Testing
import SwiftShareDomain

@Suite("Session negotiation")
struct SessionNegotiationTests {
    @Test("The default offer matches the shared fixture")
    func sharedFixture() throws {
        let data = try Data(contentsOf: Self.fixtureURL)
        let root = try #require(JSONSerialization.jsonObject(with: data) as? [String: Any])
        let offer = try #require(root["default_offer"] as? [String: Any])
        #expect(SessionOffer.default.minimumChunkSize == offer["minimum_chunk_size"] as? Int)
        #expect(SessionOffer.default.maximumChunkSize == offer["maximum_chunk_size"] as? Int)
        guard case .accepted(let parameters) = SessionNegotiator.negotiate(sender: .default, receiver: .default) else {
            Issue.record("Expected compatible default offer"); return
        }
        #expect(parameters == NegotiatedSessionParameters(
            version: NegotiatedProtocolVersion(major: 1, minor: 0),
            capabilities: ["file_transfer"],
            chunkSize: transferChunkTarget
        ))
    }

    @Test("Bootstrap messages round-trip and the sender validates the selection")
    func bootstrapRoundTrip() throws {
        let data = try Data(contentsOf: Self.fixtureURL)
        let root = try #require(JSONSerialization.jsonObject(with: data) as? [String: Any])
        let hello = SessionHello(offer: .default)
        #expect(SessionBootstrapCodec.encode(hello).hex == root["hello_hex"] as? String)
        let decodedHello = try SessionBootstrapCodec.decodeHello(SessionBootstrapCodec.encode(hello))
        #expect(decodedHello == hello)
        guard case .accepted(let parameters) = SessionNegotiator.negotiate(sender: hello.offer, receiver: .default) else {
            Issue.record("Expected accepted negotiation"); return
        }
        let decision = SessionDecision.accepted(parameters)
        #expect(SessionBootstrapCodec.encode(decision).hex == root["accepted_decision_hex"] as? String)
        let decodedDecision = try SessionBootstrapCodec.decodeDecision(SessionBootstrapCodec.encode(decision))
        #expect(decodedDecision == decision)
        #expect(try SessionNegotiator.validate(hello: hello, decision: decodedDecision) == parameters)
    }

    @Test("Unknown optional is ignored and unknown required is rejected")
    func unknownCapabilities() {
        let range = ProtocolVersionRange(major: 1, minimumMinor: 0, maximumMinor: 0)
        let optional = SessionOffer(
            versions: SessionOffer.default.versions,
            capabilities: SessionOffer.default.capabilities + [SessionCapabilityOffer(name: "future_optional", supportedVersions: [range])],
            minimumChunkSize: minimumNegotiatedChunkSize,
            maximumChunkSize: transferChunkTarget
        )
        guard case .accepted = SessionNegotiator.negotiate(sender: optional, receiver: .default) else {
            Issue.record("Unknown optional should not reject"); return
        }
        let required = SessionOffer(
            versions: SessionOffer.default.versions,
            capabilities: SessionOffer.default.capabilities + [SessionCapabilityOffer(name: "future_required", supportedVersions: [range], requiredVersions: [range])],
            minimumChunkSize: minimumNegotiatedChunkSize,
            maximumChunkSize: transferChunkTarget
        )
        #expect(SessionNegotiator.negotiate(sender: required, receiver: .default) == .rejected(.capability, "required_capability_unsupported"))
    }

    @Test("Bootstrap headers use protocol zero")
    func bootstrapHeader() throws {
        let header = TransferRecordHeader(type: .sessionHello, payloadLength: 0, sessionID: UUID(), protocolMajor: 0, protocolMinor: 0)
        #expect(try TransferRecordHeader.decode(header.encoded()) == header)
    }

    @Test("Unknown bootstrap fields are skipped and duplicate singular fields fail")
    func protobufCompatibility() throws {
        let hello = SessionHello(offer: .default)
        let withUnknown = SessionBootstrapCodec.encode(hello) + Data([0x98, 0x06, 0x01])
        #expect(try SessionBootstrapCodec.decodeHello(withUnknown) == hello)
        let duplicate = SessionBootstrapCodec.encode(hello) + SessionBootstrapCodec.encode(hello)
        #expect(throws: TransferProtocolError.violation("duplicate_singular_field")) {
            try SessionBootstrapCodec.decodeHello(duplicate)
        }
    }

    @Test("Endpoint ticket is optional, bounded, and round-trips")
    func endpointTicket() throws {
        let hello = SessionHello(offer: .default, endpointTicket: Data(repeating: 9, count: 32))
        #expect(try SessionBootstrapCodec.decodeHello(SessionBootstrapCodec.encode(hello)) == hello)
    }

    private static var fixtureURL: URL {
        URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
            .appendingPathComponent("contracts/fixtures/session-bootstrap-v1.json")
    }
}

private extension Data {
    var hex: String { map { String(format: "%02x", $0) }.joined() }
}

@Suite("Transfer Session scheduler")
struct TransferSessionSchedulerTests {
    @Test("One slot releases for retry")
    func oneSlot() async throws {
        let scheduler = TransferSessionScheduler(localDeviceID: Data(repeating: 1, count: 32))
        let first = try #require(await scheduler.reserveOutbound(peerID: Data(repeating: 2, count: 32), sessionID: UUID()))
        #expect(await scheduler.reserveOutbound(peerID: Data(repeating: 3, count: 32), sessionID: UUID()) == nil)
        await scheduler.release(token: first.token)
        #expect(await scheduler.reserveOutbound(peerID: Data(repeating: 3, count: 32), sessionID: UUID()) != nil)
    }

    @Test("Stable identities resolve simultaneous initiation")
    func tieBreak() async throws {
        let smaller = TransferSessionScheduler(localDeviceID: Data(repeating: 1, count: 32))
        let smallerLease = try #require(await smaller.reserveOutbound(peerID: Data(repeating: 2, count: 32), sessionID: UUID()))
        #expect(await smaller.admitInbound(peerID: Data(repeating: 2, count: 32), sessionID: UUID()) == .rejected(code: "simultaneous_initiation_lost"))
        #expect(await smaller.phase(token: smallerLease.token) == .outboundPreDecision)

        let larger = TransferSessionScheduler(localDeviceID: Data(repeating: 2, count: 32))
        let largerLease = try #require(await larger.reserveOutbound(peerID: Data(repeating: 1, count: 32), sessionID: UUID()))
        guard case .admitted(let incoming, let preempted) = await larger.admitInbound(peerID: Data(repeating: 1, count: 32), sessionID: UUID()) else {
            Issue.record("Expected incoming winner"); return
        }
        #expect(preempted == largerLease.token)
        #expect(await larger.phase(token: largerLease.token) == .lostDraining)
        await larger.release(token: incoming.token)
        await larger.release(token: largerLease.token)
        #expect(await larger.hasActiveSession == false)
    }
}
