package com.algorigo.algorigoble2

import android.os.ParcelUuid
import io.mockk.mockk
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * BleScanFilter.Builder 의 검증이 인자가 아니라 이전 필드 값을 검사하던 문제(P2) 테스트.
 */
class BleScanFilterTest {

    private val uuid = mockk<ParcelUuid>()
    private val mask = mockk<ParcelUuid>()

    @Test(expected = IllegalArgumentException::class)
    fun serviceData_withMaskOfDifferentLength_failsInBuilder() {
        BleScanFilter.Builder().setServiceData(uuid, byteArrayOf(1, 2), byteArrayOf(-1))
    }

    @Test(expected = IllegalArgumentException::class)
    fun manufacturerData_withMaskOfDifferentLength_failsInBuilder() {
        BleScanFilter.Builder().setManufacturerData(0x59, byteArrayOf(1, 2, 3), byteArrayOf(-1, -1))
    }

    @Test(expected = IllegalArgumentException::class)
    fun manufacturerData_withMaskButNoData_failsInBuilder() {
        BleScanFilter.Builder().setManufacturerData(0x59, null, byteArrayOf(-1))
    }

    @Test(expected = IllegalArgumentException::class)
    fun serviceUuid_withMaskButNoUuid_failsInBuilder() {
        BleScanFilter.Builder().setServiceUuid(null, mask)
    }

    // 수정 전에는 이전 호출의 (잘못된) 값을 검사해서 올바른 호출이 실패했다
    @Test
    fun validCall_afterInvalidValuesWereSet_isAccepted() {
        val builder = BleScanFilter.Builder()
        try {
            builder.setManufacturerData(0x59, byteArrayOf(1, 2, 3), byteArrayOf(-1))
        } catch (_: IllegalArgumentException) {
        }

        val filter = builder.setManufacturerData(0x59, byteArrayOf(1, 2), byteArrayOf(-1, 0)).build()

        assertArrayEquals(byteArrayOf(1, 2), filter.manufacturerData)
        assertArrayEquals(byteArrayOf(-1, 0), filter.manufacturerDataMask)
    }

    @Test
    fun validMasks_areStored() {
        val filter = BleScanFilter.Builder()
            .setServiceUuid(uuid, mask)
            .setServiceData(uuid, byteArrayOf(1), byteArrayOf(-1))
            .setManufacturerData(0x59, byteArrayOf(1, 2), byteArrayOf(-1, -1))
            .build()

        assertSame(uuid, filter.uuid)
        assertSame(mask, filter.uuidMask)
        assertArrayEquals(byteArrayOf(-1), filter.serviceDataMask)
        assertEquals(0x59, filter.manufacturerId)
    }
}
