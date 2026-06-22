package dev.swiftshare.android

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReceiveAvailabilityModelTest {
    @Test
    fun `while-open policy follows only app visibility facts`() {
        val model = model(AndroidAvailabilityPolicy.AVAILABLE_WHILE_OPEN)

        assertFalse(model.accept(ReceiveAvailabilityInput.ReceiveModeLifecycle(active = true)).shouldListen)
        assertTrue(model.accept(ReceiveAvailabilityInput.AppVisibility(foreground = true)).shouldListen)
        assertFalse(model.accept(ReceiveAvailabilityInput.AppVisibility(foreground = false)).shouldListen)
    }

    @Test
    fun `receive mode follows only foreground-service lifecycle facts`() {
        val model = model(AndroidAvailabilityPolicy.RECEIVE_MODE)

        assertFalse(model.accept(ReceiveAvailabilityInput.AppVisibility(foreground = true)).shouldListen)
        assertTrue(model.accept(ReceiveAvailabilityInput.ReceiveModeLifecycle(active = true)).shouldListen)
        assertFalse(model.accept(ReceiveAvailabilityInput.ReceiveModeLifecycle(active = false)).shouldListen)
    }

    @Test
    fun `stop intent suppresses availability until the next foreground transition`() {
        val model = model(AndroidAvailabilityPolicy.AVAILABLE_WHILE_OPEN)
        assertTrue(model.accept(ReceiveAvailabilityInput.AppVisibility(foreground = true)).shouldListen)

        val stopped = model.accept(ReceiveAvailabilityInput.StopRequested)
        val stillForeground = model.accept(ReceiveAvailabilityInput.AppVisibility(foreground = true))
        model.accept(ReceiveAvailabilityInput.AppVisibility(foreground = false))
        val nextForeground = model.accept(ReceiveAvailabilityInput.AppVisibility(foreground = true))

        assertFalse(stopped.shouldListen)
        assertFalse(stillForeground.shouldListen)
        assertTrue(nextForeground.shouldListen)
    }

    @Test
    fun `network change replaces the Advertising Epoch only while listening is desired`() {
        val model = model(AndroidAvailabilityPolicy.AVAILABLE_WHILE_OPEN)

        val inactiveChange = model.networkChanged("wifi-b")
        model.accept(ReceiveAvailabilityInput.AppVisibility(foreground = true))
        val activeChange = model.networkChanged("wifi-c")
        val duplicate = model.networkChanged("wifi-c")

        assertFalse(inactiveChange.restartAdvertisingEpoch)
        assertTrue(activeChange.shouldListen)
        assertTrue(activeChange.restartAdvertisingEpoch)
        assertFalse(duplicate.restartAdvertisingEpoch)
    }

    private fun model(policy: AndroidAvailabilityPolicy) = ReceiveAvailabilityModel(
        initialPolicy = policy,
        initialNetworkSignature = "wifi-a",
    )
}
