package dev.swiftshare.domain

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

const val TRANSFER_HEADER_SIZE = 32
const val TRANSFER_CHUNK_PRELUDE_SIZE = 64
const val TRANSFER_CHUNK_TARGET = 4 * 1024 * 1024
const val TRANSFER_CONTROL_LIMIT = 256 * 1024
const val TRANSFER_RECORD_LIMIT = 16 * 1024 * 1024
const val TRANSFER_BOOTSTRAP_LIMIT = 64 * 1024

enum class TransferRecordType(val code: Int) {
    MANIFEST(1),
    APPROVAL(2),
    PROGRESS(3),
    SENDER_FINISHED(4),
    CANCEL(5),
    TERMINAL_RESULT(6),
    CHUNK(7),
    SESSION_HELLO(240),
    SESSION_DECISION(241),
    ;

    companion object {
        fun fromCode(code: Int): TransferRecordType = entries.firstOrNull { it.code == code }
            ?: throw TransferProtocolException("unknown_record_type")
    }
}

class TransferProtocolException(
    val protocolCode: String,
) : IllegalArgumentException(protocolCode)

data class TransferRecordHeader(
    val type: TransferRecordType,
    val payloadLength: Int,
    val sessionId: UUID,
    val protocolMajor: Int = 1,
    val protocolMinor: Int = 0,
) {
    fun encode(): ByteArray {
        validatePayloadLength(type, payloadLength)
        require(protocolMajor in 0..0xffff && protocolMinor in 0..0xffff)
        if (type.isBootstrap) require(protocolMajor == 0 && protocolMinor == 0)
        return ByteBuffer.allocate(TRANSFER_HEADER_SIZE).order(ByteOrder.BIG_ENDIAN).apply {
            put(MAGIC)
            put(1.toByte())
            put(type.code.toByte())
            putShort(0.toShort())
            putShort(protocolMajor.toShort())
            putShort(protocolMinor.toShort())
            putInt(payloadLength)
            putLong(sessionId.mostSignificantBits)
            putLong(sessionId.leastSignificantBits)
        }.array()
    }

    companion object {
        private val MAGIC = byteArrayOf(0x53, 0x53, 0x48, 0x52)

        fun decode(bytes: ByteArray): TransferRecordHeader {
            if (bytes.size != TRANSFER_HEADER_SIZE) throw TransferProtocolException("truncated_header")
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
            val magic = ByteArray(4).also(buffer::get)
            if (!magic.contentEquals(MAGIC)) throw TransferProtocolException("invalid_magic")
            if (buffer.get().toInt() and 0xff != 1) throw TransferProtocolException("unsupported_header_version")
            val type = TransferRecordType.fromCode(buffer.get().toInt() and 0xff)
            if (buffer.short.toInt() and 0xffff != 0) throw TransferProtocolException("reserved_flags")
            val major = buffer.short.toInt() and 0xffff
            val minor = buffer.short.toInt() and 0xffff
            if (type.isBootstrap) {
                if (major != 0 || minor != 0) throw TransferProtocolException("invalid_bootstrap_version")
            } else if (major != 1 || minor != 0) {
                throw TransferProtocolException("incompatible_version")
            }
            val unsignedLength = buffer.int.toLong() and 0xffff_ffffL
            if (unsignedLength > Int.MAX_VALUE) throw TransferProtocolException("oversized_record")
            val length = unsignedLength.toInt()
            validatePayloadLength(type, length)
            return TransferRecordHeader(
                type = type,
                payloadLength = length,
                sessionId = UUID(buffer.long, buffer.long),
                protocolMajor = major,
                protocolMinor = minor,
            )
        }

        private fun validatePayloadLength(type: TransferRecordType, length: Int) {
            if (length < 0 || length > TRANSFER_RECORD_LIMIT) {
                throw TransferProtocolException("oversized_record")
            }
            val typeLimit = when {
                type.isBootstrap -> TRANSFER_BOOTSTRAP_LIMIT
                type == TransferRecordType.CHUNK -> TRANSFER_CHUNK_TARGET + TRANSFER_CHUNK_PRELUDE_SIZE
                else -> TRANSFER_CONTROL_LIMIT
            }
            if (length > typeLimit) throw TransferProtocolException("oversized_${type.name.lowercase()}")
            if (type == TransferRecordType.CHUNK && length < TRANSFER_CHUNK_PRELUDE_SIZE) {
                throw TransferProtocolException("truncated_chunk")
            }
        }
    }
}

