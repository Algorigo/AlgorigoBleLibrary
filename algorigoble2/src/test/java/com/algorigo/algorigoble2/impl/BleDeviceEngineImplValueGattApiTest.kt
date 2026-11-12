package com.algorigo.algorigoble2.impl

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothStatusCodes
import android.os.Build
import com.algorigo.algorigoble2.BleDevice
import com.algorigo.algorigoble2.testutil.RxTestSchedulerRule
import io.mockk.every
import io.mockk.verify
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * API 33 에서 deprecated 된 GATT API(공유된 characteristic.value / descriptor.value)를 쓰던 문제(P2) 테스트.
 *
 * 콜백 기본 구현의 동작(값을 받는 새 콜백 → deprecated 콜백 호출)은 android.jar stub 에 없으므로,
 * 프레임워크처럼 두 콜백을 차례로 호출해서 확인한다.
 */
class BleDeviceEngineImplValueGattApiTest {

    @get:Rule
    val rxRule = RxTestSchedulerRule()

    private fun connectedFixture(sdkInt: Int) = GattTestFixture(sdkInt = sdkInt).also { fixture ->
        fixture.addCharacteristic(
            UUID_RW, BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_WRITE or
                    BluetoothGattCharacteristic.PROPERTY_INDICATE
        )
        fixture.connect()
    }

    private fun GattTestFixture.characteristic() = gatt.services.single().getCharacteristic(UUID_RW)

    // 수정 전 동작 재현: 쓰기 응답을 기다리는 동안 같은 characteristic 으로 notification 이 오면 공유 value 가 바뀐다
    @Test
    fun write_returnsRequestedValue_evenIfNotificationChangedSharedValue_legacyApi() {
        val fixture = connectedFixture(Build.VERSION_CODES.S_V2)
        val characteristic = fixture.characteristic()
        val write = fixture.bleDevice.writeCharacteristicSingle(UUID_RW, REQUESTED).test()

        every { characteristic.value } returns NOTIFIED
        fixture.callback.onCharacteristicChanged(fixture.gatt, characteristic)
        fixture.callback.onCharacteristicWrite(fixture.gatt, characteristic, BluetoothGatt.GATT_SUCCESS)

        write.assertValue { it.contentEquals(REQUESTED) }
    }

