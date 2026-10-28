package com.algorigo.algorigoble2

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import com.algorigo.algorigoble2.testutil.RxTestSchedulerRule
import com.algorigo.algorigoble2.virtual.VirtualDevice
import io.mockk.every
import io.mockk.mockk
import io.reactivex.rxjava3.core.Single
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import java.util.UUID

/** getDevice(mac) 등이 Android 가 받는 형식(대문자, ":")이 아닌 MAC 도 받는지 확인한다. */
class MacAddressNormalizationTest {

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
    private val bluetoothDevices = mutableMapOf<String, BluetoothDevice>()

    init {
        val bluetoothManager = mockk<BluetoothManager>(relaxed = true)
        every { context.applicationContext } returns context
        every { context.getSystemService(Context.BLUETOOTH_SERVICE) } returns bluetoothManager
        every { bluetoothManager.adapter } returns adapter
        // 실제 BluetoothAdapter 처럼 "AA:BB:CC:DD:EE:FF" 형식만 받는다
        every { adapter.getRemoteDevice(any<String>()) } answers {
            val address = firstArg<String>()
            if (!BluetoothAdapterAddress.matches(address)) throw IllegalArgumentException("$address is not a valid Bluetooth address")
            bluetoothDevices.getOrPut(address) { mockk<BluetoothDevice>(relaxed = true).also { every { it.address } returns address } }
        }
    }

    private val virtual = BleDevice()
    private val manager = BleManager(context, virtualDevices = arrayOf(Virtual("12:34:56:78:90:ab") to virtual))

    @Test
    fun normalize_acceptsCaseAndSeparators() {
        assertEquals(ADDRESS, BleManager.normalizeMacAddress("aa:bb:cc:dd:ee:0f"))
        assertEquals(ADDRESS, BleManager.normalizeMacAddress("AABBCCDDEE0F"))
        assertEquals(ADDRESS, BleManager.normalizeMacAddress(" aa-bb-cc-dd-ee-0f "))
        assertEquals(ADDRESS, BleManager.normalizeMacAddress(ADDRESS))
        // MAC 형식이 아니면 대문자로만 바꾼다
        assertEquals("VIRTUAL-1", BleManager.normalizeMacAddress("virtual-1"))
    }

    @Test
    fun getDevice_acceptsLowercaseAndUnseparatedAddress_andReturnsSameDevice() {
        val device = manager.getDevice("aa:bb:cc:dd:ee:0f")!!

        assertEquals(ADDRESS, device.deviceId)
        assertSame(device, manager.getDevice("aabbccddee0f"))
        assertSame(device, manager.getDevice(ADDRESS))
    }

    @Test
    fun invalidAddress_returnsNull() {
        assertNull(manager.getDevice("aa:bb:cc"))
        assertNull(manager.getDevice("zz:bb:cc:dd:ee:ff"))
    }

    @Test
    fun bondedDevice_isFoundByLowercaseAddress() {
        val bonded = adapter.getRemoteDevice(ADDRESS)
        every { adapter.bondedDevices } returns setOf(bonded)

        assertEquals(ADDRESS, manager.getBondedDevice("aa:bb:cc:dd:ee:0f")!!.deviceId)
    }

    @Test
    fun virtualDevice_isFoundByAnyFormat() {
        assertSame(virtual, manager.getDevice("1234567890AB"))
        assertSame(virtual, manager.getDevice("12-34-56-78-90-ab"))
    }

    companion object {
        private const val ADDRESS = "AA:BB:CC:DD:EE:0F"
        private val BluetoothAdapterAddress = Regex("([0-9A-F]{2}:){5}[0-9A-F]{2}")
    }
}
