package com.algorigo.algorigoble2.impl

import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import com.algorigo.algorigoble2.BleScanFilter
import com.algorigo.algorigoble2.BleScanSettings
import com.algorigo.algorigoble2.exception.SystemServiceException
import com.algorigo.algorigoble2.testutil.RxTestSchedulerRule
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.slot
import io.mockk.unmockkObject
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * 스캔 중 블루투스가 꺼질 때 stopScan 에서 난 예외가 RxJavaPlugins.onError 로 새어 나가지 않는지 확인한다.
 * (RxJavaPlugins 에 error handler 가 없는 앱에서는 이 예외가 크래시로 이어진다)
 */
class BleScannerTest {

    @get:Rule
    val rxRule = RxTestSchedulerRule()

    private val bluetoothAdapter = mockk<BluetoothAdapter>(relaxed = true)
    private val scanner = mockk<BluetoothLeScanner>(relaxed = true)
    private val scanCallbackSlot = slot<ScanCallback>()

    @Before
    fun setUp() {
        // JVM 테스트용 android.jar 에서는 ScanSettings.Builder 체이닝이 null 을 반환하므로 변환기를 대체한다
        mockkObject(BleScanOptionsConverter)
        every { BleScanOptionsConverter.convertScanSettings(any()) } returns mockk()
        every { BleScanOptionsConverter.convertScanFilters(any()) } returns listOf()

        every { bluetoothAdapter.bluetoothLeScanner } returns scanner
        every { scanner.startScan(any<List<ScanFilter>>(), any<ScanSettings>(), capture(scanCallbackSlot)) } just Runs
    }

    @After
    fun tearDown() {
        unmockkObject(BleScanOptionsConverter)
    }

    @Test
    fun scanObservable_emitsScanResults_andStopsScanOnDispose() {
        val scanResult = mockk<ScanResult>(relaxed = true)

        val observer = BleScanner.scanObservable(bluetoothAdapter, BleScanSettings.Builder().build()).test()
        scanCallbackSlot.captured.onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, scanResult)
        observer.dispose()

        observer.assertValue { it.result === scanResult && !it.lost }
        verify(exactly = 1) { scanner.stopScan(scanCallbackSlot.captured) }
    }

    @Test
    fun matchLostCallback_isEmittedAsLost() {
        val found = mockk<ScanResult>(relaxed = true)
        val lost = mockk<ScanResult>(relaxed = true)

        val observer = BleScanner.scanObservable(bluetoothAdapter, BleScanSettings.Builder().build()).test()
        scanCallbackSlot.captured.onScanResult(ScanSettings.CALLBACK_TYPE_FIRST_MATCH, found)
        scanCallbackSlot.captured.onScanResult(ScanSettings.CALLBACK_TYPE_MATCH_LOST, lost)

        observer.assertValueCount(2)
        assertEquals(listOf(found to false, lost to true), observer.values().map { it.result to it.lost })
    }

    @Test
    fun matchLostCallback_isEmitted_evenIfItDoesNotPassRssiFilter() {
        val lost = mockk<ScanResult>(relaxed = true)
        every { lost.rssi } returns -100
        val filter = BleScanFilter.Builder().setRssiThreshold(-60).build()

        val observer = BleScanner.scanObservable(bluetoothAdapter, BleScanSettings.Builder().build(), filter).test()
        scanCallbackSlot.captured.onScanResult(ScanSettings.CALLBACK_TYPE_MATCH_LOST, lost)

        observer.assertValue { it.result === lost && it.lost }
    }

    @Test
    fun dispose_doesNotThrowUndeliverableError_whenScannerBecameNullBecauseBluetoothTurnedOff() {
        val observer = BleScanner.scanObservable(bluetoothAdapter, BleScanSettings.Builder().build()).test()
        every { bluetoothAdapter.bluetoothLeScanner } returns null

        observer.dispose()

        assertEquals(listOf<Throwable>(), rxRule.undeliverableErrors)
    }

    @Test
    fun dispose_doesNotThrowUndeliverableError_whenStopScanThrows() {
        every { scanner.stopScan(any<ScanCallback>()) } throws IllegalStateException("BT Adapter is not turned ON")
        val observer = BleScanner.scanObservable(bluetoothAdapter, BleScanSettings.Builder().build()).test()

        observer.dispose()

        assertEquals(listOf<Throwable>(), rxRule.undeliverableErrors)
    }

    @Test
    fun scanObservable_failsWithBluetoothUnavailableException_whenScannerIsNull() {
        every { bluetoothAdapter.bluetoothLeScanner } returns null

        BleScanner.scanObservable(bluetoothAdapter, BleScanSettings.Builder().build()).test()
            .assertError(SystemServiceException.BluetoothUnavailableException::class.java)

        assertEquals(listOf<Throwable>(), rxRule.undeliverableErrors)
    }
}
