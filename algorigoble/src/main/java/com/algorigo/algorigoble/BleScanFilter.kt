package com.algorigo.algorigoble

import android.bluetooth.BluetoothDevice

open class BleScanFilter {

    internal var deviceName: String? = null
    internal var deviceAddress: String? = null

    open fun isOk(device: BluetoothDevice?, rssi: Int, scanRecord: ByteArray?): Boolean {
        return (if (deviceName != null) deviceName.equals(device?.name) else true) &&
                (if (deviceAddress != null) deviceAddress.equals(device?.address) else true)
    }

    class Builder {

        private var deviceName: String? = null
        private var deviceAddress: String? = null

        fun setDeviceName(deviceName: String?): Builder {
            this.deviceName = deviceName
            return this
        }

        fun setDeviceAddress(deviceAddress: String): Builder {
            this.deviceAddress = deviceAddress
            return this
        }

        fun build(): BleScanFilter {
            return BleScanFilter().also {
                it.deviceName = deviceName
                it.deviceAddress = deviceAddress
            }
        }
    }
}