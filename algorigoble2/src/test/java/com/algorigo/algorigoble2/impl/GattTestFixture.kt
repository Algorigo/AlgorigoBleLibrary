package com.algorigo.algorigoble2.impl

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothProfile
import android.content.Context
import com.algorigo.algorigoble2.BleDevice
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot

/**
 * [BleDeviceEngineImpl]을 mock [BluetoothDevice]/[BluetoothGatt] 위에서 구동한다.
 * connectGatt 에 전달된 [BluetoothGattCallback]을 캡처해 두므로, 테스트에서 시스템 콜백을 직접 호출할 수 있다.
 */
internal class GattTestFixture {

    val context: Context = mockk(relaxed = true)
    val bluetoothDevice: BluetoothDevice = mockk(relaxed = true)
    val gatt: BluetoothGatt = mockk(relaxed = true)

    private val callbackSlot = slot<BluetoothGattCallback>()
    val callback: BluetoothGattCallback
        get() = callbackSlot.captured

    val engine = BleDeviceEngineImpl(context, bluetoothDevice)
    val bleDevice = BleDevice().also { it.initEngine(engine) }

    init {
        every { bluetoothDevice.address } returns ADDRESS
        every { bluetoothDevice.name } returns NAME
        every { bluetoothDevice.connectGatt(any(), any(), capture(callbackSlot)) } returns gatt
        every { gatt.device } returns bluetoothDevice
    }

    /** connectGatt → STATE_CONNECTED → onServicesDiscovered 순서로 콜백을 보내 CONNECTED 상태로 만든다. */
    fun connect() {
        val observer = engine.connectCompletable(CONNECT_TIMEOUT_MILLIS).test()
        callback.onConnectionStateChange(gatt, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED)
        callback.onServicesDiscovered(gatt, BluetoothGatt.GATT_SUCCESS)
        observer.assertComplete()
    }

    fun disconnectFromRemote() {
        callback.onConnectionStateChange(gatt, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_DISCONNECTED)
    }

    companion object {
        const val ADDRESS = "00:11:22:33:44:55"
        const val NAME = "TestDevice"
        const val CONNECT_TIMEOUT_MILLIS = 10_000L
    }
}
