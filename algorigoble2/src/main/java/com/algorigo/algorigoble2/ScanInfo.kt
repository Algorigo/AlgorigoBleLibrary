package com.algorigo.algorigoble2

import android.bluetooth.le.ScanRecord
import android.bluetooth.le.ScanResult
import android.os.Build
import android.util.SparseArray
import java.util.UUID

/**
 * 스캔 결과 하나의 정보. 광고에 없는 항목은 null 이다.
 *
 * @property serviceUuids 광고에 담긴 서비스 UUID 목록
 * @property serviceData 광고에 담긴 서비스 데이터 (서비스 UUID → 데이터)
 * @property txPower 송신 세기(dBm). 광고의 TX Power Level(AD type 0x0A), 없으면 확장 광고 헤더의 TX power(API 26+)
 * @property isConnectable 연결할 수 있는 광고인지. API 26 이상에서만 알 수 있다
 * @property timestampNanos 광고를 받은 시각. SystemClock.elapsedRealtimeNanos() 기준
 */
data class ScanInfo @JvmOverloads constructor(
    @androidx.annotation.IntRange(from = -127, to = 126) val rssi: Int?,
    val advertisingDataMap: Map<Int, ByteArray>? = null,
    val manufacturerData: SparseArray<ByteArray>? = null,
    val serviceUuids: List<UUID>? = null,
    val serviceData: Map<UUID, ByteArray>? = null,
    val txPower: Int? = null,
    val isConnectable: Boolean? = null,
    val timestampNanos: Long? = null,
) {

    constructor(scanResult: ScanResult) : this(scanResult, Build.VERSION.SDK_INT)

    internal constructor(scanResult: ScanResult, sdkInt: Int) : this(
        rssi = scanResult.rssi,
        advertisingDataMap = scanResult.scanRecord?.getAdvertisingData(sdkInt),
        manufacturerData = scanResult.scanRecord?.manufacturerSpecificData,
        serviceUuids = scanResult.scanRecord?.serviceUuids?.map { it.uuid },
        serviceData = scanResult.scanRecord?.serviceData?.mapKeys { it.key.uuid },
        txPower = scanResult.txPower(sdkInt),
        isConnectable = if (sdkInt >= Build.VERSION_CODES.O) scanResult.isConnectable else null,
        timestampNanos = scanResult.timestampNanos,
    )
}

private fun ScanRecord.getAdvertisingData(sdkInt: Int): Map<Int, ByteArray> {
    return if (sdkInt >= Build.VERSION_CODES.TIRAMISU) {
        advertisingDataMap
    } else {
        // API 32 이하에는 advertisingDataMap 이 없으므로 원본 바이트를 직접 파싱한다
        parseAdvertisingData(bytes)
    }
}

@Suppress("NewApi") // sdkInt 로 확인한다
private fun ScanResult.txPower(sdkInt: Int): Int? {
    // 광고에 TX Power Level 이 없으면 Integer.MIN_VALUE
    scanRecord?.txPowerLevel?.takeIf { it != Int.MIN_VALUE }?.let { return it }
    if (sdkInt >= Build.VERSION_CODES.O && txPower != ScanResult.TX_POWER_NOT_PRESENT) {
        return txPower
    }
    return null
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
