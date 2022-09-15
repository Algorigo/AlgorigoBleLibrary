package com.algorigo.algorigoble2

import android.util.Log
import com.jakewharton.rxrelay3.BehaviorRelay
import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.core.Observable

abstract class InitializableBleDevice : BleDevice() {

    private val initializeRelay = BehaviorRelay.create<Boolean>().toSerialized().apply {
        accept(false)
    }

    final override fun getConnectionStateObservable(): Observable<ConnectionState> {
        return Observable.combineLatest(
            super.getConnectionStateObservable(),
            initializeRelay
        ) { connectionState, initialized ->
            if (connectionState == ConnectionState.CONNECTED && !initialized) {
                ConnectionState.CONNECTING.apply {
                    status = "INITIALING"
                }
            } else {
                connectionState
            }
        }
    }

    final override fun connectCompletable(timeoutMillis: Long): Completable {
        return super.connectCompletable(timeoutMillis)
            .concatWith(getInitializeCompletable())
    }

    private fun getInitializeCompletable(): Completable {
        return Completable.defer {
            initializeCompletable()
                .doOnComplete {
                    initializeRelay.accept(true)
                }
                .doOnError {
                    Log.e(TAG, "getInitializeCompletable", it)
                    disconnect()
                }
        }
    }

    abstract fun initializeCompletable(): Completable

    override fun onDisconnected() {
        super.onDisconnected()
        initializeRelay.accept(false)
    }

    companion object {
        private val TAG = InitializableBleDevice::class.java.simpleName
    }
}