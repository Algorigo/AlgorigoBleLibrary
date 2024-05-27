package com.algorigo.algorigoblelibrary

import android.app.Service
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanRecord
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import com.algorigo.algorigoble2.BleDevice
import com.algorigo.algorigoble2.BleManager
import com.algorigo.algorigoble2.BleScanFilter

class BluetoothService : Service() {

    inner class BluetoothBinder : Binder() {
        fun getService(): BluetoothService = this@BluetoothService
    }

    private val binder = BluetoothBinder()

    lateinit var bleManager: BleManager
    lateinit var bleManager2: BleManager

    override fun onCreate() {
        super.onCreate()
        bleManager = BleManager(
            applicationContext,
            virtualDevices = arrayOf(
                VirtualBleDevice() to BleDevice(),
            )
        )
        bleManager2 = BleManager(
            applicationContext,
            delegate = object : BleManager.BleDeviceDelegate() {
                override fun createBleDevice(
                    bluetoothDevice: BluetoothDevice,
                    scanRecord: ScanRecord?
                ): BleDevice? {
                    return TestDevice()
                }

                override fun getBleScanFilters(): Array<BleScanFilter> {
                    return arrayOf(
                        BleScanFilter.Builder()
                            .setDeviceName("SC40")
                            .build()
                    )
                }
            }
        )
    }

    override fun onBind(intent: Intent): IBinder {
        return binder
    }
}
