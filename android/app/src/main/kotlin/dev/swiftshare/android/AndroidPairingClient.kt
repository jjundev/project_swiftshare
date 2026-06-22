package dev.swiftshare.android

import dev.swiftshare.domain.PairingConfirmationRequest
import dev.swiftshare.domain.PairingConfirming
import dev.swiftshare.domain.PairingDeviceDescriptor
import dev.swiftshare.domain.PairingInitiatorChannel
import dev.swiftshare.domain.PairingInitiatorConnecting
import dev.swiftshare.domain.PairingInitiatorCryptography
import dev.swiftshare.domain.PairingInitiatorEvent
import dev.swiftshare.domain.PairingInitiatorEventSink
import dev.swiftshare.domain.PairingInitiatorIdentity
import dev.swiftshare.domain.PairingInitiatorSession
import dev.swiftshare.domain.PairingPeerCommitting
import dev.swiftshare.domain.PairingQrPayload
import dev.swiftshare.domain.PairingRecordType
import dev.swiftshare.domain.PairingWireCodec
import dev.swiftshare.domain.PairingWireDevice
import dev.swiftshare.domain.PairingWireMessage
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.ECPublicKey
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

sealed interface AndroidPairingState {
    data object Idle : AndroidPairingState
    data object Connecting : AndroidPairingState
    data class AwaitingConfirmation(
        val peerName: String,
        val platform: String,
        val comparisonCode: String,
        val keyId: String,
    ) : AndroidPairingState
    data object WaitingForMac : AndroidPairingState
    data class Paired(val peer: StoredPeer) : AndroidPairingState
    data class Failed(val code: String) : AndroidPairingState
}

fun interface AndroidPairingListener {
    fun onState(state: AndroidPairingState)
}

class AndroidPairingClient(
    private val identityStore: AndroidDevelopmentIdentityStore,
    private val trustRepository: AndroidTrustRepository,
    private val listener: AndroidPairingListener,
) : AutoCloseable {
    private val executor = Executors.newSingleThreadExecutor {
        Thread(it, "swiftshare-pairing-client").apply { isDaemon = true }
    }
    private val confirmation = AndroidPairingConfirmation()
    private val connector = AndroidPairingConnector()
    private val running = AtomicBoolean(false)

    fun start(uri: String) {
        if (!running.compareAndSet(false, true)) return
        executor.execute {
            try {
                val outcome = runPairingSuspend {
                    session().execute(uri)
                }
                val peer = trustRepository.peer(outcome.peer.keyId.toTrustHex())
                    ?: error("paired_peer_missing_after_commit")
                listener.onState(AndroidPairingState.Paired(peer))
            } catch (error: Exception) {
                listener.onState(AndroidPairingState.Failed(error.message ?: "pairing_failed"))
            } finally {
                running.set(false)
            }
        }
    }

    fun approve() = confirmation.approve()
    fun reject() = confirmation.reject()

    override fun close() {
        running.set(false)
        confirmation.cancel()
        connector.close()
        executor.shutdownNow()
    }

    private fun session() = PairingInitiatorSession(
        connector = connector,
        cryptography = AndroidPairingCryptography(identityStore),
        committer = PairingPeerCommitting { peer, at -> trustRepository.upsert(peer, at) },
        confirmation = confirmation,
        events = PairingInitiatorEventSink(::onEvent),
    )

    private suspend fun onEvent(event: PairingInitiatorEvent) {
        when (event) {
            PairingInitiatorEvent.Connecting -> listener.onState(AndroidPairingState.Connecting)
            is PairingInitiatorEvent.AwaitingConfirmation -> listener.onState(
                AndroidPairingState.AwaitingConfirmation(
                    event.request.peer.displayName,
                    event.request.peer.platform,
                    event.request.comparisonCode,
                    event.request.peer.keyId.toTrustHex(),
                ),
            )
            PairingInitiatorEvent.WaitingForRemoteCommit -> listener.onState(AndroidPairingState.WaitingForMac)
            is PairingInitiatorEvent.Completed -> Unit
        }
    }
}

private class AndroidPairingConnector : PairingInitiatorConnecting {
    @Volatile private var socket: SSLSocket? = null

    override suspend fun connect(payload: PairingQrPayload): PairingInitiatorChannel {
        val tls = SSLContext.getInstance("TLSv1.3").apply {
            init(
                null,
                arrayOf<TrustManager>(ExactCertificateTrustManager(payload.certificateSha256)),
                SecureRandom(),
            )
        }
        val connection = tls.socketFactory.createSocket() as SSLSocket
        socket = connection
        connection.enabledProtocols = arrayOf("TLSv1.3")
        connection.soTimeout = 120_000
        connection.connect(InetSocketAddress(payload.host, payload.port), 15_000)
        connection.startHandshake()
        return AndroidPairingChannel(connection) { if (socket === connection) socket = null }
    }

