package com.algorigo.algorigoble2.virtual

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.location.LocationManager
import com.algorigo.algorigoble2.BleCharacteristic
import com.algorigo.algorigoble2.BleDevice
import com.algorigo.algorigoble2.BleManager
import com.algorigo.algorigoble2.BleScanFilter
import com.algorigo.algorigoble2.BleScanSettings
import com.algorigo.algorigoble2.impl.BleScanOptionsConverter
import com.algorigo.algorigoble2.testutil.RxTestSchedulerRule
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.reactivex.rxjava3.core.Single
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * VirtualDevice 를 BleManager 에 등록해서 스캔 → 연결 → read/write/notification → 해제까지 공개 API 로 확인하는 통합 테스트.
 * 실기기 없이 BleManager/BleDevice 의 흐름을 검증한다.
 */
class VirtualDeviceIntegrationTest {

    @get:Rule
    val rxRule = RxTestSchedulerRule()

    private class Sensor : VirtualDevice(ADDRESS, NAME) {
        var value = byteArrayOf(0)
        val notifying = mutableSetOf<UUID>()

        override fun getCharacteristicsSingle(): Single<List<BleCharacteristic>> = Single.just(listOf(object : BleCharacteristic() {
            override val uuid = CHARACTERISTIC
            override fun isReadable() = true
            override fun isWritable() = true
            override fun isNotifyAvailable() = true
        }))
        override fun readCharacteristicSingle(characteristicUuid: UUID): Single<ByteArray> = Single.fromCallable { value }
        override fun writeCharacteristicSingle(characteristicUuid: UUID, byteArray: ByteArray): Single<ByteArray> =
            Single.fromCallable { value = byteArray; byteArray }
        override fun onNotificationStarted(type: BleDevice.NotificationType, characteristicUuid: UUID) {
            notifying += characteristicUuid
        }
        override fun onNotificationStop(characteristicUuid: UUID) {
            notifying -= characteristicUuid
        }
        fun emit(value: Byte) = notifyByteArray(CHARACTERISTIC, byteArrayOf(value))
    }

    private val context = mockk<Context>(relaxed = true)
    // 가상 기기는 만들 때 notification 전달용 worker 를 만들므로, 테스트 scheduler 가 설정된 뒤(@Before)에 만든다
    private lateinit var sensor: Sensor
    private lateinit var manager: BleManager

    @Before
    fun setUp() {
        sensor = Sensor()
        mockkObject(BleScanOptionsConverter)
        every { BleScanOptionsConverter.convertScanSettings(any()) } returns mockk()
        every { BleScanOptionsConverter.convertScanFilters(any()) } returns listOf()
        val bluetoothManager = mockk<BluetoothManager>(relaxed = true)
        val adapter = mockk<BluetoothAdapter>(relaxed = true)
        val scanner = mockk<BluetoothLeScanner>(relaxed = true)
        val locationManager = mockk<LocationManager>(relaxed = true)
        every { context.applicationContext } returns context
        every { context.getSystemService(Context.BLUETOOTH_SERVICE) } returns bluetoothManager
        every { context.getSystemService(Context.LOCATION_SERVICE) } returns locationManager
        every { locationManager.isProviderEnabled(any()) } returns true
        every { bluetoothManager.adapter } returns adapter
        every { adapter.isEnabled } returns true
        every { adapter.bluetoothLeScanner } returns scanner
        every { scanner.startScan(any<List<ScanFilter>>(), any<ScanSettings>(), any<ScanCallback>()) } just Runs
        manager = BleManager(context, virtualDevices = arrayOf(sensor to BleDevice()))
    }

    @After
    fun tearDown() {
        unmockkObject(BleScanOptionsConverter)
    }

    @Test
    fun scan_includesVirtualDevice_onlyWhenItMatchesFilter() {
        val matching = manager.scanObservable(BleScanSettings.Builder().build(), BleScanFilter.Builder().setDeviceName(NAME).build()).test()
        val other = manager.scanObservable(BleScanSettings.Builder().build(), BleScanFilter.Builder().setDeviceName("Other").build()).test()

        assertEquals(listOf(ADDRESS), matching.values().last().map { it.first.deviceId })
        assertTrue(matching.values().last().single().first.virtual)
        assertEquals(listOf<String>(), other.values().last().map { it.first.deviceId })
    }

    @Test
    fun connectReadWriteNotifyAndClose_throughPublicApi() {
        val events = manager.getConnectionStateObservable().map { it.second }.test()
        val device = manager.getDevice(ADDRESS)!!

        device.connectCompletable().test().also { rxRule.testScheduler.advanceTimeBy(100, TimeUnit.MILLISECONDS) }.assertComplete()
        assertEquals(listOf(device), manager.getConnectedDevices())
        assertEquals(listOf(CHARACTERISTIC), device.getCharacteristicsSingle().blockingGet().map { it.uuid })

        device.writeCharacteristicSingle(CHARACTERISTIC, byteArrayOf(7)).test().assertValue { it.contentEquals(byteArrayOf(7)) }
        device.readCharacteristicSingle(CHARACTERISTIC).test().assertValue { it.contentEquals(byteArrayOf(7)) }

        val notifications = device.setupNotification(BleDevice.NotificationType.NOTIFICATION, CHARACTERISTIC).flatMap { it }.test()
        assertEquals(setOf(CHARACTERISTIC), sensor.notifying)
        sensor.emit(1)
        sensor.emit(2)
        assertEquals(listOf<Byte>(1, 2), notifications.values().map { it.single() })
        notifications.dispose()
        assertEquals(setOf<UUID>(), sensor.notifying)

        manager.close()

        assertEquals(BleDevice.ConnectionState.DISCONNECTED, device.connectionState)
        device.readCharacteristicSingle(CHARACTERISTIC).test().assertError(BleManager.DisconnectedException::class.java)
        assertEquals(
            listOf(BleDevice.ConnectionState.CONNECTING, BleDevice.ConnectionState.CONNECTED, BleDevice.ConnectionState.DISCONNECTED),
            events.values(),
        )
    }

    companion object {
        private const val ADDRESS = "12:34:56:78:90:AA"
        private const val NAME = "Sensor"
        private val CHARACTERISTIC = UUID.fromString("0000aaa1-0000-1000-8000-aabbccddeeff")
    }
}
