package com.algorigo.algorigoble2.impl

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import com.algorigo.algorigoble2.BleDevice
import com.algorigo.algorigoble2.BleManager
import com.algorigo.algorigoble2.InitializableBleDevice
import com.algorigo.algorigoble2.testutil.RxTestSchedulerRule
import io.mockk.verify
import io.reactivex.rxjava3.core.Completable
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * 연결 재시도(connectRetryCount), autoConnect 옵션, 끊겼을 때 자동 재연결(autoReconnect) 테스트.
 */
class AutoReconnectTest {

    @get:Rule
    val rxRule = RxTestSchedulerRule()

    private val fixture = GattTestFixture()
    private val device = fixture.bleDevice

    private fun advance(millis: Long) = rxRule.testScheduler.advanceTimeBy(millis, TimeUnit.MILLISECONDS)

    private fun connectGattCount(count: Int) {
        verify(exactly = count) { fixture.bluetoothDevice.connectGatt(any(), any(), any(), any()) }
    }

    /** BleDevice.connectCompletable 로 [gatt]에 연결한다. (자동 재연결 대상이 된다) */
    private fun connect(gatt: BluetoothGatt = fixture.gatt) {
        val observer = device.connectCompletable().test()
        fixture.simulateConnected(gatt)
        observer.assertComplete()
    }

    @Test
    fun autoConnect_isPassedToConnectGatt() {
        device.autoConnect = true

        device.connectCompletable().test()

        verify(exactly = 1) { fixture.bluetoothDevice.connectGatt(fixture.context, true, any(), BluetoothDevice.TRANSPORT_LE) }
    }

    @Test
    fun connectFailure_isNotRetried_byDefault() {
        val observer = device.connectCompletable().test()
        fixture.disconnectFromRemote(status = GATT_ERROR_133)
        advance(10_000)

        observer.assertError { it is BleManager.DisconnectedException && it.status == GATT_ERROR_133 }
        connectGattCount(1)
    }

    @Test
    fun connectFailure_isRetried_afterDelay() {
        val second = fixture.newGatt()
        fixture.stubConnectGatt(fixture.gatt, second)
        device.connectRetryCount = 2

        val observer = device.connectCompletable().test()
        fixture.disconnectFromRemote(status = GATT_ERROR_133)
        observer.assertNotComplete().assertNoErrors()

        advance(999)
        connectGattCount(1)
        advance(1)
        connectGattCount(2)

        fixture.simulateConnected(second)
        observer.assertComplete()
        assertEquals(BleDevice.ConnectionState.CONNECTED, device.connectionState)
    }

    @Test
    fun connectFailure_failsWithLastError_whenRetriesAreExhausted() {
        val second = fixture.newGatt()
        fixture.stubConnectGatt(fixture.gatt, second)
        device.connectRetryCount = 1

        val observer = device.connectCompletable().test()
        fixture.disconnectFromRemote(status = GATT_ERROR_133)
        advance(1_000)
        fixture.disconnectFromRemote(second, status = 62)

        observer.assertError { it is BleManager.DisconnectedException && it.status == 62 }
        connectGattCount(2)
    }

    // autoConnect 로 본딩하지 않은 RPA 기기에 연결하면 Android 가 바로 status 0x87 로 실패시킨다. 다시 해도 같다
    @Test
    fun illegalParameter_isNotRetried_norReconnected() {
        device.connectRetryCount = 3
        device.autoReconnect = true
        connect()

        fixture.disconnectFromRemote(status = REMOTE_USER_TERMINATED)
        advance(1_000)
        connectGattCount(2)
        fixture.disconnectFromRemote(status = GATT_ILLEGAL_PARAMETER)
        advance(60_000)
        connectGattCount(2)

        device.connectCompletable().test().also { fixture.disconnectFromRemote(status = GATT_ILLEGAL_PARAMETER) }
            .assertError { it is BleManager.DisconnectedException && it.status == GATT_ILLEGAL_PARAMETER }
        advance(60_000)
        connectGattCount(3)
    }

    @Test
    fun timeoutAndBluetoothDisabled_areNotRetried() {
        device.connectRetryCount = 3

        device.connectCompletable(5_000).test().also { advance(5_000) }
            .assertError(BleManager.OperationTimeoutException::class.java)
        connectGattCount(1)

        fixture.stubConnectGatt(null)
        device.connectCompletable().test()
            .assertError(BleManager.BluetoothDisabledException::class.java)
        advance(10_000)
        connectGattCount(2)
    }

