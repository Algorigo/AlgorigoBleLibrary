package com.algorigo.algorigoble2

import android.bluetooth.BluetoothDevice
import com.jakewharton.rxrelay3.PublishRelay
import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.core.Observable

internal abstract class BleManagerEngine(private val bleDeviceDelegate: BleManager.BleDeviceDelegate) {

    private val deviceMap: MutableMap<BluetoothDevice, BleDevice> = mutableMapOf()
    private val connectionStateRelay = PublishRelay.create<Pair<BleDevice, BleDevice.ConnectionState>>()

    abstract fun scanObservable(scanSettings: BleScanSettings, vararg scanFilters: BleScanFilter): Observable<List<BleDevice>>
    fun scanObservable() = scanObservable(bleDeviceDelegate.getBleScanSettings(), *bleDeviceDelegate.getBleScanFilters())

    fun getDevice(macAddress: String): BleDevice? {
        return deviceMap
            .values
            .filter { it.deviceId == macAddress }
            .let {
                return if (it.size == 1) {
                    it.first()
                } else {
                    null
                }
            }
    }

    abstract fun getBondedDevice(macAddress: String): BleDevice?
    abstract fun getBondedDevices(): List<BleDevice>
    abstract fun getConnectedDevice(macAddress: String): BleDevice?
    abstract fun getConnectedDevices(): List<BleDevice>

    fun getConnectionStateObservable(): Observable<Pair<BleDevice, BleDevice.ConnectionState>> {
        return connectionStateRelay
    }

    protected fun getBleDevice(bluetoothDevice: BluetoothDevice): BleDevice? {
        return deviceMap[bluetoothDevice] ?: (createBleDevice(bluetoothDevice)?.also { device ->
            device.getConnectionStateObservable()
                .subscribe({
                    connectionStateRelay.accept(Pair(device, it))
                }, {})
        })
    }

    protected open fun createBleDevice(bluetoothDevice: BluetoothDevice): BleDevice? {
        return bleDeviceDelegate.createBleDevice(bluetoothDevice)?.also { device ->
            deviceMap[bluetoothDevice] = device
        }
    }
}
