package dev.swiftshare.android

import android.content.ContentResolver
import android.content.ContentValues
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

data class MediaStoreReservation(
    val uri: Uri,
    val displayName: String,
)

class MediaStoreDestination(
    private val resolver: ContentResolver,
) {
    fun reserve(requestedName: String, mediaType: String): MediaStoreReservation = synchronized(reservationLock) {
        val safeName = DestinationNameAllocator.validate(requestedName)
        var suffix = 0
        while (suffix < MAX_SUFFIX_ATTEMPTS) {
            val candidate = DestinationNameAllocator.candidate(safeName, suffix)
            if (!nameExists(candidate)) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, candidate)
                    put(MediaStore.MediaColumns.MIME_TYPE, mediaType.ifBlank { "application/octet-stream" })
                    put(MediaStore.MediaColumns.RELATIVE_PATH, RELATIVE_PATH)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: throw DestinationStorageException("pending_insert_failed")
                return@synchronized MediaStoreReservation(uri, candidate)
            }
            suffix += 1
        }
        throw DestinationStorageException("name_exhausted")
    }

    fun writeVerified(
        reservation: MediaStoreReservation,
        source: InputStream,
        expectedByteCount: Long,
        expectedSha256: ByteArray,
        isCancelled: () -> Boolean = { false },
        onProgress: (Long) -> Unit = {},
    ): Long {
        require(expectedByteCount >= 0)
        require(expectedSha256.size == 32)
        val digest = MessageDigest.getInstance("SHA-256")
        var written = 0L
        try {
            resolver.openOutputStream(reservation.uri, "w")?.use { output ->
                val buffer = ByteArray(IO_BUFFER_SIZE)
                while (true) {
                    if (isCancelled()) throw DestinationCancelledException()
                    val count = source.read(buffer)
                    if (count == -1) break
                    if (count == 0) continue
                    written += count
                    if (written > expectedByteCount) throw DestinationIntegrityException("byte_count_exceeded")
                    digest.update(buffer, 0, count)
                    output.write(buffer, 0, count)
                    onProgress(written)
                }
                output.flush()
            } ?: throw DestinationStorageException("pending_open_failed")

            if (written != expectedByteCount) throw DestinationIntegrityException("byte_count_mismatch")
            if (!MessageDigest.isEqual(digest.digest(), expectedSha256)) {
                throw DestinationIntegrityException("file_sha256_mismatch")
            }
            publish(reservation)
            return written
        } catch (error: Exception) {
            abort(reservation)
            throw error
        }
    }

    fun openPendingOutput(reservation: MediaStoreReservation): OutputStream =
        resolver.openOutputStream(reservation.uri, "w")
            ?: throw DestinationStorageException("pending_open_failed")

    fun publish(reservation: MediaStoreReservation) {
        val values = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
        if (resolver.update(reservation.uri, values, null, null) != 1) {
            throw DestinationStorageException("pending_publish_failed")
        }
    }

    fun abort(reservation: MediaStoreReservation) {
        resolver.delete(reservation.uri, null, null)
    }

    fun isPublished(reservation: MediaStoreReservation): Boolean {
        resolver.query(
            reservation.uri,
            arrayOf(MediaStore.MediaColumns.IS_PENDING),
            null,
            null,
            null,
        )?.use { cursor ->
            return cursor.moveToFirst() && cursor.getInt(0) == 0
        }
        return false
    }

    private fun nameExists(displayName: String): Boolean {
        val projection = arrayOf(MediaStore.MediaColumns._ID)
        val selection = "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.DISPLAY_NAME}=?"
        val arguments = arrayOf(RELATIVE_PATH, displayName)
        resolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            arguments,
            null,
        )?.use { cursor -> return cursor.moveToFirst() }
        return false
    }

    companion object {
        val RELATIVE_PATH: String = Environment.DIRECTORY_DOWNLOADS + "/SwiftShare/"
        private const val IO_BUFFER_SIZE = 64 * 1024
        private const val MAX_SUFFIX_ATTEMPTS = 10_000
        private val reservationLock = Any()
    }
}

object DestinationNameAllocator {
    fun validate(requestedName: String): String {
        val name = requestedName.trim()
        require(name.isNotEmpty()) { "empty destination name" }
        require(name != "." && name != "..") { "unsafe destination name" }
        require('/' !in name && '\\' !in name && '\u0000' !in name) { "unsafe destination name" }
        require(name.toByteArray(Charsets.UTF_8).size <= 255) { "destination name too long" }
        return name
    }

    fun candidate(original: String, suffix: Int): String {
        require(suffix >= 0)
        if (suffix == 0) return original
        val dot = original.lastIndexOf('.')
        val hasExtension = dot > 0 && dot < original.lastIndex
        val stem = if (hasExtension) original.substring(0, dot) else original
        val extension = if (hasExtension) original.substring(dot) else ""
        return "$stem ($suffix)$extension"
    }
}

class DestinationStorageException(message: String) : Exception(message)
class DestinationIntegrityException(message: String) : Exception(message)
class DestinationCancelledException : Exception("cancelled")
