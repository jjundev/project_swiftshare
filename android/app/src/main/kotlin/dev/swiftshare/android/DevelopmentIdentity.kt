package dev.swiftshare.android

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.util.Date
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import javax.security.auth.x500.X500Principal

data class DevelopmentIdentity(
    val alias: String,
    val certificate: X509Certificate,
    val spkiSha256: ByteArray,
    val canonicalSpki: ByteArray,
    val certificateDer: ByteArray,
)

class AndroidDevelopmentIdentityStore(
    private val alias: String = DEFAULT_ALIAS,
) {
    fun exists(): Boolean = androidKeyStore().containsAlias(alias)

    fun loadOrCreate(): DevelopmentIdentity {
        val keyStore = androidKeyStore()
        if (!keyStore.containsAlias(alias)) generateIdentity()
        val certificate = keyStore.getCertificate(alias) as? X509Certificate
            ?: throw IllegalStateException("development identity certificate is missing")
        val privateKey = keyStore.getKey(alias, null)
            ?: throw IllegalStateException("development identity private key is missing")
        check(privateKey.encoded == null) { "Android Keystore identity must be non-exportable" }
        return DevelopmentIdentity(
            alias = alias,
            certificate = certificate,
            spkiSha256 = MessageDigest.getInstance("SHA-256").digest(certificate.publicKey.encoded),
            canonicalSpki = certificate.publicKey.encoded,
            certificateDer = certificate.encoded,
        )
    }

    fun signP1363(digest: ByteArray): ByteArray {
        require(digest.size == 32)
        val key = androidKeyStore().getKey(alias, null)
            ?: throw IllegalStateException("identity private key is missing")
        val der = Signature.getInstance("NONEwithECDSA").run { initSign(key as java.security.PrivateKey); update(digest); sign() }
        return derEcdsaToP1363(der)
    }

    fun delete() {
        val keyStore = androidKeyStore()
        if (keyStore.containsAlias(alias)) keyStore.deleteEntry(alias)
    }

    private fun generateIdentity() {
        val now = System.currentTimeMillis()
        val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEY_STORE)
        val specification = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY,
        )
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setCertificateSubject(X500Principal("CN=SwiftShare Android Development"))
            .setCertificateSerialNumber(BigInteger(160, SecureRandom()).abs())
            .setCertificateNotBefore(Date(now - CLOCK_SKEW_MILLIS))
            .setCertificateNotAfter(Date(now + CERTIFICATE_LIFETIME_MILLIS))
            .setUserAuthenticationRequired(false)
            .build()
        generator.initialize(specification)
        generator.generateKeyPair()
    }

    companion object {
        const val DEFAULT_ALIAS = "dev.swiftshare.transfer.identity"
        private const val ANDROID_KEY_STORE = "AndroidKeyStore"
        private const val CLOCK_SKEW_MILLIS = 5 * 60 * 1000L
        private const val CERTIFICATE_LIFETIME_MILLIS = 30L * 24 * 60 * 60 * 1000

        internal fun androidKeyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
    }
}

class PinnedSpkiTrustManager(
    pins: Collection<ByteArray>,
) : X509TrustManager {
    private val acceptedPins = pins.map(ByteArray::copyOf)

    init {
        require(acceptedPins.isNotEmpty()) { "At least one configured Peer pin is required" }
        require(acceptedPins.all { it.size == 32 }) { "SPKI pins must be SHA-256 values" }
    }

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = check(chain)
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = check(chain)
    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()

    private fun check(chain: Array<out X509Certificate>?) {
        val leaf = chain?.firstOrNull() ?: throw CertificateException("missing Peer certificate")
        leaf.checkValidity()
        val actual = MessageDigest.getInstance("SHA-256").digest(leaf.publicKey.encoded)
        if (acceptedPins.none { MessageDigest.isEqual(it, actual) }) {
            throw CertificateException("unconfigured Peer identity")
        }
    }
}

class DevelopmentTlsContextFactory(
    private val identityStore: AndroidDevelopmentIdentityStore,
) {
    fun create(pinnedPeerSpkiSha256: Collection<ByteArray>): SSLContext {
        return createWithTrustManager(PinnedSpkiTrustManager(pinnedPeerSpkiSha256))
    }

    fun create(repository: AndroidTrustRepository): SSLContext {
        return createWithTrustManager(RepositorySpkiTrustManager(repository))
    }

    private fun createWithTrustManager(trustManager: X509TrustManager): SSLContext {
        identityStore.loadOrCreate()
        val keyStore = AndroidDevelopmentIdentityStore.androidKeyStore()
        val keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
            init(keyStore, null)
        }.keyManagers
        return SSLContext.getInstance("TLSv1.3").apply {
            init(
                keyManagers,
                arrayOf<TrustManager>(trustManager),
                SecureRandom(),
            )
        }
    }

    fun createServerSocket(context: SSLContext, port: Int): SSLServerSocket {
        require(port in 0..65535)
        return (context.serverSocketFactory.createServerSocket(port) as SSLServerSocket).apply {
            enabledProtocols = arrayOf("TLSv1.3")
            needClientAuth = true
            reuseAddress = true
        }
    }
}

class RepositorySpkiTrustManager(
    private val repository: AndroidTrustRepository,
) : X509TrustManager {
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = check(chain)
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = check(chain)
    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    private fun check(chain: Array<out X509Certificate>?) {
        val leaf = chain?.firstOrNull() ?: throw CertificateException("missing Peer certificate")
        leaf.checkValidity()
        if (!repository.containsSpki(leaf.publicKey.encoded)) throw CertificateException("unpaired Peer")
    }
}

internal fun verifyP1363(certificate: X509Certificate, digest: ByteArray, signature: ByteArray): Boolean {
    if (digest.size != 32 || signature.size != 64) return false
    return Signature.getInstance("NONEwithECDSA").run {
        initVerify(certificate.publicKey); update(digest); verify(p1363ToDerEcdsa(signature))
    }
}

private fun derEcdsaToP1363(der: ByteArray): ByteArray {
    require(der.size >= 8 && der[0] == 0x30.toByte())
    var index = 2
    require(der[index++] == 0x02.toByte()); val rLength = der[index++].toInt() and 0xff
    val r = der.copyOfRange(index, index + rLength); index += rLength
    require(der[index++] == 0x02.toByte()); val sLength = der[index++].toInt() and 0xff
    val s = der.copyOfRange(index, index + sLength)
    fun normalized(value: ByteArray): ByteArray {
        val stripped = value.dropWhile { it == 0.toByte() }.toByteArray()
        require(stripped.size <= 32); return ByteArray(32 - stripped.size) + stripped
    }
    return normalized(r) + normalized(s)
}

private fun p1363ToDerEcdsa(value: ByteArray): ByteArray {
    require(value.size == 64)
    fun integer(part: ByteArray): ByteArray {
        val candidate = part.dropWhile { it == 0.toByte() }.toByteArray()
        val stripped = if (candidate.isEmpty()) byteArrayOf(0) else candidate
        val body = if (stripped[0].toInt() and 0x80 != 0) byteArrayOf(0) + stripped else stripped
        return byteArrayOf(0x02, body.size.toByte()) + body
    }
    val r = integer(value.copyOfRange(0, 32)); val s = integer(value.copyOfRange(32, 64))
    return byteArrayOf(0x30, (r.size + s.size).toByte()) + r + s
}