    @Test
    fun write_passesValueAndWriteTypeAsArguments_withoutTouchingSharedValue_api33() {
        val fixture = connectedFixture(Build.VERSION_CODES.TIRAMISU)
        val characteristic = fixture.characteristic()
        val write = fixture.bleDevice.writeCharacteristicSingle(UUID_RW, REQUESTED).test()

        every { characteristic.value } returns NOTIFIED
        fixture.callback.onCharacteristicChanged(fixture.gatt, characteristic, NOTIFIED)
        fixture.callback.onCharacteristicWrite(fixture.gatt, characteristic, BluetoothGatt.GATT_SUCCESS)

        write.assertValue { it.contentEquals(REQUESTED) }
        verify(exactly = 1) {
            fixture.gatt.writeCharacteristic(characteristic, REQUESTED, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
        }
        verify(exactly = 0) { characteristic.value = any() }
        verify(exactly = 0) { characteristic.writeType = any() }
        verify(exactly = 0) { fixture.gatt.writeCharacteristic(any()) }
    }

    @Test
    fun write_retries_whenApi33WriteIsRejectedAsBusy() {
        val fixture = connectedFixture(Build.VERSION_CODES.TIRAMISU)
        val characteristic = fixture.characteristic()
        every { fixture.gatt.writeCharacteristic(any(), any(), any()) } returnsMany
                listOf(BluetoothStatusCodes.ERROR_GATT_WRITE_REQUEST_BUSY, BluetoothStatusCodes.SUCCESS)

        val write = fixture.bleDevice.writeCharacteristicSingle(UUID_RW, REQUESTED).test()
        rxRule.testScheduler.advanceTimeBy(500, TimeUnit.MILLISECONDS)
        fixture.callback.onCharacteristicWrite(fixture.gatt, characteristic, BluetoothGatt.GATT_SUCCESS)

        verify(exactly = 2) { fixture.gatt.writeCharacteristic(characteristic, REQUESTED, any()) }
        write.assertValue { it.contentEquals(REQUESTED) }
    }

    @Test
    fun notification_isDeliveredOnceWithArgumentValue_api33() {
        val fixture = connectedFixture(Build.VERSION_CODES.TIRAMISU)
        val characteristic = fixture.characteristic()
        val descriptor = characteristic.getDescriptor(GattTestFixture.CCCD_UUID)
        val notifications = fixture.bleDevice.setupNotification(BleDevice.NotificationType.INDICATION, UUID_RW).flatMap { it }.test()
        fixture.callback.onDescriptorWrite(fixture.gatt, descriptor, BluetoothGatt.GATT_SUCCESS)

        // 프레임워크는 value 를 공유 필드에도 넣고 새 콜백을 호출하며, 새 콜백의 기본 구현은 deprecated 콜백을 호출한다
        every { characteristic.value } returns STALE
        fixture.callback.onCharacteristicChanged(fixture.gatt, characteristic, NOTIFIED)
        fixture.callback.onCharacteristicChanged(fixture.gatt, characteristic)

        notifications.assertValueCount(1)
        assertArrayEquals(NOTIFIED, notifications.values().single())
    }

    @Test
    fun read_usesArgumentValue_api33() {
        val fixture = connectedFixture(Build.VERSION_CODES.TIRAMISU)
        val characteristic = fixture.characteristic()
        val read = fixture.bleDevice.readCharacteristicSingle(UUID_RW).test()

        every { characteristic.value } returns STALE
        fixture.callback.onCharacteristicRead(fixture.gatt, characteristic, REQUESTED, BluetoothGatt.GATT_SUCCESS)
        fixture.callback.onCharacteristicRead(fixture.gatt, characteristic, BluetoothGatt.GATT_SUCCESS)

        read.assertValue { it.contentEquals(REQUESTED) }
    }

    @Test
    fun cccdWrite_passesValueAsArgument_api33() {
        val fixture = connectedFixture(Build.VERSION_CODES.TIRAMISU)
        val characteristic = fixture.characteristic()
        val descriptor = characteristic.getDescriptor(GattTestFixture.CCCD_UUID)

        val setup = fixture.bleDevice.setupNotification(BleDevice.NotificationType.INDICATION, UUID_RW).test()
        fixture.callback.onDescriptorWrite(fixture.gatt, descriptor, BluetoothGatt.GATT_SUCCESS)

        setup.assertValueCount(1)
        verify(exactly = 1) { fixture.gatt.writeDescriptor(descriptor, BleDevice.NotificationType.INDICATION.byteArray) }
        verify(exactly = 0) { descriptor.value = any() }
        verify(exactly = 0) { fixture.gatt.writeDescriptor(any()) }
    }

    @Test
    fun legacyApi_isUsedBelowApi33() {
        val fixture = connectedFixture(Build.VERSION_CODES.S_V2)
        val characteristic = fixture.characteristic()

        val write = fixture.bleDevice.writeCharacteristicSingle(UUID_RW, REQUESTED).test()
        fixture.callback.onCharacteristicWrite(fixture.gatt, characteristic, BluetoothGatt.GATT_SUCCESS)

        write.assertComplete()
        verify(exactly = 1) { characteristic.value = REQUESTED }
        verify(exactly = 1) { fixture.gatt.writeCharacteristic(characteristic) }
        verify(exactly = 0) { fixture.gatt.writeCharacteristic(any(), any(), any()) }
        assertEquals(0, rxRule.undeliverableErrors.size)
    }

    companion object {
        private val UUID_RW = UUID.fromString("0000aaa2-0000-1000-8000-aabbccddeeff")
        private val REQUESTED = byteArrayOf(0xA5.toByte(), 0x01)
        private val NOTIFIED = byteArrayOf(0x0E, 0x0E)
        private val STALE = byteArrayOf(0x00)
    }
}
