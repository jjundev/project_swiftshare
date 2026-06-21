package dev.swiftshare.domain

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.time.Instant
import java.util.UUID

class PairingTest {
    @Test fun sharedQrFixtureRoundTrips() {
        val fixture = JSONObject(checkNotNull(javaClass.getResource("/pairing-v1.json")).readText())
        val expiry = Instant.ofEpochSecond(fixture.getLong("expiresAtEpochSeconds"))
        val payload = PairingQrPayload(
            fixture.getInt("protocolMajor"), fixture.getInt("protocolMinor"),
            UUID.fromString(fixture.getString("sessionId")), fixture.getString("host"), fixture.getInt("port"),
            fixture.getString("certificateSha256Hex").hexToByteArray(),
            fixture.getString("tokenHex").hexToByteArray(), expiry,
        )
        assertEquals(payload, PairingQrPayload.decode(payload.encodedUri(), expiry.minusSeconds(1)))
        assertThrows(IllegalArgumentException::class.java) { PairingQrPayload.decode(payload.encodedUri(), expiry) }
    }

    @Test fun transcriptAndComparisonCodeAreDeterministic() {
        val mac = PairingDeviceDescriptor(ByteArray(91) { 1 }, "Mac", "macos")
        val android = PairingDeviceDescriptor(ByteArray(91) { 2 }, "Pixel", "android")
        fun make() = PairingTranscript(
            UUID.fromString("00112233-4455-6677-8899-aabbccddeeff"),
            ByteArray(32) { (0x20 + it).toByte() }, ByteArray(32) { it.toByte() },
            ByteArray(32) { 3 }, ByteArray(32) { 4 }, mac, android,
        )
        assertEquals(make().digest.toList(), make().digest.toList())
        assertEquals(6, make().comparisonCode.length)
        assertNotEquals(make().proofDigest(PairingRole.MAC).toList(), make().proofDigest(PairingRole.ANDROID).toList())
    }

    @Test fun tokenRaceAdmitsOneCandidate() {
        val now = Instant.ofEpochSecond(1_000)
        val payload = PairingQrPayload(
            sessionId = UUID.randomUUID(), host = "192.0.2.10", port = 8443,
            certificateSha256 = ByteArray(32) { 1 }, token = ByteArray(32) { 2 }, expiresAt = now.plusSeconds(60),
        )
        val session = PairingSessionStateMachine(payload)
        session.admit(payload.sessionId, payload.token, now)
        session.claim(ByteArray(32) { 3 }, true, now)
        assertThrows(PairingException::class.java) { session.claim(ByteArray(32) { 4 }, true, now) }
        session.cancel()
        assertThrows(PairingException::class.java) { session.requireConfirmation(ByteArray(32) { 3 }, now) }
    }

    @Test fun thirdFailureExhaustsToken() {
        val now = Instant.ofEpochSecond(1_000)
        val payload = PairingQrPayload(
            sessionId = UUID.randomUUID(), host = "127.0.0.1", port = 8443,
            certificateSha256 = ByteArray(32) { 1 }, token = ByteArray(32) { 2 }, expiresAt = now.plusSeconds(60),
        )
        val session = PairingSessionStateMachine(payload)
        repeat(3) { assertThrows(PairingException::class.java) { session.admit(payload.sessionId, ByteArray(32) { 9 }, now) } }
        assert((session.phase as PairingSessionPhase.Invalidated).error.code == PairingErrorCode.FAILURE_BUDGET_EXHAUSTED)
    }

    @Test fun pairingWireRoundTrips() {
        val device = PairingWireDevice(ByteArray(128) { 7 }, ByteArray(91) { 8 }, "Pixel", "android")
        val id = UUID.fromString("00112233-4455-6677-8899-aabbccddeeff")
        val start = ClientStart(id, ByteArray(32) { 1 }, device, ByteArray(32) { 2 })
        val decoded = PairingWireCodec.decode(start.type, PairingWireCodec.encode(start)) as ClientStart
        assertEquals(id, decoded.sessionId)
        assertEquals(start.token.toList(), decoded.token.toList())
        assertEquals(start.device.canonicalSpki.toList(), decoded.device.canonicalSpki.toList())
        val receipt = CommitReceipt(id, ByteArray(32) { 3 }, ByteArray(32) { 4 }, ByteArray(32) { 5 }, Instant.ofEpochSecond(1_000), ByteArray(64) { 6 })
        val decodedReceipt = PairingWireCodec.decode(receipt.type, PairingWireCodec.encode(receipt)) as CommitReceipt
        assertEquals(receipt.transcriptSha256.toList(), decodedReceipt.transcriptSha256.toList())
        assertEquals(receipt.committedAt, decodedReceipt.committedAt)
    }
}
