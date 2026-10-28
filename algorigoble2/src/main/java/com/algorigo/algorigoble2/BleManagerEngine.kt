package com.algorigo.algorigoble2

import com.algorigo.algorigoble2.virtual.VirtualDevice
import com.jakewharton.rxrelay3.PublishRelay
import io.reactivex.rxjava3.core.Observable
import java.util.logging.Logger

internal abstract class BleManagerEngine(protected val bleDeviceDelegate: BleManager.BleDeviceDelegate) {

    protected val connectionStateRelay = PublishRelay.create<Pair<BleDevice, BleDevice.ConnectionState>>()

    abstract fun scanObservable(scanSettings: BleScanSettings, vararg scanFilters: BleScanFilter): Observable<List<Pair<BleDevice, ScanInfo>>>

    abstract fun getDevices(): Collection<BleDevice>
    abstract fun getBondedDevices(): List<BleDevice>
    abstract fun getConnectedDevices(): List<BleDevice>
    abstract fun initVirtualDevice(deviceId: VirtualDevice, bleDevice: BleDevice): BleDevice

    /** 시스템에 등록한 receiver 등을 해제하고 연결된 기기를 끊는다. 여러 번 불러도 된다. */
    abstract fun close()

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
