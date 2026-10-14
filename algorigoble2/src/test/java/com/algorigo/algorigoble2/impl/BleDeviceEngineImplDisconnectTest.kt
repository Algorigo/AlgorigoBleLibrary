package com.algorigo.algorigoble2.impl

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothSocket
import com.algorigo.algorigoble2.BleDevice
import com.algorigo.algorigoble2.BleManager
import com.algorigo.algorigoble2.testutil.RxTestSchedulerRule
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * disconnect() 가 CONNECTED 상태에서만 동작하던 문제(P1) 테스트.
 */
class BleDeviceEngineImplDisconnectTest {

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
    private val characteristic = fixture.addCharacteristic(CHARACTERISTIC_UUID, BluetoothGattCharacteristic.PROPERTY_READ)

    @Test
    fun disconnect_whenConnected_emitsDisconnecting_thenDisconnectedOnCallback() {
        fixture.connect()
        val states = device.getConnectionStateObservable().test()

        device.disconnect()

        verify(exactly = 1) { fixture.gatt.disconnect() }
        verify(exactly = 0) { fixture.gatt.close() }
        assertEquals(BleDevice.ConnectionState.DISCONNECTING, device.connectionState)

        fixture.disconnectFromRemote()

        verify(exactly = 1) { fixture.gatt.close() }
        states.assertValues(
            BleDevice.ConnectionState.CONNECTED,
            BleDevice.ConnectionState.DISCONNECTING,
            BleDevice.ConnectionState.DISCONNECTED,
        )
        assertEquals(1, onDisconnectedCount)
    }

    @Test
    fun disconnect_whenConnected_failsRunningOperationImmediately() {
        fixture.connect()
        val read = device.readCharacteristicSingle(CHARACTERISTIC_UUID).test()

        device.disconnect()

        read.assertError(BleManager.DisconnectedException::class.java)
    }

    @Test
    fun disconnect_whenConnected_forcesCleanup_whenDisconnectCallbackNeverArrives() {
        fixture.connect()

        device.disconnect()
        rxRule.testScheduler.advanceTimeBy(5_000, TimeUnit.MILLISECONDS)

        verify(exactly = 1) { fixture.gatt.close() }
        assertEquals(BleDevice.ConnectionState.DISCONNECTED, device.connectionState)
        assertEquals(1, onDisconnectedCount)
    }

    @Test
    fun disconnect_calledTwice_requestsDisconnectOnce() {
        fixture.connect()

        device.disconnect()
        device.disconnect()

        verify(exactly = 1) { fixture.gatt.disconnect() }
    }

    @Test
    fun disconnect_whenConnecting_cancelsConnection() {
        val connect = fixture.engine.connectCompletable(GattTestFixture.CONNECT_TIMEOUT_MILLIS).test()
        assertEquals(BleDevice.ConnectionState.CONNECTING, device.connectionState)

        device.disconnect()

        connect.assertError(BleManager.DisconnectedException::class.java)
        verify(exactly = 1) { fixture.gatt.disconnect() }
        verify(exactly = 1) { fixture.gatt.close() }
        assertEquals(BleDevice.ConnectionState.DISCONNECTED, device.connectionState)
        assertEquals(1, onDisconnectedCount)
    }

    @Test
    fun disconnect_whenConnecting_afterLinkConnectedButBeforeServiceDiscovery_cancelsConnection() {
        val connect = fixture.engine.connectCompletable(GattTestFixture.CONNECT_TIMEOUT_MILLIS).test()
        fixture.callback.onConnectionStateChange(fixture.gatt, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED)

        device.disconnect()
        // 정리 후에 늦게 도착한 탐색 결과는 무시되어야 한다
        fixture.simulateServicesDiscovered()

        connect.assertError(BleManager.DisconnectedException::class.java)
        assertEquals(BleDevice.ConnectionState.DISCONNECTED, device.connectionState)
    }

    @Test
    fun connectAgain_rightAfterCancellingConnection_succeeds() {
        val retryGatt = fixture.newGatt()
        fixture.stubConnectGatt(fixture.gatt, retryGatt)
        fixture.engine.connectCompletable(GattTestFixture.CONNECT_TIMEOUT_MILLIS).test()
        device.disconnect()

        val retry = fixture.engine.connectCompletable(GattTestFixture.CONNECT_TIMEOUT_MILLIS).test()
        fixture.simulateConnected(retryGatt)

        retry.assertComplete()
    }

    @Test
    fun disconnect_whenDisconnected_doesNothing() {
        device.disconnect()

        verify(exactly = 0) { fixture.gatt.disconnect() }
        assertEquals(BleDevice.ConnectionState.DISCONNECTED, device.connectionState)
        assertEquals(0, onDisconnectedCount)
    }

    @Test
    fun disconnect_whenSppConnected_closesSocket_andEndsSppStream() {
        val socket = mockk<BluetoothSocket>(relaxed = true)
        every { fixture.bluetoothDevice.createRfcommSocketToServiceRecord(SPP_UUID) } returns socket
        val spp = device.connectSppSocket(SPP_UUID).test()
        spp.assertValueCount(1)
        assertEquals(BleDevice.ConnectionState.SPP_CONNECTED, device.connectionState)

        device.disconnect()

        verify { socket.close() }
        spp.assertError(BleManager.DisconnectedException::class.java)
        assertEquals(BleDevice.ConnectionState.DISCONNECTED, device.connectionState)
    }

    @Test
    fun disconnect_whenSppConnecting_closesSocketToAbortConnect() {
        val socket = mockk<BluetoothSocket>(relaxed = true)
        every { fixture.bluetoothDevice.createRfcommSocketToServiceRecord(SPP_UUID) } returns socket
        // connect() 가 진행 중일 때 disconnect() 가 호출되는 상황. 실제 소켓은 close() 되면 connect() 가 IOException 으로 끝난다
        every { socket.connect() } answers {
            device.disconnect()
            throw IOException("socket closed")
        }

        val spp = device.connectSppSocket(SPP_UUID).test()

        verify { socket.close() }
        spp.assertError(IOException::class.java)
        assertEquals(BleDevice.ConnectionState.DISCONNECTED, device.connectionState)
    }

    companion object {
        private val CHARACTERISTIC_UUID: UUID = UUID.fromString("0000c101-0000-1000-8000-00805f9b34fb")
        private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805f9b34fb")
    }
}
