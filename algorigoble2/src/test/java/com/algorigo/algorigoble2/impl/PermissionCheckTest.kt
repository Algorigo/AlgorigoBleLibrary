package com.algorigo.algorigoble2.impl

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanRecord
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import com.algorigo.algorigoble2.BleDevice
import com.algorigo.algorigoble2.BleManager
import com.algorigo.algorigoble2.BleScanSettings
import com.algorigo.algorigoble2.testutil.RxTestSchedulerRule
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * 런타임 권한을 확인하지 않아 원인을 알기 어려운 SecurityException 이 나던 문제(P2) 테스트.
 * 권한이 없을 때 플랫폼처럼 BluetoothDevice 호출이 SecurityException 을 던지도록 mock 을 구성한다.
 */
class PermissionCheckTest {

    @get:Rule
    val rxRule = RxTestSchedulerRule()

    private fun deny(context: Context, vararg permissions: String) {
        permissions.forEach { every { context.checkPermission(it, any(), any()) } returns PackageManager.PERMISSION_DENIED }
    }

    /** API 31+ 에서 BLUETOOTH_CONNECT 없이 BluetoothDevice 를 쓰면 플랫폼이 던지는 예외 */
    private fun platformDeniesConnect(device: BluetoothDevice) {
        val error = SecurityException("Need android.permission.BLUETOOTH_CONNECT permission")
        every { device.name } throws error
        every { device.bondState } throws error
        every { device.createBond() } throws error
        every { device.connectGatt(any(), any(), any(), any()) } throws error
    }

    private fun connectDeniedFixture(): GattTestFixture = GattTestFixture(sdkInt = Build.VERSION_CODES.S).also {
        deny(it.context, Manifest.permission.BLUETOOTH_CONNECT)
        platformDeniesConnect(it.bluetoothDevice)
    }

    // RxJava3 의 TestObserver 는 에러 목록을 공개하지 않으므로 assertError 로 꺼낸다
    private fun io.reactivex.rxjava3.observers.TestObserver<*>.singleError(): Throwable? {
        var error: Throwable? = null
        assertError { error = it; true }
        return error
    }

    private fun assertDenied(error: Throwable?, permission: String) {
        assertTrue("expected PermissionDeniedException but was $error", error is BleManager.PermissionDeniedException)
        assertEquals(listOf(permission), (error as BleManager.PermissionDeniedException).permissions)
    }

    @Test
    fun connect_withoutConnectPermission_failsWithPermissionDenied_beforeConnectGatt() {
        val fixture = connectDeniedFixture()

        val observer = fixture.bleDevice.connectCompletable().test()

        assertDenied(observer.singleError(), Manifest.permission.BLUETOOTH_CONNECT)
        verify(exactly = 0) { fixture.bluetoothDevice.connectGatt(any(), any(), any(), any()) }
        assertEquals(BleDevice.ConnectionState.DISCONNECTED, fixture.bleDevice.connectionState)
    }

    @Test
    fun deviceNameAndBonded_withoutConnectPermission_throwPermissionDenied() {
        val fixture = connectDeniedFixture()

        assertDenied(runCatching { fixture.bleDevice.deviceName }.exceptionOrNull(), Manifest.permission.BLUETOOTH_CONNECT)
        assertDenied(runCatching { fixture.bleDevice.bonded }.exceptionOrNull(), Manifest.permission.BLUETOOTH_CONNECT)
    }

    @Test
    fun toString_withoutConnectPermission_doesNotThrow() {
        val fixture = connectDeniedFixture()

        assertEquals("BleDevice (permission denied)(${GattTestFixture.ADDRESS})", fixture.bleDevice.toString())
    }

    @Test
    fun bond_withoutConnectPermission_failsWithPermissionDenied() {
        val fixture = connectDeniedFixture()

        assertDenied(fixture.bleDevice.bondCompletable().test().singleError(), Manifest.permission.BLUETOOTH_CONNECT)
        verify(exactly = 0) { fixture.bluetoothDevice.createBond() }
    }

    @Test
    fun connect_doesNotRequireConnectPermission_belowApi31() {
        val fixture = GattTestFixture(sdkInt = Build.VERSION_CODES.R)
        deny(fixture.context, Manifest.permission.BLUETOOTH_CONNECT)

        fixture.connect()

        assertEquals(BleDevice.ConnectionState.CONNECTED, fixture.bleDevice.connectionState)
    }

