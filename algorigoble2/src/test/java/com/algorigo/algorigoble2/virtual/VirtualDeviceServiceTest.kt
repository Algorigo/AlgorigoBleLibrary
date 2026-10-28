package com.algorigo.algorigoble2.virtual

import android.bluetooth.BluetoothGattCharacteristic
import com.algorigo.algorigoble2.BleCharacterisic
import com.algorigo.algorigoble2.BleDevice
import com.algorigo.algorigoble2.BleManager
import com.algorigo.algorigoble2.testutil.RxTestSchedulerRule
import io.reactivex.rxjava3.core.Single
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.UUID
import java.util.concurrent.TimeUnit

class VirtualDeviceServiceTest {

    @get:Rule
    val rxRule = RxTestSchedulerRule()

    private lateinit var bleDevice: BleDevice

    // 기존 가상 characteristic 처럼 새 항목을 override 하지 않은 것
    private val plain = object : BleCharacterisic() {
        override val uuid: UUID = PLAIN_UUID
        override fun isReadable() = true
        override fun isWritable() = false
        override fun isNotifyAvailable() = true
    }

    private val inService = object : BleCharacterisic() {
        override val uuid: UUID = IN_SERVICE_UUID
        override fun isReadable() = false
        override fun isWritable() = true
        override fun isNotifyAvailable() = false
        override val serviceUuid: UUID = SERVICE_UUID
    }

    @Before
    fun setUp() {
        val virtualDevice = object : VirtualDevice("AA:BB:CC:DD:EE:03", "Virtual") {
            override fun getCharacteristicsSingle(): Single<List<BleCharacterisic>> = Single.just(listOf(plain, inService))
            override fun readCharacteristicSingle(characteristicUuid: UUID): Single<ByteArray> = Single.just(byteArrayOf())
            override fun writeCharacteristicSingle(characteristicUuid: UUID, byteArray: ByteArray): Single<ByteArray> = Single.just(byteArray)
            override fun onNotificationStarted(type: BleDevice.NotificationType, characteristicUuid: UUID) {}
            override fun onNotificationStop(characteristicUuid: UUID) {}
        }
        bleDevice = BleDevice().apply { initEngine(VirtualDeviceEngine(virtualDevice)) }
    }

    @Test
    fun characteristics_areGroupedByServiceUuid() {
        bleDevice.connectCompletable().test().also { rxRule.testScheduler.advanceTimeBy(100, TimeUnit.MILLISECONDS) }.assertComplete()

        val services = bleDevice.getServicesSingle().test().values().single()

        assertEquals(listOf(VirtualDevice.UNKNOWN_SERVICE_UUID, SERVICE_UUID), services.map { it.uuid })
        assertEquals(listOf(listOf(plain), listOf(inService)), services.map { it.characteristics })
    }

    @Test
    fun defaultProperties_comeFromCapabilityFunctions() {
        assertEquals(BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY, plain.properties)
        assertEquals(BluetoothGattCharacteristic.PROPERTY_WRITE, inService.properties)
        assertEquals(null, plain.serviceUuid)
        assertEquals(listOf<UUID>(), plain.descriptorUuids)
    }

    @Test
    fun services_whenNotConnected_failWithDisconnectedException() {
        bleDevice.getServicesSingle().test().assertError(BleManager.DisconnectedException::class.java)
    }

    companion object {
        private val SERVICE_UUID = UUID.fromString("0000aaa0-0000-1000-8000-aabbccddeeff")
        private val PLAIN_UUID = UUID.fromString("0000aaa1-0000-1000-8000-aabbccddeeff")
        private val IN_SERVICE_UUID = UUID.fromString("0000aaa2-0000-1000-8000-aabbccddeeff")
    }
}
