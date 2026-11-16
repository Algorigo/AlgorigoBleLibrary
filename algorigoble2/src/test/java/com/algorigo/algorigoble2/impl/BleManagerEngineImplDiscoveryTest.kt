package com.algorigo.algorigoble2.impl

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.bluetooth.le.ScanRecord
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import com.algorigo.algorigoble2.BleDevice
import com.algorigo.algorigoble2.BleManager
import com.algorigo.algorigoble2.ScanInfo
import com.algorigo.algorigoble2.exception.SystemServiceException
import com.algorigo.algorigoble2.testutil.RxTestSchedulerRule
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import io.reactivex.rxjava3.observers.TestObserver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.IOException
import java.util.UUID

/**
 * Classic 기기 검색(discoveryObservable) 테스트. 단위 테스트의 SDK_INT 는 0 이므로 기본으로는 API 30 이하의 위치 확인 경로를 탄다.
 * receiver 등록 순서(SDK 0): [엔진의 블루투스 꺼짐 감지, 위치 provider 변경, 기기 검색, 블루투스 상태 변경].
 * 블루투스 상태의 현재 값으로 스캔 가능 상태가 먼저 나오고 그 뒤에 블루투스 상태 receiver 를 등록하므로, 기기 검색 receiver 가 그보다 먼저다.
 */
class BleManagerEngineImplDiscoveryTest {

    @get:Rule
    val rxRule = RxTestSchedulerRule()

    private val context = mockk<Context>(relaxed = true)
    private val bluetoothManager = mockk<BluetoothManager>(relaxed = true)
    private val adapter = mockk<BluetoothAdapter>(relaxed = true)
    private val locationManager = mockk<LocationManager>(relaxed = true)
    private val receivers = mutableListOf<BroadcastReceiver>()
    // startDiscovery() 가 불릴 때마다 그때까지 등록된 receiver 수
    private val registeredAtStart = mutableListOf<Int>()
    private val bluetoothDevices = mutableMapOf<String, BluetoothDevice>()
    // 기기 이름. 없으면 이름을 아직 모르는 기기
    private val names = mutableMapOf<String, String>()
    private val delegateCalls = mutableListOf<String>()

    // 이름으로 기기를 고르는 delegate. 이름을 모르면 만들지 않는다
    private val delegate = object : BleManager.BleDeviceDelegate() {
        override fun createBleDevice(bluetoothDevice: BluetoothDevice, scanRecord: ScanRecord?): BleDevice? {
            delegateCalls.add(bluetoothDevice.address)
            return if (bluetoothDevice.name?.startsWith("SPP") == true) BleDevice() else null
        }
    }

    @Before
    fun setUp() {
        every { context.getSystemService(Context.BLUETOOTH_SERVICE) } returns bluetoothManager
        every { context.getSystemService(Context.LOCATION_SERVICE) } returns locationManager
        every { context.registerReceiver(capture(receivers), any<IntentFilter>()) } returns null
        every { bluetoothManager.adapter } returns adapter
        every { adapter.isEnabled } returns true
        every { adapter.startDiscovery() } answers {
            registeredAtStart.add(receivers.size)
            true
        }
        every { locationManager.isProviderEnabled(any()) } returns true
    }

    private fun engine(sdkInt: Int = Build.VERSION.SDK_INT) = BleManagerEngineImpl(context, delegate, sdkInt)

    private fun bluetoothDevice(address: String) = bluetoothDevices.getOrPut(address) {
        mockk<BluetoothDevice>(relaxed = true).also { device ->
            every { device.address } returns address
            every { device.name } answers { names[address] }
            every { adapter.getRemoteDevice(address) } returns device
        }
    }

    // 검색을 처음 시작하기 직전에 등록된 receiver
    private fun discoveryReceiver() = receivers[registeredAtStart.first() - 1]

    private fun bluetoothStateReceiver() = receivers.last()

