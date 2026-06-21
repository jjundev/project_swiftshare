import Foundation
import Testing
@testable import SwiftShareDomain

@Suite("Pairing v1")
struct PairingTests {
    @Test("Swift decodes the shared QR fixture and round-trips its URI")
    func qrFixture() throws {
        let fixture = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent()
            .deletingLastPathComponent()
            .deletingLastPathComponent()
            .appendingPathComponent("contracts/fixtures/pairing-v1.json")
        let object = try JSONSerialization.jsonObject(with: Data(contentsOf: fixture)) as? [String: Any]
        let sessionID = try #require(UUID(uuidString: object?["sessionId"] as? String ?? ""))
        let expiry = Date(timeIntervalSince1970: TimeInterval(object?["expiresAtEpochSeconds"] as? Int ?? 0))
        let payload = try PairingQRPayload(
            protocolMajor: UInt16(object?["protocolMajor"] as? Int ?? 0),
            protocolMinor: UInt16(object?["protocolMinor"] as? Int ?? 0),
            sessionID: sessionID,
            host: object?["host"] as? String ?? "",
            port: UInt16(object?["port"] as? Int ?? 0),
            certificateSHA256: try hex(object?["certificateSha256Hex"] as? String ?? ""),
            token: try hex(object?["tokenHex"] as? String ?? ""),
            expiresAt: expiry
        )
        let decoded = try PairingQRPayload.decode(payload.encodedURI(), now: expiry.addingTimeInterval(-1))
        #expect(decoded == payload)
        #expect(throws: PairingError.self) {
            try PairingQRPayload.decode(payload.encodedURI(), now: expiry)
        }
    }

    @Test("Transcript and comparison code are deterministic")
    func transcript() throws {
        let mac = try PairingDeviceDescriptor(canonicalSPKI: Data(repeating: 1, count: 91), displayName: "Mac", platform: "macos")
        let android = try PairingDeviceDescriptor(canonicalSPKI: Data(repeating: 2, count: 91), displayName: "Pixel", platform: "android")
        let input = try PairingTranscript(
            sessionID: UUID(uuidString: "00112233-4455-6677-8899-aabbccddeeff")!,
            token: Data(0x20 ... 0x3f),
            certificateSHA256: Data(0x00 ... 0x1f),
            clientNonce: Data(repeating: 3, count: 32),
            serverNonce: Data(repeating: 4, count: 32),
            mac: mac,
            android: android
        )
        let repeated = try PairingTranscript(
            sessionID: UUID(uuidString: "00112233-4455-6677-8899-aabbccddeeff")!,
            token: Data(0x20 ... 0x3f),
            certificateSHA256: Data(0x00 ... 0x1f),
            clientNonce: Data(repeating: 3, count: 32),
            serverNonce: Data(repeating: 4, count: 32),
            mac: mac,
            android: android
        )
        #expect(input.digest == repeated.digest)
        #expect(input.comparisonCode.count == 6)
        #expect(input.proofDigest(for: .mac) != input.proofDigest(for: .android))
    }

    @Test("A token race admits one candidate and cancellation closes the QR")
    func raceAndCancellation() async throws {
        let now = Date(timeIntervalSince1970: 1_000)
        let payload = try PairingQRPayload(
            sessionID: UUID(),
            host: "192.0.2.10",
            port: 8443,
            certificateSHA256: Data(repeating: 1, count: 32),
            token: Data(repeating: 2, count: 32),
            expiresAt: now.addingTimeInterval(60)
        )
        let session = PairingSession(payload: payload)
        try await session.admit(sessionID: payload.sessionID, token: payload.token, now: now)
        try await session.claim(candidateKeyID: Data(repeating: 3, count: 32), proofIsValid: true, now: now)
        await #expect(throws: PairingError.self) {
            try await session.claim(candidateKeyID: Data(repeating: 4, count: 32), proofIsValid: true, now: now)
        }
        await session.cancel()
        await #expect(throws: PairingError.self) {
            try await session.requireConfirmation(candidateKeyID: Data(repeating: 3, count: 32), now: now)
        }
    }

    @Test("The third invalid token exhausts the session")
    func failureBudget() async throws {
        let now = Date(timeIntervalSince1970: 1_000)
        let payload = try PairingQRPayload(
            sessionID: UUID(), host: "127.0.0.1", port: 8443,
            certificateSHA256: Data(repeating: 1, count: 32),
            token: Data(repeating: 2, count: 32), expiresAt: now.addingTimeInterval(60)
        )
        let session = PairingSession(payload: payload)
        for attempt in 0 ..< 3 {
            do {
                try await session.admit(sessionID: payload.sessionID, token: Data(repeating: 9, count: 32), now: now)
                Issue.record("attempt \(attempt) unexpectedly succeeded")
            } catch { }
        }
        #expect(await session.phase == .invalidated(.failureBudgetExhausted))
    }

    @Test("Every Pairing wire message round-trips within its bound")
    func wireMessages() throws {
        let device = PairingWireDevice(
            certificateDER: Data(repeating: 7, count: 128), canonicalSPKI: Data(repeating: 8, count: 91),
            displayName: "Pixel", platform: "android"
        )
        let id = UUID(uuidString: "00112233-4455-6677-8899-aabbccddeeff")!
        let messages: [PairingWireMessage] = [
            .clientStart(sessionID: id, token: Data(repeating: 1, count: 32), device: device, nonce: Data(repeating: 2, count: 32)),
            .serverChallenge(device: device, nonce: Data(repeating: 3, count: 32)),
            .clientProof(signature: Data(repeating: 4, count: 64)),
            .serverProof(signature: Data(repeating: 5, count: 64)),
            .clientDecision(accepted: true, signature: Data(repeating: 6, count: 64)),
            .commitReceipt(sessionID: id, transcriptSHA256: Data(repeating: 7, count: 32), macKeyID: Data(repeating: 8, count: 32), androidKeyID: Data(repeating: 9, count: 32), committedAt: Date(timeIntervalSince1970: 1_000), signature: Data(repeating: 10, count: 64)),
            .commitAck(receiptSHA256: Data(repeating: 11, count: 32)),
            .error(code: "expired"),
        ]
        for message in messages {
            let encoded = PairingWireCodec.encode(message)
            #expect(encoded.count <= PairingLimits().maximumFrameBytes)
            #expect(try PairingWireCodec.decode(type: message.type, data: encoded) == message)
        }
    }
}

private func hex(_ value: String) throws -> Data {
    guard value.count.isMultiple(of: 2) else { throw PairingError.invalidQR }
    return try Data(stride(from: 0, to: value.count, by: 2).map { index in
        let start = value.index(value.startIndex, offsetBy: index)
        let end = value.index(start, offsetBy: 2)
        guard let byte = UInt8(value[start ..< end], radix: 16) else { throw PairingError.invalidQR }
        return byte
    })
}
