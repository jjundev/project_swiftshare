package dev.swiftshare.android

import dev.swiftshare.domain.ClientDecision
import dev.swiftshare.domain.ClientProof
import dev.swiftshare.domain.ClientStart
import dev.swiftshare.domain.CommitAck
import dev.swiftshare.domain.CommitReceipt
import dev.swiftshare.domain.PairingDeviceDescriptor
import dev.swiftshare.domain.PairingQrPayload
import dev.swiftshare.domain.PairingRecordType
import dev.swiftshare.domain.PairingRole
import dev.swiftshare.domain.PairingTranscript
import dev.swiftshare.domain.PairingWireCodec
import dev.swiftshare.domain.PairingWireDevice
import dev.swiftshare.domain.PairingWireError
import dev.swiftshare.domain.ServerChallenge
import dev.swiftshare.domain.ServerProof
import dev.swiftshare.domain.toBytes
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.security.interfaces.ECPublicKey
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

sealed interface AndroidPairingState {
    data object Idle : AndroidPairingState
    data object Connecting : AndroidPairingState
    data class AwaitingConfirmation(val peerName: String, val platform: String, val comparisonCode: String, val keyId: String) : AndroidPairingState
    data object WaitingForMac : AndroidPairingState
    data class Paired(val peer: StoredPeer) : AndroidPairingState
    data class Failed(val code: String) : AndroidPairingState
}

fun interface AndroidPairingListener { fun onState(state: AndroidPairingState) }

class AndroidPairingClient(
    private val identityStore: AndroidDevelopmentIdentityStore,
    private val trustRepository: AndroidTrustRepository,
    private val listener: AndroidPairingListener,
) : AutoCloseable {
    private val executor = Executors.newSingleThreadExecutor { Thread(it, "swiftshare-pairing-client").apply { isDaemon = true } }
    @Volatile private var decision: CompletableFuture<Boolean>? = null
    @Volatile private var socket: SSLSocket? = null
    private val running = AtomicBoolean(false)

    fun start(uri: String) {
        if (!running.compareAndSet(false, true)) return
        executor.execute {
            try { pair(uri) }
            catch (error: Exception) { listener.onState(AndroidPairingState.Failed(error.message ?: "pairing_failed")) }
            finally { running.set(false) }
        }
    }
    fun approve() { decision?.complete(true) }
    fun reject() { decision?.complete(false) }
    override fun close() { running.set(false); decision?.cancel(true); runCatching { socket?.close() }; executor.shutdownNow() }

    private fun pair(uri: String) {
        val qr = PairingQrPayload.decode(uri, Instant.now())
        listener.onState(AndroidPairingState.Connecting)
        val tls = SSLContext.getInstance("TLSv1.3").apply {
            init(null, arrayOf<TrustManager>(ExactCertificateTrustManager(qr.certificateSha256)), SecureRandom())
        }
        val connection = tls.socketFactory.createSocket() as SSLSocket
        socket = connection
        connection.enabledProtocols = arrayOf("TLSv1.3")
        connection.soTimeout = 120_000
        connection.connect(InetSocketAddress(qr.host, qr.port), 15_000)
        connection.startHandshake()
        val input = DataInputStream(connection.inputStream); val output = DataOutputStream(connection.outputStream)
        val identity = identityStore.loadOrCreate()
        val androidDevice = PairingWireDevice(identity.certificateDer, identity.canonicalSpki, android.os.Build.MODEL.take(128), "android")
        val clientNonce = ByteArray(32).also(SecureRandom()::nextBytes)
        write(output, ClientStart(qr.sessionId, qr.token, androidDevice, clientNonce))
        val challenge = read(input) as? ServerChallenge ?: error("server_challenge_required")
        val macCertificate = certificate(challenge.device)
        val macDescriptor = challenge.device.descriptor
        val transcript = PairingTranscript(qr.sessionId, qr.token, qr.certificateSha256, clientNonce, challenge.nonce, macDescriptor, androidDevice.descriptor)
        write(output, ClientProof(identityStore.signP1363(transcript.proofDigest(PairingRole.ANDROID))))
        val serverProof = read(input) as? ServerProof ?: error("server_proof_required")
        check(verifyP1363(macCertificate, transcript.proofDigest(PairingRole.MAC), serverProof.signature)) { "invalid_mac_proof" }
        val candidate = AndroidPairingState.AwaitingConfirmation(
            macDescriptor.displayName, macDescriptor.platform, transcript.comparisonCode, macDescriptor.keyId.toTrustHex(),
        )
        listener.onState(candidate)
        val accepted = CompletableFuture<Boolean>().also { decision = it }.get(120, TimeUnit.SECONDS)
        val decisionDigest = decisionDigest(transcript.digest, accepted)
        write(output, ClientDecision(accepted, identityStore.signP1363(decisionDigest)))
        if (!accepted) throw IllegalStateException("user_rejected")
        listener.onState(AndroidPairingState.WaitingForMac)
        when (val response = read(input)) {
            is PairingWireError -> error(response.code)
            is CommitReceipt -> {
                check(response.sessionId == qr.sessionId && response.transcriptSha256.contentEquals(transcript.digest)) { "receipt_mismatch" }
                check(response.macKeyId.contentEquals(macDescriptor.keyId) && response.androidKeyId.contentEquals(androidDevice.descriptor.keyId)) { "receipt_identity_mismatch" }
                check(verifyP1363(macCertificate, receiptDigest(response), response.signature)) { "invalid_receipt" }
                val peer = trustRepository.upsert(macDescriptor)
                val encoded = PairingWireCodec.encode(response)
                write(output, CommitAck(MessageDigest.getInstance("SHA-256").digest(encoded)))
                listener.onState(AndroidPairingState.Paired(peer))
            }
            else -> error("commit_receipt_required")
        }
        connection.close(); socket = null; decision = null
    }

    private fun certificate(device: PairingWireDevice): X509Certificate {
        val cert = java.security.cert.CertificateFactory.getInstance("X.509")
            .generateCertificate(device.certificateDer.inputStream()) as X509Certificate
        cert.checkValidity()
        check(cert.publicKey.encoded.contentEquals(device.canonicalSpki)) { "certificate_spki_mismatch" }
        return cert
    }

    private fun write(output: DataOutputStream, message: dev.swiftshare.domain.PairingWireMessage) {
        val payload = PairingWireCodec.encode(message); require(payload.size <= 65_536)
        output.writeByte(message.type.code); output.writeInt(payload.size); output.write(payload); output.flush()
    }
    private fun read(input: DataInputStream): dev.swiftshare.domain.PairingWireMessage {
        val type = PairingRecordType.entries.firstOrNull { it.code == input.readUnsignedByte() } ?: error("unknown_pairing_record")
        val length = input.readInt(); require(length in 0..65_536)
        return PairingWireCodec.decode(type, ByteArray(length).also(input::readFully))
    }
}

