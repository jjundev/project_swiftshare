package dev.swiftshare.android

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MediaStoreDestinationTest {
    @Test
    fun pendingRowsStayPendingUntilVerifiedAndReceiveDeterministicSuffixes() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val destination = MediaStoreDestination(context.contentResolver)
        val name = "swiftshare-${UUID.randomUUID()}.txt"
        val first = destination.reserve(name, "text/plain")
        val second = destination.reserve(name, "text/plain")
        try {
            assertEquals(name, first.displayName)
            assertEquals(name.removeSuffix(".txt") + " (1).txt", second.displayName)
            assertFalse(destination.isPublished(first))
            val bytes = "verified".toByteArray()
            destination.writeVerified(
                first,
                ByteArrayInputStream(bytes),
                bytes.size.toLong(),
                MessageDigest.getInstance("SHA-256").digest(bytes),
            )
            assertTrue(destination.isPublished(first))
        } finally {
            destination.abort(first)
            destination.abort(second)
        }
    }
}
