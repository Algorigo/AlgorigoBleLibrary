package com.algorigo.algorigoble2.impl

import com.algorigo.algorigoble2.BleDevice
import com.algorigo.algorigoble2.testutil.RxTestSchedulerRule
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.TimeUnit

/** BleDevice.connect() 가 돌려주는 Disposable 로 연결 시도를 취소할 수 있는지 확인한다. */
class ConnectDisposableTest {

    @get:Rule
    val rxRule = RxTestSchedulerRule()

    private val fixture = GattTestFixture()

    @Test
    fun disposingBeforeConnected_cancelsConnecting() {
        val disposable = fixture.bleDevice.connect()
        assertEquals(BleDevice.ConnectionState.CONNECTING, fixture.bleDevice.connectionState)

        disposable.dispose()

        verify { fixture.gatt.disconnect() }
        verify { fixture.gatt.close() }
        assertEquals(BleDevice.ConnectionState.DISCONNECTED, fixture.bleDevice.connectionState)
    }

    @Test
    fun disposingAfterConnected_keepsConnection() {
        val disposable = fixture.bleDevice.connect()
        fixture.simulateConnected()
        assertTrue(disposable.isDisposed)

        disposable.dispose()

        assertEquals(BleDevice.ConnectionState.CONNECTED, fixture.bleDevice.connectionState)
        verify(exactly = 0) { fixture.gatt.disconnect() }
    }

    // 실패는 로그로만 남고 RxJavaPlugins 로 새지 않는다 (앱이 크래시나지 않는다)
    @Test
    fun failure_isNotDeliveredToRxJavaPlugins() {
        fixture.bleDevice.connect()
        rxRule.testScheduler.advanceTimeBy(GattTestFixture.CONNECT_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)

        assertEquals(BleDevice.ConnectionState.DISCONNECTED, fixture.bleDevice.connectionState)
        assertEquals(listOf<Throwable>(), rxRule.undeliverableErrors)
    }
}
