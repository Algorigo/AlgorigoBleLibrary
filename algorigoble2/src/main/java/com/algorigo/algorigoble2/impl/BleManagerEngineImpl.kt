package com.algorigo.algorigoble2.impl

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanRecord
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
import com.algorigo.algorigoble2.logging.Ble
import com.algorigo.algorigoble2.rx_util.RxBroadcastReceiver
import com.algorigo.algorigoble2.rx_util.collectListLastSortedIndex
import com.algorigo.algorigoble2.virtual.VirtualDevice
import com.algorigo.algorigoble2.virtual.VirtualDeviceEngine
import com.algorigo.logger.L
import io.reactivex.rxjava3.core.Observable
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Logger

@SuppressLint("MissingPermission")
internal class BleManagerEngineImpl(private val context: Context, bleDeviceDelegate: BleManager.BleDeviceDelegate) : BleManagerEngine(bleDeviceDelegate) {

    private val bluetoothManager: BluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter = bluetoothManager.adapter

    // 스캔 콜백(binder thread)과 앱 스레드에서 동시에 접근한다. 생성은 createBleDevice 에서 deviceMap 을 잠그고 한다
    private val deviceMap: MutableMap<BluetoothDevice, BleDevice> = ConcurrentHashMap()

    private val scanAvailableObservable = scanAvailableObservable(context)
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
        return scanAvailableObservable
            .flatMap {
                BleScanner.scanObservable(bluetoothAdapter, scanSettings, *scanFilters)
                    .run {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                            mapOptional { scanResult ->
                                getBleDevice(scanResult.device, scanResult.scanRecord)
                                    ?.let { Optional.of(Pair(it, ScanInfo(scanResult))) }
                                    ?: Optional.empty()
                            }
                        } else {
                            map { scanResult ->
                                listOf(
                                    getBleDevice(scanResult.device, scanResult.scanRecord)
                                        ?.let { Pair(it, ScanInfo(scanResult)) }
                                )
                            }
                                .filter { it[0] != null }
                                .map { it[0]!! }
                        }
                    }
                    .collectListLastSortedIndex { it.first.deviceId }
                    .startWithItem(listOf())
            }
    }

    override fun getDevices(): Collection<BleDevice> {
        return deviceMap.values.toList()
    }

    override fun getBondedDevices(): List<BleDevice> {
        return bluetoothAdapter.bondedDevices.mapNotNull {
            getBleDevice(it)
        }
    }

    override fun getConnectedDevices(): List<BleDevice> {
        // bluetoothManager.getConnectedDevices(GATT) 는 다른 앱/OS 가 맺은 연결까지 포함하므로, 이 라이브러리가 연결한 기기만 돌려준다
        return deviceMap.values.filter {
            it.connected || it.connectionState == BleDevice.ConnectionState.SPP_CONNECTED
        }
    }

    override fun <T : BleDevice> getDevice(macAddress: String, clazz: Class<T>?): BleDevice? {
        val device = super.getDevice(macAddress, clazz)
        if (device != null) {
            return device
        }

        L.debug(Ble.Engine.Default, "getDevice:$macAddress")
        val bluetoothDevice = try {
            bluetoothAdapter.getRemoteDevice(macAddress)
        } catch (e: Exception) {
            L.error(Ble.Engine.Default, "getRemoteDevice error", e)
            return null
        }
        L.debug(Ble.Engine.Default, "bluetoothDevice:${bluetoothDevice.name}")
        return createBleDevice(bluetoothDevice, clazz = clazz)
    }

    private fun getBleDevice(bluetoothDevice: BluetoothDevice, scanRecord: ScanRecord? = null): BleDevice? {
        return deviceMap[bluetoothDevice] ?: (createBleDevice<BleDevice>(bluetoothDevice, scanRecord))
    }

    private fun <T : BleDevice> createBleDevice(bluetoothDevice: BluetoothDevice, scanRecord: ScanRecord? = null, clazz: Class<T>? = null): BleDevice? {
        synchronized(deviceMap) {
            // 잠금을 기다리는 동안 다른 스레드가 같은 기기를 이미 만들었을 수 있다
            deviceMap[bluetoothDevice]?.let { return it }

            return if (clazz != null) {
                clazz.newInstance()
            } else {
                bleDeviceDelegate.createBleDevice(bluetoothDevice, scanRecord)
            }
                ?.also { device ->
                    // engine 이 붙기 전의 기기가 다른 스레드에 보이지 않도록 초기화를 마친 뒤 map 에 넣는다
                    device.initEngine(BleDeviceEngineImpl(context, bluetoothDevice))
                    device.getConnectionStateObservable()
                        .subscribe({
                            connectionStateRelay.accept(Pair(device, it))
                        }, {})
                    deviceMap[bluetoothDevice] = device
                }
        }
    }

    private fun locationEnabledObservable(context: Context): Observable<Boolean> = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> Observable.just(true)
        else -> Observable
            .fromCallable {
                LocationManagerCompat.isLocationEnabled(context.locationManager)
            }
            .concatWith(
                RxBroadcastReceiver
                    .broadCastReceiverObservable(context, IntentFilter(LocationManager.PROVIDERS_CHANGED_ACTION))
                    .map { intent ->
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            intent.getBooleanExtra(LocationManager.EXTRA_PROVIDER_ENABLED, false)
                        } else {
                            LocationManagerCompat.isLocationEnabled(context.locationManager)
                        }
                    }
            )
    }

    private fun bluetoothEnabledObservable(context: Context): Observable<Boolean> {
        return Observable
            .fromCallable {
                bluetoothAdapter.isEnabled
            }
            .concatWith(RxBroadcastReceiver
                .broadCastReceiverObservable(context, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
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

    private fun scanAvailableObservable(context: Context): Observable<Pair<Boolean, Boolean>> {
        return Observable
            .combineLatest(
                locationEnabledObservable(context),
                bluetoothEnabledObservable(context)
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