val TransferRecordType.isBootstrap: Boolean
    get() = this == TransferRecordType.SESSION_HELLO || this == TransferRecordType.SESSION_DECISION

class TransferChunkPrelude(
    val itemId: UUID,
    val chunkIndex: Long,
    val offset: Long,
    val dataLength: Int,
    val sha256: ByteArray,
) {
    init {
        require(chunkIndex in 0..0xffff_ffffL)
        require(offset >= 0)
        require(dataLength in 0..TRANSFER_CHUNK_TARGET)
        require(sha256.size == 32)
    }

    fun encode(): ByteArray = ByteBuffer.allocate(TRANSFER_CHUNK_PRELUDE_SIZE).order(ByteOrder.BIG_ENDIAN).apply {
        putLong(itemId.mostSignificantBits)
        putLong(itemId.leastSignificantBits)
        putInt(chunkIndex.toInt())
        putLong(offset)
        putInt(dataLength)
        put(sha256)
    }.array()

    companion object {
        fun decode(bytes: ByteArray): TransferChunkPrelude {
            if (bytes.size != TRANSFER_CHUNK_PRELUDE_SIZE) {
                throw TransferProtocolException("invalid_chunk_prelude")
            }
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
            val itemId = UUID(buffer.long, buffer.long)
            val index = buffer.int.toLong() and 0xffff_ffffL
            val offset = buffer.long
            val length = buffer.int
            val digest = ByteArray(32).also(buffer::get)
            if (offset < 0 || length !in 0..TRANSFER_CHUNK_TARGET) {
                throw TransferProtocolException("invalid_chunk_bounds")
            }
            return TransferChunkPrelude(itemId, index, offset, length, digest)
        }
    }
}

data class TransferWireRecord(
    val header: TransferRecordHeader,
    val payload: ByteArray,
)

class IncrementalTransferRecordParser {
    private var pending = ByteArray(0)

    fun feed(fragment: ByteArray): List<TransferWireRecord> {
        if (fragment.isEmpty()) return emptyList()
        pending += fragment
        val records = mutableListOf<TransferWireRecord>()
        var consumed = 0
        while (pending.size - consumed >= TRANSFER_HEADER_SIZE) {
            val header = TransferRecordHeader.decode(
                pending.copyOfRange(consumed, consumed + TRANSFER_HEADER_SIZE),
            )
            val recordLength = TRANSFER_HEADER_SIZE + header.payloadLength
            if (pending.size - consumed < recordLength) break
            val payloadStart = consumed + TRANSFER_HEADER_SIZE
            records += TransferWireRecord(
                header,
                pending.copyOfRange(payloadStart, payloadStart + header.payloadLength),
            )
            consumed += recordLength
        }
        if (consumed > 0) pending = pending.copyOfRange(consumed, pending.size)
        if (pending.size > TRANSFER_HEADER_SIZE + TRANSFER_RECORD_LIMIT) {
            throw TransferProtocolException("oversized_buffer")
        }
        return records
    }

    fun finish() {
        if (pending.isNotEmpty()) throw TransferProtocolException("truncated_record")
    }
}

data class TransferFileEntry(
    val itemId: UUID,
    val displayName: String,
    val byteCount: Long,
    val sha256: ByteArray,
    val mediaType: String,
    val chunkSize: Int,
)