    private fun broadcast(action: String, address: String? = null, rssi: Short? = null) {
        val intent = mockk<Intent>(relaxed = true)
        every { intent.action } returns action
        every { intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) } returns address?.let { bluetoothDevice(it) }
        every { intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, any()) } answers { rssi ?: secondArg() }
        discoveryReceiver().onReceive(context, intent)
    }

    private fun found(address: String, rssi: Short? = -60) = broadcast(BluetoothDevice.ACTION_FOUND, address, rssi)

    private fun nameChanged(address: String, name: String) {
        names[address] = name
        broadcast(BluetoothDevice.ACTION_NAME_CHANGED, address)
    }

    private fun discoveryFinished() = broadcast(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)

    private fun TestObserver<List<Pair<BleDevice, ScanInfo>>>.lastAddresses() = values().last().map { it.first.deviceId }

    private fun TestObserver<*>.singleError(): Throwable {
        var error: Throwable? = null
        assertError { error = it; true }
        return error!!
    }

    @Test
    fun startsDiscovery_afterRegisteringReceiver_andEmitsEmptyListFirst() {
        names[A] = "SPP-A"
        val observer = engine().discoveryObservable().test()
        observer.assertValue(listOf())

        // 검색을 시작하기 직전에 등록된 receiver 가 검색 결과를 받는다
        found(A)

        verify(exactly = 1) { adapter.startDiscovery() }
        assertEquals(listOf(A), observer.lastAddresses())
        observer.assertNoErrors()
    }

    @Test
    fun foundDevices_keepFirstFoundOrder_andUpdateRssi() {
        names[A] = "SPP-A"
        names[B] = "SPP-B"
        val observer = engine().discoveryObservable().test()

        found(A, rssi = -70)
        found(B, rssi = -50)
        found(A, rssi = -40)

        assertEquals(listOf(A, B), observer.lastAddresses())
        assertEquals(-40, observer.values().last()[0].second.rssi)
        assertEquals(-50, observer.values().last()[1].second.rssi)
    }

    @Test
    fun unknownRssi_isNull() {
        names[A] = "SPP-A"
        val observer = engine().discoveryObservable().test()

        found(A, rssi = null)

        assertNull(observer.values().last().single().second.rssi)
    }

    @Test
    fun discoveryFinished_restartsDiscovery_andKeepsList() {
        names[A] = "SPP-A"
        names[B] = "SPP-B"
        val observer = engine().discoveryObservable().test()
        found(A)

        discoveryFinished()
        found(B)

        verify(exactly = 2) { adapter.startDiscovery() }
        assertEquals(listOf(A, B), observer.lastAddresses())
        observer.assertNoErrors().assertNotComplete()
    }

    @Test
    fun dispose_unregistersReceiver_thenCancelsDiscovery() {
        val observer = engine().discoveryObservable().test()
        val receiver = discoveryReceiver()

        observer.dispose()

        verifyOrder {
            context.unregisterReceiver(receiver)
            adapter.cancelDiscovery()
        }
        verify(exactly = 1) { adapter.startDiscovery() }
    }

    @Test
    fun cancelDiscoveryFailure_onDispose_isNotDeliveredAsUndeliverableError() {
        every { adapter.cancelDiscovery() } throws SecurityException("Need BLUETOOTH_SCAN")
        val observer = engine().discoveryObservable().test()

        observer.dispose()

        assertEquals(listOf<Throwable>(), rxRule.undeliverableErrors)
    }

    // 오래된 SPP 모듈은 이름 없이 발견되고 이름이 나중에 온다
    @Test
    fun deviceFoundWithoutName_isAddedWithItsRssi_whenNameArrives() {
        val observer = engine().discoveryObservable().test()

        found(A, rssi = -55)
        assertEquals(1, observer.values().size)

        nameChanged(A, "SPP-A")

        assertEquals(listOf(A), observer.lastAddresses())
        assertEquals(-55, observer.values().last().single().second.rssi)
        assertEquals(listOf(A, A), delegateCalls)
    }

    @Test
    fun deviceStillRejectedAfterNameArrives_isNotAdded() {
        val observer = engine().discoveryObservable().test()
        found(A)

        nameChanged(A, "Headset")

        assertEquals(1, observer.values().size)
        assertEquals(listOf(A, A), delegateCalls)
    }

    @Test
    fun nameChanged_forDeviceNotFoundInThisDiscovery_isIgnored() {
        val observer = engine().discoveryObservable().test()

        nameChanged(A, "SPP-A")

        assertEquals(1, observer.values().size)
        assertEquals(listOf<String>(), delegateCalls)
    }

    @Test
    fun nameChanged_forListedDevice_emitsListAgain_withoutCreatingAnotherDevice() {
        names[A] = "SPP-A"
        val observer = engine().discoveryObservable().test()
        found(A)
        val device = observer.values().last().single().first

        nameChanged(A, "SPP-A2")

        assertEquals(3, observer.values().size)
        assertTrue(observer.values().last().single().first === device)
        assertEquals("SPP-A2", device.deviceName)
        assertEquals(listOf(A), delegateCalls)
    }

    @Test
    fun withoutConnectPermission_failsWithPermissionDenied_beforeStartingDiscovery() {
        every { context.checkPermission(Manifest.permission.BLUETOOTH_CONNECT, any(), any()) } returns PackageManager.PERMISSION_DENIED

        val error = engine(Build.VERSION_CODES.S).discoveryObservable().test().singleError()

        assertEquals(listOf(Manifest.permission.BLUETOOTH_CONNECT), (error as BleManager.PermissionDeniedException).permissions)
        verify(exactly = 0) { adapter.startDiscovery() }
    }

    @Test
    fun withoutScanPermission_failsWithPermissionDenied_beforeStartingDiscovery() {
        every { context.checkPermission(Manifest.permission.BLUETOOTH_SCAN, any(), any()) } returns PackageManager.PERMISSION_DENIED

        val error = engine(Build.VERSION_CODES.S).discoveryObservable().test().singleError()

        assertEquals(listOf(Manifest.permission.BLUETOOTH_SCAN), (error as BleManager.PermissionDeniedException).permissions)
        verify(exactly = 0) { adapter.startDiscovery() }
    }

    @Test
    fun locationDisabled_belowApi31_failsWithLocationUnavailable_beforeStartingDiscovery() {
        every { locationManager.isProviderEnabled(any()) } returns false

        engine().discoveryObservable().test()
            .assertError(SystemServiceException.LocationUnavailableException::class.java)

        verify(exactly = 0) { adapter.startDiscovery() }
    }

    @Test
    fun bluetoothTurningOff_failsWithBluetoothUnavailable_andCancelsDiscovery() {
        val observer = engine().discoveryObservable().test()

        val intent = mockk<Intent>(relaxed = true)
        every { intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, any()) } returns BluetoothAdapter.STATE_TURNING_OFF
        bluetoothStateReceiver().onReceive(context, intent)

        observer.assertError(SystemServiceException.BluetoothUnavailableException::class.java)
        verify(exactly = 1) { adapter.cancelDiscovery() }
    }

    @Test
    fun startDiscoveryRejected_whileBluetoothIsOn_failsWithDiscoveryFailed() {
        every { adapter.startDiscovery() } returns false

        engine().discoveryObservable().test().assertError(BleManager.DiscoveryFailedException::class.java)
    }

    // 블루투스가 꺼지는 중이면 스캔 가능 상태의 에러(BluetoothUnavailableException)로 끝나게 둔다
    @Test
    fun startDiscoveryRejected_whileBluetoothIsTurningOff_doesNotFailWithDiscoveryFailed() {
        val observer = engine().discoveryObservable().test()
        every { adapter.startDiscovery() } returns false
        every { adapter.isEnabled } returns false

        discoveryFinished()

        observer.assertNoErrors()
    }

    private fun connectSppWhileDiscovering(connectFails: Boolean) {
        names[A] = "SPP-A"
        val engine = engine()
        bluetoothDevice(A)
        val device = engine.getDevice<BleDevice>(A)!!
        val observer = engine.discoveryObservable().test()
        val socket = mockk<BluetoothSocket>(relaxed = true)
        var startsWhileConnecting = -1
        every { bluetoothDevice(A).createRfcommSocketToServiceRecord(SPP_UUID) } returns socket
        every { socket.connect() } answers {
            // SppConnection 이 연결 전에 멈춘 검색의 종료 broadcast 가 연결 중에 온다
            assertEquals(BleDevice.ConnectionState.SPP_CONNECTING, device.connectionState)
            discoveryFinished()
            startsWhileConnecting = registeredAtStart.size
            if (connectFails) throw IOException("read failed, socket might closed or timeout")
        }

        val connect = device.connectSppSocket(SPP_UUID).test()

        assertEquals(if (connectFails) 0 else 1, connect.values().size)
        assertEquals(1, startsWhileConnecting)
        // 연결 시도가 끝나면 검색을 다시 시작한다
        verify(exactly = 2) { adapter.startDiscovery() }
        verifyOrder {
            adapter.cancelDiscovery()
            socket.connect()
            adapter.startDiscovery()
        }
        observer.assertNoErrors()
    }

    @Test
    fun discoveryIsNotRestarted_whileSppIsConnecting_andResumesAfterConnected() {
        connectSppWhileDiscovering(connectFails = false)
    }

    @Test
    fun discoveryIsNotRestarted_whileSppIsConnecting_andResumesAfterConnectFails() {
        connectSppWhileDiscovering(connectFails = true)
    }

    companion object {
        private const val A = "00:11:22:33:44:01"
        private const val B = "00:11:22:33:44:02"
        private val SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805f9b34fb")
    }
}