    @Test
    fun remoteDisconnect_isNotReconnected_byDefault() {
        connect()

        fixture.disconnectFromRemote(status = REMOTE_USER_TERMINATED)
        advance(60_000)

        connectGattCount(1)
        assertEquals(BleDevice.ConnectionState.DISCONNECTED, device.connectionState)
    }

    @Test
    fun remoteDisconnect_isReconnected_withAutoReconnect() {
        val second = fixture.newGatt()
        fixture.stubConnectGatt(fixture.gatt, second)
        device.autoReconnect = true
        device.autoConnect = true
        connect()
        val states = device.connectionStateObservableInternal().test()

        fixture.disconnectFromRemote(status = REMOTE_USER_TERMINATED)
        advance(999)
        connectGattCount(1)
        advance(1)
        // 다시 연결할 때도 autoConnect 설정을 쓴다
        verify(exactly = 2) { fixture.bluetoothDevice.connectGatt(any(), true, any(), any()) }

        fixture.simulateConnected(second)
        states.assertValues(
            BleDevice.ConnectionState.CONNECTED,
            BleDevice.ConnectionState.DISCONNECTED,
            BleDevice.ConnectionState.CONNECTING,
            BleDevice.ConnectionState.CONNECTED,
        )
    }

    @Test
    fun reconnect_keepsRetrying_untilConnected() {
        val second = fixture.newGatt()
        val third = fixture.newGatt()
        val fourth = fixture.newGatt()
        fixture.stubConnectGatt(fixture.gatt, second, third, fourth)
        device.autoReconnect = true
        connect()

        fixture.disconnectFromRemote(status = CONNECTION_TIMEOUT)
        advance(1_000)
        // 기기가 아직 보이지 않아 연결 타임아웃
        advance(10_000)
        connectGattCount(2)
        advance(1_000)
        // 이번에는 status 133
        fixture.disconnectFromRemote(third, status = GATT_ERROR_133)
        connectGattCount(3)
        advance(1_000)
        connectGattCount(4)

        fixture.simulateConnected(fourth)
        assertEquals(BleDevice.ConnectionState.CONNECTED, device.connectionState)
        advance(60_000)
        connectGattCount(4)
    }

    @Test
    fun disconnect_stopsReconnect_andCancelsAttempt() {
        val second = fixture.newGatt()
        fixture.stubConnectGatt(fixture.gatt, second)
        device.autoReconnect = true
        connect()

        fixture.disconnectFromRemote(status = REMOTE_USER_TERMINATED)
        advance(1_000)
        assertEquals(BleDevice.ConnectionState.CONNECTING, device.connectionState)

        device.disconnect()

        verify { second.close() }
        assertEquals(BleDevice.ConnectionState.DISCONNECTED, device.connectionState)
        advance(60_000)
        connectGattCount(2)
    }

    @Test
    fun userDisconnect_isNotReconnected() {
        device.autoReconnect = true
        connect()

        device.disconnect()
        fixture.disconnectFromRemote()
        advance(60_000)

        connectGattCount(1)
    }

    @Test
    fun disablingAutoReconnect_stopsPendingReconnect() {
        device.autoReconnect = true
        connect()

        fixture.disconnectFromRemote(status = REMOTE_USER_TERMINATED)
        device.autoReconnect = false
        advance(60_000)

        connectGattCount(1)
    }

    @Test
    fun connectWhileAlreadyConnected_keepsAutoReconnect() {
        val second = fixture.newGatt()
        fixture.stubConnectGatt(fixture.gatt, second)
        device.autoReconnect = true
        connect()

        device.connectCompletable().test().assertError(BleManager.InvalidConnectionStateException::class.java)
        fixture.disconnectFromRemote(status = REMOTE_USER_TERMINATED)
        advance(1_000)

        connectGattCount(2)
    }

    @Test
    fun failedConnect_isNotReconnected() {
        device.autoReconnect = true

        device.connectCompletable().test()
        fixture.disconnectFromRemote(status = GATT_ERROR_133)
        advance(60_000)

        connectGattCount(1)
    }

