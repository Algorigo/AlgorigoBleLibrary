package com.algorigo.algorigoble2

import android.bluetooth.BluetoothDevice
import android.content.Context
import com.algorigo.algorigoble2.impl.BleManagerEngineImpl

class BleManager(context: Context, delegate: BleDeviceDelegate = defaultBleDeviceDelegate, engine: Engine = Engine.ALGORIGO_BLE) {

    class BleNotAvailableException: Exception()
    class BondFailedException: Exception()
    class DisconnectedException: Exception()

    enum class Engine {
//        RX_ANDROID_BLE,
        ALGORIGO_BLE,
    }

    abstract class BleDeviceDelegate {

        abstract fun createBleDevice(bluetoothDevice: BluetoothDevice): BleDevice?

        fun getBleScanSettings(): BleScanSettings {
            return BleScanSettings.Builder().build()
        }

        fun getBleScanFilters(): Array<BleScanFilter> {
            return arrayOf()
        }
    }

    data class ConnectionStateData(val bleDevice: BleDevice, val connectionState: BleDevice.ConnectionState)

    private val engine: BleManagerEngine

    init {
        when (engine) {
//            Engine.RX_ANDROID_BLE -> this.engine = RxAndroidBleEngine(context.applicationContext, delegate)
            Engine.ALGORIGO_BLE -> this.engine = BleManagerEngineImpl(context.applicationContext, delegate)
        }
    }

    fun scanObservable(scanSettings: BleScanSettings, vararg scanFilters: BleScanFilter) =
        engine.scanObservable(scanSettings, *scanFilters)
    fun scanObservable() = engine.scanObservable()
    fun getDevice(macAddress: String) = engine.getDevice<BleDevice>(macAddress)
    fun <T : BleDevice> getDevice(macAddress: String, clazz: Class<T>): T? {
        return engine.getDevice(macAddress, clazz) as? T
    }
    fun getBondedDevice(macAddress: String) = engine.getBondedDevice(macAddress)
    fun getBondedDevices() = engine.getBondedDevices()
    fun getConnectedDevice(macAddress: String) = engine.getConnectedDevice(macAddress)
    fun getConnectedDevices() = engine.getConnectedDevices()
    fun getConnectionStateObservable() = engine.getConnectionStateObservable()

    companion object {
        private val defaultBleDeviceDelegate = object : BleDeviceDelegate() {
            override fun createBleDevice(bluetoothDevice: BluetoothDevice): BleDevice {
                return BleDevice()
            }
        }
    }
}
