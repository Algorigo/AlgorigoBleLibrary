package com.algorigo.algorigoble.impl

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.util.Log
import com.algorigo.algorigoble.BleDevice
import com.algorigo.algorigoble.BleManager
import com.algorigo.algorigoble.BleScanFilter
import com.algorigo.algorigoble.BleScanSettings
import com.jakewharton.rxrelay3.PublishRelay
import io.reactivex.rxjava3.core.Observable

class BleManagerImpl: BleManager() {

    private lateinit var context: Context
    private lateinit var bluetoothAdapter: BluetoothAdapter
    private var connectionStateRelay = PublishRelay.create<ConnectionStateData>().toSerialized()
    private val bleDeviceMap = mutableMapOf<String, BleDevice>()

    override fun initialize(context: Context) {
        this.context = context
        bluetoothAdapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    }

    override fun scanObservable(scanDuration: Long, scanSettings: BleScanSettings, vararg scanFilters: BleScanFilter): Observable<List<BleDevice>> {
        if (!bluetoothAdapter.isEnabled) {
            return Observable.error(BleManager.BleNotAvailableException())
        }

        val bleDeviceList = mutableListOf<BleDevice>()
        return BleScanner.scanObservable(bluetoothAdapter, scanDuration, scanSettings, *scanFilters)
            .map {
                onBluetoothDeviceFound(it)?.also {
                    if (!bleDeviceList.contains(it)) {
                        bleDeviceList.add(it)
                    }
                }
                bleDeviceList
            }
    }

    override fun scanObservable(scanSettings: BleScanSettings, vararg scanFilters: BleScanFilter): Observable<List<BleDevice>> {
        if (!bluetoothAdapter.isEnabled) {
            return Observable.error(BleManager.BleNotAvailableException())
        }

        val bleDeviceList = mutableListOf<BleDevice>()
        return BleScanner.scanObservable(bluetoothAdapter, scanSettings, *scanFilters)
            .map {
                onBluetoothDeviceFound(it)?.also {
                    if (!bleDeviceList.contains(it)) {
                        bleDeviceList.add(it)
                    }
                }
                bleDeviceList
            }
    }

    override fun scanObservable(scanDuration: Long): Observable<List<BleDevice>> {
        return scanObservable(scanDuration, bleDeviceDelegate.getBleScanSettings(), *bleDeviceDelegate.getBleScanFilters())
    }

    override fun scanObservable(): Observable<List<BleDevice>> {
        return scanObservable(bleDeviceDelegate.getBleScanSettings(), *bleDeviceDelegate.getBleScanFilters())
    }

    override fun getDevice(macAddress: String): BleDevice? {
        return bleDeviceMap.get(macAddress)
    }

    override fun getConnectionStateObservable(): Observable<ConnectionStateData> {
        return connectionStateRelay
    }

    private fun onBluetoothDeviceFound(bluetoothDevice: BluetoothDevice): BleDevice? {
        return onDeviceFound(bluetoothDevice)?.apply {
            bleDeviceMap.put(bluetoothDevice.address, this)
            (bleDeviceEngine as BleDeviceEngineImpl).apply {
                init(context, bluetoothDevice)
            }
            getConnectionStateObservable()
                .subscribe({ state ->
                    connectionStateRelay.accept(ConnectionStateData(this, state))
                }, {
                    Log.e(TAG, "", it)
                })
        }
    }

    companion object {
        private val TAG = BleManagerImpl::class.java.simpleName
    }
}