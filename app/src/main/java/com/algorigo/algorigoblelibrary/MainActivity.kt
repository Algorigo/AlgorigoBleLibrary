package com.algorigo.algorigoblelibrary

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Button
import androidx.recyclerview.widget.RecyclerView
import com.algorigo.algorigoble2.BleDevice
import com.algorigo.algorigoble2.BleScanFilter
import com.algorigo.algorigoble2.BleScanSettings
import com.algorigo.library.rx.Rx2ServiceBindingFactory
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.disposables.Disposable
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

    private var disposable: Disposable? = null
    private var connectionStateDisposable: Disposable? = null

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

    private fun initView() {
        setContentView(R.layout.activity_main)

        bleRecycler = findViewById(R.id.ble_recycler)
        startBtn = findViewById(R.id.start_btn)
        stopBtn = findViewById(R.id.stop_btn)

        bleRecycler.adapter = adapter
        startBtn.setOnClickListener {
            startScan()
        }
        stopBtn.setOnClickListener {
            disposable?.dispose()
        }
    }

    private fun startScan() {
        disposable = requestPermission()
            .andThen(Rx2ServiceBindingFactory.bind<BluetoothService.BluetoothBinder>(this, Intent(this, BluetoothService::class.java)))
            .flatMap { binder ->
                binder.getService().bleManager.let { manager ->
                    Observable.combineLatest(
                        Observable.just(manager.getConnectedDevices()),
                        Observable.just(manager.getBondedDevices()),
                        manager.scanObservable(
                            BleScanSettings.Builder().build(),
                            BleScanFilter.Builder().build()
                        ),
                        { connected, bonded, scanned ->
                            connected + bonded + scanned
                        }
                    )
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
            .take(3, TimeUnit.SECONDS)
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
                Log.e(TAG, "", it)
                startBtn.isEnabled = true
                if (true) {
                    val intent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
                    startActivityForResult(intent, 1)
                }
            }, {
                startBtn.isEnabled = true
            })
    }

    private fun requestPermission(): Completable {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            requestPermissionCompletable(arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
            ), true)
        } else {
            Completable.complete()
        }
    }

    private fun notifyDataSetChanged() {
        adapter.notifyDataSetChanged()
    }

    companion object {
        private val TAG = MainActivity::class.java.simpleName
    }
}