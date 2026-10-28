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
import com.algorigo.algorigoble2.virtual.VirtualDevice
import com.algorigo.algorigoble2.virtual.VirtualDeviceEngine
import com.algorigo.logger.L
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.core.Maybe
import io.reactivex.rxjava3.core.Observable
import java.lang.ref.ReferenceQueue
import java.lang.ref.WeakReference
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.logging.Logger

@SuppressLint("MissingPermission")
internal class BleManagerEngineImpl(private val context: Context, bleDeviceDelegate: BleManager.BleDeviceDelegate) : BleManagerEngine(bleDeviceDelegate) {

    private val bluetoothManager: BluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter = bluetoothManager.adapter

    /**
     * 기기마다 하나의 [BleDevice]를 돌려주기 위한 캐시. 스캔된 모든 기기가 영구히 남지 않도록 기기는 약하게 참조한다.
     * 앱이 참조하는 동안에는 같은 인스턴스가 유지되고, 아무도 참조하지 않는 연결되지 않은 기기는 GC 된다.
     * 연결 중이거나 연결된 기기는 앱이 참조를 버려도 연결을 정리할 수 있도록 [DeviceEntry.active]로 강하게 참조한다.
     */
    private class DeviceEntry(val key: BluetoothDevice, device: BleDevice, queue: ReferenceQueue<BleDevice>) :
        WeakReference<BleDevice>(device, queue) {
        @Volatile
        var active: BleDevice? = null
    }

    // 스캔 콜백(binder thread)과 앱 스레드에서 동시에 접근한다. 생성은 createBleDevice 에서 deviceMap 을 잠그고 한다
    private val deviceMap: MutableMap<BluetoothDevice, DeviceEntry> = ConcurrentHashMap()
    private val releasedDevices = ReferenceQueue<BleDevice>()

    // 위치 provider 변경(API 30 이하)처럼 상태가 그대로인 broadcast 로 같은 값이 다시 나와도 다시 내보내지 않는다
    private val scanAvailabilityObservable = scanAvailabilityObservable(context)
        .distinctUntilChanged()

    // 스캔에 쓴다. 스캔할 수 없는 상태가 되면 그 이유로 에러를 낸다
    private val scanAvailableObservable = scanAvailabilityObservable
        .doOnNext { availability ->
            if (!availability.locationEnabled && availability.bluetoothEnabled) {
                throw SystemServiceException.LocationUnavailableException("System location is unavailable")
            } else if (availability.locationEnabled && !availability.bluetoothEnabled) {
                throw SystemServiceException.BluetoothUnavailableException("System bluetooth is unavailable")
            } else if (!availability.locationEnabled && !availability.bluetoothEnabled) {
                throw SystemServiceException.AllUnavailableException("All system services(bluetooth, location) are unavailable")
            }
        }
        .map { it.available }

    /**
     * 블루투스가 꺼지면 이 엔진의 기기들을 정리한다.
     * 등록된 receiver 는 앱(Context)이 계속 참조하므로, 엔진을 약하게 참조해서 close() 를 부르지 않고 버린 엔진도 GC 되게 한다.
     * 엔진이 GC 된 뒤에 broadcast 를 받으면 스스로 등록을 해제한다.
     */
    private class BluetoothStateReceiver(engine: BleManagerEngineImpl) : BroadcastReceiver() {
        private val engineReference = WeakReference(engine)

