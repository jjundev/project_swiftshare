package dev.swiftshare.domain

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionNegotiationTest {
    private val fixture by lazy {
        JSONObject(requireNotNull(javaClass.classLoader?.getResource("session-bootstrap-v1.json")).readText())
    }

    @Test fun `default offer matches shared fixture and negotiates`() {
        val expected = fixture.getJSONObject("default_offer")
        assertEquals(expected.getInt("minimum_chunk_size"), SessionOffer.DEFAULT.minimumChunkSize)
        assertEquals(expected.getInt("maximum_chunk_size"), SessionOffer.DEFAULT.maximumChunkSize)
        val result = SessionNegotiator.negotiate(SessionOffer.DEFAULT, SessionOffer.DEFAULT) as NegotiationResult.Accepted
        assertEquals(NegotiatedProtocolVersion(1, 0), result.parameters.version)
        assertEquals(listOf("file_transfer"), result.parameters.capabilities)
        assertEquals(TRANSFER_CHUNK_TARGET, result.parameters.chunkSize)
    }

    @Test fun `bootstrap messages round trip and sender validates receiver selection`() {
        val hello = SessionHello(SessionOffer.DEFAULT)
        assertEquals(fixture.getString("hello_hex"), SessionBootstrapCodec.encode(hello).toHex())
        val decodedHello = SessionBootstrapCodec.decodeHello(SessionBootstrapCodec.encode(hello))
        assertEquals(hello, decodedHello)
        val parameters = (SessionNegotiator.negotiate(hello.offer, SessionOffer.DEFAULT) as NegotiationResult.Accepted).parameters
        val decision = SessionDecision.accepted(SessionOffer.DEFAULT, parameters)
        assertEquals(fixture.getString("accepted_decision_hex"), SessionBootstrapCodec.encode(decision).toHex())
        val decodedDecision = SessionBootstrapCodec.decodeDecision(SessionBootstrapCodec.encode(decision))
        assertEquals(decision, decodedDecision)
        assertEquals(parameters, SessionNegotiator.validate(decodedHello, decodedDecision))
    }

    @Test fun `unknown optional is ignored and unknown required is rejected`() {
        val optional = SessionOffer.DEFAULT.copy(capabilities = SessionOffer.DEFAULT.capabilities + CapabilityOffer(
            "future_optional", listOf(ProtocolVersionRange(1, 0, 0)),
        ))
        assertTrue(SessionNegotiator.negotiate(optional, SessionOffer.DEFAULT) is NegotiationResult.Accepted)
        val required = SessionOffer.DEFAULT.copy(capabilities = SessionOffer.DEFAULT.capabilities + CapabilityOffer(
            "future_required", listOf(ProtocolVersionRange(1, 0, 0)), listOf(ProtocolVersionRange(1, 0, 0)),
        ))
        assertEquals(
            NegotiationResult.Rejected(BootstrapRejectionCategory.CAPABILITY, "required_capability_unsupported"),
            SessionNegotiator.negotiate(required, SessionOffer.DEFAULT),
        )
    }

    @Test fun `major and minor mismatches are distinct`() {
        val major = SessionOffer.DEFAULT.copy(versions = listOf(ProtocolVersionRange(2, 0, 0)))
        assertEquals(
            NegotiationResult.Rejected(BootstrapRejectionCategory.INCOMPATIBLE_VERSION, "no_common_major"),
            SessionNegotiator.negotiate(major, SessionOffer.DEFAULT),
        )
        val minor = SessionOffer.DEFAULT.copy(versions = listOf(ProtocolVersionRange(1, 1, 1)))
        assertEquals(
            NegotiationResult.Rejected(BootstrapRejectionCategory.INCOMPATIBLE_VERSION, "no_common_minor"),
            SessionNegotiator.negotiate(minor, SessionOffer.DEFAULT),
        )
    }

    @Test fun `bootstrap headers use version zero and v1 controls do not`() {
        val session = java.util.UUID.randomUUID()
        val hello = TransferRecordHeader(TransferRecordType.SESSION_HELLO, 0, session, 0, 0)
        assertEquals(hello, TransferRecordHeader.decode(hello.encode()))
        assertThrows(IllegalArgumentException::class.java) {
            TransferRecordHeader(TransferRecordType.SESSION_HELLO, 0, session).encode()
        }
    }

    @Test fun `unknown bootstrap fields are skipped and duplicate singular fields fail`() {
        val hello = SessionHello(SessionOffer.DEFAULT)
        assertEquals(hello, SessionBootstrapCodec.decodeHello(SessionBootstrapCodec.encode(hello) + byteArrayOf(0x98.toByte(), 0x06, 0x01)))
        val error = assertThrows(TransferProtocolException::class.java) {
            SessionBootstrapCodec.decodeHello(SessionBootstrapCodec.encode(hello) + SessionBootstrapCodec.encode(hello))
        }
        assertEquals("duplicate_singular_field", error.protocolCode)
    }

    @Test fun `endpoint ticket is optional bounded and round trips`() {
        val hello = SessionHello(SessionOffer.DEFAULT, ByteArray(32) { 9 })
        assertEquals(hello, SessionBootstrapCodec.decodeHello(SessionBootstrapCodec.encode(hello)))
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
