package dev.swiftshare.domain

import java.io.ByteArrayOutputStream

const val MINIMUM_NEGOTIATED_CHUNK_SIZE = 64 * 1024

data class ProtocolVersionRange(
    val major: Int,
    val minimumMinor: Int,
    val maximumMinor: Int,
) {
    init {
        require(major in 1..0xffff)
        require(minimumMinor in 0..0xffff && maximumMinor in minimumMinor..0xffff)
    }

    fun contains(version: NegotiatedProtocolVersion): Boolean =
        version.major == major && version.minor in minimumMinor..maximumMinor
}

data class NegotiatedProtocolVersion(val major: Int, val minor: Int) {
    init { require(major in 1..0xffff && minor in 0..0xffff) }
}

data class CapabilityOffer(
    val name: String,
    val supportedVersions: List<ProtocolVersionRange>,
    val requiredVersions: List<ProtocolVersionRange> = emptyList(),
) {
    init {
        require(CAPABILITY_NAME.matches(name))
        require(supportedVersions.isNotEmpty() && supportedVersions.size <= 8)
        require(requiredVersions.size <= 8)
        require(requiredVersions.all { required -> supportedVersions.any { it.containsRange(required) } })
    }

    fun supports(version: NegotiatedProtocolVersion): Boolean = supportedVersions.any { it.contains(version) }
    fun requires(version: NegotiatedProtocolVersion): Boolean = requiredVersions.any { it.contains(version) }
}

data class SessionOffer(
    val versions: List<ProtocolVersionRange>,
    val capabilities: List<CapabilityOffer>,
    val minimumChunkSize: Int,
    val maximumChunkSize: Int,
) {
    init {
        require(versions.isNotEmpty() && versions.size <= 8)
        require(capabilities.size <= 32 && capabilities.map { it.name }.distinct().size == capabilities.size)
        require(minimumChunkSize in MINIMUM_NEGOTIATED_CHUNK_SIZE..TRANSFER_CHUNK_TARGET)
        require(maximumChunkSize in minimumChunkSize..TRANSFER_CHUNK_TARGET)
    }

    companion object {
        val DEFAULT = SessionOffer(
            versions = listOf(ProtocolVersionRange(1, 0, 0)),
            capabilities = listOf(
                CapabilityOffer(
                    name = "file_transfer",
                    supportedVersions = listOf(ProtocolVersionRange(1, 0, 0)),
                    requiredVersions = listOf(ProtocolVersionRange(1, 0, 0)),
                ),
            ),
            minimumChunkSize = MINIMUM_NEGOTIATED_CHUNK_SIZE,
            maximumChunkSize = TRANSFER_CHUNK_TARGET,
        )
    }
}

data class NegotiatedSessionParameters(
    val version: NegotiatedProtocolVersion,
    val capabilities: List<String>,
    val chunkSize: Int,
)

enum class BootstrapRejectionCategory(val code: Int) {
    INCOMPATIBLE_VERSION(1), CAPABILITY(2), BUSY(3), PROTOCOL(4), AUTHENTICATION(5),
}

class SessionHello(val offer: SessionOffer, val endpointTicket: ByteArray? = null) {
    init { require(endpointTicket == null || endpointTicket.size == ENDPOINT_TICKET_BYTES) }
    override fun equals(other: Any?): Boolean = other is SessionHello && offer == other.offer &&
        if (endpointTicket == null) other.endpointTicket == null else other.endpointTicket?.let(endpointTicket::contentEquals) == true
    override fun hashCode(): Int = 31 * offer.hashCode() + (endpointTicket?.contentHashCode() ?: 0)
}

