package com.algorigo.algorigoble2.impl

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanRecord
import android.content.Context
import com.algorigo.algorigoble2.BleDevice
import com.algorigo.algorigoble2.BleManager
import com.algorigo.algorigoble2.testutil.RxTestSchedulerRule
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** getDevice(macAddress, Class) 가 하위 클래스를 인자 없는 생성자로 만드는지 확인한다. */
class GetDeviceByClassTest {

    @get:Rule
    val rxRule = RxTestSchedulerRule()

    class CustomDevice : BleDevice()
    class NoDefaultConstructorDevice(@Suppress("unused") val value: Int) : BleDevice()

    private val context = mockk<Context>(relaxed = true)
    private val adapter = mockk<BluetoothAdapter>(relaxed = true)

    init {
        val bluetoothManager = mockk<BluetoothManager>(relaxed = true)
        every { context.getSystemService(Context.BLUETOOTH_SERVICE) } returns bluetoothManager
        every { bluetoothManager.adapter } returns adapter
        every { adapter.getRemoteDevice(any<String>()) } answers {
            mockk<BluetoothDevice>(relaxed = true).also { every { it.address } returns firstArg() }
        }
    }

    private val engine = BleManagerEngineImpl(context, object : BleManager.BleDeviceDelegate() {
        override fun createBleDevice(bluetoothDevice: BluetoothDevice, scanRecord: ScanRecord?) = BleDevice()
    })

    @Test
    fun getDevice_createsSubclassWithNoArgConstructor() {
        val device = engine.getDevice(ADDRESS, CustomDevice::class.java)

        assertTrue(device is CustomDevice)
        assertEquals(ADDRESS, device!!.deviceId)
        assertSame(device, engine.getDevice(ADDRESS, CustomDevice::class.java))
    }

    @Test(expected = IllegalArgumentException::class)
    fun getDevice_withoutNoArgConstructor_failsWithClearError() {
        engine.getDevice(ADDRESS, NoDefaultConstructorDevice::class.java)
    }

    companion object {
        private const val ADDRESS = "00:11:22:33:44:55"
    }
}
