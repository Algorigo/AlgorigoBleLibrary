package com.algorigo.algorigoble2.impl

import android.bluetooth.BluetoothGattCharacteristic
import com.algorigo.algorigoble2.BleCharacterisic
import java.util.*

class BleCharacteristicImpl(private val bluetoothGattCharacteristic: BluetoothGattCharacteristic) : BleCharacterisic() {
    override val uuid: UUID
        get() = bluetoothGattCharacteristic.uuid

    override fun isReadable() = bluetoothGattCharacteristic.properties and BluetoothGattCharacteristic.PROPERTY_READ != 0

    override fun isWritable() = bluetoothGattCharacteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0 ||
            bluetoothGattCharacteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0

    override fun isNotifyAvailable() = bluetoothGattCharacteristic.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0 ||
            bluetoothGattCharacteristic.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0
}
