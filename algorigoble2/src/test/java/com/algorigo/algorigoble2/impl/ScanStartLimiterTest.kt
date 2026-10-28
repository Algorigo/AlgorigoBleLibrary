package com.algorigo.algorigoble2.impl

import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanSettings
import com.algorigo.algorigoble2.BleScanSettings
import com.algorigo.algorigoble2.exception.SystemServiceException
import com.algorigo.algorigoble2.testutil.RxTestSchedulerRule
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.TimeUnit

/** Android 의 "30초 안에 startScan 5번" 제한을 넘지 않도록 스캔 시작을 미루는지 확인한다. */
class ScanStartLimiterTest {

    @get:Rule
    val rxRule = RxTestSchedulerRule()

    private val bluetoothAdapter = mockk<BluetoothAdapter>(relaxed = true)
    private val scanner = mockk<BluetoothLeScanner>(relaxed = true)

    @Before
    fun setUp() {
        mockkObject(BleScanOptionsConverter)
        every { BleScanOptionsConverter.convertScanSettings(any()) } returns mockk()
        every { BleScanOptionsConverter.convertScanFilters(any()) } returns listOf()
        every { bluetoothAdapter.bluetoothLeScanner } returns scanner
        every { scanner.startScan(any<List<ScanFilter>>(), any<ScanSettings>(), any<ScanCallback>()) } just Runs
    }

    @After
    fun tearDown() {
        unmockkObject(BleScanOptionsConverter)
    }

    private fun advance(millis: Long) = rxRule.testScheduler.advanceTimeBy(millis, TimeUnit.MILLISECONDS)

    private fun startCount(count: Int) {
        verify(exactly = count) { scanner.startScan(any<List<ScanFilter>>(), any<ScanSettings>(), any<ScanCallback>()) }
    }

    private fun scanAndStop() = BleScanner.scanObservable(bluetoothAdapter, BleScanSettings.Builder().build()).test()

    @Test
    fun reserve_allowsFiveStartsIn30Seconds_andDelaysTheSixth() {
        repeat(5) { assertEquals(0L, ScanStartLimiter.reserve(it * 1_000L).delayMillis) }

        // 첫 시작(0ms)이 창을 벗어난 뒤(30초 + 여유)에 시작한다
        assertEquals(30_500L, ScanStartLimiter.reserve(5_000).startAtMillis)
        // 이미 미뤄진 시작도 센다
        assertEquals(31_500L, ScanStartLimiter.reserve(6_000).startAtMillis)
        // 창을 벗어나면 바로 시작한다
        ScanStartLimiter.reset()
        repeat(5) { ScanStartLimiter.reserve(0) }
        assertEquals(0L, ScanStartLimiter.reserve(30_500).delayMillis)
    }

    @Test
    fun sixthScanWithin30Seconds_startsWhenAllowed() {
        repeat(5) {
            scanAndStop().dispose()
            advance(1_000)
        }
        startCount(5)

        val sixth = scanAndStop()
        startCount(5)
        advance(30_500 - 5_000 - 1)
        startCount(5)
        advance(1)
        startCount(6)
        sixth.assertNoErrors()
    }

    @Test
    fun delayedScanDisposedBeforeStart_doesNotCount() {
        repeat(5) { scanAndStop().dispose() }
        scanAndStop().dispose()
        advance(60_000)
        startCount(5)

        // 취소된 예약은 지워져서, 창을 벗어난 뒤의 시작은 바로 된다
        scanAndStop()
        startCount(6)
    }

    @Test
    fun failedStart_doesNotCount() {
        every { bluetoothAdapter.bluetoothLeScanner } returns null
        repeat(5) {
            scanAndStop().assertError(SystemServiceException.BluetoothUnavailableException::class.java)
        }
        every { bluetoothAdapter.bluetoothLeScanner } returns scanner

        repeat(5) { scanAndStop().dispose() }
        startCount(5)
    }
}