sealed interface TransferControlMessage
data class TransferManifest(val entries: List<TransferFileEntry>) : TransferControlMessage
data class TransferApproval(val accepted: Boolean, val reasonCode: String = "") : TransferControlMessage
enum class TransferProgressPhase(val code: Int) { TRANSFERRING(1), VERIFYING(2), COMMITTING(3) }
data class TransferProgress(
    val itemId: UUID,
    val verifiedBytes: Long,
    val verifiedChunks: Long,
    val phase: TransferProgressPhase,
) : TransferControlMessage
data class TransferSenderFinished(
    val itemId: UUID,
    val byteCount: Long,
    val sha256: ByteArray,
) : TransferControlMessage
data class TransferCancel(val reasonCode: String) : TransferControlMessage
enum class WireTerminalStatus(val code: Int) { COMPLETED(1), REJECTED(2), CANCELLED(3), FAILED(4) }
enum class TransferErrorCategory(val code: Int) {
    DISCOVERY(1), AUTHENTICATION(2), APPROVAL(3), PERMISSION(4), NETWORK(5), PROTOCOL(6),
    SOURCE(7), STORAGE(8), INTEGRITY(9), BUSY(10), INCOMPATIBLE_VERSION(11), CANCELLED(12),
}
data class TransferWireTerminalResult(
    val status: WireTerminalStatus,
    val errorCategory: TransferErrorCategory? = null,
    val errorCode: String = "",
    val committedArtifactId: String = "",
) : TransferControlMessage

object TransferControlCodec {
    fun encode(message: TransferControlMessage): ByteArray = when (message) {
        is TransferManifest -> ProtoWriter().apply {
            message.entries.forEach { bytes(1, encodeEntry(it)) }
        }.toByteArray()
        is TransferApproval -> ProtoWriter().apply {
            if (message.accepted) variable(1, 1)
            if (message.reasonCode.isNotEmpty()) string(2, message.reasonCode)
        }.toByteArray()
        is TransferProgress -> ProtoWriter().apply {
            bytes(1, message.itemId.toBytes())
            variable(2, message.verifiedBytes)
            variable(3, message.verifiedChunks)
            variable(4, message.phase.code.toLong())
        }.toByteArray()
        is TransferSenderFinished -> ProtoWriter().apply {
            bytes(1, message.itemId.toBytes())
            variable(2, message.byteCount)
            bytes(3, message.sha256)
        }.toByteArray()
        is TransferCancel -> ProtoWriter().apply { string(1, message.reasonCode) }.toByteArray()
        is TransferWireTerminalResult -> ProtoWriter().apply {
            variable(1, message.status.code.toLong())
            message.errorCategory?.let { variable(2, it.code.toLong()) }
            if (message.errorCode.isNotEmpty()) string(3, message.errorCode)
            if (message.committedArtifactId.isNotEmpty()) string(4, message.committedArtifactId)
        }.toByteArray()
    }

    fun decode(type: TransferRecordType, bytes: ByteArray): TransferControlMessage {
        if (type == TransferRecordType.CHUNK) throw TransferProtocolException("chunk_is_not_control")
        if (bytes.size > TRANSFER_CONTROL_LIMIT) throw TransferProtocolException("oversized_control")
        val reader = ProtoReader(bytes)
        return when (type) {
            TransferRecordType.MANIFEST -> decodeManifest(reader)
            TransferRecordType.APPROVAL -> decodeApproval(reader)
            TransferRecordType.PROGRESS -> decodeProgress(reader)
            TransferRecordType.SENDER_FINISHED -> decodeFinished(reader)
            TransferRecordType.CANCEL -> decodeCancel(reader)
            TransferRecordType.TERMINAL_RESULT -> decodeTerminal(reader)
            TransferRecordType.CHUNK -> error("unreachable")
            TransferRecordType.SESSION_HELLO,
            TransferRecordType.SESSION_DECISION,
            -> throw TransferProtocolException("bootstrap_is_not_v1_control")
        }
    }

