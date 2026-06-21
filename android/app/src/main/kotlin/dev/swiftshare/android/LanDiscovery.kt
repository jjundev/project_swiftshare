package dev.swiftshare.android

import android.content.Context
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import dev.swiftshare.domain.DiscoveryAdvertisement
import dev.swiftshare.domain.EndpointAddress
import dev.swiftshare.domain.EndpointAddressFamily
import dev.swiftshare.domain.EndpointQrCodec
import dev.swiftshare.domain.EndpointQrBody
import dev.swiftshare.domain.EndpointQrEnvelope
import java.security.SecureRandom
import java.time.Instant
import java.util.UUID

const val SWIFTSHARE_SERVICE_TYPE = "_swiftshare._tcp"

class EndpointTicketRegistry {
    private data class Ticket(val bytes: ByteArray, val expiresAt: Instant, var claimedBy: Pair<String, UUID>? = null)
    private val lock = Any()
    private var active: Ticket? = null

    fun issue(now: Instant = Instant.now()): Pair<ByteArray, Instant> = synchronized(lock) {
        val bytes = ByteArray(32).also(SecureRandom()::nextBytes)
        val expiry = now.plusSeconds(60)
        active = Ticket(bytes, expiry)
        bytes.copyOf() to expiry
    }

    fun claim(presented: ByteArray, peerIdHex: String, sessionId: UUID, now: Instant = Instant.now()): Boolean = synchronized(lock) {
        val ticket = active ?: return false
        if (!ticket.expiresAt.isAfter(now) || !java.security.MessageDigest.isEqual(ticket.bytes, presented)) return false
        val claimant = peerIdHex to sessionId
        if (ticket.claimedBy != null && ticket.claimedBy != claimant) return false
        ticket.claimedBy = claimant
        true
    }

    fun consume() = synchronized(lock) { active = null }
    fun invalidate() = consume()
}

class AndroidLanAdvertiser(
    context: Context,
    private val onFailure: (String) -> Unit,
) : AutoCloseable {
    private val nsd = context.getSystemService(NsdManager::class.java)
    private var registration: NsdManager.RegistrationListener? = null
    var advertisement: DiscoveryAdvertisement? = null
        private set

    @Synchronized fun start(port: Int): DiscoveryAdvertisement {
        require(port in 1..65535)
        stop()
        val epoch = DiscoveryAdvertisement(ByteArray(16).also(SecureRandom()::nextBytes))
        val info = NsdServiceInfo().apply {
            serviceName = epoch.instanceName
            serviceType = SWIFTSHARE_SERVICE_TYPE
            setPort(port)
            epoch.txt.forEach(::setAttribute)
        }
        val callback = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) { advertisement = epoch }
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                advertisement = null; onFailure("discovery_registration_$errorCode")
            }
            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) { advertisement = null }
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                advertisement = null; onFailure("discovery_unregistration_$errorCode")
            }
        }
        registration = callback
        nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, callback)
        return epoch
    }

    @Synchronized fun stop() {
        val value = registration ?: return
        registration = null
        runCatching { nsd.unregisterService(value) }
        advertisement = null
    }

    override fun close() = stop()
}

class AndroidEndpointQrIssuer(
    private val context: Context,
    private val identityStore: AndroidDevelopmentIdentityStore,
    private val tickets: EndpointTicketRegistry,
) {
    fun issue(port: Int, now: Instant = Instant.now()): String {
        val identity = identityStore.loadOrCreate()
        val (ticket, expiry) = tickets.issue(now)
        val addresses = localAddresses()
        require(addresses.isNotEmpty()) { "No reachable Wi-Fi or Ethernet address" }
        val body = EndpointQrBody(identity.spkiSha256, port, expiry, ticket, addresses.take(8))
        val bodyBytes = EndpointQrCodec.encodeBody(body)
        val signature = identityStore.signP1363(EndpointQrCodec.signingDigest(bodyBytes))
        return EndpointQrCodec.encodeUri(EndpointQrEnvelope(bodyBytes, signature))
    }

    private fun localAddresses(): List<EndpointAddress> {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val networks = connectivity.allNetworks.filter { network ->
            connectivity.getNetworkCapabilities(network)?.let {
                it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || it.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
            } == true
        }
        return networks.flatMap { network ->
            connectivity.getLinkProperties(network)?.linkAddresses.orEmpty().mapNotNull { link ->
                val address = link.address
                when {
                    address.isLoopbackAddress || address.isMulticastAddress -> null
                    address.address.size == 4 -> EndpointAddress(EndpointAddressFamily.IPV4, address.address)
                    address.address.size == 16 -> EndpointAddress(EndpointAddressFamily.IPV6, address.address)
                    else -> null
                }
            }
        }.distinctBy { it.family to it.bytes.contentHashCode() }
    }
}

fun endpointQrBitmap(contents: String, size: Int = 640): Bitmap {
    val matrix = QRCodeWriter().encode(contents, BarcodeFormat.QR_CODE, size, size)
    val pixels = IntArray(size * size)
    for (y in 0 until size) for (x in 0 until size) {
        pixels[y * size + x] = if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE
    }
    return Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).apply { setPixels(pixels, 0, size, 0, 0, size, size) }
}
