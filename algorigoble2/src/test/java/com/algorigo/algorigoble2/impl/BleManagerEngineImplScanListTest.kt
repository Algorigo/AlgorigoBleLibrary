package com.algorigo.algorigoble2.impl

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanRecord
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.location.LocationManager
import com.algorigo.algorigoble2.BleDevice
import com.algorigo.algorigoble2.BleManager
import com.algorigo.algorigoble2.BleScanSettings
import com.algorigo.algorigoble2.testutil.RxTestSchedulerRule
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.slot
import io.mockk.unmockkObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * 스캔 콜백의 CALLBACK_TYPE_MATCH_LOST 를 처리하지 않아, 사라진 기기도 발견된 기기로 목록에 남던 문제(P2) 테스트.
 */
class BleManagerEngineImplScanListTest {

    @get:Rule
    val rxRule = RxTestSchedulerRule()

    private val context = mockk<Context>(relaxed = true)
    private val bluetoothManager = mockk<BluetoothManager>(relaxed = true)
    private val adapter = mockk<BluetoothAdapter>(relaxed = true)
    private val scanner = mockk<BluetoothLeScanner>(relaxed = true)
    private val locationManager = mockk<LocationManager>(relaxed = true)
    private val callbackSlot = slot<ScanCallback>()
    private val createdAddresses = mutableListOf<String>()
    private lateinit var engine: BleManagerEngineImpl

    @Before
    fun setUp() {
        mockkObject(BleScanOptionsConverter)
        every { BleScanOptionsConverter.convertScanSettings(any()) } returns mockk()
        every { BleScanOptionsConverter.convertScanFilters(any()) } returns listOf()

        every { context.getSystemService(Context.BLUETOOTH_SERVICE) } returns bluetoothManager
        every { context.getSystemService(Context.LOCATION_SERVICE) } returns locationManager
        every { bluetoothManager.adapter } returns adapter
        every { adapter.isEnabled } returns true
        every { adapter.bluetoothLeScanner } returns scanner
        every { scanner.startScan(any<List<ScanFilter>>(), any<ScanSettings>(), capture(callbackSlot)) } just Runs
        every { locationManager.isProviderEnabled(any()) } returns true

        engine = BleManagerEngineImpl(context, object : BleManager.BleDeviceDelegate() {
            override fun createBleDevice(bluetoothDevice: BluetoothDevice, scanRecord: ScanRecord?): BleDevice {
                createdAddresses.add(bluetoothDevice.address)
                return BleDevice()
            }
        })
    }

    @After
    fun tearDown() {
        unmockkObject(BleScanOptionsConverter)
    }

    private val bluetoothDevices = mutableMapOf<String, BluetoothDevice>()

    private fun result(address: String, rssi: Int = -50): ScanResult {
        val device = bluetoothDevices.getOrPut(address) {
            mockk<BluetoothDevice>(relaxed = true).also { every { it.address } returns address }
        }
        return mockk<ScanResult>(relaxed = true).also {
            every { it.device } returns device
            every { it.rssi } returns rssi
            every { it.scanRecord } returns null
        }
    }

    private fun scan() = engine.scanObservable(
        BleScanSettings.Builder().setCallbackType(BleScanSettings.CallbackType.FIRST_MATCH_AND_MATCH_LOST).build()
    ).test()

    private fun io.reactivex.rxjava3.observers.TestObserver<List<Pair<BleDevice, com.algorigo.algorigoble2.ScanInfo>>>.lastAddresses() =
        values().last().map { it.first.deviceId }

    @Test
    fun matchLost_removesDeviceFromList() {
        val observer = scan()
        callbackSlot.captured.onScanResult(ScanSettings.CALLBACK_TYPE_FIRST_MATCH, result(A))
        callbackSlot.captured.onScanResult(ScanSettings.CALLBACK_TYPE_FIRST_MATCH, result(B))

        callbackSlot.captured.onScanResult(ScanSettings.CALLBACK_TYPE_MATCH_LOST, result(A))

        assertEquals(listOf(B), observer.lastAddresses())
    }

