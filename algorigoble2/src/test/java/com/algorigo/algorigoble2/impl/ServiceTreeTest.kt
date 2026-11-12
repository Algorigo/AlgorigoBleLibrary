package com.algorigo.algorigoble2.impl

import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattService
import com.algorigo.algorigoble2.BleManager
import com.algorigo.algorigoble2.testutil.RxTestSchedulerRule
import io.mockk.every
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.util.UUID

/** 서비스/characteristic 트리 조회 테스트 */
class ServiceTreeTest {

    @get:Rule
    val rxRule = RxTestSchedulerRule()

    private val fixture = GattTestFixture()

    @Test
    fun services_containTheirCharacteristicsWithPropertiesAndDescriptors() {
        val notify = fixture.addCharacteristic(NOTIFY_UUID, BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY, serviceUuid = TEST_SERVICE)
        fixture.addDescriptor(notify, USER_DESCRIPTION_UUID)
        val write = fixture.addCharacteristic(WRITE_UUID, BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE or BluetoothGattCharacteristic.PROPERTY_INDICATE, serviceUuid = OTHER_SERVICE)
        val otherService = write.service
        every { otherService.type } returns BluetoothGattService.SERVICE_TYPE_SECONDARY
        fixture.connect()

        val services = fixture.bleDevice.getServicesSingle().test().values().single()

        assertEquals(listOf(TEST_SERVICE, OTHER_SERVICE), services.map { it.uuid })
        assertEquals(listOf(true, false), services.map { it.isPrimary })
        services[0].characteristics.single().let {
            assertEquals(NOTIFY_UUID, it.uuid)
            assertEquals(TEST_SERVICE, it.serviceUuid)
            assertEquals(BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY, it.properties)
            assertEquals(listOf(GattTestFixture.CCCD_UUID, USER_DESCRIPTION_UUID), it.descriptorUuids)
        }
        services[1].characteristics.single().let {
            assertEquals(OTHER_SERVICE, it.serviceUuid)
            // raw properties 로 WRITE NO RESPONSE 와 INDICATE 를 구분할 수 있다
            assertEquals(BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE or BluetoothGattCharacteristic.PROPERTY_INDICATE, it.properties)
        }
    }

    // 같은 UUID 의 characteristic 이 여러 서비스에 있으면 어느 서비스의 것인지 알 수 있다
    @Test
    fun characteristicList_tellsServiceOfEachCharacteristic() {
        fixture.addCharacteristic(NOTIFY_UUID, BluetoothGattCharacteristic.PROPERTY_READ, serviceUuid = TEST_SERVICE)
        fixture.addCharacteristic(NOTIFY_UUID, BluetoothGattCharacteristic.PROPERTY_READ, serviceUuid = OTHER_SERVICE)
        fixture.connect()

        val characteristics = fixture.bleDevice.getCharacteristicsSingle().test().values().single()

        assertEquals(listOf(TEST_SERVICE, OTHER_SERVICE), characteristics.map { it.serviceUuid })
    }

    @Test
    fun services_whenNotConnected_failWithDisconnectedException() {
        fixture.bleDevice.getServicesSingle().test().assertError(BleManager.DisconnectedException::class.java)
    }

    companion object {
        private val TEST_SERVICE = UUID.fromString("0000aaa0-0000-1000-8000-aabbccddeeff")
        private val OTHER_SERVICE = UUID.fromString("0000bbb0-0000-1000-8000-aabbccddeeff")
        private val NOTIFY_UUID = UUID.fromString("0000aaa1-0000-1000-8000-aabbccddeeff")
        private val WRITE_UUID = UUID.fromString("0000aaa2-0000-1000-8000-aabbccddeeff")
        private val USER_DESCRIPTION_UUID = UUID.fromString("00002901-0000-1000-8000-00805f9b34fb")
    }
}
