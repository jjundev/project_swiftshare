package dev.swiftshare.domain

import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64

const val DISCOVERY_SCHEMA_MAJOR = 1
const val DISCOVERY_SCHEMA_MINOR = 0
const val DISCOVERY_ROTATING_ID_BYTES = 16
const val ENDPOINT_TICKET_BYTES = 32
const val ENDPOINT_QR_MAX_ADDRESSES = 8

data class DiscoveryAdvertisement(
    val rotatingId: ByteArray,
    val schemaMajor: Int = DISCOVERY_SCHEMA_MAJOR,
) {
    init { require(schemaMajor == DISCOVERY_SCHEMA_MAJOR && rotatingId.size == DISCOVERY_ROTATING_ID_BYTES) }
    val instanceName: String get() = "ss-" + Base64.getUrlEncoder().withoutPadding().encodeToString(rotatingId)
    val txt: Map<String, String> get() = mapOf(
        "v" to schemaMajor.toString(),
        "rid" to Base64.getUrlEncoder().withoutPadding().encodeToString(rotatingId),
    )
    override fun equals(other: Any?): Boolean = other is DiscoveryAdvertisement &&
        schemaMajor == other.schemaMajor && rotatingId.contentEquals(other.rotatingId)
    override fun hashCode(): Int = 31 * schemaMajor + rotatingId.contentHashCode()
}

enum class EndpointAddressFamily(val code: Int, val byteCount: Int) { IPV4(4, 4), IPV6(6, 16) }

data class EndpointAddress(val family: EndpointAddressFamily, val bytes: ByteArray) {
    init { require(bytes.size == family.byteCount) }
    fun inetAddress(): InetAddress = InetAddress.getByAddress(bytes)
    override fun equals(other: Any?): Boolean = other is EndpointAddress && family == other.family && bytes.contentEquals(other.bytes)
    override fun hashCode(): Int = 31 * family.hashCode() + bytes.contentHashCode()
}

data class EndpointQrBody(
    val peerKeyId: ByteArray,
    val port: Int,
    val expiresAt: Instant,
    val ticket: ByteArray,
    val addresses: List<EndpointAddress>,
) {
    init {
        require(peerKeyId.size == 32 && ticket.size == ENDPOINT_TICKET_BYTES)
        require(port in 1..65535 && addresses.isNotEmpty() && addresses.size <= ENDPOINT_QR_MAX_ADDRESSES)
    }
    override fun equals(other: Any?): Boolean = other is EndpointQrBody &&
        peerKeyId.contentEquals(other.peerKeyId) && port == other.port && expiresAt == other.expiresAt &&
        ticket.contentEquals(other.ticket) && addresses == other.addresses
    override fun hashCode(): Int = 31 * peerKeyId.contentHashCode() + port
}

data class EndpointQrEnvelope(val bodyBytes: ByteArray, val signatureP1363: ByteArray) {
    init { require(bodyBytes.size <= 1024 && signatureP1363.size == 64) }
    override fun equals(other: Any?): Boolean = other is EndpointQrEnvelope &&
        bodyBytes.contentEquals(other.bodyBytes) && signatureP1363.contentEquals(other.signatureP1363)
    override fun hashCode(): Int = 31 * bodyBytes.contentHashCode() + signatureP1363.contentHashCode()
}

object EndpointQrCodec {
    private const val URI_PREFIX = "swiftshare://connect/v1/"
    private val BODY_MAGIC = "SSEQ".toByteArray(Charsets.US_ASCII)
    private val ENVELOPE_MAGIC = "SSEC".toByteArray(Charsets.US_ASCII)
    private val DOMAIN = "SwiftShare-Endpoint-QR-v1".toByteArray(Charsets.US_ASCII)

    fun encodeBody(body: EndpointQrBody): ByteArray = ByteArrayOutputStream().apply {
        write(BODY_MAGIC); writeU16(DISCOVERY_SCHEMA_MAJOR); writeU16(DISCOVERY_SCHEMA_MINOR)
        write(body.peerKeyId); writeU16(body.port); writeU64(body.expiresAt.epochSecond)
        write(body.ticket); write(body.addresses.size)
        body.addresses.forEach { address -> write(address.family.code); write(address.bytes.size); write(address.bytes) }
    }.toByteArray()

