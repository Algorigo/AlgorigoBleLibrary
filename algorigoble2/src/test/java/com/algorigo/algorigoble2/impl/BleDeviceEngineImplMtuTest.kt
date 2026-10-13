package com.algorigo.algorigoble2.impl

import android.bluetooth.BluetoothGatt
import com.algorigo.algorigoble2.BleManager
import com.algorigo.algorigoble2.testutil.RxTestSchedulerRule
import io.mockk.every
import io.mockk.verify
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.TimeUnit

class BleDeviceEngineImplMtuTest {

    @get:Rule
    val rxRule = RxTestSchedulerRule()

    private val fixture = GattTestFixture()

    @Before
    fun setUp() {
        fixture.connect()
        every { fixture.gatt.requestMtu(any()) } returns true
    }

    @Test
    fun setMtuCompletable_callsRequestMtu_andCompletesOnlyAfterMtuChanged() {
        val observer = fixture.bleDevice.setMtuCompletable(247).test()

        verify(exactly = 1) { fixture.gatt.requestMtu(247) }
        observer.assertNotComplete()

        fixture.callback.onMtuChanged(fixture.gatt, 247, BluetoothGatt.GATT_SUCCESS)

        observer.assertComplete()
    }

    @Test
    fun requestMtuSingle_emitsNegotiatedMtu() {
        val observer = fixture.bleDevice.requestMtuSingle(517).test()

        fixture.callback.onMtuChanged(fixture.gatt, 185, BluetoothGatt.GATT_SUCCESS)

        observer.assertValue(185)
    }

    @Test
    fun requestMtuSingle_failsWithStatus_whenMtuChangeFailed() {
        val observer = fixture.bleDevice.requestMtuSingle(517).test()

        fixture.callback.onMtuChanged(fixture.gatt, 23, BluetoothGatt.GATT_FAILURE)

        observer.assertError { it is BleManager.MtuChangeFailedException && it.status == BluetoothGatt.GATT_FAILURE }
    }

    @Test
    fun requestMtuSingle_retries_whenRequestMtuReturnsFalse() {
        every { fixture.gatt.requestMtu(any()) } returnsMany listOf(false, true)

        val observer = fixture.bleDevice.requestMtuSingle(517).test()
        verify(exactly = 1) { fixture.gatt.requestMtu(517) }

        rxRule.testScheduler.advanceTimeBy(500, TimeUnit.MILLISECONDS)
        verify(exactly = 2) { fixture.gatt.requestMtu(517) }

        fixture.callback.onMtuChanged(fixture.gatt, 517, BluetoothGatt.GATT_SUCCESS)
        observer.assertValue(517)
    }

    @Test
    fun requestMtuSingle_failsWithDisconnectedException_whenNotConnected() {
        fixture.disconnectFromRemote()

        fixture.bleDevice.requestMtuSingle(517).test()
            .assertError(BleManager.DisconnectedException::class.java)
        verify(exactly = 0) { fixture.gatt.requestMtu(any()) }
    }
}
