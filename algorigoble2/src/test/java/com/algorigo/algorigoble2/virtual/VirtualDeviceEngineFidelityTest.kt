package com.algorigo.algorigoble2.virtual

import com.algorigo.algorigoble2.BleCharacteristic
import com.algorigo.algorigoble2.BleDevice
import com.algorigo.algorigoble2.BleManager
import com.algorigo.algorigoble2.InitializableBleDevice
import com.algorigo.algorigoble2.testutil.RxTestSchedulerRule
import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.subjects.CompletableSubject
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

internal class TestVirtualDevice : VirtualDevice("12:34:56:78:90:AB", "Virtual") {
    override fun getCharacteristicsSingle(): Single<List<BleCharacteristic>> = Single.just(listOf())
    override fun readCharacteristicSingle(characteristicUuid: UUID): Single<ByteArray> = Single.just(byteArrayOf())
    override fun writeCharacteristicSingle(characteristicUuid: UUID, byteArray: ByteArray): Single<ByteArray> = Single.just(byteArray)
    override fun onNotificationStarted(type: BleDevice.NotificationType, characteristicUuid: UUID) {}
    override fun onNotificationStop(characteristicUuid: UUID) {}
    fun send(characteristicUuid: UUID, value: Int) = notifyByteArray(characteristicUuid, byteArrayOf((value shr 8).toByte(), value.toByte()))
}

/**
 * VirtualDevice 동작이 실기기와 다르던 문제(P2) 테스트: disconnect() 가 onDisconnected() 를 호출하지 않음, 연결 중 disconnect.
 */
class VirtualDeviceEngineFidelityTest {

    @get:Rule
    val rxRule = RxTestSchedulerRule()

    private class InitDevice : InitializableBleDevice() {
        var init = CompletableSubject.create()
        override fun initializeCompletable(): Completable = init
    }

    private class CountingDevice : BleDevice() {
        var disconnectedCount = 0
        override fun onDisconnected() {
            disconnectedCount++
        }
    }

    private fun <T : BleDevice> virtualized(device: T): T = device.also { it.initEngine(VirtualDeviceEngine(TestVirtualDevice())) }

    @Test
    fun initializableDevice_reportsInitializing_afterReconnect() {
        val device = virtualized(InitDevice())
        device.connectCompletable(10_000).test()
        rxRule.testScheduler.advanceTimeBy(100, TimeUnit.MILLISECONDS)
        device.init.onComplete()
        assertEquals(BleDevice.ConnectionState.CONNECTED, device.connectionState)

        device.disconnect()
        device.init = CompletableSubject.create()
        device.connectCompletable(10_000).test()
        rxRule.testScheduler.advanceTimeBy(100, TimeUnit.MILLISECONDS)

        assertEquals(BleDevice.ConnectionState.INITIALIZING, device.connectionState)
    }

    @Test
    fun disconnect_callsOnDisconnected_onlyWhenItActuallyDisconnects() {
        val device = virtualized(CountingDevice())
        device.connectCompletable(10_000).test()
        rxRule.testScheduler.advanceTimeBy(100, TimeUnit.MILLISECONDS)

        device.disconnect()
        device.disconnect()

        assertEquals(1, device.disconnectedCount)
        assertEquals(BleDevice.ConnectionState.DISCONNECTED, device.connectionState)
    }

    @Test
    fun disconnect_whileConnecting_failsConnect_andStaysDisconnected() {
        val device = virtualized(BleDevice())
        val connect = device.connectCompletable(10_000).test()

        device.disconnect()
        rxRule.testScheduler.advanceTimeBy(100, TimeUnit.MILLISECONDS)

        connect.assertError(BleManager.DisconnectedException::class.java)
        assertEquals(BleDevice.ConnectionState.DISCONNECTED, device.connectionState)
    }

    @Test
    fun disposingConnect_whileConnecting_cancelsIt() {
        val device = virtualized(BleDevice())
        val connect = device.connectCompletable(10_000).test()

        connect.dispose()

        assertEquals(BleDevice.ConnectionState.DISCONNECTED, device.connectionState)
    }
}

/** notification 전달 순서. 실제 스레드에서 확인하므로 scheduler 를 바꾸지 않는다. */
class VirtualDeviceNotificationOrderTest {

    @Test
    fun notifications_areDeliveredInSentOrder() {
        val virtualDevice = TestVirtualDevice()
        val device = BleDevice().also { it.initEngine(VirtualDeviceEngine(virtualDevice)) }
        device.connectCompletable(10_000).blockingAwait()
        val uuid = UUID.randomUUID()
        val received = mutableListOf<Int>()
        val count = 1000
        val done = CountDownLatch(count)
        device.setupNotification(BleDevice.NotificationType.NOTIFICATION, uuid)
            .flatMap { it }
            .subscribe {
                synchronized(received) { received.add(((it[0].toInt() and 0xFF) shl 8) or (it[1].toInt() and 0xFF)) }
                done.countDown()
            }

        repeat(count) { virtualDevice.send(uuid, it) }
        done.await(10, TimeUnit.SECONDS)

        assertEquals((0 until count).toList(), synchronized(received) { received.toList() })
    }
}
