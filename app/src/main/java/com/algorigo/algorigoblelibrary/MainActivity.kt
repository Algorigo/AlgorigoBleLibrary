package com.algorigo.algorigoblelibrary

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.recyclerview.widget.RecyclerView
import com.algorigo.algorigoble2.BleDevice
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

    private lateinit var macAddressEditText: EditText
    private lateinit var macAddressButton: Button
    private lateinit var bleRecycler: RecyclerView
    private lateinit var startBtn: Button
    private lateinit var stopBtn: Button

    private val socketDisposables = mutableMapOf<String, Disposable>()

    override fun onCreate(savedInstanceState: Bundle?) {
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

    private fun requestGPSEnabledObservable(): Observable<Boolean> {
        return showDialogCompletable(getString(R.string.title_ask_to_enable_gps), getString(R.string.message_ask_to_enable_gps))
            .andThen(openGPSSettingsAndResultObservable())
    }

    private fun requestBluetoothEnabled() {
        val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
        enableBluetoothRequest.launch(enableBtIntent)
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

        macAddressEditText = findViewById(R.id.mac_address_edittext)
        macAddressButton = findViewById(R.id.mac_address_btn)
        bleRecycler = findViewById(R.id.ble_recycler)
        startBtn = findViewById(R.id.start_btn)
        stopBtn = findViewById(R.id.stop_btn)

        macAddressButton.setOnClickListener {
            macAddressEditText.text.toString().also { str ->
                Rx2ServiceBindingFactory.bind<BluetoothService.BluetoothBinder>(this, Intent(this, BluetoothService::class.java))
                    .map { binder ->
                        val macAddress =
                            when {
                                Regex("[0-9A-Fa-f]{12}").matches(str) -> {
                                    str.split(2).joinToString(":")
                                }
                                Regex("[0-9A-Fa-f]{2}:[0-9A-Fa-f]{2}:[0-9A-Fa-f]{2}:[0-9A-Fa-f]{2}:[0-9A-Fa-f]{2}:[0-9A-Fa-f]{2}").matches(str) -> {
                                    str
                                }
                                else -> {
                                    throw IllegalArgumentException("mac address pattern wrong")
                                }
                            }
                        binder.getService().bleManager.getDevice(macAddress)!!
                    }
                    .firstOrError()
                    .flatMapCompletable { it.connectCompletable() }
                    .subscribe({
                        Log.e(TAG, "device connected")
                    }, {
                        Log.e(TAG, "", it)
                        Toast.makeText(this, it.localizedMessage, Toast.LENGTH_LONG).show()
                    })
            }
        }
        bleRecycler.adapter = adapter
        startBtn.setOnClickListener {
            startScan()
        }
        stopBtn.setOnClickListener {
            disposable?.dispose()
        }
    }

    @SuppressLint("MissingPermission")
    private fun startScan() {
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
                        .retryWhen { errors ->
                            errors
                                .doOnNext {
                                    Log.e(TAG, "Error in retryWhen, $it")
                                }
                                .observeOn(AndroidSchedulers.mainThread())
                                .flatMap {
                                    when (it) {
                                        is SystemServiceException.BluetoothUnavailableException -> {
                                            requestBluetoothEnabledObservable()
                                        }

                                        is SystemServiceException.LocationUnavailableException -> {
                                            requestGPSEnabledObservable()
                                        }

                                        is SystemServiceException.AllUnavailableException -> {
                                            requestSystemServicesObservable()
                                        }

                                        else -> {
                                            Observable.empty()
                                        }
                                    }
                                }
                        }
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
                startBtn.isEnabled = false
                adapter.bleDeviceList = listOf()
            }
            .doFinally {
                startBtn.isEnabled = true
            }
            .subscribe({
                adapter.bleDeviceList = it
            }, {
                Log.e(TAG, "scan exception", it)
                startBtn.isEnabled = true
            }, {
                startBtn.isEnabled = true
            })
    }

    private fun getPermissions(): Array<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
            )
        } else {
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            )
        }
    }

    private fun notifyDataSetChanged() {
        adapter.notifyDataSetChanged()
    }

    companion object {
        private val TAG = MainActivity::class.java.simpleName
    }
}

fun String.split(size: Int): Array<String> {
    if (this.count() % size != 0) {
        throw IllegalStateException()
    }
    return Array(this.count() / size) {
        substring(it*2, it*2+2)
    }
}