package com.algorigo.algorigoble2

import com.algorigo.algorigoble2.logging.Logging
import com.algorigo.algorigoble2.virtual.VirtualDevice
import com.jakewharton.rxrelay3.PublishRelay
import io.reactivex.rxjava3.core.Observable

internal abstract class BleManagerEngine(protected val bleDeviceDelegate: BleManager.BleDeviceDelegate, protected val logging: Logging) {

    protected val connectionStateRelay = PublishRelay.create<Pair<BleDevice, BleDevice.ConnectionState>>()

    abstract fun scanObservable(scanSettings: BleScanSettings, vararg scanFilters: BleScanFilter): Observable<List<BleDevice>>

    abstract fun getDevices(): Collection<BleDevice>
    abstract fun getBondedDevices(): List<BleDevice>
    abstract fun getConnectedDevices(): List<BleDevice>
    abstract fun initVirtualDevice(deviceId: VirtualDevice, bleDevice: BleDevice): BleDevice

    open fun <T : BleDevice> getDevice(macAddress: String, clazz: Class<T>? = null): BleDevice? {
        return getDevices()
            .filter { it.deviceId == macAddress }
            .let {
                return if (it.size == 1) {
                    it.first()
                } else {
                    null
                }
            }
    }

    fun getBondedDevice(macAddress: String): BleDevice? {
        return getBondedDevices()
            .filter { it.deviceId == macAddress }
            .let {
                if (it.size == 1) {
                    it[0]
                } else {
                    null
                }
            }
    }

    fun getConnectedDevice(macAddress: String): BleDevice? {
        return getConnectedDevices()
            .filter { it.deviceId == macAddress }
            .let {
                if (it.size == 1) {
                    it[0]
                } else {
                    null
                }
            }
    }

    fun getConnectionStateObservable(): Observable<Pair<BleDevice, BleDevice.ConnectionState>> {
        return connectionStateRelay
    }
}
