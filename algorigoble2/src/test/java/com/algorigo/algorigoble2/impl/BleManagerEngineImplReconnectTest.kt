package com.algorigo.algorigoble2.impl

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanRecord
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import com.algorigo.algorigoble2.BleDevice
import com.algorigo.algorigoble2.BleManager
import com.algorigo.algorigoble2.testutil.RxTestSchedulerRule
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.TimeUnit

/** BleManager 가 기기의 자동 재연결을 이어 가거나(블루투스 켜짐) 멈추는지(close) 확인한다. */
class BleManagerEngineImplReconnectTest {

    @get:Rule
    val rxRule = RxTestSchedulerRule()

    private val context = mockk<Context>(relaxed = true)
    private val bluetoothManager = mockk<BluetoothManager>(relaxed = true)
    private val adapter = mockk<BluetoothAdapter>(relaxed = true)
    private val bluetoothDevice = mockk<BluetoothDevice>(relaxed = true)
    private val receivers = mutableListOf<BroadcastReceiver>()
    private val callback = slot<BluetoothGattCallback>()
    private val gatt = mockk<BluetoothGatt>(relaxed = true)
    private val secondGatt = mockk<BluetoothGatt>(relaxed = true)

    private val delegate = object : BleManager.BleDeviceDelegate() {
        override fun createBleDevice(bluetoothDevice: BluetoothDevice, scanRecord: ScanRecord?) = BleDevice()
    }

    init {
        every { context.getSystemService(Context.BLUETOOTH_SERVICE) } returns bluetoothManager
        every { context.applicationContext } returns context
        every { bluetoothManager.adapter } returns adapter
        every { context.registerReceiver(capture(receivers), any<IntentFilter>()) } returns null
        every { bluetoothDevice.address } returns ADDRESS
        every { adapter.getRemoteDevice(ADDRESS) } returns bluetoothDevice
        every { gatt.discoverServices() } returns true
        every { secondGatt.discoverServices() } returns true
    }

    private fun advance(millis: Long) = rxRule.testScheduler.advanceTimeBy(millis, TimeUnit.MILLISECONDS)

    private fun sendBluetoothState(state: Int) {
        val intent = mockk<Intent>(relaxed = true)
        every { intent.action } returns BluetoothAdapter.ACTION_STATE_CHANGED
        every { intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, any()) } returns state
        receivers.single().onReceive(context, intent)
    }

    private fun connectedDevice(engine: BleManagerEngineImpl): BleDevice {
        val device = engine.getDevice<BleDevice>(ADDRESS)!!
        device.autoReconnect = true
        device.connectCompletable().test()
        callback.captured.onConnectionStateChange(gatt, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED)
        callback.captured.onServicesDiscovered(gatt, BluetoothGatt.GATT_SUCCESS)
        return device
    }

    @Test
    fun bluetoothOn_resumesReconnect() {
        every { bluetoothDevice.connectGatt(any(), any(), capture(callback), any()) } returnsMany listOf(gatt, null, secondGatt)
        val engine = BleManagerEngineImpl(context, delegate)
        val device = connectedDevice(engine)

        sendBluetoothState(BluetoothAdapter.STATE_OFF)
        advance(60_000)
        verify(exactly = 2) { bluetoothDevice.connectGatt(any(), any(), any(), any()) }

        sendBluetoothState(BluetoothAdapter.STATE_ON)
        advance(1_000)
        verify(exactly = 3) { bluetoothDevice.connectGatt(any(), any(), any(), any()) }
        callback.captured.onConnectionStateChange(secondGatt, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED)
        callback.captured.onServicesDiscovered(secondGatt, BluetoothGatt.GATT_SUCCESS)
        assertEquals(BleDevice.ConnectionState.CONNECTED, device.connectionState)
    }

    @Test
    fun close_stopsPendingReconnect() {
        every { bluetoothDevice.connectGatt(any(), any(), capture(callback), any()) } returnsMany listOf(gatt, secondGatt)
        val engine = BleManagerEngineImpl(context, delegate)
        connectedDevice(engine)

        callback.captured.onConnectionStateChange(gatt, REMOTE_USER_TERMINATED, BluetoothProfile.STATE_DISCONNECTED)
        engine.close()
        advance(60_000)

        verify(exactly = 1) { bluetoothDevice.connectGatt(any(), any(), any(), any()) }
    }

    companion object {
        private const val ADDRESS = "00:11:22:33:44:55"
        private const val REMOTE_USER_TERMINATED = 19
    }
}
