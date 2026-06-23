package dev.swiftshare.domain

import java.security.MessageDigest
import java.util.UUID
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TransferWireTest {
    private val fixture: JSONObject by lazy {
        val resource = requireNotNull(javaClass.classLoader?.getResource("transfer-v1.json"))
        JSONObject(resource.readText())
    }

    @Test
    fun `Kotlin matches the fixed record header and Chunk prelude vectors`() {
        val header = TransferRecordHeader(
            type = TransferRecordType.MANIFEST,
            payloadLength = fixture.getJSONObject("control_messages").getString("manifest_hex").length / 2,
            sessionId = UUID.fromString(fixture.getString("session_id")),
        )
        assertArrayEquals(fixture.getString("record_header_hex").hexToByteArray(), header.encode())
        assertEquals(header, TransferRecordHeader.decode(header.encode()))

        val prelude = TransferChunkPrelude(
            itemId = UUID.fromString(fixture.getString("item_id")),
            chunkIndex = 3,
            offset = 12L * 1024 * 1024,
            dataLength = 3,
            sha256 = fixture.getString("sha256_hex").hexToByteArray(),
        )
        assertArrayEquals(fixture.getString("chunk_prelude_hex").hexToByteArray(), prelude.encode())
        val decoded = TransferChunkPrelude.decode(prelude.encode())
        assertEquals(prelude.itemId, decoded.itemId)
        assertEquals(prelude.chunkIndex, decoded.chunkIndex)
        assertEquals(prelude.offset, decoded.offset)
        assertArrayEquals(prelude.sha256, decoded.sha256)
    }

    @Test
    fun `Kotlin decodes and re-encodes every Protobuf control vector semantically`() {
        val controls = fixture.getJSONObject("control_messages")
        val itemId = UUID.fromString(fixture.getString("item_id"))
        val digest = fixture.getString("sha256_hex").hexToByteArray()
        val cases = listOf(
            Triple(
                TransferRecordType.MANIFEST,
                "manifest_hex",
                TransferManifest(
                    listOf(TransferFileEntry(itemId, "report.txt", 3, digest, "text/plain", TRANSFER_CHUNK_TARGET)),
                ),
            ),
            Triple(TransferRecordType.APPROVAL, "approval_hex", TransferApproval(true)),
            Triple(
                TransferRecordType.PROGRESS,
                "progress_hex",
                TransferProgress(itemId, 3, 1, TransferProgressPhase.VERIFYING),
            ),
            Triple(
                TransferRecordType.SENDER_FINISHED,
                "sender_finished_hex",
                TransferSenderFinished(itemId, 3, digest),
            ),
            Triple(TransferRecordType.CANCEL, "cancel_hex", TransferCancel("user_cancelled")),
            Triple(
                TransferRecordType.TERMINAL_RESULT,
                "terminal_result_hex",
                TransferWireTerminalResult(WireTerminalStatus.COMPLETED, committedArtifactId = "content://media/42"),
            ),
        )

        cases.forEach { (type, fixtureKey, expected) ->
            val bytes = controls.getString(fixtureKey).hexToByteArray()
            val decoded = TransferControlCodec.decode(type, bytes)
            assertControlEquals(expected, decoded)
            assertControlEquals(expected, TransferControlCodec.decode(type, TransferControlCodec.encode(expected)))
        }
    }

    @Test
    fun `Kotlin matches the batch manifest and ItemCommitted golden vectors`() {
        val controls = fixture.getJSONObject("control_messages")
        val item1 = UUID.fromString(fixture.getString("item_id"))
        val item2 = UUID.fromString(fixture.getString("item_id_2"))
        val digest = fixture.getString("sha256_hex").hexToByteArray()

        val batch = TransferManifest(
            listOf(
                TransferFileEntry(item1, "report.txt", 3, digest, "text/plain", TRANSFER_CHUNK_TARGET),
                TransferFileEntry(item2, "second.txt", 5, digest, "text/plain", TRANSFER_CHUNK_TARGET),
            ),
        )
        val batchBytes = controls.getString("manifest_batch_hex").hexToByteArray()
        assertArrayEquals(batchBytes, TransferControlCodec.encode(batch))
        val decodedBatch = TransferControlCodec.decode(TransferRecordType.MANIFEST, batchBytes) as TransferManifest
        assertEquals(2, decodedBatch.entries.size)
        assertEquals(item2, decodedBatch.entries[1].itemId)
        assertEquals("second.txt", decodedBatch.entries[1].displayName)

        val committed = TransferItemCommitted(item1, "content://media/42")
        val committedBytes = controls.getString("item_committed_hex").hexToByteArray()
        assertArrayEquals(committedBytes, TransferControlCodec.encode(committed))
        assertEquals(
            committed,
            TransferControlCodec.decode(TransferRecordType.ITEM_COMMITTED, committedBytes),
        )
    }

    @Test
    fun `incremental parser handles fragmented and coalesced records`() {
        val sessionId = UUID.fromString(fixture.getString("session_id"))
        val firstPayload = fixture.getJSONObject("control_messages").getString("approval_hex").hexToByteArray()
        val secondPayload = fixture.getJSONObject("control_messages").getString("cancel_hex").hexToByteArray()
        val first = TransferRecordHeader(TransferRecordType.APPROVAL, firstPayload.size, sessionId).encode() + firstPayload
        val second = TransferRecordHeader(TransferRecordType.CANCEL, secondPayload.size, sessionId).encode() + secondPayload
        val parser = IncrementalTransferRecordParser()

        assertTrue(parser.feed((first + second).copyOfRange(0, 7)).isEmpty())
        assertTrue(parser.feed((first + second).copyOfRange(7, first.size - 1)).isEmpty())
        val parsed = parser.feed((first + second).copyOfRange(first.size - 1, first.size + second.size))

        assertEquals(listOf(TransferRecordType.APPROVAL, TransferRecordType.CANCEL), parsed.map { it.header.type })
        assertArrayEquals(firstPayload, parsed[0].payload)
        assertArrayEquals(secondPayload, parsed[1].payload)
        parser.finish()
    }

    @Test
    fun `parser rejects malformed headers lengths and truncation before allocation`() {
        val header = fixture.getString("record_header_hex").hexToByteArray()
        val badMagic = header.copyOf().also { it[0] = 0 }
        assertProtocolCode("invalid_magic") { TransferRecordHeader.decode(badMagic) }

        val reservedFlags = header.copyOf().also { it[7] = 1 }
        assertProtocolCode("reserved_flags") { TransferRecordHeader.decode(reservedFlags) }

        val unknownType = header.copyOf().also { it[5] = 99 }
        assertProtocolCode("unknown_record_type") { TransferRecordHeader.decode(unknownType) }

        val oversized = header.copyOf().also {
            val length = TRANSFER_CONTROL_LIMIT + 1
            it[12] = (length ushr 24).toByte()
            it[13] = (length ushr 16).toByte()
            it[14] = (length ushr 8).toByte()
            it[15] = length.toByte()
        }
        assertProtocolCode("oversized_manifest") { TransferRecordHeader.decode(oversized) }

        val parser = IncrementalTransferRecordParser()
        parser.feed(header.copyOfRange(0, 12))
        assertProtocolCode("truncated_record") { parser.finish() }
    }

    @Test
    fun `malformed Protobuf and invalid identifiers fail closed`() {
        assertProtocolCode("truncated_protobuf") {
            TransferControlCodec.decode(TransferRecordType.APPROVAL, byteArrayOf(0x12, 0x05, 0x41))
        }
        val invalidProgress = byteArrayOf(0x0a, 0x01, 0x00, 0x20, 0x01)
        assertThrows(IllegalArgumentException::class.java) {
            TransferControlCodec.decode(TransferRecordType.PROGRESS, invalidProgress)
        }
        assertProtocolCode("chunk_is_not_control") {
            TransferControlCodec.decode(TransferRecordType.CHUNK, ByteArray(0))
        }
    }

    @Test
    fun `Chunk hashes are SHA-256 and payload length is exact`() {
        val data = "abc".toByteArray()
        val digest = MessageDigest.getInstance("SHA-256").digest(data)
        assertArrayEquals(fixture.getString("sha256_hex").hexToByteArray(), digest)
        val prelude = TransferChunkPrelude(UUID.fromString(fixture.getString("item_id")), 0, 0, data.size, digest)
        val payload = prelude.encode() + data
        val decoded = TransferChunkPrelude.decode(payload.copyOfRange(0, TRANSFER_CHUNK_PRELUDE_SIZE))
        assertEquals(payload.size - TRANSFER_CHUNK_PRELUDE_SIZE, decoded.dataLength)
        assertFalse(decoded.sha256.contentEquals(ByteArray(32)))
    }

    private fun assertProtocolCode(code: String, block: () -> Unit) {
        val error = assertThrows(TransferProtocolException::class.java, block)
        assertEquals(code, error.protocolCode)
    }

    private fun assertControlEquals(expected: TransferControlMessage, actual: TransferControlMessage) {
        when {
            expected is TransferManifest && actual is TransferManifest -> {
                assertEquals(expected.entries.size, actual.entries.size)
                val left = expected.entries.single()
                val right = actual.entries.single()
                assertEquals(left.itemId, right.itemId)
                assertEquals(left.displayName, right.displayName)
                assertEquals(left.byteCount, right.byteCount)
                assertArrayEquals(left.sha256, right.sha256)
                assertEquals(left.mediaType, right.mediaType)
                assertEquals(left.chunkSize, right.chunkSize)
            }
            expected is TransferSenderFinished && actual is TransferSenderFinished -> {
                assertEquals(expected.itemId, actual.itemId)
                assertEquals(expected.byteCount, actual.byteCount)
                assertArrayEquals(expected.sha256, actual.sha256)
            }
            else -> assertEquals(expected, actual)
        }
    }
}
