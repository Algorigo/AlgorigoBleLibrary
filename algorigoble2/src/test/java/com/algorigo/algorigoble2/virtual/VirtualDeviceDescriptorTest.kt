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

class VirtualDeviceDescriptorTest {

    @get:Rule
    val rxRule = RxTestSchedulerRule()

    private lateinit var bleDevice: BleDevice
    private lateinit var plainDevice: BleDevice

    private open class TestVirtualDevice(id: String) : VirtualDevice(id, "Virtual") {
        override fun getCharacteristicsSingle(): Single<List<BleCharacteristic>> = Single.just(listOf())
        override fun readCharacteristicSingle(characteristicUuid: UUID): Single<ByteArray> = Single.just(byteArrayOf())
        override fun writeCharacteristicSingle(characteristicUuid: UUID, byteArray: ByteArray): Single<ByteArray> = Single.just(byteArray)
        override fun onNotificationStarted(type: BleDevice.NotificationType, characteristicUuid: UUID) {}
        override fun onNotificationStop(characteristicUuid: UUID) {}
    }

    @Before
    fun setUp() {
        val withDescriptor = object : TestVirtualDevice("AA:BB:CC:DD:EE:01") {
            override fun readDescriptorSingle(characteristicUuid: UUID, descriptorUuid: UUID): Single<ByteArray> = Single.just(byteArrayOf(3))
            override fun writeDescriptorSingle(characteristicUuid: UUID, descriptorUuid: UUID, byteArray: ByteArray): Single<ByteArray> = Single.just(byteArray)
        }
        bleDevice = BleDevice().apply { initEngine(VirtualDeviceEngine(withDescriptor)) }
        plainDevice = BleDevice().apply { initEngine(VirtualDeviceEngine(TestVirtualDevice("AA:BB:CC:DD:EE:02"))) }
    }

    private fun connect(device: BleDevice) {
        val observer = device.connectCompletable().test()
        rxRule.testScheduler.advanceTimeBy(100, TimeUnit.MILLISECONDS)
        observer.assertComplete()
    }

    @Test
    fun overriddenDescriptor_isReadAndWritten_whenConnected() {
        connect(bleDevice)

        bleDevice.readDescriptorSingle(CHARACTERISTIC_UUID, DESCRIPTOR_UUID).test().assertValue { it.contentEquals(byteArrayOf(3)) }
        bleDevice.writeDescriptorSingle(CHARACTERISTIC_UUID, DESCRIPTOR_UUID, byteArrayOf(4)).test().assertValue { it.contentEquals(byteArrayOf(4)) }
    }

    @Test
    fun defaultVirtualDevice_hasNoDescriptor() {
        connect(plainDevice)

        plainDevice.readDescriptorSingle(CHARACTERISTIC_UUID, DESCRIPTOR_UUID).test()
            .assertError(BleManager.DescriptorNotFoundException::class.java)
    }

    @Test
    fun cccdWrite_andDisconnectedAccess_fail() {
        connect(bleDevice)
        bleDevice.writeDescriptorSingle(CHARACTERISTIC_UUID, CCCD_UUID, byteArrayOf(1, 0)).test()
            .assertError(IllegalArgumentException::class.java)

        bleDevice.disconnect()
        bleDevice.readDescriptorSingle(CHARACTERISTIC_UUID, DESCRIPTOR_UUID).test()
            .assertError(BleManager.DisconnectedException::class.java)
    }

    companion object {
        private val CHARACTERISTIC_UUID = UUID.fromString("0000aaa1-0000-1000-8000-aabbccddeeff")
        private val DESCRIPTOR_UUID = UUID.fromString("00002901-0000-1000-8000-00805f9b34fb")
        private val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
