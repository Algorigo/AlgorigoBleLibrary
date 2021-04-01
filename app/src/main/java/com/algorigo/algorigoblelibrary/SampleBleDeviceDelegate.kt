package com.algorigo.algorigoblelibrary

import android.bluetooth.BluetoothDevice
import com.algorigo.algorigoble.BleDevice
import com.algorigo.algorigoble.BleManager
import com.algorigo.algorigoble.BleScanFilter
import com.algorigo.algorigoble.BleScanSettings

class SampleBleDeviceDelegate: BleManager.BleDeviceDelegate() {
    override fun createBleDevice(bluetoothDevice: BluetoothDevice): BleDevice? {
        return when (bluetoothDevice.name) {
            else -> SampleBleDevice()
        }
    }

    override fun getBleScanSettings(): BleScanSettings {
        return BleScanSettings.Builder().build()
    }

    override fun getBleScanFilters(): Array<BleScanFilter> {
        return arrayOf()
    }
}