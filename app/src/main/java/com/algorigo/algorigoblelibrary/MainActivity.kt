package com.algorigo.algorigoblelibrary

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Button
import androidx.recyclerview.widget.RecyclerView
import com.algorigo.algorigoble2.BleDevice
import com.algorigo.library.rx.Rx2ServiceBindingFactory
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
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
    })

    private var disposable: Disposable? = null
    private var connectionStateDisposable: Disposable? = null

    private lateinit var bleRecycler: RecyclerView
    private lateinit var startBtn: Button
    private lateinit var stopBtn: Button

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
        disposable = requestPermissionCompletable(getPermissionsToRequest(), true)
            .andThen(Rx2ServiceBindingFactory.bind<BluetoothService.BluetoothBinder>(this, Intent(this, BluetoothService::class.java)))
            .flatMap { binder ->
                binder.getService().bleManager.let { manager ->
                    Observable.combineLatest(
                        Observable.interval(1, TimeUnit.SECONDS).map { manager.getConnectedDevices() },
                        manager.scanObservable(),
                        { connected, scanned ->
                            connected + scanned
                        }
                    )
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

    private fun notifyDataSetChanged() {
        adapter.notifyDataSetChanged()
    }

    companion object {
        private val TAG = MainActivity::class.java.simpleName

        fun getPermissionsToRequest() = arrayOf(
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_FINE_LOCATION
        )
    }
}