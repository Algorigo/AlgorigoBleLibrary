package com.algorigo.algorigoble2

import android.bluetooth.le.ScanRecord
import android.bluetooth.le.ScanResult
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

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
}