data class SessionDecision(
    val accepted: Boolean,
    val receiverOffer: SessionOffer,
    val negotiated: NegotiatedSessionParameters? = null,
    val rejectionCategory: BootstrapRejectionCategory? = null,
    val rejectionCode: String = "",
) {
    init {
        require(accepted == (negotiated != null))
        require(accepted || rejectionCategory != null)
        require(rejectionCode.length <= 64 && rejectionCode.all { it.code in 0x20..0x7e })
    }

    companion object {
        fun accepted(receiverOffer: SessionOffer, parameters: NegotiatedSessionParameters) =
            SessionDecision(true, receiverOffer, negotiated = parameters)

        fun rejected(receiverOffer: SessionOffer, category: BootstrapRejectionCategory, code: String) =
            SessionDecision(false, receiverOffer, rejectionCategory = category, rejectionCode = code)
    }
}

sealed interface NegotiationResult {
    data class Accepted(val parameters: NegotiatedSessionParameters) : NegotiationResult
    data class Rejected(val category: BootstrapRejectionCategory, val code: String) : NegotiationResult
}

object SessionNegotiator {
    private val knownCapabilities = setOf("file_transfer")

    fun negotiate(sender: SessionOffer, receiver: SessionOffer): NegotiationResult {
        val commonMajors = sender.versions.map { it.major }.intersect(receiver.versions.map { it.major }.toSet())
        if (commonMajors.isEmpty()) {
            return NegotiationResult.Rejected(BootstrapRejectionCategory.INCOMPATIBLE_VERSION, "no_common_major")
        }
        val major = commonMajors.max()
        var selectedMinor: Int? = null
        for (left in sender.versions.filter { it.major == major }) {
            for (right in receiver.versions.filter { it.major == major }) {
                val minimum = maxOf(left.minimumMinor, right.minimumMinor)
                val maximum = minOf(left.maximumMinor, right.maximumMinor)
                if (minimum <= maximum) selectedMinor = maxOf(selectedMinor ?: -1, maximum)
            }
        }
        val minor = selectedMinor ?: return NegotiationResult.Rejected(
            BootstrapRejectionCategory.INCOMPATIBLE_VERSION, "no_common_minor",
        )
        val version = NegotiatedProtocolVersion(major, minor)
        val senderSupported = sender.capabilities.filter { it.supports(version) }.associateBy { it.name }
        val receiverSupported = receiver.capabilities.filter { it.supports(version) }.associateBy { it.name }
        val selected = senderSupported.keys.intersect(receiverSupported.keys).filter { it in knownCapabilities }.sorted()
        val required = (sender.capabilities + receiver.capabilities).filter { it.requires(version) }.map { it.name }.toSet()
        if (required.any { it !in knownCapabilities || it !in selected }) {
            return NegotiationResult.Rejected(BootstrapRejectionCategory.CAPABILITY, "required_capability_unsupported")
        }
        val minimumChunk = maxOf(sender.minimumChunkSize, receiver.minimumChunkSize)
        val maximumChunk = minOf(sender.maximumChunkSize, receiver.maximumChunkSize)
        if (minimumChunk > maximumChunk) {
            return NegotiationResult.Rejected(BootstrapRejectionCategory.CAPABILITY, "no_common_chunk_size")
        }
        return NegotiationResult.Accepted(NegotiatedSessionParameters(version, selected, maximumChunk))
    }

    fun validate(hello: SessionHello, decision: SessionDecision): NegotiatedSessionParameters {
        if (!decision.accepted) throw TransferProtocolException(decision.rejectionCode)
        val expected = negotiate(hello.offer, decision.receiverOffer)
        val parameters = (expected as? NegotiationResult.Accepted)?.parameters
            ?: throw TransferProtocolException("invalid_negotiation_decision")
        if (parameters != decision.negotiated) throw TransferProtocolException("invalid_negotiation_selection")
        return parameters
    }
}

object SessionBootstrapCodec {
    fun encode(hello: SessionHello): ByteArray = BootstrapWriter().apply {
        message(1, encodeOffer(hello.offer))
        val ticket = hello.endpointTicket
        if (ticket != null) message(2, ticket)
    }.data

