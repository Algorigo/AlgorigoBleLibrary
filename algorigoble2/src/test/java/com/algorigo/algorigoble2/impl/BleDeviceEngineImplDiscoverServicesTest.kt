package com.algorigo.algorigoble2.impl

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothProfile
import com.algorigo.algorigoble2.BleDevice
import com.algorigo.algorigoble2.BleManager
import com.algorigo.algorigoble2.testutil.RxTestSchedulerRule
import io.mockk.every
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * 연결 후 discoverServices() 가 false 를 반환해도 무시해서, 연결 타임아웃까지 의미 없이 기다리던 문제(P2) 테스트.
 */
class BleDeviceEngineImplDiscoverServicesTest {

    @get:Rule
    val rxRule = RxTestSchedulerRule()

    private var onDisconnectedCount = 0
    private val device = object : BleDevice() {
        override fun onDisconnected() {
            super.onDisconnected()
            onDisconnectedCount++
        }
    }
    private val fixture = GattTestFixture(device)

    @Test
    fun discoverServicesReturnsFalse_failsConnectImmediately_andClosesGatt() {
        every { fixture.gatt.discoverServices() } returns false
        val connect = fixture.engine.connectCompletable(GattTestFixture.CONNECT_TIMEOUT_MILLIS).test()

        fixture.callback.onConnectionStateChange(fixture.gatt, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED)

        // 타임아웃(10초)까지 시간을 진행하지 않아도 바로 실패해야 한다
        connect.assertError(BleManager.ConnectFailedException::class.java)
        assertEquals(BleDevice.ConnectionState.DISCONNECTED, device.connectionState)
        verify(exactly = 1) { fixture.gatt.close() }
        assertEquals(1, onDisconnectedCount)
    }

    @Test
    fun discoverServicesReturnsFalse_forStaleGatt_doesNotTouchCurrentConnection() {
        fixture.connect()
        val staleGatt = fixture.newGatt()
        every { staleGatt.discoverServices() } returns false

        // 이미 정리된 이전 gatt 의 늦은 콜백은 현재 연결에 영향을 주지 않는다
        fixture.callback.onConnectionStateChange(staleGatt, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED)

        assertEquals(BleDevice.ConnectionState.CONNECTED, device.connectionState)
        verify(exactly = 0) { fixture.gatt.close() }
    }
}
