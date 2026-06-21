package dev.swiftshare.android

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import dev.swiftshare.domain.PairingDeviceDescriptor
import dev.swiftshare.domain.PeerApprovalPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ReceiveAvailabilityTest {
    @Test
    fun availabilityPolicyPersistsAndRemainsReversible() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.getSharedPreferences("unused", Context.MODE_PRIVATE).edit().clear().commit()
        val file = context.filesDir.resolve("receive-availability-v1.json")
        file.delete()
        file.resolveSibling("receive-availability-v1.json.bak").delete()
        val repository = AvailabilityPolicyRepository(context)

        assertEquals(AndroidAvailabilityPolicy.AVAILABLE_WHILE_OPEN, repository.read())
        repository.write(AndroidAvailabilityPolicy.RECEIVE_MODE)
        assertEquals(AndroidAvailabilityPolicy.RECEIVE_MODE, AvailabilityPolicyRepository(context).read())
        repository.write(AndroidAvailabilityPolicy.AVAILABLE_WHILE_OPEN)
        assertEquals(AndroidAvailabilityPolicy.AVAILABLE_WHILE_OPEN, AvailabilityPolicyRepository(context).read())
    }

    @Test
    fun approvalPolicyDoesNotInvalidateTrustButUnpairDoes() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        listOf("paired-peers-v1.json", "paired-peers-v2.json").forEach { name ->
            context.filesDir.resolve(name).delete()
            context.filesDir.resolve("$name.bak").delete()
        }
        val repository = AndroidTrustRepository(context)
        val descriptor = PairingDeviceDescriptor(ByteArray(65) { (it + 1).toByte() }, "Test Mac", "macOS")
        val peer = repository.upsert(descriptor)
        val token = requireNotNull(repository.authenticationToken(descriptor.canonicalSpki))

        repository.setApprovalPolicy(peer.keyIdHex, PeerApprovalPolicy.AUTO_ACCEPT)
        assertNotNull(repository.validate(token))

        repository.remove(peer.keyIdHex)
        assertNull(repository.validate(token))
    }
}
