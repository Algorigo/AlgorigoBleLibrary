package com.algorigo.algorigoble2.impl

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import com.algorigo.algorigoble2.BleDevice
import com.algorigo.algorigoble2.BleManager
import com.algorigo.algorigoble2.testutil.RxTestSchedulerRule
import io.mockk.every
import io.mockk.verify
import io.reactivex.rxjava3.observers.TestObserver
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * GATT 작업이 콜백을 기다리다 영원히 끝나지 않는 문제(P0)에 대한 테스트.
 * 작업 도중 연결이 끊기면 DisconnectedException, 응답이 없으면 operationTimeoutMillis 후 OperationTimeoutException 으로 끝나야 한다.
 */
class BleDeviceEngineImplOperationGuardTest {

    @get:Rule
    val rxRule = RxTestSchedulerRule()

    private val fixture = GattTestFixture()
    private val characteristic = fixture.addCharacteristic(
        CHARACTERISTIC_UUID,
        BluetoothGattCharacteristic.PROPERTY_READ or
                BluetoothGattCharacteristic.PROPERTY_WRITE or
                BluetoothGattCharacteristic.PROPERTY_NOTIFY
    )

    @Before
    fun setUp() {
        fixture.connect()
        every { fixture.gatt.requestMtu(any()) } returns true
    }

    @Test
    fun readCharacteristicSingle_emitsValue_whenReplyArrives() {
        every { characteristic.value } returns byteArrayOf(1, 2, 3)

        val observer = fixture.bleDevice.readCharacteristicSingle(CHARACTERISTIC_UUID).test()
        verify(exactly = 1) { fixture.gatt.readCharacteristic(characteristic) }

        fixture.callback.onCharacteristicRead(fixture.gatt, characteristic, BluetoothGatt.GATT_SUCCESS)

        observer.assertValue { it.contentEquals(byteArrayOf(1, 2, 3)) }
    }

    @Test
    fun readCharacteristicSingle_failsWithDisconnectedException_whenDisconnectedBeforeReply() {
        val observer = fixture.bleDevice.readCharacteristicSingle(CHARACTERISTIC_UUID).test()
        observer.assertNotComplete().assertNoErrors()

        fixture.disconnectFromRemote()

        observer.assertError(BleManager.DisconnectedException::class.java)
    }

    @Test
    fun readCharacteristicSingle_timesOut_whenReplyNeverArrives() {
        val observer = fixture.bleDevice.readCharacteristicSingle(CHARACTERISTIC_UUID).test()

        observer.assertTimeoutAfter(BleDevice.DEFAULT_OPERATION_TIMEOUT_MILLIS)
    }

    @Test
    fun operationTimeoutMillis_isAppliedToOperations() {
        fixture.bleDevice.operationTimeoutMillis = 3_000L

        val observer = fixture.bleDevice.readCharacteristicSingle(CHARACTERISTIC_UUID).test()

        observer.assertTimeoutAfter(3_000L)
    }

    @Test
    fun readCharacteristicSingle_failsWithoutGattCall_whenAlreadyDisconnected() {
        fixture.disconnectFromRemote()

        fixture.bleDevice.readCharacteristicSingle(CHARACTERISTIC_UUID).test()
            .assertError(BleManager.DisconnectedException::class.java)
        verify(exactly = 0) { fixture.gatt.readCharacteristic(any()) }
    }

    @Test
    fun writeCharacteristicSingle_failsWithDisconnectedException_whenDisconnectedBeforeReply() {
        val observer = fixture.bleDevice.writeCharacteristicSingle(CHARACTERISTIC_UUID, byteArrayOf(1)).test()
        verify(exactly = 1) { fixture.gatt.writeCharacteristic(characteristic) }
        observer.assertNotComplete().assertNoErrors()

        fixture.disconnectFromRemote()

        observer.assertError(BleManager.DisconnectedException::class.java)
    }

    @Test
    fun writeCharacteristicSingle_timesOut_whenReplyNeverArrives() {
        val observer = fixture.bleDevice.writeCharacteristicSingle(CHARACTERISTIC_UUID, byteArrayOf(1)).test()

        observer.assertTimeoutAfter(BleDevice.DEFAULT_OPERATION_TIMEOUT_MILLIS)
    }

    @Test
    fun setupNotification_timesOut_whenDescriptorWriteReplyNeverArrives() {
        val observer = fixture.bleDevice.setupNotification(BleDevice.NotificationType.NOTIFICATION, CHARACTERISTIC_UUID).test()
        verify(exactly = 1) { fixture.gatt.writeDescriptor(any()) }
        observer.assertNoValues()

        rxRule.testScheduler.advanceTimeBy(BleDevice.DEFAULT_OPERATION_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)

        observer.assertError(BleManager.OperationTimeoutException::class.java)
    }

    @Test
    fun setupNotification_keepsReceivingNotifications_afterOperationTimeoutElapsed() {
        val descriptor = characteristic.getDescriptor(GattTestFixture.CCCD_UUID)
        every { descriptor.value } returns BleDevice.NotificationType.NOTIFICATION.byteArray
        val dataObserver = fixture.bleDevice.setupNotification(BleDevice.NotificationType.NOTIFICATION, CHARACTERISTIC_UUID)
            .flatMap { it }
            .test()
        fixture.callback.onDescriptorWrite(fixture.gatt, descriptor, BluetoothGatt.GATT_SUCCESS)

        rxRule.testScheduler.advanceTimeBy(BleDevice.DEFAULT_OPERATION_TIMEOUT_MILLIS * 2, TimeUnit.MILLISECONDS)
        every { characteristic.value } returns byteArrayOf(9)
        fixture.callback.onCharacteristicChanged(fixture.gatt, characteristic)

        dataObserver.assertNoErrors()
        dataObserver.assertValue { it.contentEquals(byteArrayOf(9)) }
    }

    @Test
    fun requestMtuSingle_failsWithDisconnectedException_whenDisconnectedBeforeMtuChanged() {
        val observer = fixture.bleDevice.requestMtuSingle(517).test()
        observer.assertNotComplete().assertNoErrors()

        fixture.disconnectFromRemote()

        observer.assertError(BleManager.DisconnectedException::class.java)
    }

    @Test
    fun requestMtuSingle_timesOut_whenMtuChangedNeverArrives() {
        val observer = fixture.bleDevice.requestMtuSingle(517).test()

        observer.assertTimeoutAfter(BleDevice.DEFAULT_OPERATION_TIMEOUT_MILLIS)
    }

    private fun <T : Any> TestObserver<T>.assertTimeoutAfter(timeoutMillis: Long) {
        rxRule.testScheduler.advanceTimeBy(timeoutMillis - 1, TimeUnit.MILLISECONDS)
        assertNotComplete().assertNoErrors()

        rxRule.testScheduler.advanceTimeBy(1, TimeUnit.MILLISECONDS)
        assertError(BleManager.OperationTimeoutException::class.java)
    }

    companion object {
        private val CHARACTERISTIC_UUID: UUID = UUID.fromString("0000aaaa-0000-1000-8000-00805f9b34fb")
    }
}
