package dev.swiftshare.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class DestinationNameAllocatorTest {
    @Test
    fun `numeric suffix is inserted before the extension`() {
        assertEquals("report.txt", DestinationNameAllocator.candidate("report.txt", 0))
        assertEquals("report (1).txt", DestinationNameAllocator.candidate("report.txt", 1))
        assertEquals("archive.tar (2).gz", DestinationNameAllocator.candidate("archive.tar.gz", 2))
        assertEquals("README (3)", DestinationNameAllocator.candidate("README", 3))
        assertEquals(".env (4)", DestinationNameAllocator.candidate(".env", 4))
    }

    @Test
    fun `unsafe destination names are rejected`() {
        listOf("", ".", "..", "folder/file", "folder\\file", "bad\u0000name").forEach { name ->
            assertThrows(IllegalArgumentException::class.java) { DestinationNameAllocator.validate(name) }
        }
    }
}
