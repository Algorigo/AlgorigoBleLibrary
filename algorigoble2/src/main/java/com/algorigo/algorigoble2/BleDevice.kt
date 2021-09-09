package com.algorigo.algorigoble2

import android.bluetooth.BluetoothDevice

class BleDevice(private val bluetoothDevice: BluetoothDevice) {

    enum class ConnectionState(var status: String) {
        CONNECTING("CONNECTING"),
        CONNECTED("CONNECTED"),
        DISCONNECTED("DISCONNECTED"),
        DISCONNECTING("DISCONNECTING")
    }

    private lateinit var engine: BleManagerEngine

    val deviceId: String
        get() = bluetoothDevice.address

    override fun equals(other: Any?): Boolean {
        return other is BleDevice && this.deviceId == other.deviceId
    }
}
