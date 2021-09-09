package com.algorigo.algorigoble2

import android.bluetooth.BluetoothDevice
import io.reactivex.rxjava3.core.Observable

internal abstract class BleManagerEngine(private val bleDeviceDelegate: BleManager.BleDeviceDelegate) {

    private val deviceMap: MutableMap<BluetoothDevice, BleDevice> = mutableMapOf()

    abstract fun scanObservable(scanSettings: BleScanSettings, vararg scanFilters: BleScanFilter): Observable<List<BleDevice>>
    fun scanObservable() = scanObservable(bleDeviceDelegate.getBleScanSettings(), *bleDeviceDelegate.getBleScanFilters())
    abstract fun getDevice(macAddress: String): BleDevice?
    abstract fun getBondedDevice(macAddress: String): BleDevice?
    abstract fun getBondedDevices(): List<BleDevice>
    abstract fun getConnectedDevice(macAddress: String): BleDevice?
    abstract fun getConnectedDevices(): List<BleDevice>
    abstract fun getConnectionStateObservable(): Observable<BleManager.ConnectionStateData>

    protected open fun getBleDevice(bluetoothDevice: BluetoothDevice): BleDevice? {
        return deviceMap[bluetoothDevice] ?: createBleDevice(bluetoothDevice)
    }

    private fun createBleDevice(bluetoothDevice: BluetoothDevice): BleDevice? {
        return bleDeviceDelegate.createBleDeviceOuter(bluetoothDevice)?.also {
            deviceMap[bluetoothDevice] = it
        }
    }
}
