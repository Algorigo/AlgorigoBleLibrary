package com.algorigo.algorigoble2.impl

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import com.algorigo.algorigoble2.*
import com.algorigo.algorigoble2.BleManagerEngine
import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.core.Observable

internal class BleManagerEngineImpl(private val context: Context, bleDeviceDelegate: BleManager.BleDeviceDelegate) : BleManagerEngine(bleDeviceDelegate) {

    private val bluetoothManager: BluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter = bluetoothManager.adapter

//    private val broadcastReceiver = object : BroadcastReceiver() {
//        override fun onReceive(context: Context?, intent: Intent?) {
//            val device = intent?.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
//            when (intent?.action) {
//                BluetoothDevice.ACTION_FOUND -> Log.e("!!!", "ACTION_FOUND:${device?.name}")
//                BluetoothDevice.ACTION_BOND_STATE_CHANGED -> Log.e("!!!", "ACTION_BOND_STATE_CHANGED:${device?.name}")
//                BluetoothDevice.ACTION_ACL_CONNECTED -> Log.e("!!!", "ACTION_ACL_CONNECTED:${device?.name}")
//                BluetoothDevice.ACTION_ACL_DISCONNECT_REQUESTED -> Log.e("!!!", "ACTION_ACL_DISCONNECT_REQUESTED:${device?.name}")
//                BluetoothDevice.ACTION_ACL_DISCONNECTED -> Log.e("!!!", "ACTION_ACL_DISCONNECTED:${device?.name}")
//            }
//        }
//    }
//
//    init {
//        IntentFilter().apply {
//            addAction(BluetoothDevice.ACTION_FOUND)
//            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
//            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
//            addAction(BluetoothDevice.ACTION_ACL_DISCONNECT_REQUESTED)
//            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
//        }.also {
//            context.registerReceiver(broadcastReceiver, it)
//        }
//    }
//
//    protected fun finalize() {
//        context.unregisterReceiver(broadcastReceiver)
//    }

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