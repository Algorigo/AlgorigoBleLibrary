package com.algorigo.algorigoble2.impl

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import com.algorigo.algorigoble2.BleDevice
import com.algorigo.algorigoble2.BleManager
import com.algorigo.algorigoble2.testutil.RxTestSchedulerRule
import io.mockk.every
import io.mockk.verify
import io.reactivex.rxjava3.observers.TestObserver
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.UUID

/**
 * 같은 characteristic 을 여러 곳에서 notification 구독할 때, 하나만 해제해도 CCCD 가 꺼져 전부 끊기던 문제(P1) 테스트.
 */
class BleDeviceEngineImplNotificationSharingTest {

    @get:Rule
    val rxRule = RxTestSchedulerRule()

    private val fixture = GattTestFixture()
    private val characteristic = fixture.addCharacteristic(
        NOTIFY_UUID, BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_INDICATE
    )
    private val descriptor = characteristic.getDescriptor(GattTestFixture.CCCD_UUID)

    @Before
    fun setUp() {
        fixture.connect()
    }

    private fun subscribe(type: BleDevice.NotificationType = BleDevice.NotificationType.NOTIFICATION): TestObserver<ByteArray> =
        fixture.bleDevice.setupNotification(type, NOTIFY_UUID).flatMap { it }.test()

    private fun replyDescriptorWrite() =
        fixture.callback.onDescriptorWrite(fixture.gatt, descriptor, BluetoothGatt.GATT_SUCCESS)

    private fun notify(value: Byte) {
        every { characteristic.value } returns byteArrayOf(value)
        fixture.callback.onCharacteristicChanged(fixture.gatt, characteristic)
    }

    @Test
    fun secondSubscriber_sharesSetup_withoutWritingCccdAgain() {
        val first = subscribe()
        replyDescriptorWrite()
        val second = subscribe()

        notify(1)

        verify(exactly = 1) { fixture.gatt.writeDescriptor(descriptor) }
        first.assertValue { it.contentEquals(byteArrayOf(1)) }
        second.assertValue { it.contentEquals(byteArrayOf(1)) }
    }

    @Test
    fun subscriberRequestedBeforeSetupCompletes_sharesSameSetup() {
        val first = fixture.bleDevice.setupNotification(BleDevice.NotificationType.NOTIFICATION, NOTIFY_UUID).test()
        val second = fixture.bleDevice.setupNotification(BleDevice.NotificationType.NOTIFICATION, NOTIFY_UUID).test()

        replyDescriptorWrite()

        verify(exactly = 1) { fixture.gatt.writeDescriptor(descriptor) }
        first.assertValueCount(1)
        second.assertValueCount(1)
    }

    @Test
    fun disposingOneSubscriber_keepsNotificationForOthers() {
        val first = subscribe()
        replyDescriptorWrite()
        val second = subscribe()

        first.dispose()
        notify(2)

        verify(exactly = 0) { fixture.gatt.setCharacteristicNotification(characteristic, false) }
        verify(exactly = 1) { fixture.gatt.writeDescriptor(descriptor) }
        second.assertValue { it.contentEquals(byteArrayOf(2)) }
    }

    @Test
    fun disposingLastSubscriber_disablesNotification() {
        val first = subscribe()
        replyDescriptorWrite()
        val second = subscribe()

        first.dispose()
        second.dispose()

        verify(exactly = 1) { fixture.gatt.setCharacteristicNotification(characteristic, false) }
        verify(exactly = 2) { fixture.gatt.writeDescriptor(descriptor) }
        verify { descriptor.setValue(match { it.contentEquals(byteArrayOf(0, 0)) }) }
    }

    @Test
    fun subscribingAgainAfterAllDisposed_setsUpNotificationAgain() {
        subscribe().also { replyDescriptorWrite() }.dispose()
        replyDescriptorWrite() // CCCD 비활성화 응답

        val again = subscribe()
        replyDescriptorWrite()
        notify(3)

        verify(exactly = 3) { fixture.gatt.writeDescriptor(descriptor) }
        again.assertValue { it.contentEquals(byteArrayOf(3)) }
    }

    @Test
    fun differentNotificationType_isRejected_whileAnotherTypeIsActive() {
        val first = subscribe(BleDevice.NotificationType.NOTIFICATION)
        replyDescriptorWrite()

        subscribe(BleDevice.NotificationType.INDICATION).assertError(IllegalStateException::class.java)

        notify(4)
        first.assertValue { it.contentEquals(byteArrayOf(4)) }
        verify(exactly = 1) { fixture.gatt.writeDescriptor(descriptor) }
    }

    @Test
    fun differentNotificationType_isAllowed_afterPreviousSubscribersAreDisposed() {
        subscribe(BleDevice.NotificationType.NOTIFICATION).also { replyDescriptorWrite() }.dispose()
        replyDescriptorWrite()

        val indication = subscribe(BleDevice.NotificationType.INDICATION)
        replyDescriptorWrite()

        indication.assertNoErrors()
        verify { descriptor.setValue(match { it.contentEquals(BleDevice.NotificationType.INDICATION.byteArray) }) }
    }

    @Test
    fun allSubscribersFail_whenDisconnected_andSetupWorksAgainAfterReconnect() {
        val first = subscribe()
        replyDescriptorWrite()
        val second = subscribe()

        fixture.disconnectFromRemote()
        first.assertError(BleManager.DisconnectedException::class.java)
        second.assertError(BleManager.DisconnectedException::class.java)

        val newGatt = fixture.newGatt()
        val newCharacteristic = fixture.addCharacteristic(NOTIFY_UUID, BluetoothGattCharacteristic.PROPERTY_NOTIFY, newGatt)
        val newDescriptor = newCharacteristic.getDescriptor(GattTestFixture.CCCD_UUID)
        fixture.stubConnectGatt(newGatt)
        fixture.engine.connectCompletable(GattTestFixture.CONNECT_TIMEOUT_MILLIS).test()
        fixture.simulateConnected(newGatt)

        fixture.bleDevice.setupNotification(BleDevice.NotificationType.NOTIFICATION, NOTIFY_UUID).test()
        verify(exactly = 1) { newGatt.writeDescriptor(newDescriptor) }
    }

    companion object {
        private val NOTIFY_UUID: UUID = UUID.fromString("0000b201-0000-1000-8000-00805f9b34fb")
    }
}
