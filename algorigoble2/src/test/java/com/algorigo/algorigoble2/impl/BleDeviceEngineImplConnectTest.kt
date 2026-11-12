package com.algorigo.algorigoble2.impl

import com.algorigo.algorigoble2.BleDevice
import com.algorigo.algorigoble2.BleManager
import com.algorigo.algorigoble2.testutil.RxTestSchedulerRule
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * connect 실패/취소 후 기기가 CONNECTING 상태에 갇히지 않는지 확인한다.
 */
class BleDeviceEngineImplConnectTest {

    @get:Rule
    val rxRule = RxTestSchedulerRule()

    private var onDisconnectedCount = 0
    private val device = object : BleDevice() {
        override fun onDisconnected() {
            super.onDisconnected()
            onDisconnectedCount++
        }
    }
    private val fixture = GattTestFixture(device)

    @Test
    fun connectCompletable_failsAndStaysDisconnected_whenConnectGattReturnsNull() {
        fixture.stubConnectGatt(null)

        device.connectCompletable().test()
            .assertError(BleManager.BluetoothDisabledException::class.java)

        assertEquals(BleDevice.ConnectionState.DISCONNECTED, device.connectionState)
    }

    @Test
    fun connectCompletable_canConnectAgain_afterConnectGattReturnedNull() {
        fixture.stubConnectGatt(null, fixture.gatt)
        device.connectCompletable().test()
            .assertError(BleManager.BluetoothDisabledException::class.java)

        val observer = device.connectCompletable().test()
        fixture.simulateConnected()

        observer.assertComplete()
        assertEquals(BleDevice.ConnectionState.CONNECTED, device.connectionState)
    }

    @Test
    fun connectCompletable_closesGattAndRestoresState_whenTimedOut() {
        val observer = device.connectCompletable(5_000L).test()
        assertEquals(BleDevice.ConnectionState.CONNECTING, device.connectionState)

        rxRule.testScheduler.advanceTimeBy(5_000L, TimeUnit.MILLISECONDS)

        observer.assertError(BleManager.OperationTimeoutException::class.java)
        verify(exactly = 1) { fixture.gatt.disconnect() }
        verify(exactly = 1) { fixture.gatt.close() }
        assertEquals(BleDevice.ConnectionState.DISCONNECTED, device.connectionState)
        assertEquals(1, onDisconnectedCount)
    }

    @Test
    fun connectCompletable_cancelsConnection_whenDisposedWhileConnecting() {
        val observer = device.connectCompletable().test()
        assertEquals(BleDevice.ConnectionState.CONNECTING, device.connectionState)

        observer.dispose()

        verify(exactly = 1) { fixture.gatt.disconnect() }
        verify(exactly = 1) { fixture.gatt.close() }
        assertEquals(BleDevice.ConnectionState.DISCONNECTED, device.connectionState)
        assertEquals(1, onDisconnectedCount)

        // 취소 후 다시 연결할 수 있어야 한다
        val retryObserver = device.connectCompletable().test()
        fixture.simulateConnected()
        retryObserver.assertComplete()
    }

    @Test
    fun connectCompletable_keepsConnection_whenDisposedAfterConnected() {
        val observer = device.connectCompletable().test()
        fixture.simulateConnected()
        observer.assertComplete()

        observer.dispose()

        verify(exactly = 0) { fixture.gatt.disconnect() }
        verify(exactly = 0) { fixture.gatt.close() }
        assertEquals(BleDevice.ConnectionState.CONNECTED, device.connectionState)
    }
}
