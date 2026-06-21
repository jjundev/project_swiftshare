package dev.swiftshare.domain

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.json.JSONObject
import java.time.Instant

class DiscoveryTest {
    private val fixture by lazy { JSONObject(requireNotNull(javaClass.classLoader?.getResource("discovery-v1.json")).readText()) }
    @Test fun `endpoint qr preserves signed body bytes and rejects expiry`() {
        val body = EndpointQrBody(
            ByteArray(32) { it.toByte() }, 8443, Instant.ofEpochSecond(2_000_000_000), ByteArray(32) { (it + 32).toByte() },
            listOf(EndpointAddress(EndpointAddressFamily.IPV4, byteArrayOf(192.toByte(), 168.toByte(), 1, 5))),
        )
        val bytes = EndpointQrCodec.encodeBody(body)
        assertEquals(fixture.getString("body_hex"), bytes.toHex())
        assertEquals(fixture.getString("digest_hex"), EndpointQrCodec.signingDigest(bytes).toHex())
        val envelope = EndpointQrEnvelope(bytes, ByteArray(64) { 7 })
        val (decoded, decodedEnvelope) = EndpointQrCodec.decodeUri(EndpointQrCodec.encodeUri(envelope), Instant.ofEpochSecond(1_999_999_999))
        assertEquals(body, decoded); assertArrayEquals(bytes, decodedEnvelope.bodyBytes)
        assertThrows(IllegalArgumentException::class.java) {
            EndpointQrCodec.decodeUri(EndpointQrCodec.encodeUri(envelope), body.expiresAt)
        }
    }

    @Test fun `probe and route reducer are bounded and route aware`() {
        val probe = DiscoveryProbe(ByteArray(16) { 1 }, ByteArray(16) { 2 })
        assertEquals(probe, DiscoveryProbeCodec.decodeRequest(DiscoveryProbeCodec.encodeRequest(probe)))
        var state = reducePeerRoute(PeerRouteState(), PeerRouteEvent.Authenticated("wifi-v6"))
        state = reducePeerRoute(state, PeerRouteEvent.Authenticated("wifi-v4"))
        state = reducePeerRoute(state, PeerRouteEvent.Lost("wifi-v6"))
        assertEquals(PeerAvailability.ONLINE, state.availability)
        assertEquals(PeerAvailability.OFFLINE, reducePeerRoute(state, PeerRouteEvent.Lost("wifi-v4")).availability)
    }

    @Test fun `diagnostics prefer actionable evidence`() {
        assertEquals(ConnectivityDiagnostic.LOCAL_NETWORK_PERMISSION_DENIED, classifyConnectivity(ConnectivityEvidence(permissionDenied = true)))
        assertEquals(
            ConnectivityDiagnostic.AUTOMATIC_DISCOVERY_UNAVAILABLE,
            classifyConnectivity(ConnectivityEvidence(automaticDiscoveryFailed = true, signedQrForSamePeer = true, authenticatedUnicastSucceeded = true)),
        )
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
