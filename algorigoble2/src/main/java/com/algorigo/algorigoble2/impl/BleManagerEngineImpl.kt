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
import android.location.LocationManager
import android.os.Build
import androidx.core.location.LocationManagerCompat
import com.algorigo.algorigoble2.*
import com.algorigo.algorigoble2.exception.SystemServiceException
import com.algorigo.algorigoble2.extension.locationManager
import com.algorigo.algorigoble2.rx_util.RxBroadcastReceiver
import com.algorigo.algorigoble2.rx_util.collectListLastSortedIndex
import com.algorigo.algorigoble2.virtual.VirtualDevice
import com.algorigo.algorigoble2.virtual.VirtualDeviceEngine
import io.reactivex.rxjava3.core.Observable
import java.util.*

@SuppressLint("MissingPermission")
internal class BleManagerEngineImpl(
    bleDeviceDelegate: BleManager.BleDeviceDelegate
) : BleManagerEngine(bleDeviceDelegate) {

    private val bluetoothManager: BluetoothManager = applicationContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter = bluetoothManager.adapter

    private val deviceMap: MutableMap<BluetoothDevice, BleDevice> = mutableMapOf()

    private val scanAvailableObservable = scanAvailableObservable()
        .doOnNext { (isLocationEnabled, isBluetoothEnabled) ->
            if (!isLocationEnabled && isBluetoothEnabled) {
                throw SystemServiceException.LocationUnavailableException("System location is unavailable")
            } else if (isLocationEnabled && !isBluetoothEnabled) {
                throw SystemServiceException.BluetoothUnavailableException("System bluetooth is unavailable")
            } else if (!isLocationEnabled && !isBluetoothEnabled) {
                throw SystemServiceException.AllUnavailableException("All system services(bluetooth, location) are unavailable")
            }
        }
        .map {
            it.first && it.second
        }

    private val bluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)) {
                        BluetoothAdapter.STATE_OFF -> {
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
        applicationContext.registerReceiver(
            bluetoothReceiver,
            IntentFilter().apply { addAction(BluetoothAdapter.ACTION_STATE_CHANGED) }
        )
    }

    protected fun finalize() {
        applicationContext.unregisterReceiver(bluetoothReceiver)
    }

    override fun scanObservable(
        scanSettings: BleScanSettings,
        vararg scanFilters: BleScanFilter
    ): Observable<List<Pair<BleDevice, ScanInfo>>> {
        return scanAvailableObservable
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
                device.initEngine(BleDeviceEngineImpl(bluetoothDevice))
                device.getConnectionStateObservable()
                    .subscribe({
                        connectionStateRelay.accept(Pair(device, it))
                    }, {})
            }
    }

    private fun locationEnabledObservable(): Observable<Boolean> = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> Observable.just(true)
        else -> Observable
            .fromCallable {
                LocationManagerCompat.isLocationEnabled(applicationContext.locationManager)
            }
            .concatWith(
                RxBroadcastReceiver
                    .broadCastReceiverObservable(applicationContext, IntentFilter(LocationManager.PROVIDERS_CHANGED_ACTION))
                    .map { intent ->
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            intent.getBooleanExtra(LocationManager.EXTRA_PROVIDER_ENABLED, false)
                        } else {
                            LocationManagerCompat.isLocationEnabled(applicationContext.locationManager)
                        }
                    }
            )
    }

    private fun bluetoothEnabledObservable(): Observable<Boolean> {
        return Observable
            .fromCallable {
                bluetoothAdapter.isEnabled
            }
            .concatWith(RxBroadcastReceiver
                .broadCastReceiverObservable(applicationContext, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
                .map { intent -> intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1) }
                .map { state ->
                    when (state) {
                        BluetoothAdapter.STATE_ON -> true
                        BluetoothAdapter.STATE_TURNING_OFF,
                        BluetoothAdapter.STATE_TURNING_ON,
                        BluetoothAdapter.STATE_OFF -> false
                        else -> throw IllegalStateException("Unexpected bluetooth state: $state")
                    }
                })
    }

    private fun scanAvailableObservable(): Observable<Pair<Boolean, Boolean>> {
        return Observable
            .combineLatest(
                locationEnabledObservable(),
                bluetoothEnabledObservable()
            ) { isLocationEnabled, isBluetoothEnabled ->
                isLocationEnabled to isBluetoothEnabled
            }
    }

    override fun initVirtualDevice(virtualDevice: VirtualDevice, bleDevice: BleDevice): BleDevice {
        return bleDevice.also { device ->
            device.initEngine(VirtualDeviceEngine(virtualDevice))
            device.getConnectionStateObservable()
                .subscribe({
                    connectionStateRelay.accept(Pair(device, it))
                }, {})
        }
    }
}