    fun decodeHello(bytes: ByteArray): SessionHello {
        requireBound(bytes)
        val reader = BootstrapReader(bytes)
        var offer: SessionOffer? = null
        var ticket: ByteArray? = null
        reader.fields { field, wire -> when (field) {
            1 -> { reader.requireWire(wire, 2); if (offer != null) duplicate(); offer = decodeOffer(reader.bytes()) }
            2 -> { reader.requireWire(wire, 2); if (ticket != null) duplicate(); ticket = reader.bytes(); if (ticket?.size != ENDPOINT_TICKET_BYTES) throw TransferProtocolException("invalid_endpoint_ticket") }
            else -> reader.skip(wire)
        } }
        return SessionHello(offer ?: throw TransferProtocolException("malformed_hello"), ticket)
    }

    fun encode(decision: SessionDecision): ByteArray = BootstrapWriter().apply {
        if (decision.accepted) variable(1, 1)
        message(2, encodeOffer(decision.receiverOffer))
        decision.negotiated?.let { message(3, encodeParameters(it)) }
        decision.rejectionCategory?.let { variable(4, it.code.toLong()) }
        if (decision.rejectionCode.isNotEmpty()) string(5, decision.rejectionCode)
    }.data

    fun decodeDecision(bytes: ByteArray): SessionDecision {
        requireBound(bytes)
        val reader = BootstrapReader(bytes)
        var accepted = false
        var seenAccepted = false
        var offer: SessionOffer? = null
        var parameters: NegotiatedSessionParameters? = null
        var category: BootstrapRejectionCategory? = null
        var code = ""
        var seenCode = false
        reader.fields { field, wire -> when (field) {
            1 -> { reader.requireWire(wire, 0); if (seenAccepted) duplicate(); seenAccepted = true; accepted = reader.variable() != 0L }
            2 -> { reader.requireWire(wire, 2); if (offer != null) duplicate(); offer = decodeOffer(reader.bytes()) }
            3 -> { reader.requireWire(wire, 2); if (parameters != null) duplicate(); parameters = decodeParameters(reader.bytes()) }
            4 -> {
                reader.requireWire(wire, 0); if (category != null) duplicate()
                val value = reader.variable().toInt()
                category = BootstrapRejectionCategory.entries.firstOrNull { it.code == value }
                    ?: throw TransferProtocolException("invalid_rejection_category")
            }
            5 -> { reader.requireWire(wire, 2); if (seenCode) duplicate(); seenCode = true; code = reader.string() }
            else -> reader.skip(wire)
        } }
        return SessionDecision(accepted, offer ?: throw TransferProtocolException("invalid_decision"), parameters, category, code)
    }

    private fun encodeOffer(offer: SessionOffer): ByteArray = BootstrapWriter().apply {
        offer.versions.forEach { message(1, encodeRange(it)) }
        offer.capabilities.forEach { message(2, encodeCapability(it)) }
        variable(3, offer.minimumChunkSize.toLong())
        variable(4, offer.maximumChunkSize.toLong())
    }.data

    private fun decodeOffer(bytes: ByteArray): SessionOffer {
        val versions = mutableListOf<ProtocolVersionRange>()
        val capabilities = mutableListOf<CapabilityOffer>()
        var minimum: Int? = null
        var maximum: Int? = null
        val reader = BootstrapReader(bytes)
        reader.fields { field, wire -> when (field) {
            1 -> { reader.requireWire(wire, 2); versions += decodeRange(reader.bytes()) }
            2 -> { reader.requireWire(wire, 2); capabilities += decodeCapability(reader.bytes()) }
            3 -> { reader.requireWire(wire, 0); if (minimum != null) duplicate(); minimum = reader.variable().toBoundedInt() }
            4 -> { reader.requireWire(wire, 0); if (maximum != null) duplicate(); maximum = reader.variable().toBoundedInt() }
            else -> reader.skip(wire)
        } }
        return try {
            SessionOffer(versions, capabilities, minimum ?: 0, maximum ?: 0)
        } catch (_: IllegalArgumentException) {
            throw TransferProtocolException("invalid_session_offer")
        }
    }

