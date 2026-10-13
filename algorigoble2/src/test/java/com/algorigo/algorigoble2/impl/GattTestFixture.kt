package com.algorigo.algorigoble2.impl

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothProfile
import android.content.Context
import com.algorigo.algorigoble2.BleDevice
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import java.util.UUID

/**
 * [BleDeviceEngineImpl]을 mock [BluetoothDevice]/[BluetoothGatt] 위에서 구동한다.
 * connectGatt 에 전달된 [BluetoothGattCallback]을 캡처해 두므로, 테스트에서 시스템 콜백을 직접 호출할 수 있다.
 */
internal class GattTestFixture(val bleDevice: BleDevice = BleDevice()) {

    val context: Context = mockk(relaxed = true)
    val bluetoothDevice: BluetoothDevice = mockk(relaxed = true)
    val gatt: BluetoothGatt = mockk(relaxed = true)

    private val callbackSlot = slot<BluetoothGattCallback>()
    val callback: BluetoothGattCallback
        get() = callbackSlot.captured

    private val services = mutableListOf<BluetoothGattService>()

    val engine = BleDeviceEngineImpl(context, bluetoothDevice)

    init {
        bleDevice.initEngine(engine)
        every { bluetoothDevice.address } returns ADDRESS
        every { bluetoothDevice.name } returns NAME
        every { bluetoothDevice.connectGatt(any(), any(), capture(callbackSlot)) } returns gatt
        every { gatt.device } returns bluetoothDevice
        every { gatt.services } answers { services.toList() }
        every { gatt.readCharacteristic(any()) } returns true
        every { gatt.writeCharacteristic(any()) } returns true
        every { gatt.writeDescriptor(any()) } returns true
    }

    /** connectGatt → STATE_CONNECTED → onServicesDiscovered 순서로 콜백을 보내 CONNECTED 상태로 만든다. */
    fun connect() {
        val observer = engine.connectCompletable(CONNECT_TIMEOUT_MILLIS).test()
        simulateConnected()
        observer.assertComplete()
    }

    fun simulateConnected() {
        callback.onConnectionStateChange(gatt, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED)
        callback.onServicesDiscovered(gatt, BluetoothGatt.GATT_SUCCESS)
    }

    fun disconnectFromRemote() {
        callback.onConnectionStateChange(gatt, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_DISCONNECTED)
    }

    /** 첫 GATT 작업 전에 호출해야 한다. (서비스 목록은 처음 조회할 때 캐시된다) */
    fun addCharacteristic(uuid: UUID, properties: Int): BluetoothGattCharacteristic {
        val characteristic = mockk<BluetoothGattCharacteristic>(relaxed = true)
        val descriptor = mockk<BluetoothGattDescriptor>(relaxed = true)
        val service = mockk<BluetoothGattService>(relaxed = true)
        every { characteristic.uuid } returns uuid
        every { characteristic.properties } returns properties
        every { characteristic.getDescriptor(CCCD_UUID) } returns descriptor
        every { descriptor.characteristic } returns characteristic
        every { service.getCharacteristic(any()) } returns null
        every { service.getCharacteristic(uuid) } returns characteristic
        every { service.characteristics } returns listOf(characteristic)
        services.add(service)
        return characteristic
    }

    companion object {
        const val ADDRESS = "00:11:22:33:44:55"
        const val NAME = "TestDevice"
        const val CONNECT_TIMEOUT_MILLIS = 10_000L
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
