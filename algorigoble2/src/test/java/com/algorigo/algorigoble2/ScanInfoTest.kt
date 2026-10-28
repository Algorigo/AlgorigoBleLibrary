package com.algorigo.algorigoble2

import android.bluetooth.le.ScanRecord
import android.bluetooth.le.ScanResult
import android.os.Build
import android.os.ParcelUuid
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.UUID

/**
 * API 32 이하에서 ScanInfo.advertisingDataMap 이 항상 비어 있던 문제(P2) 테스트.
 * 단위 테스트의 Build.VERSION.SDK_INT 는 0 이므로 직접 파싱하는 경로를 탄다.
 */
class ScanInfoTest {

    private fun bytes(vararg values: Int) = values.map { it.toByte() }.toByteArray()

    // Flags(0x01) + Complete Local Name(0x09) "AB" + Manufacturer Specific(0xFF) 0x0059 0x01..0x05 + 0 padding (31바이트 버퍼)
    private val record = bytes(
        0x02, 0x01, 0x06,
        0x03, 0x09, 'A'.code, 'B'.code,
        0x08, 0xFF, 0x59, 0x00, 0x01, 0x02, 0x03, 0x04, 0x05,
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
    )

    @Test
    fun scanInfo_parsesAdvertisingDataFromRawBytes_belowApi33() {
        val scanRecord = mockk<ScanRecord>(relaxed = true)
        every { scanRecord.bytes } returns record
        val scanResult = mockk<ScanResult>(relaxed = true)
        every { scanResult.scanRecord } returns scanRecord

        val map = ScanInfo(scanResult).advertisingDataMap!!

        assertEquals(setOf(0x01, 0x09, 0xFF), map.keys)
        assertArrayEquals(bytes(0x06), map[0x01])
        assertArrayEquals("AB".toByteArray(), map[0x09])
        assertArrayEquals(bytes(0x59, 0x00, 0x01, 0x02, 0x03, 0x04, 0x05), map[0xFF])
    }

    @Test
    fun parse_stopsAtZeroLength() {
        val map = parseAdvertisingData(bytes(0x02, 0x01, 0x06, 0x00, 0x03, 0x09, 0x41, 0x42))

        assertEquals(setOf(0x01), map.keys)
    }

    @Test
    fun parse_keepsLastValue_forDuplicatedType() {
        // 광고 데이터와 scan response 에 같은 type 이 있으면 마지막 값을 쓴다 (ScanRecord.getAdvertisingDataMap 과 같음)
        val map = parseAdvertisingData(bytes(0x02, 0x0A, 0x01, 0x02, 0x0A, 0x07))

        assertArrayEquals(bytes(0x07), map[0x0A])
    }

    @Test
    fun parse_returnsStructuresBeforeMalformedLength() {
        val map = parseAdvertisingData(bytes(0x02, 0x01, 0x06, 0x09, 0xFF, 0x59))

        assertEquals(setOf(0x01), map.keys)
    }

    @Test
    fun parse_allowsEmptyData_andHandlesNullOrEmpty() {
        assertArrayEquals(bytes(), parseAdvertisingData(bytes(0x01, 0x09))[0x09])
        assertEquals(mapOf<Int, ByteArray>(), parseAdvertisingData(null))
        assertEquals(mapOf<Int, ByteArray>(), parseAdvertisingData(bytes()))
    }

    private val heartRate = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")
    private val battery = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb")

    private fun parcelUuid(uuid: UUID) = mockk<ParcelUuid>().also { every { it.uuid } returns uuid }

    private fun scanResult(txPowerLevel: Int = Int.MIN_VALUE, resultTxPower: Int = ScanResult.TX_POWER_NOT_PRESENT): ScanResult {
        val scanRecord = mockk<ScanRecord>(relaxed = true)
        every { scanRecord.bytes } returns record
        every { scanRecord.serviceUuids } returns listOf(parcelUuid(heartRate), parcelUuid(battery))
        every { scanRecord.serviceData } returns mapOf(parcelUuid(battery) to bytes(0x64))
        every { scanRecord.txPowerLevel } returns txPowerLevel
        return mockk<ScanResult>(relaxed = true).also {
            every { it.scanRecord } returns scanRecord
            every { it.rssi } returns -60
            every { it.txPower } returns resultTxPower
            every { it.isConnectable } returns true
            every { it.timestampNanos } returns 123_456_789L
        }
    }

    @Test
    fun scanInfo_hasServiceUuidsServiceDataAndTimestamp() {
        val info = ScanInfo(scanResult(), Build.VERSION_CODES.O)

        assertEquals(listOf(heartRate, battery), info.serviceUuids)
        assertEquals(setOf(battery), info.serviceData!!.keys)
        assertArrayEquals(bytes(0x64), info.serviceData!![battery])
        assertEquals(123_456_789L, info.timestampNanos)
        assertEquals(-60, info.rssi)
    }

    // 광고의 TX Power Level 이 먼저이고, 없으면 확장 광고의 TX power(API 26+), 둘 다 없으면 null
    @Test
    fun txPower_prefersAdvertisedLevel_thenResultTxPower() {
        assertEquals(-8, ScanInfo(scanResult(txPowerLevel = -8, resultTxPower = 4), Build.VERSION_CODES.O).txPower)
        assertEquals(4, ScanInfo(scanResult(resultTxPower = 4), Build.VERSION_CODES.O).txPower)
        assertEquals(null, ScanInfo(scanResult(resultTxPower = 4), Build.VERSION_CODES.N).txPower)
        assertEquals(null, ScanInfo(scanResult(), Build.VERSION_CODES.O).txPower)
    }

    @Test
    fun isConnectable_isKnownOnlyOnApi26AndAbove() {
        assertEquals(true, ScanInfo(scanResult(), Build.VERSION_CODES.O).isConnectable)
        assertEquals(null, ScanInfo(scanResult(), Build.VERSION_CODES.N).isConnectable)
    }

    // 기존 생성자(rssi 만, 또는 rssi/광고/manufacturer data)로 만든 ScanInfo 의 새 항목은 null 이다
    @Test
    fun existingConstructors_stillWork() {
        val info = ScanInfo(-50)
        assertEquals(-50, info.rssi)
        assertEquals(null, info.serviceUuids)
        assertEquals(null, info.isConnectable)
        assertEquals(null, ScanInfo(-50, mapOf(), null).timestampNanos)
    }
}
