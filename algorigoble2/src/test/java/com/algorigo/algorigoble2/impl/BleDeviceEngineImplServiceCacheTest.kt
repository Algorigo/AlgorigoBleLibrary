package com.algorigo.algorigoble2.impl

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import com.algorigo.algorigoble2.BleDevice
import com.algorigo.algorigoble2.BleManager
import com.algorigo.algorigoble2.testutil.RxTestSchedulerRule
import io.mockk.every
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.util.UUID

/**
 * 재연결이나 서비스 변경(onServiceChanged) 후에도 이전 서비스 목록/characteristic 객체를 쓰던 문제(P1)에 대한 테스트.
 */
class BleDeviceEngineImplServiceCacheTest {

    @get:Rule
    val rxRule = RxTestSchedulerRule()

    private val fixture = GattTestFixture()
    private val oldCharacteristic = fixture.addCharacteristic(OLD_UUID, BluetoothGattCharacteristic.PROPERTY_READ)

    private fun characteristicUuids(): List<UUID> =
        fixture.bleDevice.getCharacteristicsSingle().test().values().single().map { it.uuid }

    private fun reconnectWith(newGatt: BluetoothGatt) {
        fixture.disconnectFromRemote()
        fixture.stubConnectGatt(newGatt)
        val observer = fixture.engine.connectCompletable(GattTestFixture.CONNECT_TIMEOUT_MILLIS).test()
        fixture.simulateConnected(newGatt)
        observer.assertComplete()
    }

    @Test
    fun getCharacteristicsSingle_returnsServicesOfNewConnection_afterReconnect() {
        fixture.connect()
        assertEquals(listOf(OLD_UUID), characteristicUuids())

        val newGatt = fixture.newGatt()
        fixture.addCharacteristic(NEW_UUID, BluetoothGattCharacteristic.PROPERTY_READ, newGatt)
        reconnectWith(newGatt)

        assertEquals(listOf(NEW_UUID), characteristicUuids())
    }

    @Test
    fun readCharacteristicSingle_usesCharacteristicOfNewConnection_afterReconnect() {
        fixture.connect()
        fixture.bleDevice.readCharacteristicSingle(OLD_UUID).test()
        fixture.callback.onCharacteristicRead(fixture.gatt, oldCharacteristic, BluetoothGatt.GATT_SUCCESS)

        val newGatt = fixture.newGatt()
        val newCharacteristic = fixture.addCharacteristic(OLD_UUID, BluetoothGattCharacteristic.PROPERTY_READ, newGatt)
        reconnectWith(newGatt)
        fixture.bleDevice.readCharacteristicSingle(OLD_UUID).test()

        verify(exactly = 1) { newGatt.readCharacteristic(newCharacteristic) }
        verify(exactly = 0) { newGatt.readCharacteristic(oldCharacteristic) }
    }

    @Test
    fun onServiceChanged_rediscoversServices_andReflectsNewDatabase() {
        fixture.connect()
        assertEquals(listOf(OLD_UUID), characteristicUuids())

        fixture.addCharacteristic(NEW_UUID, BluetoothGattCharacteristic.PROPERTY_READ)
        fixture.callback.onServiceChanged(fixture.gatt)

        verify(exactly = 2) { fixture.gatt.discoverServices() }
        fixture.simulateServicesDiscovered()
        assertEquals(listOf(OLD_UUID, NEW_UUID), characteristicUuids())
    }

    @Test
    fun onServiceChanged_doesNotEmitConnectedAgain() {
        fixture.connect()
        val states = fixture.bleDevice.getConnectionStateObservable().test()

        fixture.callback.onServiceChanged(fixture.gatt)
        fixture.simulateServicesDiscovered()

        states.assertValues(BleDevice.ConnectionState.CONNECTED)
    }

    @Test
    fun operations_waitUntilRediscoveryCompletes_afterServiceChanged() {
        every { oldCharacteristic.value } returns byteArrayOf(7)
        fixture.connect()
        fixture.callback.onServiceChanged(fixture.gatt)

        val observer = fixture.bleDevice.readCharacteristicSingle(OLD_UUID).test()
        verify(exactly = 0) { fixture.gatt.readCharacteristic(any()) }

        fixture.simulateServicesDiscovered()
        verify(exactly = 1) { fixture.gatt.readCharacteristic(oldCharacteristic) }
        fixture.callback.onCharacteristicRead(fixture.gatt, oldCharacteristic, BluetoothGatt.GATT_SUCCESS)

        observer.assertValue { it.contentEquals(byteArrayOf(7)) }
    }

    @Test
    fun operationWaitingForRediscovery_failsWithDisconnectedException_whenDisconnected() {
        fixture.connect()
        fixture.callback.onServiceChanged(fixture.gatt)
        val observer = fixture.bleDevice.readCharacteristicSingle(OLD_UUID).test()

        fixture.disconnectFromRemote()

        observer.assertError(BleManager.DisconnectedException::class.java)
    }

    @Test
    fun operations_areNotBlocked_whenRediscoveryCannotStart() {
        fixture.connect()
        every { fixture.gatt.discoverServices() } returns false
        fixture.callback.onServiceChanged(fixture.gatt)

        fixture.bleDevice.readCharacteristicSingle(OLD_UUID).test()

        verify(exactly = 1) { fixture.gatt.readCharacteristic(oldCharacteristic) }
    }

    @Test
    fun setupNotification_isNotSetUpAgain_afterServiceRediscovery() {
        val notifyCharacteristic = fixture.addCharacteristic(NOTIFY_UUID, BluetoothGattCharacteristic.PROPERTY_NOTIFY)
        val descriptor = notifyCharacteristic.getDescriptor(GattTestFixture.CCCD_UUID)
        fixture.connect()
        val observer = fixture.bleDevice.setupNotification(BleDevice.NotificationType.NOTIFICATION, NOTIFY_UUID).test()
        fixture.callback.onDescriptorWrite(fixture.gatt, descriptor, BluetoothGatt.GATT_SUCCESS)
        observer.assertValueCount(1)

        fixture.callback.onServiceChanged(fixture.gatt)
        fixture.simulateServicesDiscovered()

        verify(exactly = 1) { fixture.gatt.writeDescriptor(any()) }
        observer.assertValueCount(1)
        observer.assertNoErrors()
    }

    companion object {
        private val OLD_UUID: UUID = UUID.fromString("0000c001-0000-1000-8000-00805f9b34fb")
        private val NEW_UUID: UUID = UUID.fromString("0000c002-0000-1000-8000-00805f9b34fb")
        private val NOTIFY_UUID: UUID = UUID.fromString("0000c003-0000-1000-8000-00805f9b34fb")
    }
}