    fun close() { runCatching { socket?.close() }; socket = null }
}

private class AndroidPairingChannel(
    private val socket: SSLSocket,
    private val onClose: () -> Unit,
) : PairingInitiatorChannel {
    private val input = DataInputStream(socket.inputStream)
    private val output = DataOutputStream(socket.outputStream)

    override suspend fun send(message: PairingWireMessage) {
        val payload = PairingWireCodec.encode(message)
        require(payload.size <= 65_536)
        output.writeByte(message.type.code)
        output.writeInt(payload.size)
        output.write(payload)
        output.flush()
    }

    override suspend fun receive(): PairingWireMessage {
        val type = PairingRecordType.entries.firstOrNull { it.code == input.readUnsignedByte() }
            ?: error("unknown_pairing_record")
        val length = input.readInt()
        require(length in 0..65_536)
        return PairingWireCodec.decode(type, ByteArray(length).also(input::readFully))
    }

    override suspend fun close() {
        runCatching(socket::close)
        onClose()
    }
}

private class AndroidPairingCryptography(
    private val identityStore: AndroidDevelopmentIdentityStore,
) : PairingInitiatorCryptography {
    override suspend fun localIdentity(): PairingInitiatorIdentity {
        val identity = identityStore.loadOrCreate()
        return PairingInitiatorIdentity(
            PairingWireDevice(
                identity.certificateDer,
                identity.canonicalSpki,
                android.os.Build.MODEL.take(128),
                "android",
            ),
        )
    }

    override suspend fun validateRemote(device: PairingWireDevice): PairingDeviceDescriptor {
        val certificate = certificate(device)
        check(certificate.publicKey.encoded.contentEquals(device.canonicalSpki)) {
            "certificate_spki_mismatch"
        }
        return device.descriptor
    }

    override suspend fun sign(digest: ByteArray): ByteArray = identityStore.signP1363(digest)

    override suspend fun verify(
        device: PairingWireDevice,
        digest: ByteArray,
        signature: ByteArray,
    ): Boolean = verifyP1363(certificate(device), digest, signature)

    private fun certificate(device: PairingWireDevice): X509Certificate {
        val value = CertificateFactory.getInstance("X.509")
            .generateCertificate(device.certificateDer.inputStream()) as X509Certificate
        value.checkValidity()
        return value
    }
}

private class AndroidPairingConfirmation : PairingConfirming {
    private val pending = AtomicReference<CompletableFuture<Boolean>?>(null)

    override suspend fun confirm(request: PairingConfirmationRequest): Boolean {
        val value = CompletableFuture<Boolean>()
        pending.set(value)
        return try {
            value.get(120, TimeUnit.SECONDS)
        } catch (_: CancellationException) {
            false
        } finally {
            pending.compareAndSet(value, null)
        }
    }

    fun approve() { pending.get()?.complete(true) }
    fun reject() { pending.get()?.complete(false) }
    fun cancel() { pending.getAndSet(null)?.cancel(true) }
}

private class ExactCertificateTrustManager(
    private val expected: ByteArray,
) : X509TrustManager {
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) =
        error("client auth unsupported")

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        val leaf = chain?.firstOrNull() ?: throw CertificateException("missing Mac certificate")
        leaf.checkValidity()
        val key = leaf.publicKey as? ECPublicKey ?: throw CertificateException("Mac key is not EC")
        if (key.params.curve.field.fieldSize != 256) throw CertificateException("Mac key is not P-256")
        leaf.keyUsage?.let { if (it.isNotEmpty() && !it[0]) throw CertificateException("digitalSignature is not allowed") }
        val actual = MessageDigest.getInstance("SHA-256").digest(leaf.encoded)
        if (!MessageDigest.isEqual(actual, expected)) {
            throw CertificateException("wrong Mac certificate fingerprint")
        }
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

private fun <T> runPairingSuspend(block: suspend () -> T): T {
    val latch = CountDownLatch(1)
    val outcome = AtomicReference<Result<T>>()
    block.startCoroutine(object : Continuation<T> {
        override val context = EmptyCoroutineContext
        override fun resumeWith(result: Result<T>) {
            outcome.set(result)
            latch.countDown()
        }
    })
    latch.await()
    return requireNotNull(outcome.get()).getOrThrow()
}
