import Foundation
import Testing
import SwiftShareDomain

@Suite("LAN discovery contracts")
struct DiscoveryTests {
    @Test("Endpoint QR preserves signed bytes and expires closed")
    func endpointQR() throws {
        let body = EndpointQRBody(
            peerKeyID: Data(0 ..< 32), port: 8443, expiresAt: Date(timeIntervalSince1970: 2_000_000_000),
            ticket: Data(32 ..< 64), addresses: [EndpointAddress(family: .ipv4, bytes: Data([192, 168, 1, 5]))]
        )
        let bytes = EndpointQRCodec.encodeBody(body)
        let fixtureURL = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent().appendingPathComponent("contracts/fixtures/discovery-v1.json")
        let fixture = try #require(JSONSerialization.jsonObject(with: Data(contentsOf: fixtureURL)) as? [String: String])
        #expect(bytes.hex == fixture["body_hex"])
        #expect(EndpointQRCodec.signingDigest(bodyBytes: bytes).hex == fixture["digest_hex"])
        let envelope = EndpointQREnvelope(bodyBytes: bytes, signatureP1363: Data(repeating: 7, count: 64))
        let decoded = try EndpointQRCodec.decodeURI(EndpointQRCodec.encodeURI(envelope), now: Date(timeIntervalSince1970: 1_999_999_999))
        #expect(decoded.0 == body); #expect(decoded.1.bodyBytes == bytes)
        #expect(throws: DiscoveryContractError.self) { try EndpointQRCodec.decodeURI(EndpointQRCodec.encodeURI(envelope), now: body.expiresAt) }
    }

    @Test("Probe and route reducer preserve remaining routes")
    func probeAndRoutes() throws {
        let probe = DiscoveryProbe(rotatingID: Data(repeating: 1, count: 16), nonce: Data(repeating: 2, count: 16))
        #expect(try DiscoveryProbeCodec.decodeRequest(DiscoveryProbeCodec.encodeRequest(probe)) == probe)
        var state = reducePeerRoute(PeerRouteState(), .authenticated("wifi-v6"))
        state = reducePeerRoute(state, .authenticated("wifi-v4")); state = reducePeerRoute(state, .lost("wifi-v6"))
        #expect(state.availability == .online); #expect(reducePeerRoute(state, .lost("wifi-v4")).availability == .offline)
    }
}

private extension Data { var hex: String { map { String(format: "%02x", $0) }.joined() } }