    private fun encodeEntry(entry: TransferFileEntry): ByteArray = ProtoWriter().apply {
        bytes(1, entry.itemId.toBytes())
        string(2, entry.displayName)
        variable(3, entry.byteCount)
        bytes(4, entry.sha256)
        if (entry.mediaType.isNotEmpty()) string(5, entry.mediaType)
        variable(6, entry.chunkSize.toLong())
    }.toByteArray()

    private fun decodeManifest(reader: ProtoReader): TransferManifest {
        val entries = mutableListOf<TransferFileEntry>()
        reader.fields { field, wire ->
            if (field == 1 && wire == 2) entries += decodeEntry(ProtoReader(reader.bytes())) else reader.skip(wire)
        }
        if (entries.size != 1) throw TransferProtocolException("manifest_requires_one_file")
        return TransferManifest(entries)
    }

    private fun decodeEntry(reader: ProtoReader): TransferFileEntry {
        var id: UUID? = null
        var name = ""
        var count = 0L
        var digest = ByteArray(0)
        var media = ""
        var chunkSize = 0
        reader.fields { field, wire ->
            when (field) {
                1 -> id = reader.bytes().toUuid()
                2 -> name = reader.string()
                3 -> count = reader.variable()
                4 -> digest = reader.bytes()
                5 -> media = reader.string()
                6 -> chunkSize = reader.variable().toInt()
                else -> reader.skip(wire)
            }
        }
        if (id == null || name.isBlank() || count < 0 || digest.size != 32 || chunkSize !in 1..TRANSFER_CHUNK_TARGET) {
            throw TransferProtocolException("invalid_manifest_entry")
        }
        return TransferFileEntry(requireNotNull(id), name, count, digest, media, chunkSize)
    }

    private fun decodeApproval(reader: ProtoReader): TransferApproval {
        var accepted = false
        var reason = ""
        reader.fields { field, wire -> when (field) {
            1 -> accepted = reader.variable() != 0L
            2 -> reason = reader.string()
            else -> reader.skip(wire)
        } }
        return TransferApproval(accepted, reason)
    }

    private fun decodeProgress(reader: ProtoReader): TransferProgress {
        var id: UUID? = null
        var bytes = 0L
        var chunks = 0L
        var phase: TransferProgressPhase? = null
        reader.fields { field, wire -> when (field) {
            1 -> id = reader.bytes().toUuid()
            2 -> bytes = reader.variable()
            3 -> chunks = reader.variable()
            4 -> {
                val code = reader.variable().toInt()
                phase = TransferProgressPhase.entries.firstOrNull { it.code == code }
            }
            else -> reader.skip(wire)
        } }
        return TransferProgress(
            requireNotNull(id) { "progress item id" }, bytes, chunks,
            phase ?: throw TransferProtocolException("invalid_progress_phase"),
        )
    }

    private fun decodeFinished(reader: ProtoReader): TransferSenderFinished {
        var id: UUID? = null
        var count = 0L
        var digest = ByteArray(0)
        reader.fields { field, wire -> when (field) {
            1 -> id = reader.bytes().toUuid()
            2 -> count = reader.variable()
            3 -> digest = reader.bytes()
            else -> reader.skip(wire)
        } }
        if (digest.size != 32) throw TransferProtocolException("invalid_file_digest")
        return TransferSenderFinished(requireNotNull(id), count, digest)
    }

    private fun decodeCancel(reader: ProtoReader): TransferCancel {
        var reason = ""
        reader.fields { field, wire -> if (field == 1) reason = reader.string() else reader.skip(wire) }
        return TransferCancel(reason)
    }

