package com.algorigo.algorigoble2.virtual

import com.algorigo.algorigoble2.BleCharacteristic
import com.algorigo.algorigoble2.BleDevice
import com.algorigo.algorigoble2.BleManager
import com.algorigo.algorigoble2.testutil.RxTestSchedulerRule
import io.reactivex.rxjava3.core.Single
import org.junit.Rule
import org.junit.Test
import java.util.UUID
import java.util.concurrent.TimeUnit

class VirtualDeviceEngineMtuTest {

    @get:Rule
    val rxRule = RxTestSchedulerRule()

    private val virtualDevice = object : VirtualDevice("AA:BB:CC:DD:EE:FF", "Virtual") {
        override fun getCharacteristicsSingle(): Single<List<BleCharacteristic>> = Single.just(listOf())
        override fun readCharacteristicSingle(characteristicUuid: UUID): Single<ByteArray> = Single.just(byteArrayOf())
        override fun writeCharacteristicSingle(characteristicUuid: UUID, byteArray: ByteArray): Single<ByteArray> = Single.just(byteArray)
        override fun onNotificationStarted(type: BleDevice.NotificationType, characteristicUuid: UUID) {}
        override fun onNotificationStop(characteristicUuid: UUID) {}
    }

    private val bleDevice = BleDevice().apply { initEngine(VirtualDeviceEngine(virtualDevice)) }

    @Test
    fun requestMtuSingle_returnsMtuClampedToValidRange_whenConnected() {
        val connectObserver = bleDevice.connectCompletable().test()
        rxRule.testScheduler.advanceTimeBy(100, TimeUnit.MILLISECONDS)
        connectObserver.assertComplete()

        bleDevice.requestMtuSingle(247).test().assertValue(247)
        bleDevice.requestMtuSingle(1000).test().assertValue(517)
        bleDevice.requestMtuSingle(10).test().assertValue(23)
    }

    @Test
    fun requestMtuSingle_failsWithDisconnectedException_whenNotConnected() {
        bleDevice.requestMtuSingle(247).test()
            .assertError(BleManager.DisconnectedException::class.java)
    }
}
