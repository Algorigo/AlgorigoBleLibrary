package com.algorigo.algorigoble2.impl

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import com.algorigo.algorigoble2.BleManager
import com.algorigo.algorigoble2.testutil.RxTestSchedulerRule
import io.mockk.every
import io.mockk.verify
import org.junit.Rule
import org.junit.Test
import java.util.UUID

/** 일반 descriptor read/write API 테스트 */
class DescriptorTest {

    @get:Rule
    val rxRule = RxTestSchedulerRule()

    private fun fixture(sdkInt: Int = 0) = GattTestFixture(sdkInt = sdkInt)

    @Test
    fun read_returnsDescriptorValue() {
        val fixture = fixture()
        val characteristic = fixture.addCharacteristic(CHARACTERISTIC_UUID, BluetoothGattCharacteristic.PROPERTY_READ)
        val descriptor = fixture.addDescriptor(characteristic, USER_DESCRIPTION_UUID)
        every { descriptor.value } returns "name".toByteArray()
        fixture.connect()

        val observer = fixture.bleDevice.readDescriptorSingle(CHARACTERISTIC_UUID, USER_DESCRIPTION_UUID).test()
        verify(exactly = 1) { fixture.gatt.readDescriptor(descriptor) }
        fixture.callback.onDescriptorRead(fixture.gatt, descriptor, BluetoothGatt.GATT_SUCCESS)

        observer.assertValue { String(it) == "name" }
    }

    @Test
    fun read_onApi33_usesCallbackValue() {
        val fixture = fixture(sdkInt = 33)
        val characteristic = fixture.addCharacteristic(CHARACTERISTIC_UUID, BluetoothGattCharacteristic.PROPERTY_READ)
        val descriptor = fixture.addDescriptor(characteristic, USER_DESCRIPTION_UUID)
        fixture.connect()

        val observer = fixture.bleDevice.readDescriptorSingle(CHARACTERISTIC_UUID, USER_DESCRIPTION_UUID).test()
        fixture.callback.onDescriptorRead(fixture.gatt, descriptor, BluetoothGatt.GATT_SUCCESS, byteArrayOf(1, 2))

        observer.assertValue { it.contentEquals(byteArrayOf(1, 2)) }
    }

    // 같은 characteristic 의 다른 descriptor 응답으로 끝나지 않는다
    @Test
    fun read_ignoresReplyOfAnotherDescriptor() {
        val fixture = fixture(sdkInt = 33)
        val characteristic = fixture.addCharacteristic(CHARACTERISTIC_UUID, BluetoothGattCharacteristic.PROPERTY_READ)
        val descriptor = fixture.addDescriptor(characteristic, USER_DESCRIPTION_UUID)
        val other = fixture.addDescriptor(characteristic, PRESENTATION_FORMAT_UUID)
        fixture.connect()

        val observer = fixture.bleDevice.readDescriptorSingle(CHARACTERISTIC_UUID, USER_DESCRIPTION_UUID).test()
        fixture.callback.onDescriptorRead(fixture.gatt, other, BluetoothGatt.GATT_SUCCESS, byteArrayOf(9))
        observer.assertNoValues()

        fixture.callback.onDescriptorRead(fixture.gatt, descriptor, BluetoothGatt.GATT_SUCCESS, byteArrayOf(1))
        observer.assertValue { it.contentEquals(byteArrayOf(1)) }
    }

    @Test
    fun write_setsValueAndWrites_beforeApi33() {
        val fixture = fixture()
        val characteristic = fixture.addCharacteristic(CHARACTERISTIC_UUID, BluetoothGattCharacteristic.PROPERTY_READ)
        val descriptor = fixture.addDescriptor(characteristic, CUSTOM_DESCRIPTOR_UUID)
        fixture.connect()

        val observer = fixture.bleDevice.writeDescriptorSingle(CHARACTERISTIC_UUID, CUSTOM_DESCRIPTOR_UUID, byteArrayOf(7)).test()
        verify { descriptor.value = byteArrayOf(7) }
        verify(exactly = 1) { fixture.gatt.writeDescriptor(descriptor) }
        fixture.callback.onDescriptorWrite(fixture.gatt, descriptor, BluetoothGatt.GATT_SUCCESS)

        observer.assertValue { it.contentEquals(byteArrayOf(7)) }
    }

