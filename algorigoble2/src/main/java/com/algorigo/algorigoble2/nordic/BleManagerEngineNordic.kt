package com.algorigo.algorigoble2.nordic

import android.bluetooth.BluetoothDevice
import android.content.Context
import com.algorigo.algorigoble2.BleDevice
import com.algorigo.algorigoble2.BleManager
import com.algorigo.algorigoble2.impl.BleManagerEngineImpl

internal class BleManagerEngineNordic(context: Context, bleDeviceDelegate: BleManager.BleDeviceDelegate) : BleManagerEngineImpl(context, bleDeviceDelegate) {

    override fun createBleDevice(bluetoothDevice: BluetoothDevice): BleDevice? {
        return bleDeviceDelegate.createBleDevice(bluetoothDevice)?.also { device ->
            deviceMap[bluetoothDevice] = device
        }?.apply {
            initEngine(BleDeviceEngineNordic(context, bluetoothDevice))
        }
    }
}
