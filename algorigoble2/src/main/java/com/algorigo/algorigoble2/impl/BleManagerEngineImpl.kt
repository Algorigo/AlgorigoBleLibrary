package com.algorigo.algorigoble2.impl

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import com.algorigo.algorigoble2.*
import com.algorigo.algorigoble2.logging.Logging
import com.algorigo.algorigoble2.rx_util.collectListLastSortedIndex
import com.algorigo.algorigoble2.virtual.VirtualDevice
import com.algorigo.algorigoble2.virtual.VirtualDeviceEngine
import com.jakewharton.rxrelay3.BehaviorRelay
import io.reactivex.rxjava3.core.Observable
import java.util.*

@SuppressLint("MissingPermission")
internal class BleManagerEngineImpl(private val context: Context, bleDeviceDelegate: BleManager.BleDeviceDelegate, logging: Logging) : BleManagerEngine(bleDeviceDelegate, logging) {

    private val bluetoothManager: BluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter = bluetoothManager.adapter

    private val deviceMap: MutableMap<BluetoothDevice, BleDevice> = mutableMapOf()

    private val bluetoothStateRelay = BehaviorRelay.create<Boolean>().apply {
        accept(bluetoothAdapter.isEnabled)
    }
    private val bluetoothStateObservable = bluetoothStateRelay
        .doOnNext {
            if (!it) {
                throw BleManager.BleNotAvailableException()
            }
        }
    private val bluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)) {
                        BluetoothAdapter.STATE_ON -> {
                            bluetoothStateRelay.accept(true)
                        }
                        BluetoothAdapter.STATE_OFF -> {
                            bluetoothStateRelay.accept(false)
                            getDevices()
                                .forEach {
                                    (it.engine as? BleDeviceEngineImpl)?.onBluetoothDisabled()
                                }
                        }
                    }
                }
            }
        }
    }

    init {
        context.registerReceiver(
            bluetoothReceiver,
            IntentFilter().apply { addAction(BluetoothAdapter.ACTION_STATE_CHANGED) }
        )
    }

    protected fun finalize() {
        context.unregisterReceiver(bluetoothReceiver)
    }

    override fun scanObservable(
        scanSettings: BleScanSettings,
        vararg scanFilters: BleScanFilter
    ): Observable<List<Pair<BleDevice, ScanInfo>>> {
        return bluetoothStateObservable
            .flatMap {
                Observable.just(listOf<Pair<BleDevice, ScanInfo>>())
                    .concatWith(
                        BleScanner.scanObservable(bluetoothAdapter, scanSettings, *scanFilters)
                            .run {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                                    mapOptional { scanResult ->
                                        getBleDevice(scanResult.device)
                                            ?.let { Optional.of(Pair(it, ScanInfo(scanResult))) }
                                            ?: Optional.empty()
                                    }
                                } else {
                                    map { scanResult ->
                                        listOf(getBleDevice(scanResult.device)?.let { Pair(it, ScanInfo(scanResult)) })
                                    }
                                        .filter { it[0] != null }
                                        .map { it[0]!! }
                                }
                            }
                            .collectListLastSortedIndex {
                                it.first.deviceId
                            }
                    )
            }
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
        } + deviceMap.values.filter { it.connectionState == BleDevice.ConnectionState.SPP_CONNECTED }
    }

    override fun <T : BleDevice> getDevice(macAddress: String, clazz: Class<T>?): BleDevice? {
        val device = super.getDevice(macAddress, clazz)
        if (device != null) {
            return device
        }

        logging.d("getDevice:$macAddress")
        val bluetoothDevice = try {
            bluetoothAdapter.getRemoteDevice(macAddress)
        } catch (e: Exception) {
            logging.e("getRemoteDevice error", e)
            return null
        }
        logging.d("bluetoothDevice:${bluetoothDevice.name}")
        return createBleDevice(bluetoothDevice, clazz)
    }

    private fun getBleDevice(bluetoothDevice: BluetoothDevice): BleDevice? {
        return deviceMap[bluetoothDevice] ?: (createBleDevice<BleDevice>(bluetoothDevice))
    }

    private fun <T : BleDevice> createBleDevice(bluetoothDevice: BluetoothDevice, clazz: Class<T>? = null): BleDevice? {
        return if (clazz != null) {
            clazz.newInstance()
        } else {
            bleDeviceDelegate.createBleDevice(bluetoothDevice)
        }
            ?.also { device ->
                deviceMap[bluetoothDevice] = device
                device.initEngine(BleDeviceEngineImpl(context, bluetoothDevice, logging), logging)
                device.getConnectionStateObservable()
                    .subscribe({
                        connectionStateRelay.accept(Pair(device, it))
                    }, {})
            }
    }

    override fun initVirtualDevice(virtualDevice: VirtualDevice, bleDevice: BleDevice): BleDevice {
        return bleDevice.also { device ->
            device.initEngine(VirtualDeviceEngine(virtualDevice, logging), logging)
            device.getConnectionStateObservable()
                .subscribe({
                    connectionStateRelay.accept(Pair(device, it))
                }, {})
        }
    }
}
