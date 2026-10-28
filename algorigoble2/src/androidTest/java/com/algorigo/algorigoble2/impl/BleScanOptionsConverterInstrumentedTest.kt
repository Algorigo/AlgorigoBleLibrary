package com.algorigo.algorigoble2.impl

import android.bluetooth.le.ScanSettings
import android.os.Build
import android.os.ParcelUuid
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.algorigo.algorigoble2.BleScanFilter
import com.algorigo.algorigoble2.BleScanSettings
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/**
 * BleScanSettings/BleScanFilter 를 Android ScanSettings/ScanFilter 로 바꾸는 변환기 테스트.
 * Android 의 Builder 는 JVM 단위 테스트에서 동작하지 않으므로(android.jar stub) 기기에서 실행한다.
 */
@RunWith(AndroidJUnit4::class)
class BleScanOptionsConverterInstrumentedTest {

    @Test
    fun scanSettings_areConverted() {
        val settings = BleScanOptionsConverter.convertScanSettings(
            BleScanSettings.Builder()
                .setScanMode(BleScanSettings.ScanMode.LOW_LATENCY)
                .setReportDelay(0)
                .setCallbackType(BleScanSettings.CallbackType.FIRST_MATCH_AND_MATCH_LOST)
                .setLegacy(false)
                .setPhy(ScanSettings.PHY_LE_ALL_SUPPORTED)
                .build()
        )

        assertEquals(ScanSettings.SCAN_MODE_LOW_LATENCY, settings.scanMode)
        assertEquals(0L, settings.reportDelayMillis)
        assertEquals(ScanSettings.CALLBACK_TYPE_FIRST_MATCH or ScanSettings.CALLBACK_TYPE_MATCH_LOST, settings.callbackType)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            assertFalse(settings.legacy)
            assertEquals(ScanSettings.PHY_LE_ALL_SUPPORTED, settings.phy)
        }
    }

    @Test
    fun defaultScanSettings_areLowPowerAllMatches() {
        val settings = BleScanOptionsConverter.convertScanSettings(BleScanSettings.Builder().build())

        assertEquals(ScanSettings.SCAN_MODE_LOW_POWER, settings.scanMode)
        assertEquals(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, settings.callbackType)
    }

    @Test
    fun scanFilters_areConverted() {
        val serviceUuid = ParcelUuid(UUID.fromString("0000aaa0-0000-1000-8000-aabbccddeeff"))
        val uuidMask = ParcelUuid(UUID.fromString("ffffffff-0000-0000-0000-000000000000"))
        val serviceDataUuid = ParcelUuid(UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb"))

        val filters = BleScanOptionsConverter.convertScanFilters(arrayOf(
            BleScanFilter.Builder()
                .setDeviceName("Sensor")
                .setDeviceAddress("00:11:22:33:44:55")
                .setServiceUuid(serviceUuid, uuidMask)
                .setServiceData(serviceDataUuid, byteArrayOf(1, 2), byteArrayOf(-1, 0))
                .setManufacturerData(0x59, byteArrayOf(1, 2, 3), byteArrayOf(-1, -1, 0))
                .build(),
            BleScanFilter.Builder().build(),
        ))

        assertEquals(2, filters.size)
        filters[0].let {
            assertEquals("Sensor", it.deviceName)
            assertEquals("00:11:22:33:44:55", it.deviceAddress)
            assertEquals(serviceUuid, it.serviceUuid)
            assertEquals(uuidMask, it.serviceUuidMask)
            assertEquals(serviceDataUuid, it.serviceDataUuid)
            assertArrayEquals(byteArrayOf(1, 2), it.serviceData)
            assertArrayEquals(byteArrayOf(-1, 0), it.serviceDataMask)
            assertEquals(0x59, it.manufacturerId)
            assertArrayEquals(byteArrayOf(1, 2, 3), it.manufacturerData)
            assertArrayEquals(byteArrayOf(-1, -1, 0), it.manufacturerDataMask)
        }
        // 아무 조건도 없는 필터는 빈 ScanFilter 가 된다
        filters[1].let {
            assertNull(it.deviceName)
            assertNull(it.serviceUuid)
            assertEquals(-1, it.manufacturerId)
        }
    }

    @Test
    fun solicitationUuid_isConverted_onApi29AndAbove() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val solicitation = ParcelUuid(UUID.fromString("0000fef3-0000-1000-8000-00805f9b34fb"))

        val filter = BleScanOptionsConverter.convertScanFilters(arrayOf(
            BleScanFilter.Builder().setServiceSolicitationUuid(solicitation).build()
        )).single()

        assertEquals(solicitation, filter.serviceSolicitationUuid)
    }
}
