package com.algorigo.algorigoblelibrary

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.recyclerview.widget.RecyclerView
import com.algorigo.algorigoble2.BleDevice
import com.algorigo.algorigoble2.BleManager
import com.algorigo.algorigoble2.BleScanFilter
import com.algorigo.algorigoble2.BleScanSettings
import com.algorigo.algorigoble2.ScanInfo
import com.algorigo.algorigoble2.exception.SystemServiceException
import com.algorigo.library.rx.Rx2ServiceBindingFactory
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.disposables.Disposable
import io.reactivex.rxjava3.subjects.PublishSubject
import java.util.concurrent.TimeUnit

class MainActivity : RequestPermissionActivity() {

    private val adapter = BleRecyclerAdapter(object : BleRecyclerAdapter.BleRecyclerListener {
        override fun onSelect(bleDevice: BleDevice) {
            if (bleDevice.connected) {
                Intent(this@MainActivity, DeviceActivity::class.java).apply {
                    putExtra(DeviceActivity.DEVICE_MAC_ADDRESS, bleDevice.deviceId)
                }.also {
                    startActivity(it)
                }
            }
        }

        override fun onBindButton(bleDevice: BleDevice) {
            if (!bleDevice.bonded) {
                bleDevice.bondCompletable()
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe({
                        notifyDataSetChanged()
                    }, {
                        Log.e("!!!", "bondCompletable", it)
                    })
            }
        }

        override fun onConnectButton(bleDevice: BleDevice) {
            when (bleDevice.connectionState) {
                BleDevice.ConnectionState.CONNECTED -> {
                    bleDevice.disconnect()
                }
                BleDevice.ConnectionState.DISCONNECTED -> {
                    bleDevice.connect()
                }
                else -> {}
            }
        }

        override fun onConnectSppButton(bleDevice: BleDevice) {
            if (socketDisposables.containsKey(bleDevice.deviceId)) {
                socketDisposables.remove(bleDevice.deviceId)?.dispose()
            } else {
                socketDisposables[bleDevice.deviceId] = bleDevice.connectSppSocket()
                    .flatMap { socket ->
                        Observable.interval(100, TimeUnit.MILLISECONDS)
                            .flatMapMaybe { socket.readSingle().onErrorComplete { it is NoSuchElementException } }
                    }
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe({
                        Log.e("!!!", "${it.size}:${it.contentToString()}")
                    }, {
                        Log.e("!!!", "", it)
                    })
            }
        }
    })

