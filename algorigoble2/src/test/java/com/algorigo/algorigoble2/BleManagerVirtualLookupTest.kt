package com.algorigo.algorigoble2

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import com.algorigo.algorigoble2.testutil.RxTestSchedulerRule
import com.algorigo.algorigoble2.virtual.VirtualDevice
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.reactivex.rxjava3.core.Single
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * BleManager.getDevice() 가 가상 기기보다 실제 엔진을 먼저 조회하던 문제(P2) 테스트.
 */
class BleManagerVirtualLookupTest {

    @get:Rule
    val rxRule = RxTestSchedulerRule()

    private class Virtual(address: String) : VirtualDevice(address, "Virtual") {
        override fun getCharacteristicsSingle(): Single<List<BleCharacteristic>> = Single.just(listOf())
        override fun readCharacteristicSingle(characteristicUuid: UUID): Single<ByteArray> = Single.just(byteArrayOf())
        override fun writeCharacteristicSingle(characteristicUuid: UUID, byteArray: ByteArray): Single<ByteArray> = Single.just(byteArray)
        override fun onNotificationStarted(type: BleDevice.NotificationType, characteristicUuid: UUID) {}
        override fun onNotificationStop(characteristicUuid: UUID) {}
    }

    private val context = mockk<Context>(relaxed = true)
    private val adapter = mockk<BluetoothAdapter>(relaxed = true)

    init {
        val bluetoothManager = mockk<BluetoothManager>(relaxed = true)
        every { context.applicationContext } returns context
        every { context.getSystemService(Context.BLUETOOTH_SERVICE) } returns bluetoothManager
        every { bluetoothManager.adapter } returns adapter
        // 실제 BluetoothAdapter 처럼 대문자 MAC 만 받는다
        every { adapter.getRemoteDevice(any<String>()) } answers {
            val address = firstArg<String>()
            if (address != address.uppercase()) throw IllegalArgumentException("$address is not a valid Bluetooth address")
            mockk<BluetoothDevice>(relaxed = true).also { every { it.address } returns address }
        }
    }

    private val validVirtual = BleDevice()
    private val lowercaseVirtual = BleDevice()
    private val manager = BleManager(
        context,
        virtualDevices = arrayOf(Virtual(VALID_ADDRESS) to validVirtual, Virtual(LOWERCASE_ADDRESS) to lowercaseVirtual),
    )

    @Test
    fun getDevice_returnsVirtualDevice_whenItsAddressIsAValidMac() {
        assertSame(validVirtual, manager.getDevice(VALID_ADDRESS))
    }

    @Test
    fun getDevice_returnsVirtualDevice_ignoringCase_withoutQueryingAdapter() {
        assertSame(lowercaseVirtual, manager.getDevice(LOWERCASE_ADDRESS))
        assertSame(lowercaseVirtual, manager.getDevice(LOWERCASE_ADDRESS.uppercase()))
        verify(exactly = 0) { adapter.getRemoteDevice(any<String>()) }
    }

    @Test
    fun getDevice_returnsRealDevice_forOtherAddress() {
        val device = manager.getDevice(REAL_ADDRESS)

        assertFalse(device!!.virtual)
        assertTrue(device.deviceId == REAL_ADDRESS)
    }

    @Test
    fun getConnectedDevice_findsVirtualDevice_ignoringCase() {
        assertNull(manager.getConnectedDevice(LOWERCASE_ADDRESS.uppercase()))
        lowercaseVirtual.connectCompletable().test()
        rxRule.testScheduler.advanceTimeBy(100, TimeUnit.MILLISECONDS)

        assertSame(lowercaseVirtual, manager.getConnectedDevice(LOWERCASE_ADDRESS.uppercase()))
    }

    // 엔진을 고르던 이전 생성자도 같은 BleManager 를 만든다
    @Suppress("DEPRECATION")
    @Test
    fun deprecatedEngineConstructor_stillWorks() {
        val device = BleDevice()
        val delegate = object : BleManager.BleDeviceDelegate() {
            override fun createBleDevice(bluetoothDevice: BluetoothDevice, scanRecord: android.bluetooth.le.ScanRecord?) = BleDevice()
        }
        val legacy = BleManager(context, delegate, BleManager.Engine.ALGORIGO_BLE, arrayOf(Virtual(VALID_ADDRESS) to device))

        assertSame(device, legacy.getDevice(VALID_ADDRESS))
        assertTrue(device.virtual)
    }

    companion object {
        private const val VALID_ADDRESS = "12:34:56:78:90:01"
        private const val LOWERCASE_ADDRESS = "12:34:56:78:90:ab"
        private const val REAL_ADDRESS = "00:11:22:33:44:55"
    }
}
