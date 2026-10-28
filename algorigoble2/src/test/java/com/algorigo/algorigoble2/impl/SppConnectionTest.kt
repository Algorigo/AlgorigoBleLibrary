package com.algorigo.algorigoble2.impl

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Context
import com.algorigo.algorigoble2.BleDevice
import com.algorigo.algorigoble2.BleManager
import com.algorigo.algorigoble2.testutil.RxTestSchedulerRule
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.UUID

/**
 * BLE 와 Classic(SPP) 책임 분리(P2) 테스트: SPP 연결이 GATT 상태 머신과 분리된 SppConnection 에서 관리되고,
 * 기기의 공개 연결 상태는 두 상태를 합쳐 이전과 같게 보이는지 확인한다.
 */
class SppConnectionTest {

    @get:Rule
    val rxRule = RxTestSchedulerRule()

    private val bluetoothDevice = mockk<BluetoothDevice>(relaxed = true)
    private val socket = mockk<BluetoothSocket>(relaxed = true)

    init {
        every { bluetoothDevice.createRfcommSocketToServiceRecord(any()) } returns socket
    }

    private fun connection() = SppConnection(mockk<Context>(relaxed = true), bluetoothDevice, Any()) { "test" }

    @Test
    fun connection_hasItsOwnStates() {
        val connection = connection()
        val states = connection.stateObservable.map { it.connectionState }.test()

        val spp = connection.connect(SPP_UUID) {}.test()
        connection.disconnect()

        spp.assertValueCount(1).assertError(BleManager.DisconnectedException::class.java)
        states.assertValues(
            BleDevice.ConnectionState.DISCONNECTED,
            BleDevice.ConnectionState.SPP_CONNECTING,
            BleDevice.ConnectionState.SPP_CONNECTED,
            BleDevice.ConnectionState.DISCONNECTED,
        )
        verify { socket.close() }
    }

    @Test
    fun connect_doesNotCreateSocket_whenCheckFails() {
        val connection = connection()

        connection.connect(SPP_UUID) { throw BleManager.InvalidConnectionStateException(BleDevice.ConnectionState.CONNECTED, BleDevice.ConnectionState.DISCONNECTED) }
            .test()
            .assertError(BleManager.InvalidConnectionStateException::class.java)

        verify(exactly = 0) { bluetoothDevice.createRfcommSocketToServiceRecord(any()) }
        assertTrue(connection.state is SppConnection.State.DISCONNECTED)
    }

    @Test
    fun disconnect_withoutConnection_returnsFalse() {
        assertEquals(false, connection().disconnect())
    }

    @Test
    fun device_reportsSppStates_throughItsSingleConnectionState() {
        val fixture = GattTestFixture()
        every { fixture.bluetoothDevice.createRfcommSocketToServiceRecord(SPP_UUID) } returns socket
        val states = fixture.bleDevice.getConnectionStateObservable().test()

        fixture.bleDevice.connectSppSocket(SPP_UUID).test().assertValueCount(1)
        // SPP 로 연결된 동안 GATT 작업은 GATT 를 건드리지 않고 연결 끊김으로 실패한다
        fixture.bleDevice.readCharacteristicSingle(SPP_UUID).test().assertError(BleManager.DisconnectedException::class.java)
        fixture.bleDevice.disconnect()

        states.assertValues(
            BleDevice.ConnectionState.DISCONNECTED,
            BleDevice.ConnectionState.SPP_CONNECTING,
            BleDevice.ConnectionState.SPP_CONNECTED,
            BleDevice.ConnectionState.DISCONNECTED,
        )
        verify(exactly = 0) { fixture.bluetoothDevice.connectGatt(any(), any(), any(), any()) }
    }

    companion object {
        private val SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805f9b34fb")
    }
}
