package com.algorigo.algorigoble2

import com.algorigo.algorigoble2.testutil.RxTestSchedulerRule
import com.jakewharton.rxrelay3.BehaviorRelay
import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.subjects.CompletableSubject
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * InitializableBleDevice 의 초기화에 타임아웃이 없고, 실패하면 disconnect() 가 두 번 호출되던 문제(P2) 테스트.
 */
class InitializableBleDeviceInitTimeoutTest {

    @get:Rule
    val rxRule = RxTestSchedulerRule()

    /** 바로 연결되고 disconnect() 호출 횟수를 세는 engine */
    private class FakeEngine : BleDeviceEngine() {
        val state = BehaviorRelay.createDefault(BleDevice.ConnectionState.DISCONNECTED)
        var disconnectCount = 0
        override val deviceId = "00:11:22:33:44:55"
        override val deviceName: String? = null
        override val bonded = false
        override fun bondCompletable(): Completable = Completable.complete()
        override fun unbondCompletable(): Completable = Completable.complete()
        override fun getConnectionStateObservable(): Observable<BleDevice.ConnectionState> = state
        override fun connectCompletable(timeoutMillis: Long): Completable = Completable.fromAction { state.accept(BleDevice.ConnectionState.CONNECTED) }
        override fun disconnect() {
            disconnectCount++
            state.accept(BleDevice.ConnectionState.DISCONNECTED)
            bleDevice.onDisconnected()
        }
        override fun getCharacteristicsSingle(): Single<List<BleCharacterisic>> = Single.never()
        override fun readCharacteristicSingle(serviceUuid: UUID?, characteristicUuid: UUID): Single<ByteArray> = Single.never()
        override fun writeCharacteristicSingle(serviceUuid: UUID?, characteristicUuid: UUID, byteArray: ByteArray, writeType: BleDevice.WriteType): Single<ByteArray> = Single.never()
        override fun setupNotification(type: BleDevice.NotificationType, serviceUuid: UUID?, characteristicUuid: UUID): Observable<Observable<ByteArray>> = Observable.never()
        override fun connectSppSocket(uuid: UUID?): Observable<BleSppSocket> = Observable.never()
        override fun requestMtuSingle(mtu: Int): Single<Int> = Single.never()
        override fun requestConnectionPriorityCompletable(priority: BleDevice.ConnectionPriority): Completable = Completable.never()
    }

    private val initialize = CompletableSubject.create()
    private val device = object : InitializableBleDevice() {
        override fun initializeCompletable(): Completable = initialize
    }
    private val engine = FakeEngine().also { device.initEngine(it) }

    @Test
    fun initialization_timesOut_andDisconnectsOnce() {
        val connect = device.connectCompletable().test()
        assertEquals(BleDevice.ConnectionState.INITIALIZING, device.connectionState)

        rxRule.testScheduler.advanceTimeBy(InitializableBleDevice.DEFAULT_INITIALIZE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)

        connect.assertError(BleManager.OperationTimeoutException::class.java)
        assertEquals(1, engine.disconnectCount)
        assertEquals(BleDevice.ConnectionState.DISCONNECTED, device.connectionState)
    }

    @Test
    fun initializeTimeout_isConfigurable() {
        device.initializeTimeoutMillis = 3_000
        val connect = device.connectCompletable().test()

        rxRule.testScheduler.advanceTimeBy(2_999, TimeUnit.MILLISECONDS)
        connect.assertNotComplete().assertNoErrors()
        rxRule.testScheduler.advanceTimeBy(1, TimeUnit.MILLISECONDS)

        connect.assertError(BleManager.OperationTimeoutException::class.java)
    }

    @Test
    fun initializationFailure_disconnectsOnce() {
        val connect = device.connectCompletable().test()

        initialize.onError(IllegalStateException("init failed"))

        connect.assertError(IllegalStateException::class.java)
        assertEquals(1, engine.disconnectCount)
    }

    @Test
    fun disposingDuringInitialization_disconnects() {
        val connect = device.connectCompletable().test()

        connect.dispose()

        assertEquals(1, engine.disconnectCount)
        assertEquals(BleDevice.ConnectionState.DISCONNECTED, device.connectionState)
    }

    @Test
    fun completedInitialization_isNotDisconnected() {
        val connect = device.connectCompletable().test()

        initialize.onComplete()
        connect.dispose()

        connect.assertComplete()
        assertEquals(0, engine.disconnectCount)
        assertEquals(BleDevice.ConnectionState.CONNECTED, device.connectionState)
    }
}
