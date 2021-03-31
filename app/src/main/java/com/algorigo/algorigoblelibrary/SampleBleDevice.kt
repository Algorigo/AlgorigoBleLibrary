package com.algorigo.algorigoblelibrary

import com.algorigo.algorigoble.BleDevice

class SampleBleDevice: BleDevice() {

    private var version = ""

    override fun onDisconnected() {
        super.onDisconnected()
        version = ""
    }

    companion object {
        private val TAG = SampleBleDevice::class.java.simpleName
    }
}