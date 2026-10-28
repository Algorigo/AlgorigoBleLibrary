package com.algorigo.algorigoble2.virtual

import com.algorigo.algorigoble2.BleCharacteristic
import com.algorigo.algorigoble2.BleDevice
import com.algorigo.algorigoble2.BleManager
import com.algorigo.algorigoble2.testutil.RxTestSchedulerRule
import io.reactivex.rxjava3.core.Single
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.UUID
import java.util.concurrent.TimeUnit

class VirtualDeviceRssiTest {

    @get:Rule
    val rxRule = RxTestSchedulerRule()

    private lateinit var bleDevice: BleDevice

    @Before
    fun setUp() {
        val virtualDevice = object : VirtualDevice("AA:BB:CC:DD:EE:FF", "Virtual") {
            override fun readRssi(): Int = -55
            override fun getCharacteristicsSingle(): Single<List<BleCharacteristic>> = Single.just(listOf())
            override fun readCharacteristicSingle(characteristicUuid: UUID): Single<ByteArray> = Single.just(byteArrayOf())
            override fun writeCharacteristicSingle(characteristicUuid: UUID, byteArray: ByteArray): Single<ByteArray> = Single.just(byteArray)
            override fun onNotificationStarted(type: BleDevice.NotificationType, characteristicUuid: UUID) {}
            override fun onNotificationStop(characteristicUuid: UUID) {}
        }
        bleDevice = BleDevice().apply { initEngine(VirtualDeviceEngine(virtualDevice)) }
    }

    @Test
    fun readRssi_returnsVirtualDeviceRssi_whenConnected() {
        val connectObserver = bleDevice.connectCompletable().test()
        rxRule.testScheduler.advanceTimeBy(100, TimeUnit.MILLISECONDS)
        connectObserver.assertComplete()

        bleDevice.readRssiSingle().test().assertValue(-55)
    }

    @Test
    fun readRssi_failsWithDisconnectedException_whenNotConnected() {
        bleDevice.readRssiSingle().test()
            .assertError(BleManager.DisconnectedException::class.java)
    }
}
