package com.algorigo.algorigoble2

import com.algorigo.algorigoble2.logging.Ble
import com.algorigo.logger.L
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
                ConnectionState.INITIALIZING
            } else {
                connectionState
            }
        }
            // 두 relay 중 하나만 바뀌어도 combineLatest 가 다시 내보내므로 같은 상태가 연달아 나오지 않게 한다
            .distinctUntilChanged()
    }

    final override fun connectCompletable(timeoutMillis: Long): Completable {
        return super.connectCompletable(timeoutMillis)
            .concatWith(getInitializeCompletable().doOnError { disconnect() })
    }

    private fun getInitializeCompletable(): Completable {
        return Completable.defer {
            initializeCompletable()
                .doOnComplete {
                    initializeRelay.accept(true)
                }
                .doOnError {
                    L.error(Ble.Device, "getInitializeCompletable", it)
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