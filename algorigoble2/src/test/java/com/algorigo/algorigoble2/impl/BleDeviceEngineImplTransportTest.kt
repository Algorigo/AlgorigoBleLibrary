package com.algorigo.algorigoble2.impl

import android.bluetooth.BluetoothDevice
import com.algorigo.algorigoble2.testutil.RxTestSchedulerRule
import io.mockk.verify
import org.junit.Rule
import org.junit.Test

/**
 * connectGatt 에 transport 를 지정하지 않아 듀얼모드 기기에서 BR/EDR 로 연결을 시도할 수 있던 문제(P1) 테스트.
 */
class BleDeviceEngineImplTransportTest {

    @get:Rule
    val rxRule = RxTestSchedulerRule()

    private val fixture = GattTestFixture()

    @Test
    fun connectCompletable_requestsLeTransport() {
        fixture.connect()

        verify(exactly = 1) { fixture.bluetoothDevice.connectGatt(fixture.context, false, any(), BluetoothDevice.TRANSPORT_LE) }
        verify(exactly = 0) { fixture.bluetoothDevice.connectGatt(any(), any(), any()) }
    }
}
