package com.algorigo.algorigoble2.impl

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import com.algorigo.algorigoble2.BleDevice
import com.algorigo.algorigoble2.BleManager
import com.algorigo.algorigoble2.testutil.RxTestSchedulerRule
import io.mockk.every
import io.mockk.verify
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * GATT 작업 큐(P1) 테스트. Android 는 BluetoothGatt 당 한 번에 하나의 작업만 처리하므로,
 * 앞 작업이 콜백/타임아웃/연결 끊김으로 끝나기 전에는 다음 GATT 호출이 일어나면 안 된다.
 */
class BleDeviceEngineImplOperationQueueTest {

    @get:Rule
    val rxRule = RxTestSchedulerRule()

    private val fixture = GattTestFixture()
    private val first = fixture.addCharacteristic(FIRST_UUID, BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_WRITE)
    private val second = fixture.addCharacteristic(SECOND_UUID, BluetoothGattCharacteristic.PROPERTY_READ)
    private val noResponse = fixture.addCharacteristic(NO_RESPONSE_UUID, BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)
    private val notify = fixture.addCharacteristic(NOTIFY_UUID, BluetoothGattCharacteristic.PROPERTY_NOTIFY)

    @Before
    fun setUp() {
        every { first.value } returns byteArrayOf(1)
        every { second.value } returns byteArrayOf(2)
        fixture.connect()
    }

    private fun replyRead(characteristic: BluetoothGattCharacteristic) =
        fixture.callback.onCharacteristicRead(fixture.gatt, characteristic, BluetoothGatt.GATT_SUCCESS)

    @Test
    fun concurrentOperations_areExecutedOneAtATime() {
        val firstRead = fixture.bleDevice.readCharacteristicSingle(FIRST_UUID).test()
        val secondRead = fixture.bleDevice.readCharacteristicSingle(SECOND_UUID).test()

        verify(exactly = 1) { fixture.gatt.readCharacteristic(first) }
        verify(exactly = 0) { fixture.gatt.readCharacteristic(second) }

        replyRead(first)
        firstRead.assertValue { it.contentEquals(byteArrayOf(1)) }
        verify(exactly = 1) { fixture.gatt.readCharacteristic(second) }

        replyRead(second)
        secondRead.assertValue { it.contentEquals(byteArrayOf(2)) }
    }

    @Test
    fun differentOperationTypes_areSerialized() {
        val mtu = fixture.bleDevice.requestMtuSingle(247).test()
        every { fixture.gatt.requestMtu(any()) } returns true
        val notification = fixture.bleDevice.setupNotification(BleDevice.NotificationType.NOTIFICATION, NOTIFY_UUID).test()
        val write = fixture.bleDevice.writeCharacteristicSingle(FIRST_UUID, byteArrayOf(9)).test()

        // requestMtu 는 mock 기본값(false) → 재시도 대기 중. 큐가 막혀 있으므로 다른 작업은 시작되지 않는다
        verify(exactly = 0) { fixture.gatt.writeDescriptor(any()) }
        verify(exactly = 0) { fixture.gatt.writeCharacteristic(any()) }

        rxRule.testScheduler.advanceTimeBy(500, TimeUnit.MILLISECONDS)
        verify(exactly = 2) { fixture.gatt.requestMtu(247) }
        fixture.callback.onMtuChanged(fixture.gatt, 247, BluetoothGatt.GATT_SUCCESS)
        mtu.assertValue(247)

        verify(exactly = 1) { fixture.gatt.writeDescriptor(any()) }
        verify(exactly = 0) { fixture.gatt.writeCharacteristic(any()) }
        fixture.callback.onDescriptorWrite(fixture.gatt, notify.getDescriptor(GattTestFixture.CCCD_UUID), BluetoothGatt.GATT_SUCCESS)
        notification.assertValueCount(1)

        verify(exactly = 1) { fixture.gatt.writeCharacteristic(first) }
        fixture.callback.onCharacteristicWrite(fixture.gatt, first, BluetoothGatt.GATT_SUCCESS)
        write.assertComplete()
    }

