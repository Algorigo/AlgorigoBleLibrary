package com.algorigo.algorigoble2.impl

import android.bluetooth.BluetoothGattCharacteristic
import android.os.Build
import com.algorigo.algorigoble2.BleDevice
import com.algorigo.algorigoble2.BleManager
import com.algorigo.algorigoble2.testutil.RxTestSchedulerRule
import io.mockk.every
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.util.UUID

/**
 * CCCD descriptor 가 없는 characteristic 에 notification 을 설정하면 원인을 알기 어려운 NPE 가 나던 문제(P2) 테스트.
 */
class BleDeviceEngineImplMissingCccdTest {

    @get:Rule
    val rxRule = RxTestSchedulerRule()

    private fun connectedFixture(sdkInt: Int, properties: Int, withCccd: Boolean): Pair<GattTestFixture, BluetoothGattCharacteristic> {
        val fixture = GattTestFixture(sdkInt = sdkInt)
        val characteristic = fixture.addCharacteristic(UUID_INDICATE, properties)
        if (!withCccd) {
            every { characteristic.getDescriptor(GattTestFixture.CCCD_UUID) } returns null
        }
        fixture.connect()
        return fixture to characteristic
    }

    private fun assertFailsWithoutTouchingGatt(sdkInt: Int) {
        val (fixture, characteristic) = connectedFixture(sdkInt, BluetoothGattCharacteristic.PROPERTY_INDICATE, withCccd = false)

        fixture.bleDevice.setupNotification(BleDevice.NotificationType.INDICATION, UUID_INDICATE).test().assertError {
            it is BleManager.DescriptorNotFoundException &&
                    it.characteristicUuid == UUID_INDICATE && it.descriptorUuid == GattTestFixture.CCCD_UUID
        }
        verify(exactly = 0) { fixture.gatt.setCharacteristicNotification(characteristic, any()) }
        verify(exactly = 0) { fixture.gatt.writeDescriptor(any()) }
        verify(exactly = 0) { fixture.gatt.writeDescriptor(any(), any()) }
        assertEquals(listOf<Throwable>(), rxRule.undeliverableErrors)
    }

    @Test
    fun missingCccd_failsWithDescriptorNotFound_legacyApi() {
        assertFailsWithoutTouchingGatt(Build.VERSION_CODES.S_V2)
    }

    @Test
    fun missingCccd_failsWithDescriptorNotFound_api33() {
        assertFailsWithoutTouchingGatt(Build.VERSION_CODES.TIRAMISU)
    }

    @Test
    fun notNotifiable_failsWithoutEnablingLocalNotification() {
        val (fixture, characteristic) = connectedFixture(Build.VERSION_CODES.TIRAMISU, BluetoothGattCharacteristic.PROPERTY_READ, withCccd = true)

        fixture.bleDevice.setupNotification(BleDevice.NotificationType.NOTIFICATION, UUID_INDICATE).test()
            .assertError(BleDeviceEngineImpl.IllegalCharacteristicProperty::class.java)
        verify(exactly = 0) { fixture.gatt.setCharacteristicNotification(characteristic, any()) }
    }

    companion object {
        private val UUID_INDICATE = UUID.fromString("00002a05-0000-1000-8000-00805f9b34fb")
    }
}
