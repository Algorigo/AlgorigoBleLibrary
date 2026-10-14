package com.algorigo.algorigoble2.impl

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import com.algorigo.algorigoble2.BleDevice
import com.algorigo.algorigoble2.testutil.RxTestSchedulerRule
import io.mockk.verify
import io.mockk.verifyOrder
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.UUID

/**
 * 쓰기 타입 문제(P2) 테스트.
 * writeType 을 지정하지 않으면 Android 는 WRITE NO RESPONSE 속성이 있는 characteristic 을 응답 없이 쓰므로,
 * 요청한 타입에 맞게 BluetoothGattCharacteristic.writeType 을 매번 지정해야 한다.
 */
class BleDeviceEngineImplWriteTypeTest {

    @get:Rule
    val rxRule = RxTestSchedulerRule()

    private val fixture = GattTestFixture()
    private val both = fixture.addCharacteristic(
        BOTH_UUID, BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE
    )
    private val withResponseOnly = fixture.addCharacteristic(WITH_RESPONSE_UUID, BluetoothGattCharacteristic.PROPERTY_WRITE)
    private val withoutResponseOnly = fixture.addCharacteristic(WITHOUT_RESPONSE_UUID, BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)
    private val readOnly = fixture.addCharacteristic(READ_ONLY_UUID, BluetoothGattCharacteristic.PROPERTY_READ)

    @Before
    fun setUp() {
        fixture.connect()
    }

    private fun write(uuid: UUID, characteristic: BluetoothGattCharacteristic, writeType: BleDevice.WriteType? = null) {
        val observer = if (writeType == null) {
            fixture.bleDevice.writeCharacteristicSingle(uuid, byteArrayOf(1)).test()
        } else {
            fixture.bleDevice.writeCharacteristicSingle(uuid, byteArrayOf(1), writeType).test()
        }
        fixture.callback.onCharacteristicWrite(fixture.gatt, characteristic, BluetoothGatt.GATT_SUCCESS)
        observer.assertComplete()
    }

    @Test
    fun defaultWrite_usesWriteRequest_whenCharacteristicSupportsBoth() {
        write(BOTH_UUID, both)

        verifyOrder {
            both.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            fixture.gatt.writeCharacteristic(both)
        }
    }

    @Test
    fun withoutResponse_usesWriteCommand_whenCharacteristicSupportsBoth() {
        write(BOTH_UUID, both, BleDevice.WriteType.WITHOUT_RESPONSE)

        verifyOrder {
            both.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            fixture.gatt.writeCharacteristic(both)
        }
    }

    @Test
    fun writeTypeIsSetEveryTime_soPreviousWriteTypeDoesNotLeak() {
        write(BOTH_UUID, both, BleDevice.WriteType.WITHOUT_RESPONSE)
        write(BOTH_UUID, both)

        verifyOrder {
            both.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            fixture.gatt.writeCharacteristic(both)
            both.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            fixture.gatt.writeCharacteristic(both)
        }
    }

    @Test
    fun defaultWrite_usesWriteCommand_whenOnlyWriteNoResponseIsSupported() {
        write(WITHOUT_RESPONSE_UUID, withoutResponseOnly)

        verify { withoutResponseOnly.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE }
    }

    @Test
    fun withResponse_usesWriteRequest_whenOnlyWriteIsSupported() {
        write(WITH_RESPONSE_UUID, withResponseOnly, BleDevice.WriteType.WITH_RESPONSE)

        verify { withResponseOnly.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT }
    }

    @Test
    fun unsupportedWriteType_failsWithoutGattCall() {
        fixture.bleDevice.writeCharacteristicSingle(WITH_RESPONSE_UUID, byteArrayOf(1), BleDevice.WriteType.WITHOUT_RESPONSE).test()
            .assertError(BleDeviceEngineImpl.IllegalCharacteristicProperty::class.java)
        fixture.bleDevice.writeCharacteristicSingle(WITHOUT_RESPONSE_UUID, byteArrayOf(1), BleDevice.WriteType.WITH_RESPONSE).test()
            .assertError(BleDeviceEngineImpl.IllegalCharacteristicProperty::class.java)
        fixture.bleDevice.writeCharacteristicSingle(READ_ONLY_UUID, byteArrayOf(1)).test()
            .assertError(BleDeviceEngineImpl.IllegalCharacteristicProperty::class.java)

        verify(exactly = 0) { fixture.gatt.writeCharacteristic(any()) }
    }

    companion object {
        private val BOTH_UUID: UUID = UUID.fromString("0000a101-0000-1000-8000-00805f9b34fb")
        private val WITH_RESPONSE_UUID: UUID = UUID.fromString("0000a102-0000-1000-8000-00805f9b34fb")
        private val WITHOUT_RESPONSE_UUID: UUID = UUID.fromString("0000a103-0000-1000-8000-00805f9b34fb")
        private val READ_ONLY_UUID: UUID = UUID.fromString("0000a104-0000-1000-8000-00805f9b34fb")
    }
}
