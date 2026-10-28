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
        // API 32 이하에는 advertisingDataMap 이 없으므로 원본 바이트를 직접 파싱한다
        parseAdvertisingData(bytes)
    }
}

/**
 * 광고 데이터 원본을 AD type → data 로 파싱한다. 원본은 AD structure(length 1바이트, type 1바이트, data length-1바이트)의 반복이다.
 * ScanRecord.getAdvertisingDataMap()(API 33+)과 같이 길이가 0 이면(뒤쪽 0 padding) 끝내고, 같은 type 이 여러 번 나오면 마지막 값을 쓴다.
 * 길이가 남은 바이트를 넘는 잘못된 structure 를 만나면 그 앞까지만 돌려준다.
 */
internal fun parseAdvertisingData(bytes: ByteArray?): Map<Int, ByteArray> {
    val map = mutableMapOf<Int, ByteArray>()
    if (bytes == null) {
        return map
    }
    var position = 0
    while (position < bytes.size) {
        val length = bytes[position].toInt() and 0xFF
        if (length == 0 || position + 1 + length > bytes.size) {
            break
        }
        val type = bytes[position + 1].toInt() and 0xFF
        map[type] = bytes.copyOfRange(position + 2, position + 1 + length)
        position += 1 + length
    }
    return map
}
