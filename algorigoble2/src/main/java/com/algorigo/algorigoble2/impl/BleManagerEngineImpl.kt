package com.algorigo.algorigoble2.impl

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import com.algorigo.algorigoble2.*
import io.reactivex.rxjava3.core.Observable

internal class BleManagerEngineImpl(private val context: Context, bleDeviceDelegate: BleManager.BleDeviceDelegate) : BleManagerEngine(bleDeviceDelegate) {

    private val bluetoothManager: BluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter = bluetoothManager.adapter

    override fun scanObservable(
        scanSettings: BleScanSettings,
        vararg scanFilters: BleScanFilter
    ): Observable<List<BleDevice>> {
        if (!bluetoothAdapter.isEnabled) {
            return Observable.error(BleManager.BleNotAvailableException())
        }

        val bleDeviceList = mutableListOf<BleDevice>()
        return Observable.just(listOf<BleDevice>())
            .concatWith(BleScanner.scanObservable(bluetoothAdapter, scanSettings, *scanFilters)
            .map {
                getBleDevice(it)?.also {
                    if (!bleDeviceList.contains(it)) {
                        bleDeviceList.add(it)
                    }
                }
                bleDeviceList
            })
    }

    override fun getBondedDevice(macAddress: String): BleDevice? {
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

    override fun getBondedDevices(): List<BleDevice> {
        return bluetoothAdapter.bondedDevices.mapNotNull {
            getBleDevice(it)
        }
    }

    override fun getConnectedDevice(macAddress: String): BleDevice? {
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

    override fun getConnectedDevices(): List<BleDevice> {
        return bluetoothManager.getConnectedDevices(BluetoothProfile.GATT).mapNotNull {
            getBleDevice(it)
        }
    }

    override fun createBleDevice(bluetoothDevice: BluetoothDevice): BleDevice? {
        return super.createBleDevice(bluetoothDevice)?.apply {
            initEngine(BleDeviceEngineImpl(context, bluetoothDevice))
        }
    }
}