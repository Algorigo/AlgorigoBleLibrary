package com.algorigo.algorigoblelibrary

import com.algorigo.algorigoble.BleDevice
import com.algorigo.algorigoble.InitializableBleDevice
import io.reactivex.Completable
import io.reactivex.Observable
import io.reactivex.Single
import java.util.*

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