    private fun encodeRange(range: ProtocolVersionRange): ByteArray = BootstrapWriter().apply {
        variable(1, range.major.toLong()); variable(2, range.minimumMinor.toLong()); variable(3, range.maximumMinor.toLong())
    }.data

    private fun decodeRange(bytes: ByteArray): ProtocolVersionRange {
        var major: Int? = null; var minimum: Int? = null; var maximum: Int? = null
        val reader = BootstrapReader(bytes)
        reader.fields { field, wire -> when (field) {
            1 -> { reader.requireWire(wire, 0); if (major != null) duplicate(); major = reader.variable().toBoundedInt() }
            2 -> { reader.requireWire(wire, 0); if (minimum != null) duplicate(); minimum = reader.variable().toBoundedInt() }
            3 -> { reader.requireWire(wire, 0); if (maximum != null) duplicate(); maximum = reader.variable().toBoundedInt() }
            else -> reader.skip(wire)
        } }
        return try { ProtocolVersionRange(major ?: 0, minimum ?: 0, maximum ?: 0) }
        catch (_: IllegalArgumentException) { throw TransferProtocolException("invalid_version_range") }
    }

    private fun encodeCapability(capability: CapabilityOffer): ByteArray = BootstrapWriter().apply {
        string(1, capability.name)
        capability.supportedVersions.forEach { message(2, encodeRange(it)) }
        capability.requiredVersions.forEach { message(3, encodeRange(it)) }
    }.data

    private fun decodeCapability(bytes: ByteArray): CapabilityOffer {
        var name: String? = null
        val supported = mutableListOf<ProtocolVersionRange>()
        val required = mutableListOf<ProtocolVersionRange>()
        val reader = BootstrapReader(bytes)
        reader.fields { field, wire -> when (field) {
            1 -> { reader.requireWire(wire, 2); if (name != null) duplicate(); name = reader.string() }
            2 -> { reader.requireWire(wire, 2); supported += decodeRange(reader.bytes()) }
            3 -> { reader.requireWire(wire, 2); required += decodeRange(reader.bytes()) }
            else -> reader.skip(wire)
        } }
        return try { CapabilityOffer(name ?: "", supported, required) }
        catch (_: IllegalArgumentException) { throw TransferProtocolException("invalid_capability_offer") }
    }

    private fun encodeParameters(parameters: NegotiatedSessionParameters): ByteArray = BootstrapWriter().apply {
        message(1, BootstrapWriter().apply {
            variable(1, parameters.version.major.toLong()); variable(2, parameters.version.minor.toLong())
        }.data)
        parameters.capabilities.forEach { string(2, it) }
        variable(3, parameters.chunkSize.toLong())
    }.data

    private fun decodeParameters(bytes: ByteArray): NegotiatedSessionParameters {
        var version: NegotiatedProtocolVersion? = null
        val capabilities = mutableListOf<String>()
        var chunk: Int? = null
        val reader = BootstrapReader(bytes)
        reader.fields { field, wire -> when (field) {
            1 -> {
                reader.requireWire(wire, 2); if (version != null) duplicate()
                val nested = BootstrapReader(reader.bytes()); var major: Int? = null; var minor: Int? = null
                nested.fields { nestedField, nestedWire -> when (nestedField) {
                    1 -> { nested.requireWire(nestedWire, 0); if (major != null) duplicate(); major = nested.variable().toBoundedInt() }
                    2 -> { nested.requireWire(nestedWire, 0); if (minor != null) duplicate(); minor = nested.variable().toBoundedInt() }
                    else -> nested.skip(nestedWire)
                } }
                version = try { NegotiatedProtocolVersion(major ?: 0, minor ?: 0) }
                catch (_: IllegalArgumentException) { throw TransferProtocolException("invalid_negotiated_version") }
            }
            2 -> { reader.requireWire(wire, 2); capabilities += reader.string() }
            3 -> { reader.requireWire(wire, 0); if (chunk != null) duplicate(); chunk = reader.variable().toBoundedInt() }
            else -> reader.skip(wire)
        } }
        if (capabilities.size > 32 || capabilities.distinct().size != capabilities.size || capabilities.any { !CAPABILITY_NAME.matches(it) }) {
            throw TransferProtocolException("invalid_negotiated_capabilities")
        }
        return NegotiatedSessionParameters(
            version ?: throw TransferProtocolException("invalid_negotiated_version"), capabilities, chunk ?: 0,
        )
    }

