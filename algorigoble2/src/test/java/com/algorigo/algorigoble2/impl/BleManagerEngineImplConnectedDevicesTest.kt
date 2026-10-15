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
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * getConnectedDevices() 가 시스템 전체의 GATT 연결(다른 앱/OS 가 맺은 연결 포함)을 그대로 반환하던 문제(P2) 테스트.
 */
class BleManagerEngineImplConnectedDevicesTest {

    @get:Rule
    val rxRule = RxTestSchedulerRule()

    private val context = mockk<Context>(relaxed = true)
    private val bluetoothManager = mockk<BluetoothManager>(relaxed = true)
    private val adapter = mockk<BluetoothAdapter>(relaxed = true)
    private val systemConnected = mutableListOf<BluetoothDevice>()
    private val created = mutableListOf<String>()

    private val delegate = object : BleManager.BleDeviceDelegate() {
        override fun createBleDevice(bluetoothDevice: BluetoothDevice, scanRecord: ScanRecord?): BleDevice {
            created.add(bluetoothDevice.address)
            return BleDevice()
        }
    }

    init {
        every { context.getSystemService(Context.BLUETOOTH_SERVICE) } returns bluetoothManager
        every { bluetoothManager.adapter } returns adapter
        every { bluetoothManager.getConnectedDevices(BluetoothProfile.GATT) } answers { systemConnected.toList() }
    }

    private val engine = BleManagerEngineImpl(context, delegate)

    private fun mockDevice(address: String) = mockk<BluetoothDevice>(relaxed = true).also {
        every { it.address } returns address
        every { adapter.getRemoteDevice(address) } returns it
    }

    /** 라이브러리로 연결해서 CONNECTED 로 만든다. (시스템 연결 목록에도 추가된다) */
    private fun connectWithLibrary(bluetoothDevice: BluetoothDevice): BleDevice {
        val callback = slot<BluetoothGattCallback>()
        val gatt = mockk<BluetoothGatt>(relaxed = true)
        every { gatt.discoverServices() } returns true
        every { bluetoothDevice.connectGatt(any(), any(), capture(callback), any()) } returns gatt
        val device = engine.getDevice<BleDevice>(bluetoothDevice.address, null)!!
        device.connectCompletable().test()
        callback.captured.onConnectionStateChange(gatt, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED)
        callback.captured.onServicesDiscovered(gatt, BluetoothGatt.GATT_SUCCESS)
        systemConnected.add(bluetoothDevice)
        return device
    }

    @Test
    fun deviceConnectedByAnotherApp_isNotIncluded_andNoBleDeviceIsCreated() {
        systemConnected.add(mockDevice(OTHER_APP_DEVICE))

        assertEquals(listOf<BleDevice>(), engine.getConnectedDevices())
        assertEquals(listOf<String>(), created)
        assertEquals(null, engine.getConnectedDevice(OTHER_APP_DEVICE))
    }

    @Test
    fun onlyDevicesConnectedByThisLibrary_areIncluded() {
        val connected = connectWithLibrary(mockDevice(CONNECTED_DEVICE))
        engine.getDevice<BleDevice>(mockDevice(DISCONNECTED_DEVICE).address, null)
        systemConnected.add(mockDevice(OTHER_APP_DEVICE))

        assertEquals(listOf(connected), engine.getConnectedDevices())
        assertEquals(connected, engine.getConnectedDevice(CONNECTED_DEVICE))
        assertEquals(true, engine.getConnectedDevices().all { it.connected })
    }

    companion object {
        private const val CONNECTED_DEVICE = "00:11:22:33:44:01"
        private const val DISCONNECTED_DEVICE = "00:11:22:33:44:02"
        private const val OTHER_APP_DEVICE = "00:11:22:33:44:03"
    }
}
