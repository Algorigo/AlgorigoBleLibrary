package com.algorigo.algorigoble2.impl

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import com.algorigo.algorigoble2.BleDevice
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import java.util.UUID

/**
 * [BleDeviceEngineImpl]을 mock [BluetoothDevice]/[BluetoothGatt] 위에서 구동한다.
 * connectGatt 에 전달된 [BluetoothGattCallback]을 캡처해 두므로, 테스트에서 시스템 콜백을 직접 호출할 수 있다.
 *
 * mock gatt 는 실제 [BluetoothGatt]처럼 동작한다:
 * - [BluetoothGatt.getServices]는 마지막 서비스 탐색 시점의 목록을 반환한다.
 * - [BluetoothGatt.discoverServices]를 호출하면 목록이 비워지고, [simulateServicesDiscovered] 시점에 서버 DB 가 반영된다.
 *
 * [sdkInt]는 엔진이 값 기반 GATT API(API 33+)를 쓸지 정한다. 기본값은 단위 테스트의 Build.VERSION.SDK_INT(0)라서 기존 API 경로를 탄다.
 */
internal class GattTestFixture(val bleDevice: BleDevice = BleDevice(), sdkInt: Int = Build.VERSION.SDK_INT) {

    private class FakeGattState {
        val serverServices = mutableListOf<BluetoothGattService>()
        var discoveredServices = listOf<BluetoothGattService>()
    }

    val context: Context = mockk(relaxed = true)
    val bluetoothDevice: BluetoothDevice = mockk(relaxed = true)

    private val gattStates = mutableMapOf<BluetoothGatt, FakeGattState>()
    val gatt: BluetoothGatt = newGatt()

    private val callbackSlot = slot<BluetoothGattCallback>()
    val callback: BluetoothGattCallback
        get() = callbackSlot.captured

    val engine = BleDeviceEngineImpl(context, bluetoothDevice, sdkInt)

    init {
        bleDevice.initEngine(engine)
        every { bluetoothDevice.address } returns ADDRESS
        every { bluetoothDevice.name } returns NAME
        every { bluetoothDevice.connectGatt(any(), any(), capture(callbackSlot)) } returns gatt
        every { bluetoothDevice.connectGatt(any(), any(), capture(callbackSlot), any()) } returns gatt
    }

    /** 재연결 시 connectGatt 가 반환할 새 gatt 를 만든다. 서버 DB 는 비어 있는 상태로 시작한다. */
    fun newGatt(): BluetoothGatt {
        val newGatt = mockk<BluetoothGatt>(relaxed = true)
        val state = FakeGattState()
        gattStates[newGatt] = state
        every { newGatt.device } returns bluetoothDevice
        every { newGatt.services } answers { state.discoveredServices }
        every { newGatt.discoverServices() } answers {
            state.discoveredServices = listOf()
            true
        }
        every { newGatt.readCharacteristic(any()) } returns true
        every { newGatt.writeCharacteristic(any()) } returns true
        every { newGatt.writeDescriptor(any()) } returns true
        every { newGatt.writeCharacteristic(any(), any(), any()) } returns BluetoothStatusCodes.SUCCESS
        every { newGatt.writeDescriptor(any(), any()) } returns BluetoothStatusCodes.SUCCESS
        return newGatt
    }

    /** connectGatt 반환값을 호출 순서대로 지정한다. null 은 블루투스가 꺼져 있을 때의 반환값이다. */
    fun stubConnectGatt(vararg results: BluetoothGatt?) {
        every { bluetoothDevice.connectGatt(any(), any(), capture(callbackSlot)) } returnsMany results.toList()
        every { bluetoothDevice.connectGatt(any(), any(), capture(callbackSlot), any()) } returnsMany results.toList()
    }

    /** connectGatt → STATE_CONNECTED → onServicesDiscovered 순서로 콜백을 보내 CONNECTED 상태로 만든다. */
    fun connect() {
        val observer = engine.connectCompletable(CONNECT_TIMEOUT_MILLIS).test()
        simulateConnected()
        observer.assertComplete()
    }

    fun simulateConnected(targetGatt: BluetoothGatt = gatt) {
        callback.onConnectionStateChange(targetGatt, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED)
        simulateServicesDiscovered(targetGatt)
    }

    /** 서버 DB 를 탐색 결과로 반영한 뒤 onServicesDiscovered 를 보낸다. 실패 status 이면 서비스 목록은 비어 있다. */
    fun simulateServicesDiscovered(targetGatt: BluetoothGatt = gatt, status: Int = BluetoothGatt.GATT_SUCCESS) {
        gattStates.getValue(targetGatt).let {
            it.discoveredServices = if (status == BluetoothGatt.GATT_SUCCESS) it.serverServices.toList() else listOf()
        }
        callback.onServicesDiscovered(targetGatt, status)
    }

    fun disconnectFromRemote(targetGatt: BluetoothGatt = gatt) {
        callback.onConnectionStateChange(targetGatt, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_DISCONNECTED)
    }

    /**
     * 서버 DB 에 characteristic 을 추가한다. 다음 서비스 탐색부터 gatt.services 에 나타난다.
     * 호출마다 [serviceUuid]의 서비스를 하나 새로 만든다. (기본값은 호출마다 다른 서비스 UUID)
     */
    fun addCharacteristic(
        uuid: UUID,
        properties: Int,
        targetGatt: BluetoothGatt = gatt,
        serviceUuid: UUID = UUID.randomUUID(),
    ): BluetoothGattCharacteristic {
        val characteristic = mockk<BluetoothGattCharacteristic>(relaxed = true)
        val descriptor = mockk<BluetoothGattDescriptor>(relaxed = true)
        val service = mockk<BluetoothGattService>(relaxed = true)
        every { service.uuid } returns serviceUuid
        every { characteristic.service } returns service
        every { characteristic.uuid } returns uuid
        every { characteristic.properties } returns properties
        every { characteristic.getDescriptor(CCCD_UUID) } returns descriptor
        every { descriptor.characteristic } returns characteristic
        every { service.getCharacteristic(any()) } returns null
        every { service.getCharacteristic(uuid) } returns characteristic
        every { service.characteristics } returns listOf(characteristic)
        gattStates.getValue(targetGatt).serverServices.add(service)
        return characteristic
    }

    companion object {
        const val ADDRESS = "00:11:22:33:44:55"
        const val NAME = "TestDevice"
        const val CONNECT_TIMEOUT_MILLIS = 10_000L
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