        override fun onReceive(context: Context?, intent: Intent?) {
            val engine = engineReference.get()
            if (engine == null) {
                context?.applicationContext?.let { unregister(it, this) }
                return
            }
            if (intent?.action != BluetoothAdapter.ACTION_STATE_CHANGED) {
                return
            }
            when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)) {
                BluetoothAdapter.STATE_OFF -> engine.getDevices()
                    .forEach {
                        (it.engine as? BleDeviceEngineImpl)?.onBluetoothDisabled()
                    }
                // 블루투스가 꺼져서 멈춘 자동 재연결을 다시 시작한다
                BluetoothAdapter.STATE_ON -> engine.getDevices()
                    .forEach { it.notifyBluetoothEnabled() }
            }
        }
    }

    private val bluetoothReceiver = BluetoothStateReceiver(this)
    @Volatile
    private var closed = false

    init {
        context.registerReceiver(
            bluetoothReceiver,
            IntentFilter().apply { addAction(BluetoothAdapter.ACTION_STATE_CHANGED) }
        )
    }

    override fun close() {
        synchronized(this) {
            if (closed) {
                return
            }
            closed = true
        }
        unregister(context, bluetoothReceiver)
        // 블루투스 꺼짐을 더 이상 감지하지 않으므로, 이 엔진이 연결한 기기는 끊는다. (자동 재연결을 기다리는 기기도 멈춘다)
        getDevices()
            .filter { it.connectionState != BleDevice.ConnectionState.DISCONNECTED || it.reconnectPending }
            .forEach { it.disconnect() }
    }

    override fun scanObservable(
        scanSettings: BleScanSettings,
        vararg scanFilters: BleScanFilter
    ): Observable<List<Pair<BleDevice, ScanInfo>>> {
        return Observable.defer {
            // 권한이 없으면 플랫폼의 SecurityException 대신 PermissionDeniedException 으로 바로 실패한다
            BlePermissions.checkScan(context)
            // 스캔 가능 상태가 다시 나오면 이전 스캔을 멈추고 새로 시작한다. (flatMap 이면 두 스캔이 동시에 돌며 목록이 번갈아 전달된다)
            scanAvailableObservable
                .switchMap {
                    BleScanner.scanObservable(bluetoothAdapter, scanSettings, *scanFilters)
                        .collectScanList(scanSettings.deviceLostTimeoutMillis)
                        .startWithItem(listOf())
                }
        }
    }

    /**
     * 발견된 기기를 처음 발견된 순서대로 모아 목록으로 내보낸다. 같은 기기는 최신 [ScanInfo]로 바꾼다.
     * 기기가 사라졌다는 결과(CALLBACK_TYPE_MATCH_LOST)이거나 [deviceLostTimeoutMillis](0 이면 사용 안 함) 동안 다시 보이지 않으면 목록에서 뺀다.
     * 목록이 바뀌지 않으면 내보내지 않는다.
     */
    private fun Observable<BleScanner.ScanEvent>.collectScanList(deviceLostTimeoutMillis: Long): Observable<List<Pair<BleDevice, ScanInfo>>> = Observable.defer {
        // 구독마다 새 목록을 쓴다. 키는 BleDevice.deviceId 와 같은 MAC 주소
        val devices = LinkedHashMap<String, Pair<BleDevice, ScanInfo>>()
        val lastSeenMillis = HashMap<String, Long>()
        // 스캔 결과처럼 main thread 에서 시간을 재고 목록을 내보낸다
        val scheduler = AndroidSchedulers.mainThread()
        val results = map { Optional.of(it) }
        val events = if (deviceLostTimeoutMillis > 0) {
            // 시간이 지나 사라진 기기를 확인하는 신호(빈 값)를 섞는다
            val checkPeriodMillis = minOf(deviceLostTimeoutMillis, DEVICE_LOST_CHECK_PERIOD_MILLIS)
            results.mergeWith(Observable.interval(checkPeriodMillis, TimeUnit.MILLISECONDS, scheduler).map { Optional.empty() })
        } else {
            results
        }
        events.concatMapMaybe { optionalEvent ->
            val now = scheduler.now(TimeUnit.MILLISECONDS)
            val event = optionalEvent.orElse(null)
            if (event == null) {
                val lost = lastSeenMillis.filterValues { now - it >= deviceLostTimeoutMillis }.keys
                if (lost.isEmpty()) {
                    return@concatMapMaybe Maybe.empty()
                }
                lost.forEach {
                    devices.remove(it)
                    lastSeenMillis.remove(it)
                }
                L.debug(Ble.Engine.Default, "scan device lost timeout : $lost")
                return@concatMapMaybe Maybe.just(devices.values.toList())
            }
            val address = event.result.device.address
            if (event.lost) {
                lastSeenMillis.remove(address)
                // 사라진 기기를 위해 BleDevice 를 새로 만들지 않는다
                if (devices.remove(address) == null) {
                    return@concatMapMaybe Maybe.empty()
                }
            } else {
                val device = getBleDevice(event.result.device, event.result.scanRecord)
                    ?: return@concatMapMaybe Maybe.empty()
                devices[address] = Pair(device, ScanInfo(event.result))
                lastSeenMillis[address] = now
            }
            Maybe.just(devices.values.toList())
        }
    }

    override fun getDevices(): Collection<BleDevice> {
        return deviceMap.values.mapNotNull { it.get() }
    }

    override fun getBondedDevices(): List<BleDevice> {
        BlePermissions.checkConnect(context)
        return bluetoothAdapter.bondedDevices.mapNotNull {
            getBleDevice(it)
        }
    }

    override fun getConnectedDevices(): List<BleDevice> {
        // bluetoothManager.getConnectedDevices(GATT) 는 다른 앱/OS 가 맺은 연결까지 포함하므로, 이 라이브러리가 연결한 기기만 돌려준다
        return getDevices().filter {
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
        return createBleDevice(bluetoothDevice, clazz = clazz)
    }

    private fun getBleDevice(bluetoothDevice: BluetoothDevice, scanRecord: ScanRecord? = null): BleDevice? {
        return deviceMap[bluetoothDevice]?.get() ?: (createBleDevice<BleDevice>(bluetoothDevice, scanRecord))
    }

    private fun <T : BleDevice> createBleDevice(bluetoothDevice: BluetoothDevice, scanRecord: ScanRecord? = null, clazz: Class<T>? = null): BleDevice? {
        synchronized(deviceMap) {
            // 잠금을 기다리는 동안 다른 스레드가 같은 기기를 이미 만들었을 수 있다
            deviceMap[bluetoothDevice]?.get()?.let { return it }
            removeReleasedDevices()

            return if (clazz != null) {
                clazz.newInstance()
            } else {
                bleDeviceDelegate.createBleDevice(bluetoothDevice, scanRecord)
            }
                ?.also { device ->
                    // engine 이 붙기 전의 기기가 다른 스레드에 보이지 않도록 초기화를 마친 뒤 map 에 넣는다
                    device.initEngine(BleDeviceEngineImpl(context, bluetoothDevice))
                    val entry = DeviceEntry(bluetoothDevice, device, releasedDevices)
                    subscribeConnectionState(device) { state ->
                        entry.active = if (state == BleDevice.ConnectionState.DISCONNECTED) null else device
                    }
                    deviceMap[bluetoothDevice] = entry
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
                    // EXTRA_PROVIDER_ENABLED 는 바뀐 provider 하나의 상태라서, 다른 provider 가 켜져 있어도 false 일 수 있다.
                    // 위치 사용 가능 여부는 매번 다시 읽는다
                    .map { LocationManagerCompat.isLocationEnabled(context.locationManager) }
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
                    // STATE_ON 이 아니면(꺼지는 중, 켜지는 중, BLE 전용 상태 등) 스캔할 수 없다
                    state == BluetoothAdapter.STATE_ON
                })
    }

    private fun scanAvailabilityObservable(context: Context): Observable<ScanAvailability> {
        return Observable
            .combineLatest(
                locationEnabledObservable(context),
                bluetoothEnabledObservable(context)
            ) { isLocationEnabled, isBluetoothEnabled ->
                ScanAvailability(bluetoothEnabled = isBluetoothEnabled, locationEnabled = isLocationEnabled)
            }
    }

    override val isBluetoothEnabled: Boolean
        get() = bluetoothAdapter.isEnabled

    override fun getScanAvailabilityObservable(): Observable<ScanAvailability> = scanAvailabilityObservable

    override fun initVirtualDevice(virtualDevice: VirtualDevice, bleDevice: BleDevice): BleDevice {
        return bleDevice.also { device ->
            device.initEngine(VirtualDeviceEngine(virtualDevice))
            subscribeConnectionState(device)
        }
    }

    /**
     * 기기의 상태 변화를 [connectionStateRelay]로 전달한다. 처음 상태(DISCONNECTED)는 변화가 아니므로 전달하지 않는다.
     * (기기가 만들어질 때마다 DISCONNECTED 가 전달되면 스캔 중에 의미 없는 이벤트가 쏟아진다)
     */
    private fun subscribeConnectionState(device: BleDevice, onChanged: (BleDevice.ConnectionState) -> Unit = {}) {
        // 구독은 기기 안의 relay 가 들고 있으므로 기기가 GC 되면 함께 사라진다.
        // 기기마다 callback 스레드를 붙잡지 않도록 내부 스트림을 쓰고, 사용자에게는 BleManager 에서 callback 스레드로 넘긴다
        device.connectionStateObservableInternal()
            .skip(1)
            .subscribe({
                onChanged(it)
                connectionStateRelay.accept(Pair(device, it))
            }, {})
    }

    companion object {
        // 시간이 지나 사라진 기기를 확인하는 최대 간격
        private const val DEVICE_LOST_CHECK_PERIOD_MILLIS = 1_000L

        private fun unregister(context: Context, receiver: BroadcastReceiver) {
            try {
                context.unregisterReceiver(receiver)
            } catch (exception: IllegalArgumentException) {
                // 이미 해제된 receiver
            }
        }
    }

    /** GC 된 기기의 항목을 map 에서 지운다. (deviceMap 잠금 안에서 호출) */
    private fun removeReleasedDevices() {
        while (true) {
            val entry = releasedDevices.poll() as? DeviceEntry ?: return
            deviceMap.remove(entry.key, entry)
        }
    }
}
