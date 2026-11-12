package com.algorigo.algorigoble2.impl

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import com.algorigo.algorigoble2.BleManager
import com.algorigo.algorigoble2.testutil.RxTestSchedulerRule
import io.mockk.every
import io.mockk.verify
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.UUID
import java.util.concurrent.TimeUnit

/** 연결된 기기의 RSSI 읽기 테스트 */
class ReadRssiTest {

    @get:Rule
    val rxRule = RxTestSchedulerRule()

    private val fixture = GattTestFixture()

    @Before
    fun setUp() {
        every { fixture.gatt.readRemoteRssi() } returns true
    }

    @Test
    fun readRssi_emitsRssiFromCallback() {
        fixture.connect()

        val observer = fixture.bleDevice.readRssiSingle().test()
        verify(exactly = 1) { fixture.gatt.readRemoteRssi() }
        observer.assertNoValues()

        fixture.callback.onReadRemoteRssi(fixture.gatt, -42, BluetoothGatt.GATT_SUCCESS)

        observer.assertValue(-42)
    }

    @Test
    fun failedStatus_failsWithGattOperationFailed() {
        fixture.connect()

        val observer = fixture.bleDevice.readRssiSingle().test()
        fixture.callback.onReadRemoteRssi(fixture.gatt, 0, BluetoothGatt.GATT_FAILURE)

        observer.assertError { it is BleManager.GattOperationFailedException && it.operation == "READ_RSSI" && it.status == BluetoothGatt.GATT_FAILURE }
    }

    @Test
    fun rejectedRequest_isRetried() {
        fixture.connect()
        every { fixture.gatt.readRemoteRssi() } returnsMany listOf(false, true)

        val observer = fixture.bleDevice.readRssiSingle().test()
        rxRule.testScheduler.advanceTimeBy(500, TimeUnit.MILLISECONDS)
        verify(exactly = 2) { fixture.gatt.readRemoteRssi() }

        fixture.callback.onReadRemoteRssi(fixture.gatt, -60, BluetoothGatt.GATT_SUCCESS)
        observer.assertValue(-60)
    }

    // Android 는 RSSI 읽기도 다른 GATT 요청이 처리 중이면 거절하므로 같은 작업 큐에서 차례를 기다린다
    @Test
    fun readRssi_waitsForPendingGattOperation() {
        val characteristic = fixture.addCharacteristic(CHARACTERISTIC_UUID, BluetoothGattCharacteristic.PROPERTY_READ)
        every { characteristic.value } returns byteArrayOf(1)
        fixture.connect()

        val read = fixture.bleDevice.readCharacteristicSingle(CHARACTERISTIC_UUID).test()
        val rssi = fixture.bleDevice.readRssiSingle().test()
        verify(exactly = 0) { fixture.gatt.readRemoteRssi() }

        fixture.callback.onCharacteristicRead(fixture.gatt, characteristic, BluetoothGatt.GATT_SUCCESS)
        read.assertValueCount(1)
        verify(exactly = 1) { fixture.gatt.readRemoteRssi() }

        fixture.callback.onReadRemoteRssi(fixture.gatt, -50, BluetoothGatt.GATT_SUCCESS)
        rssi.assertValue(-50)
    }

    @Test
    fun disconnectWhileReading_failsWithDisconnectedException() {
        fixture.connect()

        val observer = fixture.bleDevice.readRssiSingle().test()
        fixture.disconnectFromRemote(status = 19)

        observer.assertError { it is BleManager.DisconnectedException && it.status == 19 }
    }

    @Test
    fun readRssi_whenNotConnected_failsWithoutGattCall() {
        fixture.bleDevice.readRssiSingle().test()
            .assertError(BleManager.DisconnectedException::class.java)

        verify(exactly = 0) { fixture.gatt.readRemoteRssi() }
    }

    companion object {
        private val CHARACTERISTIC_UUID = UUID.fromString("0000aaa1-0000-1000-8000-aabbccddeeff")
    }
}
