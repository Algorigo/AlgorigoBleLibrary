package com.algorigo.algorigoble2.impl

import com.algorigo.algorigoble2.BleDevice
import com.algorigo.algorigoble2.InitializableBleDevice
import com.algorigo.algorigoble2.testutil.RxTestSchedulerRule
import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.subjects.CompletableSubject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * InitializableBleDevice 가 ConnectionState.CONNECTING 싱글턴의 status 를 바꿔
 * 모든 기기의 CONNECTING 표시가 "INITIALING" 으로 바뀌던 문제(P1) 테스트. (기존 API 만 사용)
 */
class ConnectionStateGlobalMutationTest {

    @get:Rule
    val rxRule = RxTestSchedulerRule()

    private val initialize = CompletableSubject.create()
    private val initializableDevice = object : InitializableBleDevice() {
        override fun initializeCompletable(): Completable = initialize
    }
    private val fixture = GattTestFixture(initializableDevice)

    @Test
    fun initializingDevice_doesNotChangeConnectingStatusOfOtherDevices() {
        initializableDevice.connectCompletable().test()
        fixture.simulateConnected()
        initializableDevice.connectionState // 초기화 중 상태를 계산하게 한다

        val otherDevice = GattTestFixture()
        otherDevice.engine.connectCompletable(GattTestFixture.CONNECT_TIMEOUT_MILLIS).test()

        assertEquals(BleDevice.ConnectionState.CONNECTING, otherDevice.bleDevice.connectionState)
        assertEquals("CONNECTING", otherDevice.bleDevice.connectionState.status)
        assertEquals("CONNECTING", BleDevice.ConnectionState.CONNECTING.status)
    }

    @Test
    fun initializableDevice_doesNotEmitDuplicateStates() {
        val states = initializableDevice.getConnectionStateObservable().test()

        initializableDevice.connectCompletable().test()
        fixture.simulateConnected()
        initialize.onComplete()
        fixture.disconnectFromRemote()

        val values = states.values()
        assertTrue("duplicate states: $values", values.zipWithNext().none { (a, b) -> a == b })
        assertEquals(BleDevice.ConnectionState.CONNECTED, values[values.size - 2])
        assertEquals(BleDevice.ConnectionState.DISCONNECTED, values.last())
    }
}