    private fun decodeTerminal(reader: ProtoReader): TransferWireTerminalResult {
        var status: WireTerminalStatus? = null
        var category: TransferErrorCategory? = null
        var code = ""
        var artifact = ""
        reader.fields { field, wire -> when (field) {
            1 -> {
                val value = reader.variable().toInt()
                status = WireTerminalStatus.entries.firstOrNull { it.code == value }
            }
            2 -> {
                val value = reader.variable().toInt()
                category = TransferErrorCategory.entries.firstOrNull { it.code == value }
            }
            3 -> code = reader.string()
            4 -> artifact = reader.string()
            else -> reader.skip(wire)
        } }
        return TransferWireTerminalResult(
            status ?: throw TransferProtocolException("invalid_terminal_status"), category, code, artifact,
        )
    }
}

private class ProtoWriter {
    private val output = ByteArrayOutputStream()

    fun variable(field: Int, value: Long) {
        if (value < 0) throw TransferProtocolException("negative_varint")
        varint((field shl 3).toLong())
        varint(value)
    }

    fun bytes(field: Int, value: ByteArray) {
        varint(((field shl 3) or 2).toLong())
        varint(value.size.toLong())
        output.write(value)
    }

    fun string(field: Int, value: String) = bytes(field, value.toByteArray(Charsets.UTF_8))
    fun toByteArray(): ByteArray = output.toByteArray()

    private fun varint(input: Long) {
        var value = input
        do {
            var byte = (value and 0x7f).toInt()
            value = value ushr 7
            if (value != 0L) byte = byte or 0x80
            output.write(byte)
        } while (value != 0L)
    }
}

private class ProtoReader(private val input: ByteArray) {
    private var position = 0

    fun fields(block: (field: Int, wire: Int) -> Unit) {
        while (position < input.size) {
            val tag = variable()
            val field = (tag ushr 3).toInt()
            val wire = (tag and 7).toInt()
            if (field == 0) throw TransferProtocolException("invalid_protobuf_tag")
            block(field, wire)
        }
    }

    fun variable(): Long {
        var result = 0L
        var shift = 0
        while (shift < 64) {
            if (position >= input.size) throw TransferProtocolException("truncated_protobuf")
            val byte = input[position++].toInt() and 0xff
            result = result or ((byte and 0x7f).toLong() shl shift)
            if (byte and 0x80 == 0) return result
            shift += 7
        }
        throw TransferProtocolException("invalid_varint")
    }

    fun bytes(): ByteArray {
        val length = variable()
        if (length > Int.MAX_VALUE || position + length.toInt() > input.size) {
            throw TransferProtocolException("truncated_protobuf")
        }
        return input.copyOfRange(position, position + length.toInt()).also { position += length.toInt() }
    }

    fun string(): String = bytes().toString(Charsets.UTF_8)

    fun skip(wire: Int) {
        when (wire) {
            0 -> variable()
            1 -> advance(8)
            2 -> advance(variable().toInt())
            5 -> advance(4)
            else -> throw TransferProtocolException("unsupported_protobuf_wire_type")
        }
    }

    private fun advance(count: Int) {
        if (count < 0 || position + count > input.size) throw TransferProtocolException("truncated_protobuf")
        position += count
    }
}

fun UUID.toBytes(): ByteArray = ByteBuffer.allocate(16).order(ByteOrder.BIG_ENDIAN).apply {
    putLong(mostSignificantBits)
    putLong(leastSignificantBits)
}.array()

fun ByteArray.toUuid(): UUID {
    if (size != 16) throw TransferProtocolException("invalid_uuid")
    val buffer = ByteBuffer.wrap(this).order(ByteOrder.BIG_ENDIAN)
    return UUID(buffer.long, buffer.long)
}

fun String.hexToByteArray(): ByteArray {
    if (length % 2 != 0) throw IllegalArgumentException("odd hex length")
    return ByteArray(length / 2) { index -> substring(index * 2, index * 2 + 2).toInt(16).toByte() }
}
