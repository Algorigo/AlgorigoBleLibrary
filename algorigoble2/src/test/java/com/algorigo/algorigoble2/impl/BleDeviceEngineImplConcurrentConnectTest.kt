package com.algorigo.algorigoble2.impl

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothSocket
import com.algorigo.algorigoble2.BleDevice
import com.algorigo.algorigoble2.BleManager
import com.algorigo.algorigoble2.testutil.RxTestSchedulerRule
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.reactivex.rxjava3.observers.TestObserver
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 상태 확인과 CONNECTING 전환이 원자적이지 않아 동시에 connect 하면 connectGatt 가 여러 번 불리고 gatt 가 누수되던 문제(P2) 테스트.
 */
class BleDeviceEngineImplConcurrentConnectTest {

    @get:Rule
    val rxRule = RxTestSchedulerRule()

    private val fixture = GattTestFixture()
    private val executor = Executors.newFixedThreadPool(THREADS)
    private val connectGattCalls = AtomicInteger()

    @After
    fun tearDown() {
        executor.shutdownNow()
    }

    /** connectGatt 가 바인더 호출처럼 시간이 걸리도록 해서 경쟁 구간을 넓힌다. 호출마다 다른 gatt 를 반환한다. */
    private fun stubSlowConnectGatt() {
        val gatts = ConcurrentLinkedQueue((1..THREADS).map { mockk<BluetoothGatt>(relaxed = true) })
        every { fixture.bluetoothDevice.connectGatt(any(), any(), any(), any()) } answers {
            connectGattCalls.incrementAndGet()
            Thread.sleep(50)
            gatts.poll()
        }
    }

    private fun stubSlowRfcommSocket(): AtomicInteger {
        val created = AtomicInteger()
        every { fixture.bluetoothDevice.createRfcommSocketToServiceRecord(any()) } answers {
            created.incrementAndGet()
            Thread.sleep(50)
            mockk<BluetoothSocket>(relaxed = true)
        }
        return created
    }

    @Test
    fun concurrentConnect_callsConnectGattOnlyOnce() {
        stubSlowConnectGatt()
        val barrier = CyclicBarrier(THREADS)

        val observers = (1..THREADS).map {
            executor.submit(Callable {
                barrier.await()
                fixture.engine.connectCompletable(GattTestFixture.CONNECT_TIMEOUT_MILLIS).test()
            })
        }.map { it.get(5, TimeUnit.SECONDS) }

        assertEquals(1, connectGattCalls.get())
        // 하나는 연결을 기다리고, 나머지는 이미 연결 중이라 InvalidConnectionStateException 으로 끝난다
        assertEquals(1, observers.count { it.isWaiting() })
        assertEquals(THREADS - 1, observers.count { it.failedWith(BleManager.InvalidConnectionStateException::class.java) })
        assertEquals(BleDevice.ConnectionState.CONNECTING, fixture.bleDevice.connectionState)
    }

    @Test
    fun concurrentGattAndSppConnect_startOnlyOneConnection() {
        stubSlowConnectGatt()
        val rfcommCreated = stubSlowRfcommSocket()
        val barrier = CyclicBarrier(2)

        val gattConnect = executor.submit(Callable {
            barrier.await()
            fixture.engine.connectCompletable(GattTestFixture.CONNECT_TIMEOUT_MILLIS).test()
        })
        val sppConnect = executor.submit(Callable {
            barrier.await()
            fixture.engine.connectSppSocket(SPP_UUID).test()
        })
        val observers: List<TestObserver<*>> = listOf(gattConnect.get(5, TimeUnit.SECONDS), sppConnect.get(5, TimeUnit.SECONDS))

        assertEquals("only one of connectGatt / RFCOMM socket must be created", 1, connectGattCalls.get() + rfcommCreated.get())
        assertEquals(1, observers.count { it.failedWith(BleManager.InvalidConnectionStateException::class.java) })
    }

    @Test
    fun connectSpp_whileGattConnected_failsWithoutChangingGattState() {
        fixture.connect()
        val rfcommCreated = stubSlowRfcommSocket()

        fixture.engine.connectSppSocket(SPP_UUID).test()
            .assertError(BleManager.InvalidConnectionStateException::class.java)

        assertEquals(0, rfcommCreated.get())
        assertEquals(BleDevice.ConnectionState.CONNECTED, fixture.bleDevice.connectionState)
        verify(exactly = 0) { fixture.gatt.close() }
    }

    private fun TestObserver<*>.isWaiting() = runCatching { assertNoErrors().assertNotComplete() }.isSuccess

    private fun TestObserver<*>.failedWith(type: Class<out Throwable>) = runCatching { assertError(type) }.isSuccess

    companion object {
        private const val THREADS = 8
        private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805f9b34fb")
    }
}
