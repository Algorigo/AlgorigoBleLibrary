package com.algorigo.algorigoble2.impl

import android.bluetooth.BluetoothGatt
import com.algorigo.algorigoble2.BleDevice
import com.algorigo.algorigoble2.BleManager
import com.algorigo.algorigoble2.testutil.RxTestSchedulerRule
import io.mockk.every
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.lang.reflect.Modifier

/** 연결 우선순위(connection priority) 요청 API 테스트 */
class ConnectionPriorityTest {

    @get:Rule
    val rxRule = RxTestSchedulerRule()

    private val fixture = GattTestFixture()

    @Test
    fun request_passesPriorityToGatt() {
        fixture.connect()
        every { fixture.gatt.requestConnectionPriority(any()) } returns true

        fixture.bleDevice.requestConnectionPriorityCompletable(BleDevice.ConnectionPriority.HIGH).test().assertComplete()
        fixture.bleDevice.requestConnectionPriorityCompletable(BleDevice.ConnectionPriority.LOW_POWER).test().assertComplete()

        verify(exactly = 1) { fixture.gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH) }
        verify(exactly = 1) { fixture.gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_LOW_POWER) }
    }

    @Test
    fun rejectedRequest_failsWithGattRequestRejected() {
        fixture.connect()
        every { fixture.gatt.requestConnectionPriority(any()) } returns false

        fixture.bleDevice.requestConnectionPriorityCompletable(BleDevice.ConnectionPriority.HIGH).test()
            .assertError { it is BleManager.GattRequestRejectedException && it.operation == "REQUEST_CONNECTION_PRIORITY" }
    }

    @Test
    fun request_whenNotConnected_failsWithoutGattCall() {
        fixture.bleDevice.requestConnectionPriorityCompletable(BleDevice.ConnectionPriority.HIGH).test()
            .assertError(BleManager.DisconnectedException::class.java)

        verify(exactly = 0) { fixture.gatt.requestConnectionPriority(any()) }
    }

    // 숨겨진 콜백은 이름과 JVM 시그니처가 같아야 런타임에 override 된다
    @Test
    fun hiddenConnectionUpdatedCallback_hasFrameworkSignature() {
        fixture.connect()
        val int = Int::class.javaPrimitiveType
        val method = fixture.callback.javaClass.getDeclaredMethod("onConnectionUpdated", BluetoothGatt::class.java, int, int, int, int)

        assertTrue(Modifier.isPublic(method.modifiers))
        assertEquals(Void.TYPE, method.returnType)
    }
}
