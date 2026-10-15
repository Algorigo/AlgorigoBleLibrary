package com.algorigo.algorigoble2.impl

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
 * 블루투스가 꺼졌을 때 CONNECTED 상태인 기기만 정리하던 문제(P2) 테스트.
 */
class BleDeviceEngineImplBluetoothOffTest {

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
    fun bluetoothOff_whenConnected_closesGatt() {
        fixture.connect()

        fixture.engine.onBluetoothDisabled()

        assertEquals(BleDevice.ConnectionState.DISCONNECTED, device.connectionState)
        verify(exactly = 1) { fixture.gatt.close() }
        assertEquals(1, onDisconnectedCount)
    }

    @Test
    fun bluetoothOff_whenConnecting_failsConnectImmediately_andClosesGatt() {
        val connect = fixture.engine.connectCompletable(GattTestFixture.CONNECT_TIMEOUT_MILLIS).test()

        fixture.engine.onBluetoothDisabled()

        // 연결 타임아웃(10초)까지 기다리지 않는다
        connect.assertError(BleManager.ConnectFailedException::class.java)
        assertEquals(BleDevice.ConnectionState.DISCONNECTED, device.connectionState)
        verify(exactly = 1) { fixture.gatt.close() }
        assertEquals(1, onDisconnectedCount)
    }

    @Test
    fun bluetoothOff_whenDisconnecting_closesGattWithoutWaitingForFallback() {
        fixture.connect()
        device.disconnect()

        fixture.engine.onBluetoothDisabled()

        assertEquals(BleDevice.ConnectionState.DISCONNECTED, device.connectionState)
        verify(exactly = 1) { fixture.gatt.close() }
        // disconnect() 의 5초 fallback 이 다시 정리하지 않는다
        rxRule.testScheduler.advanceTimeBy(10, TimeUnit.SECONDS)
        verify(exactly = 1) { fixture.gatt.close() }
        assertEquals(1, onDisconnectedCount)
    }

    @Test
    fun bluetoothOff_whenSppConnected_closesSocket_andEndsSppStream() {
        val socket = mockk<BluetoothSocket>(relaxed = true)
        every { fixture.bluetoothDevice.createRfcommSocketToServiceRecord(SPP_UUID) } returns socket
        val spp = device.connectSppSocket(SPP_UUID).test()
        spp.assertValueCount(1)

        fixture.engine.onBluetoothDisabled()

        verify { socket.close() }
        spp.assertError(BleManager.DisconnectedException::class.java)
        assertEquals(BleDevice.ConnectionState.DISCONNECTED, device.connectionState)
    }

    @Test
    fun bluetoothOff_whenSppConnecting_closesSocketToAbortConnect() {
        val socket = mockk<BluetoothSocket>(relaxed = true)
        every { fixture.bluetoothDevice.createRfcommSocketToServiceRecord(SPP_UUID) } returns socket
        // connect() 가 진행 중일 때 블루투스가 꺼지는 상황. 실제 소켓은 close() 되면 connect() 가 IOException 으로 끝난다
        every { socket.connect() } answers {
            fixture.engine.onBluetoothDisabled()
            throw IOException("socket closed")
        }

        val spp = device.connectSppSocket(SPP_UUID).test()

        verify { socket.close() }
        spp.assertError(IOException::class.java)
        assertEquals(BleDevice.ConnectionState.DISCONNECTED, device.connectionState)
    }

    @Test
    fun bluetoothOff_whenDisconnected_doesNothing() {
        fixture.engine.onBluetoothDisabled()

        assertEquals(BleDevice.ConnectionState.DISCONNECTED, device.connectionState)
        verify(exactly = 0) { fixture.gatt.close() }
        assertEquals(0, onDisconnectedCount)
    }

    companion object {
        private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805f9b34fb")
    }
}