    @Test
    fun write_passesValue_onApi33() {
        val fixture = fixture(sdkInt = 33)
        val characteristic = fixture.addCharacteristic(CHARACTERISTIC_UUID, BluetoothGattCharacteristic.PROPERTY_READ)
        val descriptor = fixture.addDescriptor(characteristic, CUSTOM_DESCRIPTOR_UUID)
        fixture.connect()

        val observer = fixture.bleDevice.writeDescriptorSingle(CHARACTERISTIC_UUID, CUSTOM_DESCRIPTOR_UUID, byteArrayOf(7)).test()
        verify(exactly = 1) { fixture.gatt.writeDescriptor(descriptor, byteArrayOf(7)) }
        fixture.callback.onDescriptorWrite(fixture.gatt, descriptor, BluetoothGatt.GATT_SUCCESS)

        observer.assertValue { it.contentEquals(byteArrayOf(7)) }
    }

    @Test
    fun write_failsWithStatus() {
        val fixture = fixture()
        val characteristic = fixture.addCharacteristic(CHARACTERISTIC_UUID, BluetoothGattCharacteristic.PROPERTY_READ)
        val descriptor = fixture.addDescriptor(characteristic, USER_DESCRIPTION_UUID)
        fixture.connect()

        val observer = fixture.bleDevice.writeDescriptorSingle(CHARACTERISTIC_UUID, USER_DESCRIPTION_UUID, byteArrayOf(1)).test()
        fixture.callback.onDescriptorWrite(fixture.gatt, descriptor, BluetoothGatt.GATT_WRITE_NOT_PERMITTED)

        observer.assertError { it is BleManager.GattOperationFailedException && it.operation == "WRITE_DESCRIPTOR" && it.status == BluetoothGatt.GATT_WRITE_NOT_PERMITTED }
    }

    // CCCD 는 setupNotification 이 관리하므로 직접 쓸 수 없다. 읽기는 된다
    @Test
    fun cccd_canBeRead_butNotWritten() {
        val fixture = fixture(sdkInt = 33)
        val characteristic = fixture.addCharacteristic(CHARACTERISTIC_UUID, BluetoothGattCharacteristic.PROPERTY_NOTIFY)
        val cccd = characteristic.getDescriptor(GattTestFixture.CCCD_UUID)
        fixture.connect()

        fixture.bleDevice.writeDescriptorSingle(CHARACTERISTIC_UUID, GattTestFixture.CCCD_UUID, byteArrayOf(1, 0)).test()
            .assertError(IllegalArgumentException::class.java)
        verify(exactly = 0) { fixture.gatt.writeDescriptor(any(), any()) }

        val read = fixture.bleDevice.readDescriptorSingle(CHARACTERISTIC_UUID, GattTestFixture.CCCD_UUID).test()
        fixture.callback.onDescriptorRead(fixture.gatt, cccd, BluetoothGatt.GATT_SUCCESS, byteArrayOf(0, 0))
        read.assertValue { it.contentEquals(byteArrayOf(0, 0)) }
    }

    @Test
    fun missingDescriptorOrCharacteristic_failsWithoutGattCall() {
        val fixture = fixture()
        fixture.addCharacteristic(CHARACTERISTIC_UUID, BluetoothGattCharacteristic.PROPERTY_READ)
        fixture.connect()

        fixture.bleDevice.readDescriptorSingle(CHARACTERISTIC_UUID, USER_DESCRIPTION_UUID).test()
            .assertError { it is BleManager.DescriptorNotFoundException && it.characteristicUuid == CHARACTERISTIC_UUID && it.descriptorUuid == USER_DESCRIPTION_UUID }
        fixture.bleDevice.writeDescriptorSingle(UUID.randomUUID(), USER_DESCRIPTION_UUID, byteArrayOf(1)).test()
            .assertError(BleManager.CharacteristicNotFoundException::class.java)
        verify(exactly = 0) { fixture.gatt.readDescriptor(any()) }
        verify(exactly = 0) { fixture.gatt.writeDescriptor(any()) }
    }

