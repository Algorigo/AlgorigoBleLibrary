package com.algorigo.algorigoble2.impl

import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanSettings
import android.os.Build
import androidx.annotation.RequiresApi
import com.algorigo.algorigoble2.BleScanFilter
import com.algorigo.algorigoble2.BleScanSettings

internal object BleScanOptionsConverter {

    @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
    fun convertScanSettings(scanSettings: BleScanSettings): ScanSettings {
        return ScanSettings.Builder()
            .setScanMode(scanSettings.scanMode.value)
            .setReportDelay(scanSettings.reportDelayMillis)
            .run {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    setNumOfMatches(scanSettings.numOfMatches.value)
                        .setCallbackType(scanSettings.callbackType.value)
                        .setMatchMode(scanSettings.matchMode.value)
                } else {
                    this
                }
            }
            .run {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    setLegacy(scanSettings.legacy)
                        .setPhy(scanSettings.phy)
                } else {
                    this
                }
            }
            .build()
    }

    fun convertScanFilters(filters: Array<out BleScanFilter>): List<ScanFilter> {
        return filters.map { filter ->
            ScanFilter.Builder().apply {
                filter.name?.also {
                    setDeviceName(it)
                }
                filter.deviceAddress?.also {
                    setDeviceAddress(it)
                }
                filter.uuid?.also {
                    setServiceUuid(it, filter.uuidMask)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    filter.solicitationUuid?.also {
                        setServiceSolicitationUuid(it)
                        setServiceSolicitationUuid(it, filter.solicitationUuidMask)
                    }
                }
                filter.serviceDataUuid?.also {
                    setServiceData(it, filter.serviceData, filter.serviceDataMask)
                }
                filter.manufacturerId?.also {
                    setManufacturerData(it, filter.manufacturerData, filter.manufacturerDataMask)
                }
            }
                .build()
        }
    }
}