package com.algorigo.algorigoblelibrary

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import com.algorigo.algorigoble2.BleManager

class BluetoothService : Service() {

    inner class BluetoothBinder : Binder() {
        fun getService(): BluetoothService = this@BluetoothService
    }

    private val binder = BluetoothBinder()

    lateinit var bleManager: BleManager

    override fun onCreate() {
        super.onCreate()
        bleManager = BleManager(applicationContext, engine = BleManager.Engine.RX_ANDROID_BLE)
    }

    override fun onBind(intent: Intent): IBinder {
        return binder
    }
}
