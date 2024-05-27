package com.algorigo.algorigoblelibrary

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.RecyclerView
import com.algorigo.algorigoble2.BleDevice
import com.algorigo.library.rx.Rx2ServiceBindingFactory
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.disposables.Disposable

class TestActivity : RequestPermissionActivity() {

    private val bleDeviceAdapter = BleRecyclerAdapter(object : BleRecyclerAdapter.BleRecyclerListener {
        override fun onSelect(bleDevice: BleDevice) {
            // Do something
        }

        override fun onBindButton(bleDevice: BleDevice) {
            // Do something
        }

        override fun onConnectButton(bleDevice: BleDevice) {
            // Do something
        }

        override fun onConnectSppButton(bleDevice: BleDevice) {
            // Do something
        }
    })

    private val testDeviceAdapter = BleRecyclerAdapter(object : BleRecyclerAdapter.BleRecyclerListener {
        override fun onSelect(testDevice: BleDevice) {
            // Do something
        }

        override fun onBindButton(testDevice: BleDevice) {
            // Do something
        }

        override fun onConnectButton(testDevice: BleDevice) {
            // Do something
        }

        override fun onConnectSppButton(testDevice: BleDevice) {
            // Do something
        }
    })

    private var disposable: Disposable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_test)

        findViewById<RecyclerView>(R.id.ble_device_recycler).adapter = bleDeviceAdapter
        findViewById<RecyclerView>(R.id.test_device_recycler).adapter = testDeviceAdapter
    }

    override fun onResume() {
        super.onResume()
        disposable = requestPermissionCompletable(getPermissions())
            .andThen(Rx2ServiceBindingFactory.bind<BluetoothService.BluetoothBinder>(this, Intent(this, BluetoothService::class.java)))
            .flatMapCompletable { binder ->
                Completable.merge(listOf(
                    binder.getService().bleManager.scanObservable()
                        .observeOn(AndroidSchedulers.mainThread())
                        .doOnNext {
                            bleDeviceAdapter.bleDeviceList = it
                            bleDeviceAdapter.notifyDataSetChanged()
                        }
                        .ignoreElements(),
                    binder.getService().bleManager2.scanObservable()
                        .observeOn(AndroidSchedulers.mainThread())
                        .doOnNext {
                            testDeviceAdapter.bleDeviceList = it
                            testDeviceAdapter.notifyDataSetChanged()
                        }
                        .ignoreElements(),
                ))
            }
            .doFinally {
                disposable = null
            }
            .subscribe({
                // Permission granted
            }, {
                // Permission denied
            })
    }

    override fun onPause() {
        super.onPause()
        disposable?.dispose()
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
}