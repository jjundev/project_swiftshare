package dev.swiftshare.domain

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.text.Normalizer
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.UUID

data class PairingLimits(
    val maximumQrBytes: Int = 2_048,
    val maximumFrameBytes: Int = 65_536,
    val failureBudget: Int = 3,
    val admissionDuration: Duration = Duration.ofSeconds(60),
    val confirmationDuration: Duration = Duration.ofSeconds(120),
    val recoveryDuration: Duration = Duration.ofSeconds(600),
)

data class PairingQrPayload(
    val protocolMajor: Int = 1,
    val protocolMinor: Int = 0,
    val sessionId: UUID,
    val host: String,
    val port: Int,
    val certificateSha256: ByteArray,
    val token: ByteArray,
    val expiresAt: Instant,
) {
    init {
        require(protocolMajor == 1 && host.isNotEmpty() && host.toByteArray().size <= 255)
        require(port in 1..65535 && certificateSha256.size == 32 && token.size == 32)
    }

    fun encodedUri(limits: PairingLimits = PairingLimits()): String {
        val writer = PairingProtoWriter().apply {
            variable(1, protocolMajor.toLong())
            variable(2, protocolMinor.toLong())
            bytes(3, sessionId.toBytes())
            string(4, host)
            variable(5, port.toLong())
            bytes(6, certificateSha256)
            bytes(7, token)
            variable(8, expiresAt.epochSecond)
        }
        require(writer.data.size <= limits.maximumQrBytes)
        return "swiftshare://pair/v1/" + Base64.getUrlEncoder().withoutPadding().encodeToString(writer.data)
    }

    override fun equals(other: Any?): Boolean = other is PairingQrPayload &&
        protocolMajor == other.protocolMajor && protocolMinor == other.protocolMinor &&
        sessionId == other.sessionId && host == other.host && port == other.port &&
        certificateSha256.contentEquals(other.certificateSha256) && token.contentEquals(other.token) &&
        expiresAt == other.expiresAt

    override fun hashCode(): Int = sessionId.hashCode()

    companion object {
        fun decode(uri: String, now: Instant, limits: PairingLimits = PairingLimits()): PairingQrPayload {
            val prefix = "swiftshare://pair/v1/"
            require(uri.startsWith(prefix))
            val bytes = Base64.getUrlDecoder().decode(uri.removePrefix(prefix))
            require(bytes.size <= limits.maximumQrBytes)
            val reader = PairingProtoReader(bytes)
            var major: Int? = null
            var minor = 0
            var session: UUID? = null
            var host: String? = null
            var port: Int? = null
            var fingerprint: ByteArray? = null
            var token: ByteArray? = null
            var expiry: Instant? = null
            while (true) {
                val field = reader.nextField() ?: break
                when (field.number) {
                    1 -> major = reader.variable(field).toInt()
                    2 -> minor = reader.variable(field).toInt()
                    3 -> session = reader.bytes(field).toUuid()
                    4 -> host = reader.bytes(field).decodeToString()
                    5 -> port = reader.variable(field).toInt()
                    6 -> fingerprint = reader.bytes(field)
                    7 -> token = reader.bytes(field)
                    8 -> expiry = Instant.ofEpochSecond(reader.variable(field))
                    else -> reader.skip(field)
                }
            }
            require(expiry != null && expiry!!.isAfter(now))
            return PairingQrPayload(major!!, minor, session!!, host!!, port!!, fingerprint!!, token!!, expiry!!)
        }
    }
}

