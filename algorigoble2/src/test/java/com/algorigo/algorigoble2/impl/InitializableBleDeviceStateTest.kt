package com.algorigo.algorigoble2.impl

import com.algorigo.algorigoble2.BleDevice
import com.algorigo.algorigoble2.InitializableBleDevice
import com.algorigo.algorigoble2.testutil.RxTestSchedulerRule
import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.subjects.CompletableSubject
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * InitializableBleDevice 의 초기화 단계가 ConnectionState.INITIALIZING 으로 보이는지 확인한다.
 */
class InitializableBleDeviceStateTest {

    @get:Rule
    val rxRule = RxTestSchedulerRule()

    private val initialize = CompletableSubject.create()
    private val device = object : InitializableBleDevice() {
        override fun initializeCompletable(): Completable = initialize
    }
    private val fixture = GattTestFixture(device)

    @Test
    fun connectionState_isInitializing_untilInitializationCompletes() {
        val connect = device.connectCompletable().test()
        fixture.simulateConnected()

        assertEquals(BleDevice.ConnectionState.INITIALIZING, device.connectionState)
        assertEquals("INITIALIZING", device.connectionState.status)
        connect.assertNotComplete()

        initialize.onComplete()

        connect.assertComplete()
        assertEquals(BleDevice.ConnectionState.CONNECTED, device.connectionState)
    }

    @Test
    fun connectionStates_followConnectInitializeDisconnectOrder() {
        val states = device.getConnectionStateObservable().test()

        device.connectCompletable().test()
        fixture.simulateConnected()
        initialize.onComplete()
        fixture.disconnectFromRemote()

        states.assertValues(
            BleDevice.ConnectionState.DISCONNECTED,
            BleDevice.ConnectionState.CONNECTING,
            BleDevice.ConnectionState.INITIALIZING,
            BleDevice.ConnectionState.CONNECTED,
            BleDevice.ConnectionState.DISCONNECTED,
        )
    }

    @Test
    fun initializationFailure_disconnects_andReportsDisconnected() {
        val connect = device.connectCompletable().test()
        fixture.simulateConnected()

        initialize.onError(IllegalStateException("init failed"))
        fixture.disconnectFromRemote()

        connect.assertError(IllegalStateException::class.java)
        assertEquals(BleDevice.ConnectionState.DISCONNECTED, device.connectionState)
    }

    @Test
    fun reconnect_goesThroughInitializingAgain() {
        device.connectCompletable().test()
        fixture.simulateConnected()
        initialize.onComplete()
        fixture.disconnectFromRemote()

        val newGatt = fixture.newGatt()
        fixture.stubConnectGatt(newGatt)
        device.connectCompletable().test()
        fixture.simulateConnected(newGatt)

        // initialize 는 이미 완료된 subject 라 다시 구독하면 바로 완료된다. 재연결 후 CONNECTED 가 되어야 한다
        assertEquals(BleDevice.ConnectionState.CONNECTED, device.connectionState)
    }
}
