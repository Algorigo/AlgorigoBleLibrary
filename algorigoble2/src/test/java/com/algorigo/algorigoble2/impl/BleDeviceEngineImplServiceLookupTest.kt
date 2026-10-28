package com.algorigo.algorigoble2.impl

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import com.algorigo.algorigoble2.BleDevice
import com.algorigo.algorigoble2.BleManager
import com.algorigo.algorigoble2.testutil.RxTestSchedulerRule
import io.mockk.every
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.UUID

/**
 * characteristic 을 UUID 로만 찾아서 여러 서비스에 같은 UUID 가 있으면 첫 번째 것만 쓰이고,
 * 찾지 못하면 메시지 없는 IllegalStateException 이 나던 문제(P2) 테스트.
 */
class BleDeviceEngineImplServiceLookupTest {

    @get:Rule
    val rxRule = RxTestSchedulerRule()

    private val fixture = GattTestFixture()
    private val properties = BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_WRITE or
            BluetoothGattCharacteristic.PROPERTY_NOTIFY
    private val inFirst = fixture.addCharacteristic(SHARED_UUID, properties, serviceUuid = FIRST_SERVICE)
    private val inSecond = fixture.addCharacteristic(SHARED_UUID, properties, serviceUuid = SECOND_SERVICE)
    private val onlyInSecond = fixture.addCharacteristic(ONLY_SECOND_UUID, properties, serviceUuid = SECOND_SERVICE)

    @Before
    fun setUp() {
        fixture.connect()
    }

    @Test
    fun read_withoutService_usesFirstFound() {
        fixture.bleDevice.readCharacteristicSingle(SHARED_UUID).test()

        verify(exactly = 1) { fixture.gatt.readCharacteristic(inFirst) }
        verify(exactly = 0) { fixture.gatt.readCharacteristic(inSecond) }
    }

    @Test
    fun read_withService_usesCharacteristicOfThatService() {
        every { inSecond.value } returns byteArrayOf(2)
        val read = fixture.bleDevice.readCharacteristicSingle(SECOND_SERVICE, SHARED_UUID).test()
        fixture.callback.onCharacteristicRead(fixture.gatt, inSecond, BluetoothGatt.GATT_SUCCESS)

        read.assertValue { it.contentEquals(byteArrayOf(2)) }
        verify(exactly = 1) { fixture.gatt.readCharacteristic(inSecond) }
        verify(exactly = 0) { fixture.gatt.readCharacteristic(inFirst) }
    }

    @Test
    fun write_withService_usesCharacteristicOfThatService() {
        fixture.bleDevice.writeCharacteristicSingle(SECOND_SERVICE, SHARED_UUID, byteArrayOf(9)).test()

        verify(exactly = 1) { fixture.gatt.writeCharacteristic(inSecond) }
        verify(exactly = 0) { fixture.gatt.writeCharacteristic(inFirst) }
    }

    // 수정 전 코드에도 있는 API 로 확인한다: 메시지 없는 IllegalStateException() 이었다
    @Test
    fun read_notFound_failsWithMessageNamingCharacteristic() {
        fixture.bleDevice.readCharacteristicSingle(MISSING_UUID).test()
            .assertError { it is IllegalStateException && it.message?.contains(MISSING_UUID.toString()) == true }
    }

    @Test
    fun read_inServiceWithoutCharacteristic_failsWithCharacteristicNotFound() {
        fixture.bleDevice.readCharacteristicSingle(FIRST_SERVICE, ONLY_SECOND_UUID).test().assertError {
            it is BleManager.CharacteristicNotFoundException &&
                    it.serviceUuid == FIRST_SERVICE && it.characteristicUuid == ONLY_SECOND_UUID
        }
        verify(exactly = 0) { fixture.gatt.readCharacteristic(any()) }
    }

    @Test
    fun notification_withService_receivesOnlyThatServicesNotifications() {
        val notifications = fixture.bleDevice.setupNotification(BleDevice.NotificationType.NOTIFICATION, SECOND_SERVICE, SHARED_UUID)
            .flatMap { it }.test()
        fixture.callback.onDescriptorWrite(fixture.gatt, inSecond.getDescriptor(GattTestFixture.CCCD_UUID), BluetoothGatt.GATT_SUCCESS)

        every { inFirst.value } returns byteArrayOf(1)
        fixture.callback.onCharacteristicChanged(fixture.gatt, inFirst)
        every { inSecond.value } returns byteArrayOf(2)
        fixture.callback.onCharacteristicChanged(fixture.gatt, inSecond)

        verify(exactly = 1) { fixture.gatt.setCharacteristicNotification(inSecond, true) }
        verify(exactly = 0) { fixture.gatt.setCharacteristicNotification(inFirst, true) }
        assertEquals(listOf(listOf<Byte>(2)), notifications.values().map { it.toList() })
    }

    @Test
    fun notification_withAndWithoutService_forSameCharacteristic_shareSetup() {
        val withoutService = fixture.bleDevice.setupNotification(BleDevice.NotificationType.NOTIFICATION, SHARED_UUID).test()
        fixture.callback.onDescriptorWrite(fixture.gatt, inFirst.getDescriptor(GattTestFixture.CCCD_UUID), BluetoothGatt.GATT_SUCCESS)
        val withService = fixture.bleDevice.setupNotification(BleDevice.NotificationType.NOTIFICATION, FIRST_SERVICE, SHARED_UUID).test()

        withoutService.assertValueCount(1)
        withService.assertValueCount(1)
        verify(exactly = 1) { fixture.gatt.writeDescriptor(any()) }

        // 한쪽을 해제해도 CCCD 를 끄지 않는다
        withoutService.dispose()
        verify(exactly = 1) { fixture.gatt.writeDescriptor(any()) }
    }

    companion object {
        private val FIRST_SERVICE = UUID.fromString("0000aaa0-0000-1000-8000-aabbccddeeff")
        private val SECOND_SERVICE = UUID.fromString("0000bbb0-0000-1000-8000-aabbccddeeff")
        private val SHARED_UUID = UUID.fromString("00002a38-0000-1000-8000-00805f9b34fb")
        private val ONLY_SECOND_UUID = UUID.fromString("0000bbb1-0000-1000-8000-aabbccddeeff")
        private val MISSING_UUID = UUID.fromString("0000ffff-0000-1000-8000-aabbccddeeff")
    }
}