private class ExactCertificateTrustManager(private val expected: ByteArray) : X509TrustManager {
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = error("client auth unsupported")
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        val leaf = chain?.firstOrNull() ?: throw CertificateException("missing Mac certificate")
        leaf.checkValidity()
        val key = leaf.publicKey as? ECPublicKey ?: throw CertificateException("Mac key is not EC")
        if (key.params.curve.field.fieldSize != 256) throw CertificateException("Mac key is not P-256")
        leaf.keyUsage?.let { if (it.isNotEmpty() && !it[0]) throw CertificateException("digitalSignature is not allowed") }
        val actual = MessageDigest.getInstance("SHA-256").digest(leaf.encoded)
        if (!MessageDigest.isEqual(actual, expected)) throw CertificateException("wrong Mac certificate fingerprint")
    }
    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

internal fun decisionDigest(transcript: ByteArray, accepted: Boolean): ByteArray = MessageDigest.getInstance("SHA-256").digest(
    "SwiftShare-Pairing-v1-decision-${if (accepted) "accept" else "reject"}".toByteArray() + transcript,
)
internal fun receiptDigest(value: CommitReceipt): ByteArray = MessageDigest.getInstance("SHA-256").digest(
    "SwiftShare-Pairing-v1-receipt".toByteArray() + value.sessionId.toBytes() + value.transcriptSha256 + value.macKeyId + value.androidKeyId +
        java.nio.ByteBuffer.allocate(8).putLong(value.committedAt.epochSecond).array(),
)
