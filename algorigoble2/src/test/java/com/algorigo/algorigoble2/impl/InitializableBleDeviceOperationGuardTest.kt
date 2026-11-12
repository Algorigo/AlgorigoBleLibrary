package com.algorigo.algorigoble2.impl

import android.bluetooth.BluetoothGattCharacteristic
import com.algorigo.algorigoble2.BleDevice
import com.algorigo.algorigoble2.BleManager
import com.algorigo.algorigoble2.InitializableBleDevice
import com.algorigo.algorigoble2.testutil.RxTestSchedulerRule
import io.mockk.verify
import io.reactivex.rxjava3.core.Completable
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * initializeCompletable 안의 GATT 작업이 응답을 받지 못해도 connectCompletable 이 끝나는지 확인한다.
 */
class InitializableBleDeviceOperationGuardTest {

    @get:Rule
    val rxRule = RxTestSchedulerRule()

    private val device = object : InitializableBleDevice() {
        override fun initializeCompletable(): Completable =
            readCharacteristicSingle(CHARACTERISTIC_UUID).ignoreElement()
    }
    private val fixture = GattTestFixture(device).apply {
        addCharacteristic(CHARACTERISTIC_UUID, BluetoothGattCharacteristic.PROPERTY_READ)
    }

    @Test
    fun connectCompletable_fails_whenDisconnectedDuringInitialization() {
        val observer = device.connectCompletable().test()
        fixture.simulateConnected()
        verify(exactly = 1) { fixture.gatt.readCharacteristic(any()) }
        observer.assertNotComplete().assertNoErrors()

        fixture.disconnectFromRemote()

        observer.assertError(BleManager.DisconnectedException::class.java)
        assertEquals(BleDevice.ConnectionState.DISCONNECTED, device.connectionState)
    }

    @Test
    fun connectCompletable_failsAndDisconnects_whenInitializationReplyNeverArrives() {
        val observer = device.connectCompletable().test()
        fixture.simulateConnected()

        rxRule.testScheduler.advanceTimeBy(BleDevice.DEFAULT_OPERATION_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)

        observer.assertError(BleManager.OperationTimeoutException::class.java)
        verify { fixture.gatt.disconnect() }
    }

    companion object {
        private val CHARACTERISTIC_UUID: UUID = UUID.fromString("0000bbbb-0000-1000-8000-00805f9b34fb")
    }
}
