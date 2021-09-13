package com.algorigo.algorigoble2

import com.jakewharton.rxrelay3.BehaviorRelay
import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.core.Observable

abstract class BleDeviceEngine {

    abstract val deviceId: String
    abstract val deviceName: String?
    abstract val bondState: Int

    protected val connectionStateRelay: BehaviorRelay<BleDevice.ConnectionState> = BehaviorRelay.create<BleDevice.ConnectionState>().apply { accept(BleDevice.ConnectionState.DISCONNECTED) }

    abstract fun bondCompletable(): Completable

    fun isConnected(): Boolean = getConnectionStateObservable().firstOrError().map { it == BleDevice.ConnectionState.CONNECTED }.blockingGet()
    fun getConnectionStateObservable(): Observable<BleDevice.ConnectionState> = connectionStateRelay

    abstract fun connectCompletable(): Completable

    abstract fun disconnect()
}
