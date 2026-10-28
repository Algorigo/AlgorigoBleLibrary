package com.algorigo.algorigoble2.impl

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.os.ParcelUuid
import com.algorigo.algorigoble2.BleDevice
import com.algorigo.algorigoble2.BleSppSocket
import com.algorigo.algorigoble2.testutil.RxTestSchedulerRule
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.io.IOException
import java.io.InputStream
import java.util.UUID

/**
 * SPP 관련 문제(P2) 테스트: uuids 가 null 이면 NPE, read() 반환 길이 무시, size 검증 오타, 연결 실패 시 소켓 누수, cancelDiscovery 없음.
 */
class SppSocketTest {

    @get:Rule
    val rxRule = RxTestSchedulerRule()

    /** available() 은 [data] 전체, read() 는 한 번에 최대 [chunk] 바이트씩 돌려주는 스트림 */
    private class ChunkedInputStream(private val data: ByteArray, private val chunk: Int) : InputStream() {
        private var position = 0
        override fun available() = data.size - position
        override fun read(): Int = if (position < data.size) data[position++].toInt() and 0xFF else -1
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (position >= data.size) return -1
            val length = minOf(len, chunk, data.size - position)
            System.arraycopy(data, position, b, off, length)
            position += length
            return length
        }
    }

    private fun connectedSocket(input: InputStream): BleSppSocket {
        val bluetoothSocket = mockk<BluetoothSocket>(relaxed = true)
        every { bluetoothSocket.inputStream } returns input
        return BleSppSocket(bluetoothSocket)
    }

    @Test
    fun read_withSize_readsUntilFull_whenReadReturnsLess() {
        val socket = connectedSocket(ChunkedInputStream(byteArrayOf(1, 2, 3, 4), chunk = 2))

        socket.readSingle(4).test().assertValue { it.contentEquals(byteArrayOf(1, 2, 3, 4)) }
    }

    @Test
    fun read_available_returnsOnlyBytesActuallyRead() {
        val socket = connectedSocket(ChunkedInputStream(byteArrayOf(1, 2, 3, 4), chunk = 3))

        socket.readSingle().test().assertValue { it.contentEquals(byteArrayOf(1, 2, 3)) }
    }

    @Test
    fun read_withNegativeSizeOtherThanMinusOne_isRejected() {
        val socket = connectedSocket(ChunkedInputStream(byteArrayOf(1, 2), chunk = 2))

        socket.readSingle(-2).test().assertError(IllegalArgumentException::class.java)
    }

    @Test
    fun constructor_closesSocket_whenConnectFails() {
        val bluetoothSocket = mockk<BluetoothSocket>(relaxed = true)
        every { bluetoothSocket.connect() } throws IOException("read failed")

        val result = runCatching { BleSppSocket(bluetoothSocket) }

        assertEquals(IOException::class.java, result.exceptionOrNull()?.javaClass)
        verify(exactly = 1) { bluetoothSocket.close() }
    }

    private val fixture = GattTestFixture()
    private val device: BleDevice get() = fixture.bleDevice

    @Test
    fun connect_withoutUuid_usesSppUuid_whenDeviceUuidsAreUnknown() {
        every { fixture.bluetoothDevice.uuids } returns null
        val socket = mockk<BluetoothSocket>(relaxed = true)
        every { fixture.bluetoothDevice.createRfcommSocketToServiceRecord(SPP_UUID) } returns socket

        device.connectSppSocket().test().assertValueCount(1)

        verify(exactly = 1) { fixture.bluetoothDevice.createRfcommSocketToServiceRecord(SPP_UUID) }
    }

    @Test
    fun connect_withoutUuid_prefersSpp_amongDeviceUuids() {
        // JVM 테스트용 android.jar 의 ParcelUuid 는 값을 담지 않으므로 mock 으로 만든다
        val parcelUuids = listOf(OTHER_UUID, SPP_UUID).map { uuid -> mockk<ParcelUuid>().also { every { it.uuid } returns uuid } }
        every { fixture.bluetoothDevice.uuids } returns parcelUuids.toTypedArray()
        every { fixture.bluetoothDevice.createRfcommSocketToServiceRecord(any()) } returns mockk(relaxed = true)

        device.connectSppSocket().test().assertValueCount(1)

        verify(exactly = 1) { fixture.bluetoothDevice.createRfcommSocketToServiceRecord(SPP_UUID) }
    }

    @Test
    fun connectFailure_closesSocket_andReturnsToDisconnected() {
        val socket = mockk<BluetoothSocket>(relaxed = true)
        every { socket.connect() } throws IOException("read failed, socket might closed or timeout")
        every { fixture.bluetoothDevice.createRfcommSocketToServiceRecord(SPP_UUID) } returns socket

        device.connectSppSocket(SPP_UUID).test().assertError(IOException::class.java)

        verify(atLeast = 1) { socket.close() }
        assertEquals(BleDevice.ConnectionState.DISCONNECTED, device.connectionState)
    }

    @Test
    fun connect_cancelsDiscovery_beforeConnecting() {
        val bluetoothManager = mockk<BluetoothManager>(relaxed = true)
        val adapter = mockk<BluetoothAdapter>(relaxed = true)
        every { fixture.context.getSystemService(Context.BLUETOOTH_SERVICE) } returns bluetoothManager
        every { bluetoothManager.adapter } returns adapter
        val socket = mockk<BluetoothSocket>(relaxed = true)
        every { fixture.bluetoothDevice.createRfcommSocketToServiceRecord(SPP_UUID) } returns socket

        device.connectSppSocket(SPP_UUID).test().assertValueCount(1)

        verifyOrder {
            adapter.cancelDiscovery()
            socket.connect()
        }
    }

    companion object {
        private val SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805f9b34fb")
        private val OTHER_UUID = UUID.fromString("0000110a-0000-1000-8000-00805f9b34fb")
    }
}
