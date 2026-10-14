package com.algorigo.algorigoble2.impl

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanRecord
import android.content.Context
import com.algorigo.algorigoble2.BleDevice
import com.algorigo.algorigoble2.BleManager
import io.mockk.every
import io.mockk.mockk
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Callable
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * deviceMap 이 thread-safe 하지 않아 같은 기기에 BleDevice(와 engine)가 여러 개 만들어지던 문제(P1) 테스트.
 */
class BleManagerEngineImplDeviceMapTest {

    private val context = mockk<Context>(relaxed = true)
    private val bluetoothManager = mockk<BluetoothManager>(relaxed = true)
    private val adapter = mockk<BluetoothAdapter>(relaxed = true)
    private val createdCount = AtomicInteger()
    private var creationDelayMillis = 0L
    private val executor = Executors.newFixedThreadPool(THREADS)

    private val delegate = object : BleManager.BleDeviceDelegate() {
        override fun createBleDevice(bluetoothDevice: BluetoothDevice, scanRecord: ScanRecord?): BleDevice {
            createdCount.incrementAndGet()
            // 무거운 기기 객체를 만드는 상황을 흉내 내어 경쟁 구간을 넓힌다
            if (creationDelayMillis > 0) Thread.sleep(creationDelayMillis)
            return BleDevice()
        }
    }

    init {
        every { context.getSystemService(Context.BLUETOOTH_SERVICE) } returns bluetoothManager
        every { bluetoothManager.adapter } returns adapter
        every { bluetoothManager.getConnectedDevices(any()) } returns listOf()
    }

    @After
    fun tearDown() {
        executor.shutdownNow()
    }

    private fun mockDevice(address: String) = mockk<BluetoothDevice>(relaxed = true).also {
        every { it.address } returns address
        every { adapter.getRemoteDevice(address) } returns it
    }

    @Test
    fun concurrentGetDevice_forSameDevice_createsOnlyOneBleDevice() {
        mockDevice(MAC)
        creationDelayMillis = 50
        val engine = BleManagerEngineImpl(context, delegate)
        val barrier = CyclicBarrier(THREADS)

        val devices = (1..THREADS).map {
            executor.submit(Callable {
                barrier.await()
                engine.getDevice<BleDevice>(MAC, null)!!.also { device -> device.deviceId } // engine 이 초기화되어 있어야 한다
            })
        }.map { it.get(5, TimeUnit.SECONDS) }

        assertTrue("all callers must get the same instance", devices.all { it === devices[0] })
        assertEquals(1, createdCount.get())
        assertEquals(1, engine.getDevices().size)
    }

    @Test
    fun iteratingDevices_whileOtherThreadAddsDevices_doesNotThrow() {
        val addresses = (0 until 300).map { "00:11:22:33:%02X:%02X".format(it / 256, it % 256) }
        addresses.forEach { mockDevice(it) }
        val engine = BleManagerEngineImpl(context, delegate)
        val adding = AtomicBoolean(true)

        val writer = executor.submit {
            addresses.forEach { engine.getDevice<BleDevice>(it, null) }
            adding.set(false)
        }
        val reader = executor.submit(Callable {
            var iterations = 0
            while (adding.get()) {
                engine.getDevices().forEach { it.deviceId }
                engine.getConnectedDevices()
                iterations++
            }
            iterations
        })

        writer.get(10, TimeUnit.SECONDS)
        reader.get(10, TimeUnit.SECONDS)
        assertEquals(addresses.size, engine.getDevices().size)
    }

    companion object {
        private const val THREADS = 8
        private const val MAC = "00:11:22:33:44:55"
    }
}