data class PairingDeviceDescriptor(
    val canonicalSpki: ByteArray,
    val displayName: String,
    val platform: String,
) {
    val keyId: ByteArray get() = sha256(canonicalSpki)
    init {
        val normalized = Normalizer.normalize(displayName, Normalizer.Form.NFC)
        require(canonicalSpki.isNotEmpty() && normalized == displayName && displayName.toByteArray().size in 1..128)
        require(platform.toByteArray().size in 1..32)
        require(displayName.none { Character.isISOControl(it) || it in BIDI_CONTROLS })
    }
    override fun equals(other: Any?): Boolean = other is PairingDeviceDescriptor &&
        canonicalSpki.contentEquals(other.canonicalSpki) && displayName == other.displayName && platform == other.platform
    override fun hashCode(): Int = canonicalSpki.contentHashCode()
}

enum class PairingRole { MAC, ANDROID }

class PairingTranscript(
    sessionId: UUID,
    token: ByteArray,
    certificateSha256: ByteArray,
    clientNonce: ByteArray,
    serverNonce: ByteArray,
    mac: PairingDeviceDescriptor,
    android: PairingDeviceDescriptor,
) {
    val digest: ByteArray
    init {
        require(token.size == 32 && certificateSha256.size == 32 && clientNonce.size == 32 && serverNonce.size == 32)
        val buffer = ArrayList<Byte>()
        fun add(value: ByteArray) { value.forEach(buffer::add) }
        add("SwiftShare-Pairing-v1".toByteArray())
        add(sessionId.toBytes()); add(sha256(token)); add(certificateSha256); add(clientNonce); add(serverNonce)
        listOf(mac.canonicalSpki, mac.displayName.toByteArray(), mac.platform.toByteArray(),
            android.canonicalSpki, android.displayName.toByteArray(), android.platform.toByteArray()).forEach {
            add(ByteBuffer.allocate(4).putInt(it.size).array()); add(it)
        }
        digest = sha256(buffer.toByteArray())
    }
    fun proofDigest(role: PairingRole): ByteArray = sha256(
        "SwiftShare-Pairing-v1-proof-${role.name.lowercase()}".toByteArray() + digest,
    )
    val comparisonCode: String get() {
        var counter = 0
        val upper = 0xffff_ffffL - (0xffff_ffffL % 1_000_000L)
        while (true) {
            val candidate = ByteBuffer.wrap(sha256(
                "SwiftShare-Pairing-v1-code".toByteArray() + digest + ByteBuffer.allocate(4).putInt(counter).array(),
            ), 0, 4).int.toLong() and 0xffff_ffffL
            if (candidate < upper) return "%06d".format(candidate % 1_000_000L)
            counter++
        }
    }
}

sealed interface PairingSessionPhase {
    data object Accepting : PairingSessionPhase
    data class Claimed(val candidateKeyId: ByteArray, val confirmationDeadline: Instant) : PairingSessionPhase
    data class Committed(val candidateKeyId: ByteArray, val recoveryDeadline: Instant) : PairingSessionPhase
    data class Invalidated(val error: PairingException) : PairingSessionPhase
}

