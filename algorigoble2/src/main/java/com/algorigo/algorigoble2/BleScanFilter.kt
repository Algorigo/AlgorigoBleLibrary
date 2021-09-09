package com.algorigo.algorigoble2

import android.bluetooth.BluetoothDevice

class BleScanFilter {

    fun isOk(device: BluetoothDevice?, rssi: Int, scanRecord: ByteArray?): Boolean {
        return true
    }
}
