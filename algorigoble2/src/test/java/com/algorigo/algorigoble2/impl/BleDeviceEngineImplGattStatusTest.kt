package com.algorigo.algorigoble2.impl

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothProfile
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
 * GATT 콜백의 실패 status 를 성공으로 처리하던 문제(P1) 테스트.
 */
class BleDeviceEngineImplGattStatusTest {

    @get:Rule
    val rxRule = RxTestSchedulerRule()

    private val fixture = GattTestFixture()
    private val characteristic = fixture.addCharacteristic(
        UUID_A,
        BluetoothGattCharacteristic.PROPERTY_READ or
                BluetoothGattCharacteristic.PROPERTY_WRITE or
                BluetoothGattCharacteristic.PROPERTY_NOTIFY
    )
    private val other = fixture.addCharacteristic(UUID_B, BluetoothGattCharacteristic.PROPERTY_READ)

    private fun isGattFailure(operation: String, status: Int) = { error: Throwable ->
        error is BleManager.GattOperationFailedException && error.operation == operation && error.status == status
    }

    @Test
    fun readCharacteristicSingle_fails_whenReadStatusIsNotSuccess() {
        fixture.connect()
        every { characteristic.value } returns byteArrayOf(0x55)
        val read = fixture.bleDevice.readCharacteristicSingle(UUID_A).test()

        fixture.callback.onCharacteristicRead(fixture.gatt, characteristic, INSUFFICIENT_AUTHENTICATION)

        read.assertError(isGattFailure("READ_CHARACTERISTIC", INSUFFICIENT_AUTHENTICATION))
    }

    @Test
    fun writeCharacteristicSingle_fails_whenWriteStatusIsNotSuccess() {
        fixture.connect()
        val write = fixture.bleDevice.writeCharacteristicSingle(UUID_A, byteArrayOf(1)).test()

        fixture.callback.onCharacteristicWrite(fixture.gatt, characteristic, APPLICATION_ERROR)

        write.assertError(isGattFailure("WRITE_CHARACTERISTIC", APPLICATION_ERROR))
    }

    @Test
    fun setupNotification_fails_whenDescriptorWriteStatusIsNotSuccess() {
        fixture.connect()
        val notification = fixture.bleDevice.setupNotification(BleDevice.NotificationType.NOTIFICATION, UUID_A).test()

        fixture.callback.onDescriptorWrite(fixture.gatt, characteristic.getDescriptor(GattTestFixture.CCCD_UUID), CCCD_IMPROPERLY_CONFIGURED)

        notification.assertNoValues()
        notification.assertError(isGattFailure("WRITE_DESCRIPTOR", CCCD_IMPROPERLY_CONFIGURED))
    }

    @Test
    fun nextQueuedOperation_runs_afterPreviousOperationFailedWithStatus() {
        fixture.connect()
        every { other.value } returns byteArrayOf(2)
        val failed = fixture.bleDevice.readCharacteristicSingle(UUID_A).test()
        val next = fixture.bleDevice.readCharacteristicSingle(UUID_B).test()

        fixture.callback.onCharacteristicRead(fixture.gatt, characteristic, INSUFFICIENT_AUTHENTICATION)
        fixture.callback.onCharacteristicRead(fixture.gatt, other, BluetoothGatt.GATT_SUCCESS)

        failed.assertError(BleManager.GattOperationFailedException::class.java)
        next.assertValue { it.contentEquals(byteArrayOf(2)) }
    }

    @Test
    fun connectCompletable_fails_andClosesGatt_whenInitialServiceDiscoveryFails() {
        val connect = fixture.engine.connectCompletable(GattTestFixture.CONNECT_TIMEOUT_MILLIS).test()
        fixture.callback.onConnectionStateChange(fixture.gatt, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED)

        fixture.simulateServicesDiscovered(status = BluetoothGatt.GATT_FAILURE)

        connect.assertError(isGattFailure("DISCOVER_SERVICES", BluetoothGatt.GATT_FAILURE))
        verify(exactly = 1) { fixture.gatt.disconnect() }
        verify(exactly = 1) { fixture.gatt.close() }
        assertEquals(BleDevice.ConnectionState.DISCONNECTED, fixture.bleDevice.connectionState)
    }

    @Test
    fun connectCompletable_canConnectAgain_rightAfterServiceDiscoveryFailure() {
        val retryGatt = fixture.newGatt()
        fixture.stubConnectGatt(fixture.gatt, retryGatt)
        var retry: io.reactivex.rxjava3.observers.TestObserver<Void>? = null
        fixture.engine.connectCompletable(GattTestFixture.CONNECT_TIMEOUT_MILLIS)
            // 에러를 받은 즉시 다시 연결해도 상태가 DISCONNECTED 여야 한다
            .doOnError { retry = fixture.engine.connectCompletable(GattTestFixture.CONNECT_TIMEOUT_MILLIS).test() }
            .test()
        fixture.callback.onConnectionStateChange(fixture.gatt, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED)
        fixture.simulateServicesDiscovered(status = BluetoothGatt.GATT_FAILURE)

        fixture.simulateConnected(retryGatt)

        retry!!.assertComplete()
        assertEquals(BleDevice.ConnectionState.CONNECTED, fixture.bleDevice.connectionState)
    }

    @Test
    fun serviceRediscoveryFailure_keepsConnection_andDoesNotBlockOperations() {
        fixture.connect()
        fixture.callback.onServiceChanged(fixture.gatt)

        fixture.simulateServicesDiscovered(status = BluetoothGatt.GATT_FAILURE)

        verify(exactly = 0) { fixture.gatt.disconnect() }
        assertEquals(BleDevice.ConnectionState.CONNECTED, fixture.bleDevice.connectionState)
        // 서비스 목록이 비어 있으므로 작업은 기다리지 않고 바로 실패한다
        fixture.bleDevice.readCharacteristicSingle(UUID_A).test().assertError(IllegalStateException::class.java)
    }

    companion object {
        private val UUID_A: UUID = UUID.fromString("0000e001-0000-1000-8000-00805f9b34fb")
        private val UUID_B: UUID = UUID.fromString("0000e002-0000-1000-8000-00805f9b34fb")
        private const val INSUFFICIENT_AUTHENTICATION = 0x05 // BluetoothGatt.GATT_INSUFFICIENT_AUTHENTICATION
        private const val APPLICATION_ERROR = 0x80
        private const val CCCD_IMPROPERLY_CONFIGURED = 0xFD
    }
}
