package com.algorigo.algorigoblelibrary

import android.Manifest
import android.bluetooth.BluetoothDevice
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.util.Log
import android.widget.Button
import com.algorigo.algorigoble2.BleDevice
import com.algorigo.algorigoble2.BleManager
import com.algorigo.algorigoble2.BleScanFilter
import com.algorigo.algorigoble2.BleScanSettings
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import java.util.concurrent.TimeUnit

class MainActivity : RequestPermissionActivity() {

    private lateinit var scanButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        scanButton = findViewById<Button>(R.id.scanButton)

        scanButton.setOnClickListener {
            requestPermissionCompletable(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION))
                .andThen(BleManager(this, engine = BleManager.Engine.ALGORIGO_BLE).scanObservable())
                .take(10, TimeUnit.SECONDS)
                .observeOn(AndroidSchedulers.mainThread())
                .doOnSubscribe {
                    scanButton.isEnabled = false
                }
                .doFinally {
                    scanButton.isEnabled = true
                }
                .subscribe({
                    Log.e("!!!", "onNext:${it.map { it.deviceId }.joinToString()}")
                }, {
                    Log.e("!!!", "onError", it)
                })
        }
    }
}