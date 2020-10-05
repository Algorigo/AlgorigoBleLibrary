package com.algorigo.algorigoblelibrary

import android.bluetooth.BluetoothDevice
import com.algorigo.algorigoble.BleDevice
import com.algorigo.algorigoble.BleManager
import com.algorigo.algorigoble.BleScanFilter
import com.algorigo.algorigoble.BleScanSettings
import com.algorigo.algorigoblelibrary.bruxweeper.BruxweeperBleDevice
import com.algorigo.algorigoblelibrary.carseat.CarSeatDevice
import com.algorigo.algorigoblelibrary.mldp_terminal.MLDPTerminal
import com.algorigo.algorigoblelibrary.smart_chair.SC01Device
import com.algorigo.algorigoblelibrary.smart_chair.SC20Device
import com.algorigo.algorigoblelibrary.smart_chair.SC21Device
import com.algorigo.algorigoblelibrary.smart_chair.SC30Device

class SampleBleDeviceDelegate: BleManager.BleDeviceDelegate() {
    override fun createBleDevice(bluetoothDevice: BluetoothDevice): BleDevice? {
        return when {
            SC01Device.isMatch(bluetoothDevice) -> SC01Device()
            SC20Device.isMatch(bluetoothDevice) -> SC20Device()
            SC21Device.isMatch(bluetoothDevice) -> SC21Device()
            SC30Device.isMatch(bluetoothDevice) -> SC30Device()
            BruxweeperBleDevice.isMatch(bluetoothDevice) -> BruxweeperBleDevice()
            MLDPTerminal.MAC_ADDRESS.equals(bluetoothDevice.address) -> MLDPTerminal()
            CarSeatDevice.isMatch(bluetoothDevice) -> CarSeatDevice()
            else -> SampleBleDevice()
        }
    }

    override fun getBleScanSettings(): BleScanSettings {
        return BleScanSettings.Builder().build()
    }

    override fun getBleScanFilters(): Array<BleScanFilter> {
        return arrayOf(
            SC01Device.getScanFilter(),
            SC20Device.getScanFilter(),
            SC21Device.getScanFilter(),
            SC30Device.getScanFilter(),
            BruxweeperBleDevice.getScanFilter(),
            MLDPTerminal.getScanFilter(),
            CarSeatDevice.getScanFilter()
        )
    }
}