    @Test
    fun nextOperation_startsAfterPreviousOperationTimesOut() {
        val firstRead = fixture.bleDevice.readCharacteristicSingle(FIRST_UUID).test()
        val secondRead = fixture.bleDevice.readCharacteristicSingle(SECOND_UUID).test()
        verify(exactly = 0) { fixture.gatt.readCharacteristic(second) }

        rxRule.testScheduler.advanceTimeBy(BleDevice.DEFAULT_OPERATION_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)

        firstRead.assertError(TimeoutException::class.java)
        verify(exactly = 1) { fixture.gatt.readCharacteristic(second) }
        replyRead(second)
        secondRead.assertValue { it.contentEquals(byteArrayOf(2)) }
    }

    @Test
    fun queuedOperation_isSkippedWithoutGattCall_whenCancelledBeforeStart() {
        fixture.bleDevice.readCharacteristicSingle(FIRST_UUID).test()
        val secondRead = fixture.bleDevice.readCharacteristicSingle(SECOND_UUID).test()

        secondRead.dispose()
        replyRead(first)

        verify(exactly = 0) { fixture.gatt.readCharacteristic(second) }
    }

    @Test
    fun cancellingRunningOperation_keepsQueueBlockedUntilItsCallback() {
        val firstRead = fixture.bleDevice.readCharacteristicSingle(FIRST_UUID).test()
        firstRead.dispose()
        fixture.bleDevice.readCharacteristicSingle(SECOND_UUID).test()

        // 첫 read 의 콜백이 오기 전에는 BluetoothGatt 가 busy 이므로 다음 작업을 시작하지 않는다
        verify(exactly = 0) { fixture.gatt.readCharacteristic(second) }

        replyRead(first)
        verify(exactly = 1) { fixture.gatt.readCharacteristic(second) }
    }

    @Test
    fun runningAndQueuedOperations_failWithDisconnectedException_whenDisconnected() {
        val firstRead = fixture.bleDevice.readCharacteristicSingle(FIRST_UUID).test()
        val secondRead = fixture.bleDevice.readCharacteristicSingle(SECOND_UUID).test()

        fixture.disconnectFromRemote()

        firstRead.assertError(BleManager.DisconnectedException::class.java)
        secondRead.assertError(BleManager.DisconnectedException::class.java)
        verify(exactly = 0) { fixture.gatt.readCharacteristic(second) }
    }

    @Test
    fun writeWithoutResponse_waitsForOnCharacteristicWrite_beforeNextOperation() {
        every { noResponse.value } returns byteArrayOf(5)
        val write = fixture.bleDevice.writeCharacteristicSingle(NO_RESPONSE_UUID, byteArrayOf(5)).test()
        fixture.bleDevice.readCharacteristicSingle(SECOND_UUID).test()

        write.assertNotComplete()
        verify(exactly = 0) { fixture.gatt.readCharacteristic(second) }

        fixture.callback.onCharacteristicWrite(fixture.gatt, noResponse, BluetoothGatt.GATT_SUCCESS)

        write.assertValue { it.contentEquals(byteArrayOf(5)) }
        verify(exactly = 1) { fixture.gatt.readCharacteristic(second) }
    }

    @Test
    fun operation_failsWithCommunicationFailedException_whenRetriesAreExhausted() {
        every { fixture.gatt.readCharacteristic(any()) } returns false

        val read = fixture.bleDevice.readCharacteristicSingle(FIRST_UUID).test()
        rxRule.testScheduler.advanceTimeBy(500L * 5, TimeUnit.MILLISECONDS)

        verify(exactly = 6) { fixture.gatt.readCharacteristic(first) }
        read.assertError(BleDeviceEngineImpl.CommunicationFailedException::class.java)
    }

    companion object {
        private val FIRST_UUID: UUID = UUID.fromString("0000d001-0000-1000-8000-00805f9b34fb")
        private val SECOND_UUID: UUID = UUID.fromString("0000d002-0000-1000-8000-00805f9b34fb")
        private val NO_RESPONSE_UUID: UUID = UUID.fromString("0000d003-0000-1000-8000-00805f9b34fb")
        private val NOTIFY_UUID: UUID = UUID.fromString("0000d004-0000-1000-8000-00805f9b34fb")
    }
}