    @Test
    fun bluetoothOff_pausesReconnect_untilBluetoothIsEnabled() {
        val second = fixture.newGatt()
        fixture.stubConnectGatt(fixture.gatt, null, second)
        device.autoReconnect = true
        connect()

        fixture.engine.onBluetoothDisabled()
        advance(1_000)
        // 블루투스가 꺼져 있어 connectGatt 가 null 을 반환한다. 다시 켜질 때까지 시도하지 않는다
        advance(60_000)
        connectGattCount(2)

        device.notifyBluetoothEnabled()
        advance(1_000)
        connectGattCount(3)
        fixture.simulateConnected(second)
        assertEquals(BleDevice.ConnectionState.CONNECTED, device.connectionState)
    }

    @Test
    fun initializableDevice_isInitializedAgain_onReconnect() {
        val initializable = object : InitializableBleDevice() {
            var initializeCount = 0
            override fun initializeCompletable(): Completable = Completable.fromAction { initializeCount++ }
        }
        val fixture = GattTestFixture(initializable)
        val second = fixture.newGatt()
        fixture.stubConnectGatt(fixture.gatt, second)
        initializable.autoReconnect = true
        initializable.connectCompletable().test().also { fixture.simulateConnected() }.assertComplete()

        fixture.disconnectFromRemote(status = REMOTE_USER_TERMINATED)
        advance(1_000)
        fixture.simulateConnected(second)

        assertEquals(2, initializable.initializeCount)
        assertEquals(BleDevice.ConnectionState.CONNECTED, initializable.connectionState)
    }

    @Test
    fun initializeFailure_isRetried_withConnectRetry() {
        val initializable = object : InitializableBleDevice() {
            var initializeCount = 0
            override fun initializeCompletable(): Completable = Completable.defer {
                if (initializeCount++ == 0) Completable.error(BleManager.DisconnectedException(8)) else Completable.complete()
            }
        }
        val fixture = GattTestFixture(initializable)
        val second = fixture.newGatt()
        fixture.stubConnectGatt(fixture.gatt, second)
        initializable.connectRetryCount = 1

        val observer = initializable.connectCompletable().test()
        fixture.simulateConnected()
        // 초기화에 실패하면 연결을 끊는다. 끊긴 뒤에 다시 연결한다
        verify { fixture.gatt.disconnect() }
        fixture.disconnectFromRemote()
        advance(1_000)
        fixture.simulateConnected(second)

        observer.assertComplete()
        assertEquals(2, initializable.initializeCount)
    }

    @Test
    fun discoverServicesFailure_isRetried_butInitializeGattFailure_isNot() {
        val second = fixture.newGatt()
        val third = fixture.newGatt()
        fixture.stubConnectGatt(fixture.gatt, second, third)
        device.connectRetryCount = 1

        val observer = device.connectCompletable().test()
        fixture.callback.onConnectionStateChange(fixture.gatt, BluetoothGatt.GATT_SUCCESS, android.bluetooth.BluetoothProfile.STATE_CONNECTED)
        fixture.simulateServicesDiscovered(status = BluetoothGatt.GATT_FAILURE)
        advance(1_000)
        fixture.simulateConnected(second)
        observer.assertComplete()
        connectGattCount(2)

        val initializable = object : InitializableBleDevice() {
            override fun initializeCompletable(): Completable =
                Completable.error(BleManager.GattOperationFailedException("READ_CHARACTERISTIC", GATT_INSUFFICIENT_AUTHENTICATION))
        }
        val initFixture = GattTestFixture(initializable)
        initializable.connectRetryCount = 3
        val initObserver = initializable.connectCompletable().test()
        initFixture.simulateConnected()
        initFixture.disconnectFromRemote()
        advance(60_000)

        initObserver.assertError { it is BleManager.GattOperationFailedException && it.status == GATT_INSUFFICIENT_AUTHENTICATION }
        verify(exactly = 1) { initFixture.bluetoothDevice.connectGatt(any(), any(), any(), any()) }
    }

    companion object {
        private const val GATT_INSUFFICIENT_AUTHENTICATION = 5
        private const val GATT_ERROR_133 = 133
        private const val CONNECTION_TIMEOUT = 8
        private const val REMOTE_USER_TERMINATED = 19
        private const val GATT_ILLEGAL_PARAMETER = 0x87
    }
}
