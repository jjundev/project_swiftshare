package dev.swiftshare.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.security.KeyStore
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DevelopmentIdentityTest {
    @Test
    fun privateKeyRemainsNonExportableAndIdentityIsStable() {
        val alias = "dev.swiftshare.test.${System.nanoTime()}"
        val store = AndroidDevelopmentIdentityStore(alias)
        try {
            val first = store.loadOrCreate()
            val second = store.loadOrCreate()
            val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

            assertEquals(first.certificate, second.certificate)
            assertArrayEquals(first.spkiSha256, second.spkiSha256)
            assertNull(keyStore.getKey(alias, null).encoded)
        } finally {
            store.delete()
        }
    }
}
