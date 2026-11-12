package com.algorigo.algorigoble2.impl

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanRecord
import android.content.Context
import com.algorigo.algorigoble2.BleDevice
import com.algorigo.algorigoble2.BleManager
import com.algorigo.algorigoble2.testutil.RxTestSchedulerRule
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * 스캔된 기기가 deviceMap 에 영구히 남고, 기기마다 처음 DISCONNECTED 이벤트가 전달되던 문제(P2) 테스트.
 */
class BleManagerEngineImplDeviceReleaseTest {

    @get:Rule
    val rxRule = RxTestSchedulerRule()

    private val context = mockk<Context>(relaxed = true)
    private val bluetoothManager = mockk<BluetoothManager>(relaxed = true)
    private val adapter = mockk<BluetoothAdapter>(relaxed = true)
    private var createdCount = 0

    private val delegate = object : BleManager.BleDeviceDelegate() {
        override fun createBleDevice(bluetoothDevice: BluetoothDevice, scanRecord: ScanRecord?): BleDevice {
            createdCount++
            return BleDevice()
        }
    }

    init {
        every { context.getSystemService(Context.BLUETOOTH_SERVICE) } returns bluetoothManager
        every { bluetoothManager.adapter } returns adapter
    }

    private val engine = BleManagerEngineImpl(context, delegate)

    private fun mockDevice(address: String) = mockk<BluetoothDevice>(relaxed = true).also {
        every { it.address } returns address
        every { adapter.getRemoteDevice(address) } returns it
    }

    /** GC 를 반복해서 [condition]이 참이 될 때까지 기다린다 */
    private fun awaitGc(condition: () -> Boolean): Boolean {
        repeat(50) {
            System.gc()
            if (condition()) return true
            Thread.sleep(20)
        }
        return condition()
    }

    // 별도 함수로 만들어 테스트 코드에 기기 참조가 남지 않게 한다
    private fun createWithoutKeepingReference(address: String) {
        engine.getDevice<BleDevice>(address, null)
    }

    /** 라이브러리로 연결해서 CONNECTED 로 만든 뒤, 테스트용 mock 이 잡고 있는 콜백 참조를 지운다 */
    private fun connectWithoutKeepingReference(bluetoothDevice: BluetoothDevice) {
        val callback = slot<BluetoothGattCallback>()
        val gatt = mockk<BluetoothGatt>(relaxed = true)
        every { gatt.discoverServices() } returns true
        every { bluetoothDevice.connectGatt(any(), any(), capture(callback), any()) } returns gatt
        engine.getDevice<BleDevice>(bluetoothDevice.address, null)!!.connectCompletable().test()
        callback.captured.onConnectionStateChange(gatt, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED)
        callback.captured.onServicesDiscovered(gatt, BluetoothGatt.GATT_SUCCESS)
        // connectGatt stub 과 기록된 호출이 콜백(→ engine → 기기)을 잡고 있으므로 지운다
        val address = bluetoothDevice.address
        clearMocks(bluetoothDevice)
        every { bluetoothDevice.address } returns address
        // TestScheduler 는 취소된 작업(완료된 연결의 타임아웃 10초)도 그 시각까지 큐에 남겨 두고, 그 작업이 기기를 참조한다. 큐를 비운다
        rxRule.testScheduler.advanceTimeBy(GattTestFixture.CONNECT_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
    }

    private fun disconnectWithoutKeepingReference() {
        engine.getConnectedDevices().single().disconnect()
        // 원격 끊김 콜백 대신 disconnect() 의 fallback(5초)으로 DISCONNECTED 가 되게 한다
        rxRule.testScheduler.advanceTimeBy(5, TimeUnit.SECONDS)
    }

    @Test
    fun unreferencedDisconnectedDevice_isReleased() {
        mockDevice(MAC)
        createWithoutKeepingReference(MAC)

        assertTrue("device must be released after GC", awaitGc { engine.getDevices().isEmpty() })
    }

    @Test
    fun referencedDevice_keepsSameInstanceAcrossGc() {
        mockDevice(MAC)
        val device = engine.getDevice<BleDevice>(MAC, null)

        awaitGc { false }

        assertSame(device, engine.getDevice<BleDevice>(MAC, null))
        assertEquals(1, createdCount)
    }

    @Test
    fun connectedDevice_isKeptWithoutAppReference_andReleasedAfterDisconnect() {
        connectWithoutKeepingReference(mockDevice(MAC))

        awaitGc { false }
        assertEquals(1, engine.getConnectedDevices().size)

        disconnectWithoutKeepingReference()
        assertTrue("device must be released after disconnect", awaitGc { engine.getDevices().isEmpty() })
    }

    @Test
    fun initialDisconnectedState_isNotForwarded_butChangesAre() {
        val events = engine.getConnectionStateObservable().test()

        createWithoutKeepingReference(MAC.also { mockDevice(it) })
        events.assertNoValues()

        connectWithoutKeepingReference(mockDevice(OTHER_MAC))
        assertEquals(
            listOf(BleDevice.ConnectionState.CONNECTING, BleDevice.ConnectionState.CONNECTED),
            events.values().map { it.second },
        )
    }

    companion object {
        private const val MAC = "00:11:22:33:44:55"
        private const val OTHER_MAC = "00:11:22:33:44:66"
    }
}