    private fun requireBound(bytes: ByteArray) {
        if (bytes.size > TRANSFER_BOOTSTRAP_LIMIT) throw TransferProtocolException("oversized_bootstrap")
    }

    private fun duplicate(): Nothing = throw TransferProtocolException("duplicate_singular_field")
    private fun Long.toBoundedInt(): Int {
        if (this !in 0..0xffff_ffffL) throw TransferProtocolException("integer_overflow")
        return toInt()
    }
}

private val CAPABILITY_NAME = Regex("[a-z][a-z0-9_.-]{0,63}")
private fun ProtocolVersionRange.containsRange(other: ProtocolVersionRange): Boolean =
    major == other.major && minimumMinor <= other.minimumMinor && maximumMinor >= other.maximumMinor

private class BootstrapWriter {
    private val output = ByteArrayOutputStream()
    val data: ByteArray get() = output.toByteArray()
    fun variable(field: Int, value: Long) { varint((field shl 3).toLong()); varint(value) }
    fun message(field: Int, value: ByteArray) { varint(((field shl 3) or 2).toLong()); varint(value.size.toLong()); output.write(value) }
    fun string(field: Int, value: String) = message(field, value.toByteArray(Charsets.UTF_8))
    private fun varint(input: Long) { var value = input; do { var byte = (value and 0x7f).toInt(); value = value ushr 7; if (value != 0L) byte = byte or 0x80; output.write(byte) } while (value != 0L) }
}

private class BootstrapReader(private val input: ByteArray) {
    private var position = 0
    fun fields(block: (Int, Int) -> Unit) { while (position < input.size) { val tag = variable(); val field = (tag ushr 3).toInt(); if (field == 0) throw TransferProtocolException("invalid_protobuf_tag"); block(field, (tag and 7).toInt()) } }
    fun requireWire(actual: Int, expected: Int) { if (actual != expected) throw TransferProtocolException("invalid_known_field_wire_type") }
    fun variable(): Long { var result = 0L; var shift = 0; while (shift < 64) { if (position >= input.size) throw TransferProtocolException("truncated_protobuf"); val byte = input[position++].toInt() and 0xff; result = result or ((byte and 0x7f).toLong() shl shift); if (byte and 0x80 == 0) return result; shift += 7 }; throw TransferProtocolException("invalid_varint") }
    fun bytes(): ByteArray { val length = variable(); if (length > Int.MAX_VALUE || length < 0 || position + length.toInt() > input.size) throw TransferProtocolException("truncated_protobuf"); return input.copyOfRange(position, position + length.toInt()).also { position += length.toInt() } }
    fun string(): String = bytes().toString(Charsets.UTF_8)
    fun skip(wire: Int) { when (wire) { 0 -> variable(); 1 -> advance(8); 2 -> advance(variable().toInt()); 5 -> advance(4); else -> throw TransferProtocolException("unsupported_protobuf_wire_type") } }
    private fun advance(count: Int) { if (count < 0 || position + count > input.size) throw TransferProtocolException("truncated_protobuf"); position += count }
}
