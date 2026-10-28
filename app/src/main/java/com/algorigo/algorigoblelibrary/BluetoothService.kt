package com.algorigo.algorigoblelibrary

import android.app.Service
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanRecord
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.util.Log
import com.algorigo.algorigoble2.BleDevice
import com.algorigo.algorigoble2.BleManager
import com.algorigo.algorigoble2.BleScanFilter
import com.algorigo.logger.Level
import com.algorigo.logger.LogManager
import com.algorigo.logger.handler.AndroidHandler

class BluetoothService : Service() {

    inner class BluetoothBinder : Binder() {
        fun getService(): BluetoothService = this@BluetoothService
    }

    private val binder = BluetoothBinder()

    lateinit var bleManager: BleManager
    lateinit var bleManager2: BleManager
    val virtualBleDevice = VirtualBleDevice()

    override fun onCreate() {
        super.onCreate()
        BleManager.getLogger()
            .apply {
                level = Level.VERBOSE.level
                // 서비스가 다시 만들어져도 handler 는 한 번만 붙인다 (붙인 수만큼 같은 로그가 반복된다)
                if (handlers.none { it is AndroidHandler }) {
                    addHandler(AndroidHandler())
                }
            }
        // Android 가 java.util.logging root logger 에 붙여 둔 기본 handler 가 NOTICE 이상의 로그를 한 번 더 남기지 않게 한다
        LogManager.removeRootAndroidHandler()
        bleManager = BleManager(
            applicationContext,
            virtualDevices = arrayOf(
                virtualBleDevice to BleDevice(),
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