    @Test
    fun serviceUuid_selectsDescriptorOfThatService() {
        val fixture = fixture(sdkInt = 33)
        val first = fixture.addCharacteristic(CHARACTERISTIC_UUID, BluetoothGattCharacteristic.PROPERTY_READ, serviceUuid = FIRST_SERVICE_UUID)
        val second = fixture.addCharacteristic(CHARACTERISTIC_UUID, BluetoothGattCharacteristic.PROPERTY_READ, serviceUuid = SECOND_SERVICE_UUID)
        fixture.addDescriptor(first, USER_DESCRIPTION_UUID)
        val secondDescriptor = fixture.addDescriptor(second, USER_DESCRIPTION_UUID)
        fixture.connect()

        fixture.bleDevice.readDescriptorSingle(SECOND_SERVICE_UUID, CHARACTERISTIC_UUID, USER_DESCRIPTION_UUID).test()

        verify(exactly = 1) { fixture.gatt.readDescriptor(secondDescriptor) }
    }

    @Test
    fun descriptorRead_waitsForPendingOperation() {
        val fixture = fixture(sdkInt = 33)
        val characteristic = fixture.addCharacteristic(CHARACTERISTIC_UUID, BluetoothGattCharacteristic.PROPERTY_READ)
        val descriptor = fixture.addDescriptor(characteristic, USER_DESCRIPTION_UUID)
        fixture.connect()

        val read = fixture.bleDevice.readCharacteristicSingle(CHARACTERISTIC_UUID).test()
        val descriptorRead = fixture.bleDevice.readDescriptorSingle(CHARACTERISTIC_UUID, USER_DESCRIPTION_UUID).test()
        verify(exactly = 0) { fixture.gatt.readDescriptor(any()) }

        fixture.callback.onCharacteristicRead(fixture.gatt, characteristic, byteArrayOf(5), BluetoothGatt.GATT_SUCCESS)
        read.assertValue { it.contentEquals(byteArrayOf(5)) }
        verify(exactly = 1) { fixture.gatt.readDescriptor(descriptor) }
        fixture.callback.onDescriptorRead(fixture.gatt, descriptor, BluetoothGatt.GATT_SUCCESS, byteArrayOf(6))
        descriptorRead.assertValue { it.contentEquals(byteArrayOf(6)) }
    }

    @Test
    fun notConnected_failsWithDisconnectedException() {
        val fixture = fixture()

        fixture.bleDevice.readDescriptorSingle(CHARACTERISTIC_UUID, USER_DESCRIPTION_UUID).test()
            .assertError(BleManager.DisconnectedException::class.java)
        fixture.bleDevice.writeDescriptorSingle(CHARACTERISTIC_UUID, USER_DESCRIPTION_UUID, byteArrayOf(1)).test()
            .assertError(BleManager.DisconnectedException::class.java)
    }

    companion object {
        private val CHARACTERISTIC_UUID = UUID.fromString("0000aaa1-0000-1000-8000-aabbccddeeff")
        private val CUSTOM_DESCRIPTOR_UUID = UUID.fromString("0000aab0-0000-1000-8000-aabbccddeeff")
        private val USER_DESCRIPTION_UUID = UUID.fromString("00002901-0000-1000-8000-00805f9b34fb")
        private val PRESENTATION_FORMAT_UUID = UUID.fromString("00002904-0000-1000-8000-00805f9b34fb")
        private val FIRST_SERVICE_UUID = UUID.fromString("0000aaa0-0000-1000-8000-aabbccddeeff")
        private val SECOND_SERVICE_UUID = UUID.fromString("0000bbb0-0000-1000-8000-aabbccddeeff")
    }
}
