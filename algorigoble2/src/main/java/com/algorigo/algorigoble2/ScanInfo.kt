package com.algorigo.algorigoble2

import android.bluetooth.le.ScanRecord
import android.bluetooth.le.ScanResult
import android.os.Build
import android.util.SparseArray

data class ScanInfo(
    @androidx.annotation.IntRange(from = -127, to = 126) val rssi: Int?,
    val advertisingDataMap: Map<Int, ByteArray>? = null,
    val manufacturerData: SparseArray<ByteArray>? = null
) {

    constructor(scanResult: ScanResult)
            : this(scanResult.rssi,
        scanResult.scanRecord?.getAdvertisingData(),
        scanResult.scanRecord?.manufacturerSpecificData)
}

private fun ScanRecord.getAdvertisingData(): Map<Int, ByteArray> {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        advertisingDataMap
    } else {
        val map = mutableMapOf<Int, ByteArray>()
        bytes
        return map
    }
}
