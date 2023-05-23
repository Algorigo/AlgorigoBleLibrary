package com.algorigo.algorigoble2

import android.bluetooth.BluetoothDevice
import com.algorigo.algorigoble2.impl.BleManagerEngineImpl
import com.algorigo.algorigoble2.virtual.VirtualDevice
import io.reactivex.rxjava3.core.Observable

class BleManager(
    private val delegate: BleDeviceDelegate = defaultBleDeviceDelegate,
    engine: Engine = Engine.ALGORIGO_BLE,
    virtualDevices: Array<Pair<VirtualDevice, BleDevice>> = arrayOf()
) {

    class BondFailedException : Exception()
    class DisconnectedException : Exception()

    enum class Engine {
//        RX_ANDROID_BLE,
        ALGORIGO_BLE,
    }

    abstract class BleDeviceDelegate {

        abstract fun createBleDevice(bluetoothDevice: BluetoothDevice): BleDevice?

        fun getBleScanSettings(): BleScanSettings {
            return BleScanSettings
                .Builder()
                .build()
        }

        fun getBleScanFilters(): Array<BleScanFilter> {
            return arrayOf()
        }
    }

    data class ConnectionStateData(
        val bleDevice: BleDevice,
        val connectionState: BleDevice.ConnectionState,
    )

    private val engine: BleManagerEngine
    private val virtualDevices: Map<String, BleDevice>

    init {
        when (engine) {
//            Engine.RX_ANDROID_BLE -> this.engine = RxAndroidBleEngine(context.applicationContext, delegate)
            Engine.ALGORIGO_BLE -> this.engine = BleManagerEngineImpl(delegate)
        }
        this.virtualDevices = virtualDevices.associate {
            Pair(
                it.first.deviceId,
                this.engine.initVirtualDevice(it.first, it.second.apply { virtual = true })
            )
        }
    }

    fun scanObservable(scanSettings: BleScanSettings, vararg scanFilters: BleScanFilter): Observable<List<Pair<BleDevice, ScanInfo>>> {
        val virtuals = virtualDevices.values.filter { device ->
            scanFilters.isEmpty() ||
                    scanFilters.firstOrNull { it.isOk(device.deviceName, device.deviceId) } != null
        }
        return engine.scanObservable(scanSettings, *scanFilters)
            .map { devices ->
                devices + virtuals.filter { virtual ->
                    devices.firstOrNull { it.first.deviceId == virtual.deviceId } == null
                }.map {
                    Pair(it, ScanInfo(0))
                }
            }
    }

    fun scanObservable(): Observable<List<Pair<BleDevice, ScanInfo>>> {
        return scanObservable(delegate.getBleScanSettings(), *delegate.getBleScanFilters())
    }
    fun getDevice(macAddress: String) = getDeviceInner<BleDevice>(macAddress)
    fun <T : BleDevice> getDevice(macAddress: String, clazz: Class<T>) = getDeviceInner(macAddress, clazz)
    private fun <T : BleDevice> getDeviceInner(macAddress: String, clazz: Class<T>? = null): T? {
        return (engine.getDevice(macAddress, clazz) as? T)
            ?: virtualDevices[macAddress] as? T
    }
    fun getBondedDevice(macAddress: String) = engine.getBondedDevice(macAddress)
        ?: virtualDevices[macAddress]
    fun getBondedDevices() = engine.getBondedDevices()
        .let { devices ->
            devices + virtualDevices.values.filter { virtual ->
                devices.none { it.deviceId == virtual.deviceId }
            }
        }
    fun getConnectedDevice(macAddress: String): BleDevice? {
        return engine.getConnectedDevice(macAddress)
            ?: virtualDevices[macAddress]?.let { if (it.connected) it else null }
    }
    fun getConnectedDevices(): List<BleDevice> {
        return engine.getConnectedDevices()
            .let { devices ->
                devices + virtualDevices.values.filter { virtual ->
                    virtual.connected && devices.none { it.deviceId == virtual.deviceId }
                }
            }
    }
    fun getConnectionStateObservable() = engine.getConnectionStateObservable()

    companion object {
        private val defaultBleDeviceDelegate = object : BleDeviceDelegate() {
            override fun createBleDevice(bluetoothDevice: BluetoothDevice): BleDevice {
                return BleDevice()
            }
        }
    }
}
