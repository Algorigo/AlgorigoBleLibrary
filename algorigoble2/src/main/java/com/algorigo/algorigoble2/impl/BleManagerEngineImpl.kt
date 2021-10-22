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

    private val deviceMap: MutableMap<BluetoothDevice, BleDevice> = mutableMapOf()

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

    override fun getDevices(): Collection<BleDevice> {
        return deviceMap.values
    }

    override fun getBondedDevices(): List<BleDevice> {
        return bluetoothAdapter.bondedDevices.mapNotNull {
            getBleDevice(it)
        }
    }

    override fun getConnectedDevices(): List<BleDevice> {
        return bluetoothManager.getConnectedDevices(BluetoothProfile.GATT).mapNotNull {
            getBleDevice(it)
        }
    }

    private fun getBleDevice(bluetoothDevice: BluetoothDevice): BleDevice? {
        return deviceMap[bluetoothDevice] ?: (createBleDevice(bluetoothDevice)?.also { device ->
            device.getConnectionStateObservable()
                .subscribe({
                    connectionStateRelay.accept(Pair(device, it))
                }, {})
        })
    }

    private fun createBleDevice(bluetoothDevice: BluetoothDevice): BleDevice? {
        return bleDeviceDelegate.createBleDevice(bluetoothDevice)?.also { device ->
            deviceMap[bluetoothDevice] = device
        }?.apply {
            initEngine(BleDeviceEngineImpl(context, bluetoothDevice))
        }
    }
}
