package com.algorigo.algorigoble.rxandroidble

import com.algorigo.algorigoble.BleScanFilter
import com.algorigo.algorigoble.BleScanSettings
import com.polidea.rxandroidble2.scan.ScanFilter
import com.polidea.rxandroidble2.scan.ScanSettings

object BleScanOptionsConverterRx {

    fun convertScanSettings(scanOptions: BleScanSettings): ScanSettings {
        return ScanSettings.Builder()
            .setScanMode(when (scanOptions.scanMode) {
                BleScanSettings.ScanMode.SCAN_MODE_OPPORTUNISTIC -> ScanSettings.SCAN_MODE_OPPORTUNISTIC
                BleScanSettings.ScanMode.SCAN_MODE_LOW_POWER -> ScanSettings.SCAN_MODE_LOW_POWER
                BleScanSettings.ScanMode.SCAN_MODE_BALANCED -> ScanSettings.SCAN_MODE_BALANCED
                BleScanSettings.ScanMode.SCAN_MODE_LOW_LATENCY -> ScanSettings.SCAN_MODE_LOW_LATENCY
            })
            .setCallbackType(when (scanOptions.callbackType) {
                BleScanSettings.CallbackType.CALLBACK_TYPE_ALL_MATCHES -> ScanSettings.CALLBACK_TYPE_ALL_MATCHES
                BleScanSettings.CallbackType.CALLBACK_TYPE_FIRST_MATCH -> ScanSettings.CALLBACK_TYPE_FIRST_MATCH
                BleScanSettings.CallbackType.CALLBACK_TYPE_MATCH_LOST -> ScanSettings.CALLBACK_TYPE_MATCH_LOST
            })
            .build()
    }

    fun convertScanFilters(filters: Array<out BleScanFilter>): Array<ScanFilter> {
        return filters.map {
            ScanFilter.Builder().apply {
                setDeviceName(it.deviceName)
                it.deviceAddress?.let { deviceAddress ->
                    setDeviceAddress(deviceAddress)
                }
            }
                .build()
        }
            .toTypedArray()
    }
}