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
 * 작업이 타임아웃된 뒤에도 Android BluetoothGatt 가 busy 로 남아 다음 작업이 연달아 실패하던 문제 테스트.
 * 타임아웃된 요청이 처리 중이면, 큐는 늦은 콜백/연결 끊김/최대 31초 중 먼저 오는 시점까지 다음 작업을 시작하지 않는다.
 */
class BleDeviceEngineImplTimeoutReleaseTest {

    @get:Rule
    val rxRule = RxTestSchedulerRule()

    private val fixture = GattTestFixture()
    private val stuck = fixture.addCharacteristic(STUCK_UUID, BluetoothGattCharacteristic.PROPERTY_READ)
    private val normal = fixture.addCharacteristic(NORMAL_UUID, BluetoothGattCharacteristic.PROPERTY_READ)

    @Before
    fun setUp() {
        every { normal.value } returns byteArrayOf(7)
        fixture.connect()
    }

    private fun advance(millis: Long) = rxRule.testScheduler.advanceTimeBy(millis, TimeUnit.MILLISECONDS)

    private fun timeOutStuckRead() {
        fixture.bleDevice.readCharacteristicSingle(STUCK_UUID).test().also {
            advance(BleDevice.DEFAULT_OPERATION_TIMEOUT_MILLIS)
            it.assertError(TimeoutException::class.java)
        }
    }

    @Test
    fun queuedOperation_waitsUntilLateCallback_thenSucceeds() {
        val stuckRead = fixture.bleDevice.readCharacteristicSingle(STUCK_UUID).test()
        val normalRead = fixture.bleDevice.readCharacteristicSingle(NORMAL_UUID).test()
        advance(BleDevice.DEFAULT_OPERATION_TIMEOUT_MILLIS)
        stuckRead.assertError(TimeoutException::class.java)

        advance(15_000)
        verify(exactly = 0) { fixture.gatt.readCharacteristic(normal) }

        fixture.callback.onCharacteristicRead(fixture.gatt, stuck, BluetoothGatt.GATT_SUCCESS)
        verify(exactly = 1) { fixture.gatt.readCharacteristic(normal) }
        fixture.callback.onCharacteristicRead(fixture.gatt, normal, BluetoothGatt.GATT_SUCCESS)
        normalRead.assertValue { it.contentEquals(byteArrayOf(7)) }
    }

    @Test
    fun operationRequestedAfterTimeout_alsoWaitsForRelease() {
        timeOutStuckRead()

        fixture.bleDevice.readCharacteristicSingle(NORMAL_UUID).test()

        verify(exactly = 0) { fixture.gatt.readCharacteristic(normal) }
        fixture.callback.onCharacteristicRead(fixture.gatt, stuck, BluetoothGatt.GATT_SUCCESS)
        verify(exactly = 1) { fixture.gatt.readCharacteristic(normal) }
    }

    @Test
    fun queuedOperations_failWithDisconnectedException_withoutGattCall_whenLinkDropsWhileWaiting() {
        timeOutStuckRead()
        val normalRead = fixture.bleDevice.readCharacteristicSingle(NORMAL_UUID).test()

        // Android 는 ATT transaction timeout(30초)에 링크를 끊는다
        fixture.disconnectFromRemote()

        normalRead.assertError(BleManager.DisconnectedException::class.java)
        verify(exactly = 0) { fixture.gatt.readCharacteristic(normal) }
    }

    @Test
    fun queue_proceeds_afterMaximumWait_whenNeitherCallbackNorDisconnectArrives() {
        timeOutStuckRead()
        val normalRead = fixture.bleDevice.readCharacteristicSingle(NORMAL_UUID).test()

        advance(31_000 - 1)
        verify(exactly = 0) { fixture.gatt.readCharacteristic(normal) }

        advance(1)
        verify(exactly = 1) { fixture.gatt.readCharacteristic(normal) }
        fixture.callback.onCharacteristicRead(fixture.gatt, normal, BluetoothGatt.GATT_SUCCESS)
        normalRead.assertValue { it.contentEquals(byteArrayOf(7)) }
    }

    @Test
    fun queue_doesNotWait_whenTimedOutOperationNeverSentGattRequest() {
        // 서비스 재탐색을 기다리다 타임아웃된 작업은 GATT 요청을 보내지 않았으므로 BluetoothGatt 는 busy 가 아니다
        fixture.callback.onServiceChanged(fixture.gatt)
        val waitingRead = fixture.bleDevice.readCharacteristicSingle(STUCK_UUID).test()
        advance(BleDevice.DEFAULT_OPERATION_TIMEOUT_MILLIS)
        waitingRead.assertError(TimeoutException::class.java)
        verify(exactly = 0) { fixture.gatt.readCharacteristic(any()) }

        fixture.bleDevice.readCharacteristicSingle(NORMAL_UUID).test()
        fixture.simulateServicesDiscovered()

        verify(exactly = 1) { fixture.gatt.readCharacteristic(normal) }
    }

    @Test
    fun queue_doesNotWait_whenRequestWasRejected() {
        every { fixture.gatt.readCharacteristic(stuck) } returns false
        val rejected = fixture.bleDevice.readCharacteristicSingle(STUCK_UUID).test()
        advance(500L * 5)
        rejected.assertError(BleDeviceEngineImpl.CommunicationFailedException::class.java)

        fixture.bleDevice.readCharacteristicSingle(NORMAL_UUID).test()

        verify(exactly = 1) { fixture.gatt.readCharacteristic(normal) }
    }

    @Test
    fun shortOperationTimeout_nextOperationStartsRightAfterLateCallback_withoutBusyRetry() {
        fixture.bleDevice.operationTimeoutMillis = 1
        fixture.bleDevice.readCharacteristicSingle(STUCK_UUID).test().also {
            advance(1)
            it.assertError(TimeoutException::class.java)
        }
        fixture.bleDevice.operationTimeoutMillis = BleDevice.DEFAULT_OPERATION_TIMEOUT_MILLIS
        val normalRead = fixture.bleDevice.readCharacteristicSingle(NORMAL_UUID).test()

        fixture.callback.onCharacteristicRead(fixture.gatt, stuck, BluetoothGatt.GATT_SUCCESS)
        fixture.callback.onCharacteristicRead(fixture.gatt, normal, BluetoothGatt.GATT_SUCCESS)

        normalRead.assertValue { it.contentEquals(byteArrayOf(7)) }
        verify(exactly = 1) { fixture.gatt.readCharacteristic(normal) }
    }

    companion object {
        private val STUCK_UUID: UUID = UUID.fromString("0000f001-0000-1000-8000-00805f9b34fb")
        private val NORMAL_UUID: UUID = UUID.fromString("0000f002-0000-1000-8000-00805f9b34fb")
    }
}