    @Test
    fun permissionRules_byApiLevel() {
        val context = mockk<Context>(relaxed = true)
        deny(context, Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

        assertEquals(listOf(Manifest.permission.BLUETOOTH_SCAN), BlePermissions.missingScanPermissions(context, Build.VERSION_CODES.S))
        assertEquals(listOf(Manifest.permission.ACCESS_FINE_LOCATION), BlePermissions.missingScanPermissions(context, Build.VERSION_CODES.R))
        assertEquals(listOf(Manifest.permission.ACCESS_COARSE_LOCATION), BlePermissions.missingScanPermissions(context, Build.VERSION_CODES.P))
        assertEquals(listOf(Manifest.permission.BLUETOOTH_CONNECT), BlePermissions.missingConnectPermissions(context, Build.VERSION_CODES.S))
        assertEquals(listOf<String>(), BlePermissions.missingConnectPermissions(context, Build.VERSION_CODES.R))

        // API 28 이하는 FINE 이 있으면 COARSE 가 없어도 된다
        every { context.checkPermission(Manifest.permission.ACCESS_FINE_LOCATION, any(), any()) } returns PackageManager.PERMISSION_GRANTED
        assertEquals(listOf<String>(), BlePermissions.missingScanPermissions(context, Build.VERSION_CODES.P))
    }

    private fun managerEngine(context: Context, adapter: BluetoothAdapter): BleManagerEngineImpl {
        val bluetoothManager = mockk<BluetoothManager>(relaxed = true)
        every { context.getSystemService(Context.BLUETOOTH_SERVICE) } returns bluetoothManager
        every { bluetoothManager.adapter } returns adapter
        return BleManagerEngineImpl(context, object : BleManager.BleDeviceDelegate() {
            override fun createBleDevice(bluetoothDevice: BluetoothDevice, scanRecord: ScanRecord?) = BleDevice()
        })
    }

    // 단위 테스트의 SDK_INT 는 0 이므로 스캔에는 위치 권한이 필요하다
    @Test
    fun scan_withoutPermission_failsWithPermissionDenied_beforeStartScan() {
        mockkObject(BleScanOptionsConverter)
        try {
            every { BleScanOptionsConverter.convertScanSettings(any()) } returns mockk()
            every { BleScanOptionsConverter.convertScanFilters(any()) } returns listOf()
            val context = mockk<Context>(relaxed = true)
            val adapter = mockk<BluetoothAdapter>(relaxed = true)
            val scanner = mockk<BluetoothLeScanner>(relaxed = true)
            val locationManager = mockk<LocationManager>(relaxed = true)
            every { context.getSystemService(Context.LOCATION_SERVICE) } returns locationManager
            every { locationManager.isProviderEnabled(any()) } returns true
            every { adapter.isEnabled } returns true
            every { adapter.bluetoothLeScanner } returns scanner
            deny(context, Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

            val observer = managerEngine(context, adapter).scanObservable(BleScanSettings.Builder().build()).test()

            assertDenied(observer.singleError(), Manifest.permission.ACCESS_COARSE_LOCATION)
            verify(exactly = 0) { scanner.startScan(any<List<ScanFilter>>(), any<ScanSettings>(), any<ScanCallback>()) }
        } finally {
            unmockkObject(BleScanOptionsConverter)
        }
    }

    @Test
    fun getDevice_worksWithoutPermissionToReadName() {
        val context = mockk<Context>(relaxed = true)
        val adapter = mockk<BluetoothAdapter>(relaxed = true)
        val bluetoothDevice = mockk<BluetoothDevice>(relaxed = true)
        every { bluetoothDevice.address } returns GattTestFixture.ADDRESS
        every { adapter.getRemoteDevice(GattTestFixture.ADDRESS) } returns bluetoothDevice
        platformDeniesConnect(bluetoothDevice)

        val device = managerEngine(context, adapter).getDevice<BleDevice>(GattTestFixture.ADDRESS)

        assertNotNull(device)
        assertEquals(GattTestFixture.ADDRESS, device!!.deviceId)
    }
}
