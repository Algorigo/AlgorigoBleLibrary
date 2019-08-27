package com.algorigo.algorigoble

import android.util.Log
import com.jakewharton.rxrelay2.PublishRelay
import io.reactivex.Completable
import io.reactivex.Observable

abstract class InitializableBleDevice: BleDevice() {

    private var initialized = false
    private val initializeRelay = PublishRelay.create<ConnectionState>().toSerialized()

    final override val connectionState: ConnectionState
        get() {
            return if (super.connectionState == ConnectionState.CONNECTED && !initialized) {
                ConnectionState.CONNECTING.apply {
                    status = "INITIALING"
                }
            } else {
                super.connectionState
            }
        }

    final override val connected: Boolean
        get() = super.connected && initialized

    final override fun connectCompletableImpl(autoConnect: Boolean): Completable {
        return super.connectCompletableImpl(autoConnect)
            .concatWith(getInitializeCompletable())
    }

    final override fun connectCompletableImpl(autoConnect: Boolean, milliSec: Long): Completable {
        return super.connectCompletableImpl(autoConnect, milliSec)
            .concatWith(getInitializeCompletable())
    }

    private fun getInitializeCompletable(): Completable {
        return Completable.defer {
            initializeCompletable()
                .doOnComplete {
                    initialized = true
                    initializeRelay.accept(ConnectionState.CONNECTED)
                }
                .doOnError {
                    Log.e(TAG, "", it)
                    disconnect()
                }
        }
    }

    abstract fun initializeCompletable(): Completable

    override fun onDisconnected() {
        super.onDisconnected()
        initialized = false
    }

    override fun getConnectionStateObservable(): Observable<ConnectionState> {
        return super.getConnectionStateObservable()
            .map {
                if (it == ConnectionState.CONNECTED && !initialized) {
                    ConnectionState.CONNECTING.apply {
                        status = "INITIALING"
                    }
                } else {
                    it
                }
            }
            .mergeWith(initializeRelay)
    }

    companion object {
        private val TAG = InitializableBleDevice::class.java.simpleName
    }
}