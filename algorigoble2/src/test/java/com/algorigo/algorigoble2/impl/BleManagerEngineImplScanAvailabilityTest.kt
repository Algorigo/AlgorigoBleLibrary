package com.algorigo.algorigoble2.impl

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.LocationManager
import com.algorigo.algorigoble2.BleManager
import com.algorigo.algorigoble2.BleScanSettings
import com.algorigo.algorigoble2.ScanAvailability
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

/**
 * API 30 이하에서 위치 provider 가 바뀌어 스캔 가능 상태가 다시 나오면 스캔이 하나 더 시작되던 문제(P2) 테스트.
 * 단위 테스트의 Build.VERSION.SDK_INT 는 0 이므로 API 30 이하의 위치 broadcast 경로를 탄다.
 */
class BleManagerEngineImplScanAvailabilityTest {

    @get:Rule
    val rxRule = RxTestSchedulerRule()

    private val context = mockk<Context>(relaxed = true)
    private val bluetoothManager = mockk<BluetoothManager>(relaxed = true)
    private val adapter = mockk<BluetoothAdapter>(relaxed = true)
    private val scanner = mockk<BluetoothLeScanner>(relaxed = true)
    private val locationManager = mockk<LocationManager>(relaxed = true)
    // 등록 순서: [엔진의 블루투스 꺼짐 감지, 스캔의 위치 provider 변경, 스캔의 블루투스 상태 변경]
    private val receivers = mutableListOf<BroadcastReceiver>()
    private lateinit var engine: BleManagerEngineImpl

    @Before
    fun setUp() {
        // JVM 테스트용 android.jar 에서는 ScanSettings.Builder 체이닝이 null 을 반환하므로 변환기를 대체한다
        mockkObject(BleScanOptionsConverter)
        every { BleScanOptionsConverter.convertScanSettings(any()) } returns mockk()
        every { BleScanOptionsConverter.convertScanFilters(any()) } returns listOf()

        every { context.getSystemService(Context.BLUETOOTH_SERVICE) } returns bluetoothManager
        every { context.getSystemService(Context.LOCATION_SERVICE) } returns locationManager
        every { context.registerReceiver(capture(receivers), any<IntentFilter>()) } returns null
        every { bluetoothManager.adapter } returns adapter
        every { adapter.isEnabled } returns true
        every { adapter.bluetoothLeScanner } returns scanner
        every { scanner.startScan(any<List<ScanFilter>>(), any<ScanSettings>(), any<ScanCallback>()) } just Runs
        every { locationManager.isProviderEnabled(any()) } returns true

        engine = BleManagerEngineImpl(context, object : BleManager.BleDeviceDelegate() {
            override fun createBleDevice(bluetoothDevice: android.bluetooth.BluetoothDevice, scanRecord: android.bluetooth.le.ScanRecord?) = null
        })
    }

    @After
    fun tearDown() {
        unmockkObject(BleScanOptionsConverter)
    }

    private fun providersChanged(changedProviderEnabled: Boolean) {
        val intent = mockk<Intent>(relaxed = true)
        every { intent.getBooleanExtra(LocationManager.EXTRA_PROVIDER_ENABLED, any()) } returns changedProviderEnabled
        receivers[1].onReceive(context, intent)
    }

    private fun bluetoothStateChanged(state: Int) {
        val intent = mockk<Intent>(relaxed = true)
        every { intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, any()) } returns state
        receivers[2].onReceive(context, intent)
    }

    @Test
    fun locationProviderChange_whileLocationStaysEnabled_doesNotStartAnotherScan() {
        val observer = engine.scanObservable(BleScanSettings.Builder().build()).test()

        providersChanged(changedProviderEnabled = true)

        verify(exactly = 1) { scanner.startScan(any<List<ScanFilter>>(), any<ScanSettings>(), any<ScanCallback>()) }
        verify(exactly = 0) { scanner.stopScan(any<ScanCallback>()) }
        // 스캔을 다시 시작하면 빈 목록이 한 번 더 전달된다
        assertEquals(1, observer.values().size)
        observer.assertNoErrors()
    }

    @Test
    fun oneProviderDisabled_whileLocationStaysEnabled_keepsScanning() {
        val observer = engine.scanObservable(BleScanSettings.Builder().build()).test()

        every { locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) } returns false
        providersChanged(changedProviderEnabled = false)

        observer.assertNoErrors()
        verify(exactly = 1) { scanner.startScan(any<List<ScanFilter>>(), any<ScanSettings>(), any<ScanCallback>()) }
    }

    @Test
    fun locationDisabled_failsWithLocationUnavailable() {
        val observer = engine.scanObservable(BleScanSettings.Builder().build()).test()

        every { locationManager.isProviderEnabled(any()) } returns false
        providersChanged(changedProviderEnabled = false)

        observer.assertError(SystemServiceException.LocationUnavailableException::class.java)
        verify(exactly = 1) { scanner.stopScan(any<ScanCallback>()) }
    }

    // 공개 상태 스트림은 스캔할 수 없는 상태도 에러 없이 알린다
    @Test
    fun availability_emitsCurrentStateAndChanges_withoutError() {
        val observer = engine.getScanAvailabilityObservable().test()

        every { locationManager.isProviderEnabled(any()) } returns false
        providersChanged(changedProviderEnabled = false)
        // 상태가 그대로인 broadcast 는 다시 내보내지 않는다
        providersChanged(changedProviderEnabled = false)
        bluetoothStateChanged(BluetoothAdapter.STATE_TURNING_OFF)
        every { locationManager.isProviderEnabled(any()) } returns true
        providersChanged(changedProviderEnabled = true)
        bluetoothStateChanged(BluetoothAdapter.STATE_ON)

        observer.assertValues(
            ScanAvailability(bluetoothEnabled = true, locationEnabled = true),
            ScanAvailability(bluetoothEnabled = true, locationEnabled = false),
            ScanAvailability(bluetoothEnabled = false, locationEnabled = false),
            ScanAvailability(bluetoothEnabled = false, locationEnabled = true),
            ScanAvailability(bluetoothEnabled = true, locationEnabled = true),
        )
        observer.assertNoErrors().assertNotComplete()
        assertEquals(listOf(true, false, false, false, true), observer.values().map { it.available })
    }

    @Test
    fun isBluetoothEnabled_readsAdapterState() {
        assertEquals(true, engine.isBluetoothEnabled)
        every { adapter.isEnabled } returns false
        assertEquals(false, engine.isBluetoothEnabled)
    }

    @Test
    fun bluetoothTurningOff_failsWithBluetoothUnavailable() {
        val observer = engine.scanObservable(BleScanSettings.Builder().build()).test()

        bluetoothStateChanged(BluetoothAdapter.STATE_TURNING_OFF)

        observer.assertError(SystemServiceException.BluetoothUnavailableException::class.java)
        verify(exactly = 1) { scanner.stopScan(any<ScanCallback>()) }
    }
}