    @Test
    fun foundAgainAfterLost_isAddedToEnd() {
        val observer = scan()
        callbackSlot.captured.onScanResult(ScanSettings.CALLBACK_TYPE_FIRST_MATCH, result(A))
        callbackSlot.captured.onScanResult(ScanSettings.CALLBACK_TYPE_FIRST_MATCH, result(B))
        callbackSlot.captured.onScanResult(ScanSettings.CALLBACK_TYPE_MATCH_LOST, result(A))

        callbackSlot.captured.onScanResult(ScanSettings.CALLBACK_TYPE_FIRST_MATCH, result(A))

        assertEquals(listOf(B, A), observer.lastAddresses())
    }

    @Test
    fun matchLost_forUnknownDevice_doesNotEmitOrCreateDevice() {
        val observer = scan()
        callbackSlot.captured.onScanResult(ScanSettings.CALLBACK_TYPE_FIRST_MATCH, result(A))
        val emitted = observer.values().size

        callbackSlot.captured.onScanResult(ScanSettings.CALLBACK_TYPE_MATCH_LOST, result(B))

        assertEquals(emitted, observer.values().size)
        assertEquals(listOf(A), createdAddresses)
    }

    @Test
    fun repeatedResults_keepFirstSeenOrder_andUpdateScanInfo() {
        val observer = scan()
        callbackSlot.captured.onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, result(A, rssi = -70))
        callbackSlot.captured.onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, result(B))
        callbackSlot.captured.onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, result(A, rssi = -40))

        assertEquals(listOf(A, B), observer.lastAddresses())
        assertEquals(-40, observer.values().last().first().second.rssi)
    }

    private fun scanWithLostTimeout(timeoutMillis: Long) = engine.scanObservable(
        BleScanSettings.Builder().setDeviceLostTimeout(timeoutMillis).build()
    ).test()

    private fun advance(millis: Long) = rxRule.testScheduler.advanceTimeBy(millis, TimeUnit.MILLISECONDS)

    @Test
    fun deviceNotSeenForLostTimeout_isRemoved() {
        val observer = scanWithLostTimeout(3_000)
        callbackSlot.captured.onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, result(A))
        advance(2_000)
        callbackSlot.captured.onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, result(B))

        advance(999)
        assertEquals(listOf(A, B), observer.lastAddresses())
        advance(1)
        assertEquals(listOf(B), observer.lastAddresses())

        // 계속 보이는 기기는 남는다
        advance(1_000)
        callbackSlot.captured.onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, result(B))
        advance(2_000)
        assertEquals(listOf(B), observer.lastAddresses())
        advance(1_000)
        assertEquals(listOf<String>(), observer.lastAddresses())

        // 다시 보이면 목록에 다시 추가된다
        callbackSlot.captured.onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, result(A))
        assertEquals(listOf(A), observer.lastAddresses())
        observer.assertNoErrors()
    }

    @Test
    fun withoutLostTimeout_devicesStay() {
        val observer = scanWithLostTimeout(0)
        callbackSlot.captured.onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, result(A))
        val emitted = observer.values().size

        advance(10 * 60_000)

        assertEquals(emitted, observer.values().size)
        assertEquals(listOf(A), observer.lastAddresses())
    }

    @Test
    fun lostTimeout_doesNotEmit_whenNothingIsRemoved() {
        val observer = scanWithLostTimeout(5_000)
        callbackSlot.captured.onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, result(A))
        val emitted = observer.values().size

        repeat(4) {
            advance(1_000)
            callbackSlot.captured.onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, result(A))
        }

        // 같은 기기의 결과마다 목록(ScanInfo 갱신)은 나오지만, 시간 확인만으로는 나오지 않는다
        assertEquals(emitted + 4, observer.values().size)
    }

    companion object {
        private const val A = "00:11:22:33:44:01"
        private const val B = "00:11:22:33:44:02"
    }
}
