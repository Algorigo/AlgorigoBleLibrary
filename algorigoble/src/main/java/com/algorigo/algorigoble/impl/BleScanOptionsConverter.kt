package com.algorigo.algorigoble.impl

import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanSettings
import android.os.Build
import androidx.annotation.RequiresApi
import com.algorigo.algorigoble.BleScanFilter
import com.algorigo.algorigoble.BleScanSettings

object BleScanOptionsConverter {

    @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
    fun convertScanSettings(scanSettings: BleScanSettings): ScanSettings {
        return ScanSettings.Builder()
            .setScanMode(when (scanSettings.scanMode) {
                BleScanSettings.ScanMode.SCAN_MODE_OPPORTUNISTIC -> ScanSettings.SCAN_MODE_OPPORTUNISTIC
                BleScanSettings.ScanMode.SCAN_MODE_LOW_POWER -> ScanSettings.SCAN_MODE_LOW_POWER
                BleScanSettings.ScanMode.SCAN_MODE_BALANCED -> ScanSettings.SCAN_MODE_BALANCED
                BleScanSettings.ScanMode.SCAN_MODE_LOW_LATENCY -> ScanSettings.SCAN_MODE_LOW_LATENCY
            })
            .run {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    setCallbackType(when (scanSettings.callbackType) {
                        BleScanSettings.CallbackType.CALLBACK_TYPE_ALL_MATCHES -> ScanSettings.CALLBACK_TYPE_ALL_MATCHES
                        BleScanSettings.CallbackType.CALLBACK_TYPE_FIRST_MATCH -> ScanSettings.CALLBACK_TYPE_FIRST_MATCH
                        BleScanSettings.CallbackType.CALLBACK_TYPE_MATCH_LOST -> ScanSettings.CALLBACK_TYPE_MATCH_LOST
                    })
                } else {
                    this
                }
            }
            .build()
    }

    @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
    fun convertScanFilters(filters: Array<out BleScanFilter>): List<ScanFilter> {
        return filters.map {
            ScanFilter.Builder().apply {
                setDeviceName(it.deviceName)
                it.deviceAddress?.let { deviceAddress ->
                    setDeviceAddress(deviceAddress)
                }
            }
                .build()
        }
    }
}