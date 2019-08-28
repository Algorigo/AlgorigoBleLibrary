package com.algorigo.algorigoble

import android.bluetooth.BluetoothDevice
import android.content.Context
import io.reactivex.Observable

abstract class BleManager {

    class BleNotAvailableException(message: String?, cause: Throwable?) : RuntimeException(message, cause) {
        constructor(): this(null, null)
        constructor(message: String?): this(message, null)
        constructor(cause: Throwable?): this(null, cause)
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
            return BleScanSettings.Builder().build()
        }

        override fun getBleScanFilters(): Array<BleScanFilter> {
            return arrayOf()
        }
    }

    data class ConnectionStateData(val bleDevice: BleDevice, val connectionState: BleDevice.ConnectionState)

    private val deviceMap: MutableMap<BluetoothDevice, BleDevice> = mutableMapOf()
    var bleDeviceDelegate = defaultBleDeviceDelegate

    internal abstract fun initialize(context: Context)
    abstract fun scanObservable(scanDuration: Long, scanSettings: BleScanSettings, vararg scanFilters: BleScanFilter): Observable<List<BleDevice>>
    abstract fun scanObservable(scanSettings: BleScanSettings, vararg scanFilters: BleScanFilter): Observable<List<BleDevice>>
    abstract fun scanObservable(scanDuration: Long): Observable<List<BleDevice>>
    abstract fun scanObservable(): Observable<List<BleDevice>>
    abstract fun getDevice(macAddress: String): BleDevice?
    abstract fun getConnectionStateObservable(): Observable<ConnectionStateData>

    protected fun onDeviceFound(bluetoothDevice: BluetoothDevice): BleDevice? {
        return deviceMap[bluetoothDevice] ?: createBleDevice(bluetoothDevice)
    }

    private fun createBleDevice(bluetoothDevice: BluetoothDevice): BleDevice? {
        return bleDeviceDelegate.createBleDeviceOuter(bluetoothDevice)?.also {
            deviceMap[bluetoothDevice] = it
        }
    }

    fun getConnectedDevices(): List<BleDevice> {
        return deviceMap.values.toList().filter {
            it.connectionState == BleDevice.ConnectionState.CONNECTED
        }
    }

    fun getDevice(bluetoothDevice: BluetoothDevice): BleDevice? {
        return deviceMap[bluetoothDevice]
    }

    companion object {
        private val TAG = BleManager::class.java.simpleName

        private var bleManagerComponenet: BleManagerComponent? = null

        fun init(context: Context) {
            bleManagerComponenet = DaggerBleManagerComponent.builder()
                .bleManagerModule(BleManagerComponent.BleManagerModule(context.applicationContext))
                .build()
        }

        fun getInstance(): BleManager {
            if (bleManagerComponenet != null) {
                return bleManagerComponenet!!.bleManager()
            } else {
                throw IllegalStateException("BleManager is not initialized")
            }
        }

        fun generateDeviceEngine(): BleDeviceEngine {
            if (bleManagerComponenet != null) {
                return bleManagerComponenet!!.bleDeviceEngine()
            } else {
                throw IllegalStateException("BleManager is not initialized")
            }
        }
    }
}