class PairingSessionStateMachine(
    val payload: PairingQrPayload,
    private val limits: PairingLimits = PairingLimits(),
) {
    var phase: PairingSessionPhase = PairingSessionPhase.Accepting
        private set
    private var failures = 0

    @Synchronized fun admit(sessionId: UUID, token: ByteArray, now: Instant) {
        check(phase is PairingSessionPhase.Accepting) { throw PairingException(PairingErrorCode.ALREADY_CLAIMED) }
        if (!now.isBefore(payload.expiresAt)) invalidateAndThrow(PairingErrorCode.EXPIRED)
        if (sessionId != payload.sessionId || token.size != 32 || !MessageDigest.isEqual(token, payload.token)) {
            fail(PairingErrorCode.INVALID_TOKEN)
        }
    }

    @Synchronized fun claim(candidateKeyId: ByteArray, proofIsValid: Boolean, now: Instant) {
        check(phase is PairingSessionPhase.Accepting) { throw PairingException(PairingErrorCode.ALREADY_CLAIMED) }
        if (!proofIsValid) fail(PairingErrorCode.INVALID_PROOF)
        phase = PairingSessionPhase.Claimed(candidateKeyId.copyOf(), now.plus(limits.confirmationDuration))
    }

    @Synchronized fun requireConfirmation(candidateKeyId: ByteArray, now: Instant) {
        val claimed = phase as? PairingSessionPhase.Claimed ?: throw PairingException(PairingErrorCode.ALREADY_CLAIMED)
        if (!MessageDigest.isEqual(claimed.candidateKeyId, candidateKeyId)) throw PairingException(PairingErrorCode.ALREADY_CLAIMED)
        if (!now.isBefore(claimed.confirmationDeadline)) invalidateAndThrow(PairingErrorCode.EXPIRED)
    }

    @Synchronized fun commit(candidateKeyId: ByteArray, now: Instant) {
        requireConfirmation(candidateKeyId, now)
        phase = PairingSessionPhase.Committed(candidateKeyId.copyOf(), now.plus(limits.recoveryDuration))
    }

    @Synchronized fun cancel() { phase = PairingSessionPhase.Invalidated(PairingException(PairingErrorCode.CANCELLED)) }

    private fun fail(code: PairingErrorCode): Nothing {
        failures++
        if (failures >= limits.failureBudget) invalidateAndThrow(PairingErrorCode.FAILURE_BUDGET_EXHAUSTED)
        throw PairingException(code)
    }
    private fun invalidateAndThrow(code: PairingErrorCode): Nothing {
        val error = PairingException(code); phase = PairingSessionPhase.Invalidated(error); throw error
    }
}

enum class PairingErrorCode { INVALID_QR, OVERSIZED, EXPIRED, INVALID_TOKEN, INVALID_IDENTITY, INVALID_TRANSCRIPT, INVALID_PROOF, ALREADY_CLAIMED, FAILURE_BUDGET_EXHAUSTED, CANCELLED, STORAGE }
class PairingException(val code: PairingErrorCode) : IllegalArgumentException(code.name.lowercase())

private val BIDI_CONTROLS = setOf('\u061c', '\u200e', '\u200f', '\u202a', '\u202b', '\u202c', '\u202d', '\u202e', '\u2066', '\u2067', '\u2068', '\u2069')
private fun sha256(value: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(value)
private class PairingProtoWriter {
    private val output = ArrayList<Byte>()
    val data: ByteArray get() = output.toByteArray()
    fun variable(field: Int, value: Long) { tag(field, 0); varint(value) }
    fun bytes(field: Int, value: ByteArray) { tag(field, 2); varint(value.size.toLong()); value.forEach(output::add) }
    fun string(field: Int, value: String) = bytes(field, value.toByteArray())
    private fun tag(field: Int, wire: Int) = varint(((field shl 3) or wire).toLong())
    private fun varint(input: Long) { var value = input; while (value and -128L != 0L) { output += ((value and 127) or 128).toByte(); value = value ushr 7 }; output += value.toByte() }
}

private class PairingProtoReader(private val data: ByteArray) {
    data class Field(val number: Int, val wire: Int)
    private var index = 0
    fun nextField(): Field? { if (index == data.size) return null; val tag = varint(); return Field((tag ushr 3).toInt(), (tag and 7).toInt()).also { require(it.number > 0 && it.wire in setOf(0, 2)) } }
    fun variable(field: Field): Long { require(field.wire == 0); return varint() }
    fun bytes(field: Field): ByteArray { require(field.wire == 2); val count = varint().toInt(); require(count >= 0 && index + count <= data.size); return data.copyOfRange(index, index + count).also { index += count } }
    fun skip(field: Field) { if (field.wire == 0) varint() else bytes(field) }
    private fun varint(): Long { var value = 0L; for (shift in 0..63 step 7) { require(index < data.size); val byte = data[index++].toInt() and 0xff; value = value or ((byte and 0x7f).toLong() shl shift); if (byte and 0x80 == 0) return value }; error("invalid varint") }
}
