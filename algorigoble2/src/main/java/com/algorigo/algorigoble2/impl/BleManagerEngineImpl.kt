package com.algorigo.algorigoble2.impl

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.util.Log
import com.algorigo.algorigoble2.*
import com.algorigo.algorigoble2.BleManagerEngine
import io.reactivex.rxjava3.core.Observable

internal class BleManagerEngineImpl(private val context: Context, bleDeviceDelegate: BleManager.BleDeviceDelegate) : BleManagerEngine(bleDeviceDelegate) {

    private val bluetoothAdapter: BluetoothAdapter

    init {
        bluetoothAdapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    }

    override fun scanObservable(
        scanDuration: Long,
        scanSettings: BleScanSettings,
        vararg scanFilters: BleScanFilter
    ): Observable<List<BleDevice>> {
        TODO("Not yet implemented")
    }

    override fun scanObservable(
        scanSettings: BleScanSettings,
        vararg scanFilters: BleScanFilter
    ): Observable<List<BleDevice>> {
        TODO("Not yet implemented")
    }

    override fun scanObservable(scanDuration: Long): Observable<List<BleDevice>> {
        TODO("Not yet implemented")
    }

    override fun scanObservable(): Observable<List<BleDevice>> {
        TODO("Not yet implemented")
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
        TODO("Not yet implemented")
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