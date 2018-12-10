package com.algorigo.algorigoblelibrary

import com.algorigo.algorigoble.InitializableBleDevice
import io.reactivex.Completable
import io.reactivex.Observable
import io.reactivex.Single
import java.util.*

class SampleBleDevice: InitializableBleDevice() {

    private var version = ""

    override fun initializeCompletable(): Completable {
        return getVersionSingle()!!.doOnSuccess { version = it }.ignoreElement()
    }

    override fun onDisconnected() {
        super.onDisconnected()
        version = ""
    }

    fun sendDataOn(): Observable<ByteArray>? {
        return setupNotification(UUID.fromString(UUID_SEND_DATA_ON))
            ?.flatMap { it }
    }

    fun setVersionSingle(value: String): Single<String>? {
        val byteArray = value.toByteArray(Charsets.UTF_8)
        return writeCharacteristic(UUID.fromString(UUID_DATA_VERSION), byteArray)
                ?.map {
                    version = it.contentToString()
                    version
                }
    }

    fun getVersionSingle(): Single<String>? {
        return readCharacteristic(UUID.fromString(UUID_DATA_VERSION))
                ?.map {
                    version = it.contentToString()
                    version
                }
    }

    fun getVersion(): String {
        return version
    }

    companion object {
        private val TAG = SampleBleDevice::class.java.simpleName

        const val BLE_NAME = "SampleBle"

        private const val UUID_SEND_DATA_ON = "6e400003-b5a3-f393-e0a9-e50e24dcca9e"
        private const val UUID_DATA_VERSION = "9fd42004-e46f-7c9a-57b1-2da365e18fa1"
    }
}