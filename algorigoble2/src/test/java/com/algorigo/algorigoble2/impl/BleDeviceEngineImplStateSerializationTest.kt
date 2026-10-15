package com.algorigo.algorigoble2.impl

import com.algorigo.algorigoble2.BleDevice
import com.algorigo.algorigoble2.testutil.RxTestSchedulerRule
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * 여러 스레드(바인더 콜백, Rx 타이머, 앱)가 동시에 상태를 바꾸면 관찰자가 상태를 동시에, 순서가 뒤바뀐 채로 받던 문제(P2) 테스트.
 */
class BleDeviceEngineImplStateSerializationTest {

    @get:Rule
    val rxRule = RxTestSchedulerRule()

    private val executor = Executors.newFixedThreadPool(2)

    @After
    fun tearDown() {
        executor.shutdownNow()
    }

    @Test
    fun concurrentStateChanges_areDeliveredOneAtATime_inTheOrderTheyHappened() {
        val overlapped = AtomicBoolean(false)
        val mismatches = mutableListOf<String>()

        repeat(ITERATIONS) { iteration ->
            val fixture = GattTestFixture()
            fixture.connect()
            val active = AtomicInteger()
            val received = Collections.synchronizedList(mutableListOf<BleDevice.ConnectionState>())
            fixture.engine.getConnectionStateObservable().subscribe {
                if (active.incrementAndGet() > 1) overlapped.set(true)
                received.add(it)
                // 상태를 받아 처리하는 데 시간이 걸리는 관찰자
                Thread.sleep(10)
                active.decrementAndGet()
            }

            // 앱 스레드의 disconnect() 와 바인더 스레드의 연결 끊김 콜백이 동시에 일어난다
            val barrier = CyclicBarrier(2)
            listOf(
                executor.submit { barrier.await(); fixture.engine.disconnect() },
                executor.submit { barrier.await(); fixture.disconnectFromRemote() },
            ).forEach { it.get(5, TimeUnit.SECONDS) }

            val current = fixture.bleDevice.connectionState
            if (received.last() != current) {
                mismatches.add("#$iteration received=$received current=$current")
            }
        }

        assertTrue("observer received states concurrently", !overlapped.get())
        assertEquals("last received state must be the current state", listOf<String>(), mismatches)
    }

    companion object {
        private const val ITERATIONS = 20
    }
}
