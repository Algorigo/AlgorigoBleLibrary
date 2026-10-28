package com.algorigo.algorigoble2.impl

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import com.algorigo.algorigoble2.BleDevice
import com.algorigo.algorigoble2.BleManager
import com.algorigo.algorigoble2.CallbackScheduler
import com.algorigo.algorigoble2.testutil.RxTestSchedulerRule
import io.mockk.every
import io.mockk.verify
import io.reactivex.rxjava3.schedulers.TestScheduler
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.UUID

/**
 * GATT 콜백, notification, 상태 변경이 바인더 스레드(또는 상태를 바꾼 스레드)에서 사용자 코드로 바로 전달되던 문제(P2) 테스트.
 * callback scheduler 를 TestScheduler 로 바꿔, 사용자 코드가 콜백 안에서 실행되지 않고 나중에 전달되는지 확인한다.
 */
class CallbackThreadTest {

    @get:Rule
    val rxRule = RxTestSchedulerRule()

    private val callbackScheduler = TestScheduler()
    private val fixture = GattTestFixture()
    private val first = fixture.addCharacteristic(FIRST_UUID, BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY)
    private val second = fixture.addCharacteristic(SECOND_UUID, BluetoothGattCharacteristic.PROPERTY_READ)
    private val device: BleDevice get() = fixture.bleDevice

    @Before
    fun setUp() {
        fixture.connect()
        CallbackScheduler.override = callbackScheduler
    }

    @After
    fun tearDown() {
        CallbackScheduler.override = null
    }

    @Test
    fun readResult_isNotDeliveredInsideGattCallback_andNextOperationStartsRightAway() {
        every { first.value } returns byteArrayOf(1)
        val firstRead = device.readCharacteristicSingle(FIRST_UUID).test()
        val secondRead = device.readCharacteristicSingle(SECOND_UUID).test()

        fixture.callback.onCharacteristicRead(fixture.gatt, first, BluetoothGatt.GATT_SUCCESS)

        // 사용자 구독자는 아직 실행되지 않았지만 다음 작업은 이미 시작됐다
        firstRead.assertNoValues()
        verify(exactly = 1) { fixture.gatt.readCharacteristic(second) }

        callbackScheduler.triggerActions()
        firstRead.assertValue { it.contentEquals(byteArrayOf(1)) }
        secondRead.assertNoValues()
    }

    @Test
    fun connectionState_isNotDeliveredOnTheThreadThatChangedIt() {
        val states = device.getConnectionStateObservable().test()
        callbackScheduler.triggerActions()
        states.assertValues(BleDevice.ConnectionState.CONNECTED)

        device.disconnect()

        // disconnect() 를 부른 스레드(상태 잠금 안)에서 관찰자가 실행되지 않는다
        states.assertValues(BleDevice.ConnectionState.CONNECTED)
        // 현재 상태는 바로 읽을 수 있다
        assertEquals(BleDevice.ConnectionState.DISCONNECTING, device.connectionState)

        callbackScheduler.triggerActions()
        states.assertValues(BleDevice.ConnectionState.CONNECTED, BleDevice.ConnectionState.DISCONNECTING)
    }

    @Test
    fun notifications_areDeliveredLater_inOrder_beforeDisconnectError() {
        val notifications = device.setupNotification(BleDevice.NotificationType.NOTIFICATION, FIRST_UUID).flatMap { it }.test()
        fixture.callback.onDescriptorWrite(fixture.gatt, first.getDescriptor(GattTestFixture.CCCD_UUID), BluetoothGatt.GATT_SUCCESS)
        callbackScheduler.triggerActions()

        listOf<Byte>(1, 2, 3).forEach {
            every { first.value } returns byteArrayOf(it)
            fixture.callback.onCharacteristicChanged(fixture.gatt, first)
        }
        fixture.disconnectFromRemote()
        notifications.assertNoValues()

        callbackScheduler.triggerActions()

        // 연결이 끊기기 전에 받은 값을 모두 전달한 뒤 에러를 전달한다
        assertEquals(listOf(listOf<Byte>(1), listOf<Byte>(2), listOf<Byte>(3)), notifications.values().map { it.toList() })
        notifications.assertError(BleManager.DisconnectedException::class.java)
    }

    @Test
    fun connectCompletion_isDeliveredOnCallbackScheduler() {
        device.disconnect()
        fixture.disconnectFromRemote()
        val connect = device.connectCompletable().test()

        fixture.simulateConnected()
        connect.assertNotComplete()

        callbackScheduler.triggerActions()
        connect.assertComplete()
    }

    companion object {
        private val FIRST_UUID = UUID.fromString("0000aaa1-0000-1000-8000-aabbccddeeff")
        private val SECOND_UUID = UUID.fromString("00002a38-0000-1000-8000-00805f9b34fb")
    }
}
