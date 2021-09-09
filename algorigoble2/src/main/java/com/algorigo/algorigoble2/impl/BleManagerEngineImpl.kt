package com.algorigo.algorigoble2.impl

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.util.Log
import com.algorigo.algorigoble2.*
import com.algorigo.algorigoble2.BleManagerEngine
import io.reactivex.rxjava3.core.Observable

internal class BleManagerEngineImpl(private val context: Context, bleDeviceDelegate: BleManager.BleDeviceDelegate) : BleManagerEngine(bleDeviceDelegate) {

    private val bluetoothManager: BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter

    init {
        bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter
    }

    override fun scanObservable(
        scanSettings: BleScanSettings,
        vararg scanFilters: BleScanFilter
    ): Observable<List<BleDevice>> {
        if (!bluetoothAdapter.isEnabled) {
            return Observable.error(BleManager.BleNotAvailableException())
        }

        val bleDeviceList = mutableListOf<BleDevice>()
        return BleScanner.scanObservable(bluetoothAdapter, scanSettings, *scanFilters)
            .map {
                getBleDevice(it)?.also {
                    if (!bleDeviceList.contains(it)) {
                        bleDeviceList.add(it)
                    }
                }
                bleDeviceList
            }
    }

    override fun getDevice(macAddress: String): BleDevice? {
        TODO("Not yet implemented")
    }

    override fun getBondedDevice(macAddress: String): BleDevice? {
        TODO("Not yet implemented")
    }

    override fun getBondedDevices(): List<BleDevice> {
        return bluetoothAdapter.bondedDevices.mapNotNull {
            getBleDevice(it)
        }
    }

    override fun getConnectedDevice(macAddress: String): BleDevice? {
        TODO("Not yet implemented")
    }

    override fun getConnectedDevices(): List<BleDevice> {
        return bluetoothManager.getConnectedDevices(BluetoothProfile.GATT).mapNotNull {
            getBleDevice(it)
        }
    }

    override fun getConnectionStateObservable(): Observable<BleManager.ConnectionStateData> {
        TODO("Not yet implemented")
    }

    override fun getBleDevice(bluetoothDevice: BluetoothDevice): BleDevice? {
        return super.getBleDevice(bluetoothDevice)?.apply {
//            (bleDeviceEngine as BleDeviceEngineRxAndroidBle).rxBleDevice = rxBleDevice
//            getConnectionStateObservable()
//                .subscribe({ state ->
//                    connectionStateRelay.accept(ConnectionStateData(this, state))
//                }, {
//                    Log.e(TAG, "", it)
//                })
        }
    }
}