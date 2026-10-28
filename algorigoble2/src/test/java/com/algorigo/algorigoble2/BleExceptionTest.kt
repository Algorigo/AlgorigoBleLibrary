package com.algorigo.algorigoble2

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import com.algorigo.algorigoble2.exception.SystemServiceException
import com.algorigo.algorigoble2.impl.GattTestFixture
import com.algorigo.algorigoble2.testutil.RxTestSchedulerRule
import io.mockk.every
import io.reactivex.rxjava3.observers.TestObserver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * 예외 계층 정리(P2) 테스트: 라이브러리 예외가 BleException 으로 묶이고, 연결 끊김 사유(status)와 작업 이름이 전달되는지 확인한다.
 */
class BleExceptionTest {

    @get:Rule
    val rxRule = RxTestSchedulerRule()

    private val fixture = GattTestFixture()
    private val characteristic = fixture.addCharacteristic(
        UUID_A, BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY
    )

    private fun TestObserver<*>.singleError(): Throwable {
        var error: Throwable? = null
        assertError { error = it; true }
        return error!!
    }

    @Test
    fun allLibraryExceptions_areBleExceptions() {
        val nested = BleManager::class.java.declaredClasses.filter { Throwable::class.java.isAssignableFrom(it) }
        val all = nested + SystemServiceException::class.java.declaredClasses.toList()

        assertTrue(nested.size >= 15)
        all.forEach { assertTrue("${it.simpleName} must be a BleException", BleException::class.java.isAssignableFrom(it)) }
    }

    @Test
    fun remoteDisconnect_failsOperationAndNotification_withDisconnectStatus() {
        fixture.connect()
        val notifications = fixture.bleDevice.setupNotification(BleDevice.NotificationType.NOTIFICATION, UUID_A).flatMap { it }.test()
        fixture.callback.onDescriptorWrite(fixture.gatt, characteristic.getDescriptor(GattTestFixture.CCCD_UUID), BluetoothGatt.GATT_SUCCESS)
        val read = fixture.bleDevice.readCharacteristicSingle(UUID_A).test()

        fixture.disconnectFromRemote(status = REMOTE_USER_TERMINATED)

        assertEquals(REMOTE_USER_TERMINATED, (read.singleError() as BleManager.DisconnectedException).status)
        assertEquals(REMOTE_USER_TERMINATED, (notifications.singleError() as BleManager.DisconnectedException).status)
    }

    @Test
    fun disconnectWhileConnecting_failsConnect_withDisconnectStatus() {
        val connect = fixture.bleDevice.connectCompletable().test()

        fixture.disconnectFromRemote(status = GATT_ERROR)

        assertEquals(GATT_ERROR, (connect.singleError() as BleManager.DisconnectedException).status)
    }

    @Test
    fun localDisconnect_failsOperation_withoutStatus() {
        fixture.connect()
        val read = fixture.bleDevice.readCharacteristicSingle(UUID_A).test()

        fixture.bleDevice.disconnect()

        assertNull((read.singleError() as BleManager.DisconnectedException).status)
    }

    @Test
    fun bluetoothOff_failsOperation_withBluetoothDisabled_whichIsADisconnect() {
        fixture.connect()
        val read = fixture.bleDevice.readCharacteristicSingle(UUID_A).test()

        fixture.engine.onBluetoothDisabled()

        assertTrue(read.singleError() is BleManager.BluetoothDisabledException)
        read.assertError(BleManager.DisconnectedException::class.java)
    }

    @Test
    fun timeouts_nameTheOperation() {
        val connect = fixture.bleDevice.connectCompletable(3_000).test()
        rxRule.testScheduler.advanceTimeBy(3_000, TimeUnit.MILLISECONDS)
        (connect.singleError() as BleManager.OperationTimeoutException).let {
            assertEquals("CONNECT", it.operation)
            assertEquals(3_000L, it.timeoutMillis)
        }

        fixture.connect()
        val read = fixture.bleDevice.readCharacteristicSingle(UUID_A).test()
        rxRule.testScheduler.advanceTimeBy(BleDevice.DEFAULT_OPERATION_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
        assertEquals("READ_CHARACTERISTIC", (read.singleError() as BleManager.OperationTimeoutException).operation)
    }

    @Test
    fun rejectedGattRequest_failsWithGattRequestRejected_afterRetries() {
        fixture.connect()
        every { fixture.gatt.readCharacteristic(any()) } returns false

        val read = fixture.bleDevice.readCharacteristicSingle(UUID_A).test()
        rxRule.testScheduler.advanceTimeBy(5_000, TimeUnit.MILLISECONDS)

        assertEquals("READ_CHARACTERISTIC", (read.singleError() as BleManager.GattRequestRejectedException).operation)
    }

    @Test
    fun mtuChangeFailure_isAGattOperationFailure() {
        val error: BleManager.GattOperationFailedException = BleManager.MtuChangeFailedException(0x85)

        assertEquals("REQUEST_MTU", error.operation)
        assertEquals(0x85, error.status)
    }

    @Test
    fun connectWhileConnected_failsWithInvalidConnectionState() {
        fixture.connect()

        (fixture.bleDevice.connectCompletable().test().singleError() as BleManager.InvalidConnectionStateException).let {
            assertEquals(BleDevice.ConnectionState.CONNECTED, it.state)
            assertEquals(BleDevice.ConnectionState.DISCONNECTED, it.expected)
        }
    }

    companion object {
        private val UUID_A = UUID.fromString("0000aaa1-0000-1000-8000-aabbccddeeff")
        private const val REMOTE_USER_TERMINATED = 19
        private const val GATT_ERROR = 133
    }
}
