package com.algorigo.algorigoble2

import android.bluetooth.BluetoothDevice
import android.content.Context
import com.algorigo.algorigoble2.impl.BleManagerEngineImpl
import com.algorigo.algorigoble2.logging.DefaultLogger
import com.algorigo.algorigoble2.logging.Logger
import com.algorigo.algorigoble2.logging.Logging
import com.algorigo.algorigoble2.virtual.VirtualDevice
import io.reactivex.rxjava3.core.Observable

class BleManager(
    context: Context,
    private val delegate: BleDeviceDelegate = defaultBleDeviceDelegate,
    engine: Engine = Engine.ALGORIGO_BLE,
    logger: Logger? = null,
    virtualDevices: Array<Pair<VirtualDevice, BleDevice>> = arrayOf()
) {

    class BleNotAvailableException : Exception()
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
        val logging = if (logger != null) {
            Logging(logger)
        } else {
            Logging(DefaultLogger())
        }
        when (engine) {
//            Engine.RX_ANDROID_BLE -> this.engine = RxAndroidBleEngine(context.applicationContext, delegate)
            Engine.ALGORIGO_BLE -> this.engine = BleManagerEngineImpl(context.applicationContext, delegate, logging)
        }
        this.virtualDevices = virtualDevices.associate {
            Pair(
                it.first.deviceId,
                this.engine.initVirtualDevice(it.first, it.second.apply { virtual = true })
            )
        }
    }

    fun scanObservable(scanSettings: BleScanSettings, vararg scanFilters: BleScanFilter): Observable<List<BleDevice>> {
        val virtuals = virtualDevices.values.filter { device ->
            scanFilters.isEmpty() ||
                    scanFilters.firstOrNull { it.isOk(device.deviceName, device.deviceId) } != null
        }
        return engine.scanObservable(scanSettings, *scanFilters)
            .map { devices ->
                devices + virtuals.filter { virtual ->
                    devices.firstOrNull { it.deviceId == virtual.deviceId } == null
                }
            }
    }

    fun scanObservable(): Observable<List<BleDevice>> {
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
            virtualDevices.values.filter { virtual ->
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
                virtualDevices.values.filter { virtual ->
                    devices.none { it.deviceId == virtual.deviceId && virtual.connected }
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
