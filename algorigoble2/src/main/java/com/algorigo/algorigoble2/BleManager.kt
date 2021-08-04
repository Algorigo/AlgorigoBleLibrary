package com.algorigo.algorigoble2

import android.bluetooth.BluetoothDevice
import android.content.Context
import com.algorigo.algorigoble2.impl.BleManagerEngineImpl
import com.algorigo.algorigoble2.rxandroidble.RxAndroidBleEngine

class BleManager(context: Context, delegate: BleDeviceDelegate, engine: Engine = Engine.RX_ANDROID_BLE) {

    enum class Engine {
        RX_ANDROID_BLE,
        ALGORIGO_BLE,
    }

    abstract class BleDeviceDelegate {
        fun createBleDeviceOuter(bluetoothDevice: BluetoothDevice): BleDevice? {
            return createBleDevice(bluetoothDevice)
        }

        abstract fun createBleDevice(bluetoothDevice: BluetoothDevice): BleDevice?
        abstract fun getBleScanSettings(): BleScanSettings
        abstract fun getBleScanFilters(): Array<BleScanFilter>
    }

    private val defaultBleDeviceDelegate = object : BleDeviceDelegate() {
        override fun createBleDevice(bluetoothDevice: BluetoothDevice): BleDevice {
            return BleDevice()
        }

        override fun getBleScanSettings(): BleScanSettings {
            return BleScanSettings()
        }

        override fun getBleScanFilters(): Array<BleScanFilter> {
            return arrayOf()
        }
    }

    data class ConnectionStateData(val bleDevice: BleDevice, val connectionState: BleDevice.ConnectionState)

    private val engine: BleManagerEngine

    init {
        when (engine) {
            Engine.RX_ANDROID_BLE -> {
                this.engine = RxAndroidBleEngine(context.applicationContext, delegate)
            }
            Engine.ALGORIGO_BLE -> {
                this.engine = BleManagerEngineImpl(context.applicationContext, delegate)
            }
        }
    }

    fun scanObservable(scanDuration: Long, scanSettings: BleScanSettings, vararg scanFilters: BleScanFilter) =
        engine.scanObservable(scanDuration, scanSettings, *scanFilters)
    fun scanObservable(scanSettings: BleScanSettings, vararg scanFilters: BleScanFilter) =
        engine.scanObservable(scanSettings, *scanFilters)
    fun scanObservable(scanDuration: Long) = engine.scanObservable(scanDuration)
    fun scanObservable() = engine.scanObservable()
    fun getDevice(macAddress: String) = engine.getDevice(macAddress)
    fun getBondedDevice(macAddress: String) = engine.getBondedDevice(macAddress)
    fun getBondedDevices() = engine.getBondedDevices()
    fun getConnectedDevice(macAddress: String) = engine.getConnectedDevice(macAddress)
    fun getConnectedDevices() = engine.getConnectedDevices()
    fun getConnectionStateObservable() = engine.getConnectionStateObservable()

}