    fun decodeBody(bytes: ByteArray): EndpointQrBody {
        val reader = FixedReader(bytes)
        require(reader.bytes(4).contentEquals(BODY_MAGIC)); require(reader.u16() == DISCOVERY_SCHEMA_MAJOR)
        require(reader.u16() == DISCOVERY_SCHEMA_MINOR)
        val peer = reader.bytes(32); val port = reader.u16(); val expiry = reader.u64()
        val ticket = reader.bytes(ENDPOINT_TICKET_BYTES); val count = reader.u8()
        require(count in 1..ENDPOINT_QR_MAX_ADDRESSES)
        val addresses = List(count) {
            val family = EndpointAddressFamily.entries.firstOrNull { it.code == reader.u8() } ?: error("invalid address family")
            require(reader.u8() == family.byteCount); EndpointAddress(family, reader.bytes(family.byteCount))
        }
        require(reader.finished)
        return EndpointQrBody(peer, port, Instant.ofEpochSecond(expiry), ticket, addresses)
    }

    fun signingDigest(bodyBytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(DOMAIN + bodyBytes)

    fun encodeUri(envelope: EndpointQrEnvelope): String {
        val raw = ByteArrayOutputStream().apply {
            write(ENVELOPE_MAGIC); writeU16(envelope.bodyBytes.size); write(envelope.bodyBytes); write(envelope.signatureP1363)
        }.toByteArray()
        return URI_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(raw)
    }

    fun decodeUri(uri: String, now: Instant): Pair<EndpointQrBody, EndpointQrEnvelope> {
        require(uri.startsWith(URI_PREFIX))
        val reader = FixedReader(Base64.getUrlDecoder().decode(uri.removePrefix(URI_PREFIX)))
        require(reader.bytes(4).contentEquals(ENVELOPE_MAGIC))
        val bodyBytes = reader.bytes(reader.u16()); val signature = reader.bytes(64); require(reader.finished)
        val body = decodeBody(bodyBytes); require(body.expiresAt.isAfter(now))
        return body to EndpointQrEnvelope(bodyBytes, signature)
    }
}

data class DiscoveryProbe(val rotatingId: ByteArray, val nonce: ByteArray) {
    init { require(rotatingId.size == 16 && nonce.size == 16) }
    override fun equals(other: Any?): Boolean = other is DiscoveryProbe && rotatingId.contentEquals(other.rotatingId) && nonce.contentEquals(other.nonce)
    override fun hashCode(): Int = 31 * rotatingId.contentHashCode() + nonce.contentHashCode()
}

object DiscoveryProbeCodec {
    val magic: ByteArray = "SSDR".toByteArray(Charsets.US_ASCII)
    fun encodeRequest(value: DiscoveryProbe): ByteArray = magic + byteArrayOf(1, 0) + value.rotatingId + value.nonce
    fun decodeRequest(bytes: ByteArray): DiscoveryProbe {
        require(bytes.size == 38 && bytes.copyOfRange(0, 4).contentEquals(magic) && bytes[4] == 1.toByte() && bytes[5] == 0.toByte())
        return DiscoveryProbe(bytes.copyOfRange(6, 22), bytes.copyOfRange(22, 38))
    }
    fun encodeReply(value: DiscoveryProbe, available: Boolean): ByteArray =
        magic + byteArrayOf(1, 0, if (available) 1 else 0) + value.rotatingId + value.nonce
    fun decodeReply(bytes: ByteArray): Pair<DiscoveryProbe, Boolean> {
        require(bytes.size == 39 && bytes.copyOfRange(0, 4).contentEquals(magic) && bytes[4] == 1.toByte() && bytes[5] == 0.toByte())
        return DiscoveryProbe(bytes.copyOfRange(7, 23), bytes.copyOfRange(23, 39)) to (bytes[6] == 1.toByte())
    }
}

enum class PeerAvailability { OFFLINE, LOCATING, AUTHENTICATING, ONLINE, INCOMPATIBLE, NEEDS_QR }
data class PeerRouteState(val routes: Set<String> = emptySet(), val availability: PeerAvailability = PeerAvailability.OFFLINE)
sealed interface PeerRouteEvent {
    data class Candidate(val id: String) : PeerRouteEvent
    data class Authenticating(val id: String) : PeerRouteEvent
    data class Authenticated(val id: String) : PeerRouteEvent
    data class Lost(val id: String) : PeerRouteEvent
    data object Incompatible : PeerRouteEvent
    data object DiscoveryUnavailable : PeerRouteEvent
    data object Reset : PeerRouteEvent
}
fun reducePeerRoute(state: PeerRouteState, event: PeerRouteEvent): PeerRouteState = when (event) {
    is PeerRouteEvent.Candidate -> state.copy(availability = if (state.routes.isEmpty()) PeerAvailability.LOCATING else PeerAvailability.ONLINE)
    is PeerRouteEvent.Authenticating -> state.copy(availability = if (state.routes.isEmpty()) PeerAvailability.AUTHENTICATING else PeerAvailability.ONLINE)
    is PeerRouteEvent.Authenticated -> PeerRouteState(state.routes + event.id, PeerAvailability.ONLINE)
    is PeerRouteEvent.Lost -> (state.routes - event.id).let { PeerRouteState(it, if (it.isEmpty()) PeerAvailability.OFFLINE else PeerAvailability.ONLINE) }
    PeerRouteEvent.Incompatible -> state.copy(availability = PeerAvailability.INCOMPATIBLE)
    PeerRouteEvent.DiscoveryUnavailable -> state.copy(availability = if (state.routes.isEmpty()) PeerAvailability.NEEDS_QR else PeerAvailability.ONLINE)
    PeerRouteEvent.Reset -> PeerRouteState()
}

enum class ConnectivityDiagnostic {
    LOCAL_NETWORK_PERMISSION_DENIED, WIFI_UNAVAILABLE, VPN_INTERFERENCE_SUSPECTED,
    AUTOMATIC_DISCOVERY_UNAVAILABLE, CLIENT_ISOLATION_SUSPECTED, FIREWALL_SUSPECTED,
    ENDPOINT_STALE_OR_UNREACHABLE,
}
data class ConnectivityEvidence(
    val permissionDenied: Boolean = false,
    val hasLanInterface: Boolean = true,
    val vpnActive: Boolean = false,
    val automaticDiscoveryFailed: Boolean = false,
    val signedQrForSamePeer: Boolean = false,
    val authenticatedUnicastSucceeded: Boolean = false,
    val sameSubnetTimedOut: Boolean = false,
    val connectionRefused: Boolean = false,
)
fun classifyConnectivity(value: ConnectivityEvidence): ConnectivityDiagnostic = when {
    value.permissionDenied -> ConnectivityDiagnostic.LOCAL_NETWORK_PERMISSION_DENIED
    !value.hasLanInterface -> ConnectivityDiagnostic.WIFI_UNAVAILABLE
    value.vpnActive -> ConnectivityDiagnostic.VPN_INTERFERENCE_SUSPECTED
    value.automaticDiscoveryFailed && value.signedQrForSamePeer && value.authenticatedUnicastSucceeded -> ConnectivityDiagnostic.AUTOMATIC_DISCOVERY_UNAVAILABLE
    value.automaticDiscoveryFailed && value.signedQrForSamePeer && value.sameSubnetTimedOut -> ConnectivityDiagnostic.CLIENT_ISOLATION_SUSPECTED
    value.connectionRefused -> ConnectivityDiagnostic.FIREWALL_SUSPECTED
    else -> ConnectivityDiagnostic.ENDPOINT_STALE_OR_UNREACHABLE
}

private fun ByteArrayOutputStream.writeU16(value: Int) { write((value ushr 8) and 0xff); write(value and 0xff) }
private fun ByteArrayOutputStream.writeU64(value: Long) { write(ByteBuffer.allocate(8).putLong(value).array()) }
private class FixedReader(private val bytes: ByteArray) {
    private var index = 0
    val finished: Boolean get() = index == bytes.size
    fun bytes(count: Int): ByteArray { require(count >= 0 && index + count <= bytes.size); return bytes.copyOfRange(index, index + count).also { index += count } }
    fun u8(): Int = bytes(1)[0].toInt() and 0xff
    fun u16(): Int = (u8() shl 8) or u8()
    fun u64(): Long = ByteBuffer.wrap(bytes(8)).long.also { require(it >= 0) }
}
