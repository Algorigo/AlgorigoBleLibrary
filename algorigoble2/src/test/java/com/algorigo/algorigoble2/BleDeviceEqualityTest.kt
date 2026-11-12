package com.algorigo.algorigoble2

import android.bluetooth.BluetoothDevice
import android.content.Context
import com.algorigo.algorigoble2.impl.BleDeviceEngineImpl
import io.mockk.every
import io.mockk.mockk
import io.reactivex.rxjava3.core.Observable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * BleDevice 가 equals 만 override 하고 hashCode 는 override 하지 않던 문제,
 * initEngine() 전에 equals/toString 을 부르면 UninitializedPropertyAccessException 이 나던 문제(P2) 테스트.
 */
class BleDeviceEqualityTest {

    private open class SubDevice : BleDevice()

    private fun device(address: String, device: BleDevice = BleDevice()): BleDevice {
        val bluetoothDevice = mockk<BluetoothDevice>(relaxed = true)
        every { bluetoothDevice.address } returns address
        every { bluetoothDevice.name } returns "Name"
        device.initEngine(BleDeviceEngineImpl(mockk<Context>(relaxed = true), bluetoothDevice))
        return device
    }

    @Test
    fun sameDeviceId_isOneElement_inHashBasedCollections() {
        val first = device(ADDRESS)
        val second = device(ADDRESS)

        assertEquals(first, second)
        assertEquals(first.hashCode(), second.hashCode())
        assertEquals(1, hashSetOf(first, second).size)
        assertEquals(1, Observable.just(first, second).distinct().toList().blockingGet().size)
        assertEquals("value", hashMapOf<BleDevice, String>(first to "value")[second])
    }

    @Test
    fun differentDeviceId_isNotEqual() {
        assertNotEquals(device(ADDRESS), device(OTHER_ADDRESS))
        assertEquals(2, hashSetOf(device(ADDRESS), device(OTHER_ADDRESS)).size)
    }

    @Test
    fun subclassWithSameDeviceId_isEqual() {
        val sub = device(ADDRESS, SubDevice())
        val plain = device(ADDRESS)

        assertEquals(sub, plain)
        assertEquals(plain, sub)
        assertEquals(sub.hashCode(), plain.hashCode())
    }

    @Test
    fun beforeInitEngine_equalsHashCodeToString_doNotThrow() {
        val uninitialized = BleDevice()
        val otherUninitialized = BleDevice()
        val initialized = device(ADDRESS)

        assertTrue(uninitialized == uninitialized)
        assertFalse(uninitialized == otherUninitialized)
        assertFalse(uninitialized == initialized)
        assertFalse(initialized == uninitialized)
        assertEquals(System.identityHashCode(uninitialized), uninitialized.hashCode())
        assertEquals("BleDevice(engine not initialized)", uninitialized.toString())
    }

    @Test
    fun toString_includesNameAndDeviceId_afterInitEngine() {
        assertEquals("BleDevice Name($ADDRESS)", device(ADDRESS).toString())
    }

    companion object {
        private const val ADDRESS = "00:11:22:33:44:55"
        private const val OTHER_ADDRESS = "00:11:22:33:44:66"
    }
}
