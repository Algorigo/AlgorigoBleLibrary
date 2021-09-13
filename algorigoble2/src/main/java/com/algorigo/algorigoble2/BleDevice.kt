package com.algorigo.algorigoble2

import android.util.Log
import com.jakewharton.rxrelay3.BehaviorRelay
import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.core.Observable

class BleDevice() {

    enum class ConnectionState(var status: String) {
        CONNECTING("CONNECTING"),
        CONNECTED("CONNECTED"),
        DISCONNECTED("DISCONNECTED"),
        DISCONNECTING("DISCONNECTING")
    }

    internal lateinit var engine: BleDeviceEngine

    val deviceId: String
        get() = engine.deviceId

    val deviceName: String?
        get() = engine.deviceName

    val connected: Boolean
        get() = engine.isConnected()

    val connectionState: ConnectionState
        get() = engine.getConnectionStateObservable().blockingFirst()

    fun connectCompletable() = connectCompletableImpl()

    internal open fun connectCompletableImpl() = engine.connectCompletable()

    fun connect() {
        connectCompletable().subscribe({
            Log.e(TAG, "connected")
        }, {
            Log.e(TAG, "conntection fail", it)
        })
    }

    fun disconnect() = engine.disconnect()

    fun getConnectionStateObservable() = engine.getConnectionStateObservable()

    override fun toString(): String {
        return "$deviceName($deviceId)"
    }

    override fun equals(other: Any?): Boolean {
        return other is BleDevice && this.deviceId == other.deviceId
    }

    companion object {
        private val TAG = BleDevice::class.java.simpleName
    }
}