    private val enableBluetoothRequest =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (it.resultCode == RESULT_OK) {
                bluetoothEnabledSubject.onNext(true)
            } else {
                bluetoothEnabledSubject.onNext(false)
            }
        }

    private val bluetoothEnabledSubject = PublishSubject.create<Boolean>()

    private var disposable: Disposable? = null
    private var connectionStateDisposable: Disposable? = null
    private var connectDisposable: Disposable? = null

    private lateinit var macAddressEditText: EditText
    private lateinit var macAddressButton: Button
    private lateinit var bleRecycler: RecyclerView
    private lateinit var startBtn: Button
    private lateinit var discoveryBtn: Button
    private lateinit var stopBtn: Button
    private lateinit var namedOnlyCheckBox: CheckBox

    // 마지막으로 받은 스캔/검색 목록. '이름 있는 기기만'을 바꾸면 다시 걸러서 보여 준다
    private var foundDevices = listOf<Pair<BleDevice, ScanInfo?>>()

    private val socketDisposables = mutableMapOf<String, Disposable>()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        Intent(this, BluetoothService::class.java).also {
            startService(it)
        }
        initView()
    }

    override fun onResume() {
        super.onResume()
        notifyDataSetChanged()
        connectionStateDisposable = Rx2ServiceBindingFactory.bind<BluetoothService.BluetoothBinder>(this, Intent(this, BluetoothService::class.java))
            .flatMap {
                it.getService().bleManager.getConnectionStateObservable()
            }
            .observeOn(AndroidSchedulers.mainThread())
            .doFinally {
                connectionStateDisposable = null
            }
            .subscribe({
                notifyDataSetChanged()
            }, {
                Log.e(TAG, "it")
                connectionStateDisposable = null
            })
    }

    override fun onPause() {
        super.onPause()
        connectionStateDisposable?.dispose()
    }

    override fun onDestroy() {
        super.onDestroy()
        connectDisposable?.dispose()
    }

    private fun requestGPSEnabledObservable(): Observable<Boolean> {
        return showDialogCompletable(getString(R.string.title_ask_to_enable_gps), getString(R.string.message_ask_to_enable_gps))
            .andThen(openGPSSettingsAndResultObservable())
    }

    private fun requestBluetoothEnabled() {
        enableBluetoothRequest.launch(BleManager.createEnableBluetoothIntent())
    }

    private fun requestBluetoothEnabledObservable(): Observable<Boolean> {
        return Completable
            .fromCallable {
                requestBluetoothEnabled()
            }
            .andThen(bluetoothEnabledSubject)
            .doOnNext {
                if (!it) {
                    error("You must allow the system bluetooth service.")
                }
            }
    }

    private fun requestSystemServicesObservable(): Observable<Boolean> {
        return requestBluetoothEnabledObservable()
            .flatMap {
                requestGPSEnabledObservable()
            }
    }

    private fun initView() {
        setContentView(R.layout.activity_main)
        findViewById<View>(R.id.main).applySystemBarsPadding()

        macAddressEditText = findViewById(R.id.mac_address_edittext)
        macAddressButton = findViewById(R.id.mac_address_btn)
        bleRecycler = findViewById(R.id.ble_recycler)
        startBtn = findViewById(R.id.start_btn)
        discoveryBtn = findViewById(R.id.discovery_btn)
        stopBtn = findViewById(R.id.stop_btn)
        namedOnlyCheckBox = findViewById(R.id.named_only_checkbox)

        macAddressButton.setOnClickListener {
            macAddressEditText.text.toString().also { str ->
                connectDisposable = Rx2ServiceBindingFactory.bind<BluetoothService.BluetoothBinder>(this, Intent(this, BluetoothService::class.java))
                    .map { binder ->
                        // 라이브러리가 대소문자와 구분자 형식을 맞춘다. 형식이 맞지 않으면 null
                        binder.getService().bleManager.getDevice(str)
                            ?: throw IllegalArgumentException("mac address pattern wrong")
                    }
                    .firstOrError()
                    .flatMapCompletable { it.connectCompletable() }
                    // 연결 타임아웃은 computation 스레드에서 오므로 Toast 를 띄우기 전에 main thread 로 옮긴다
                    .observeOn(AndroidSchedulers.mainThread())
                    .doOnSubscribe {
                        macAddressButton.isEnabled = false
                    }
                    .doFinally {
                        macAddressButton.isEnabled = true
                    }
                    .subscribe({
                        Log.e(TAG, "device connected")
                    }, {
                        Log.e(TAG, "", it)
                        Toast.makeText(this, it.localizedMessage ?: it.javaClass.simpleName, Toast.LENGTH_LONG).show()
                    })
            }
        }
        bleRecycler.adapter = adapter
        startBtn.setOnClickListener {
            startScan()
        }
        discoveryBtn.setOnClickListener {
            startDiscovery()
        }
        stopBtn.setOnClickListener {
            disposable?.dispose()
        }
        namedOnlyCheckBox.setOnCheckedChangeListener { _, _ ->
            showDevices(foundDevices)
        }
    }

    @SuppressLint("MissingPermission")
    private fun startScan() {
        disposable?.dispose()
        disposable = requestPermissionCompletable(getPermissions(), true)
            .andThen(Rx2ServiceBindingFactory.bind<BluetoothService.BluetoothBinder>(this, Intent(this, BluetoothService::class.java)))
            .flatMap { binder ->
                binder.getService().bleManager.let { manager ->
                    Observable.combineLatest(
                        Observable.just(manager.getConnectedDevices()),
                        Observable.just(manager.getBondedDevices()),
                        manager.scanObservable(
                            BleScanSettings.Builder().build(),
                            BleScanFilter.Builder().build()
                        )
                    ) { connected, bonded, scanned ->
                        val map = (connected + bonded)
                            .map { it.deviceId to Pair<BleDevice, ScanInfo?>(it, null) }
                            .toMap()
                            .toMutableMap()
                        map.putAll(scanned
                            .map { it.first.deviceId to Pair<BleDevice, ScanInfo?>(it.first, it.second) }
                            .toMap()
                            .toMutableMap())
                        map.values.toList()
                    }
                        .retryWhenSystemServicesEnabled()
//                        .map { devices ->
//                            val pattern = Pattern.compile("Algo")
//                            devices.filter { device ->
//                                device.deviceName?.let {
//                                    pattern.matcher(it).find()
//                                } ?: false
//                            }
//                        }
                }
            }
            .take(1, TimeUnit.MINUTES)
            .observeOn(AndroidSchedulers.mainThread())
            .doOnSubscribe {
                disposable = it
                setSearching(true)
                showDevices(listOf())
            }
            .doFinally {
                setSearching(false)
            }
            .subscribe({
                showDevices(it)
            }, {
                Log.e(TAG, "scan exception", it)
                showError(it)
            })
    }

    /**
     * Classic 기기 검색. BLE 광고를 하지 않는 기기(오래된 SPP 모듈 등)를 찾는다.
     * 라이브러리가 약 12초마다 끝나는 검색을 다시 시작하므로, 스캔처럼 1분 뒤나 Stop 으로 멈춘다.
     */
    private fun startDiscovery() {
        disposable?.dispose()
        disposable = requestPermissionCompletable(getPermissions(), true)
            .andThen(Rx2ServiceBindingFactory.bind<BluetoothService.BluetoothBinder>(this, Intent(this, BluetoothService::class.java)))
            .flatMap { binder ->
                binder.getService().bleManager.discoveryObservable()
                    .retryWhenSystemServicesEnabled()
            }
            .take(1, TimeUnit.MINUTES)
            .observeOn(AndroidSchedulers.mainThread())
            .doOnSubscribe {
                disposable = it
                setSearching(true)
                showDevices(listOf())
            }
            .doFinally {
                setSearching(false)
            }
            .subscribe({ devices ->
                Log.d(TAG, "discovered ${devices.size} devices")
                showDevices(devices)
            }, {
                Log.e(TAG, "discovery exception", it)
                showError(it)
            })
    }

    /** 블루투스나 위치가 꺼져서 끝나면 켜 달라고 요청하고, 켜지면 다시 구독한다. 다른 에러는 그대로 전달한다. */
    private fun <T : Any> Observable<T>.retryWhenSystemServicesEnabled(): Observable<T> = retryWhen { errors ->
        errors
            .doOnNext {
                Log.e(TAG, "Error in retryWhen, $it")
            }
            .observeOn(AndroidSchedulers.mainThread())
            .flatMap {
                when (it) {
                    is SystemServiceException.BluetoothUnavailableException -> requestBluetoothEnabledObservable()
                    is SystemServiceException.LocationUnavailableException -> requestGPSEnabledObservable()
                    is SystemServiceException.AllUnavailableException -> requestSystemServicesObservable()
                    else -> Observable.error(it)
                }
            }
    }

    // 스캔과 검색은 하나만 실행한다
    private fun setSearching(searching: Boolean) {
        startBtn.isEnabled = !searching
        discoveryBtn.isEnabled = !searching
    }

    // 주변에 이름 없는 BLE 기기가 많으면 목록이 길어지므로, 선택하면 이름 있는 기기만 보여 준다
    private fun showDevices(devices: List<Pair<BleDevice, ScanInfo?>>) {
        foundDevices = devices
        adapter.bleDeviceList = if (namedOnlyCheckBox.isChecked) {
            devices.filter { !it.first.deviceName.isNullOrEmpty() }
        } else {
            devices
        }
    }

    private fun showError(throwable: Throwable) {
        Toast.makeText(this, throwable.localizedMessage ?: throwable.javaClass.simpleName, Toast.LENGTH_LONG).show()
    }

    // 이 기기(Android 버전)에서 스캔과 연결에 필요한 권한
    private fun getPermissions(): Array<String> = BleManager.getRequiredPermissions().toTypedArray()

    private fun notifyDataSetChanged() {
        adapter.notifyDataSetChanged()
    }

    companion object {
        private val TAG = MainActivity::class.java.simpleName
    }
}
