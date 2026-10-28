package com.algorigo.algorigoble2

import com.algorigo.algorigoble2.logging.Ble
import com.algorigo.logger.L
import com.jakewharton.rxrelay3.BehaviorRelay
import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.core.Observable
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

abstract class InitializableBleDevice : BleDevice() {

    private val initializeRelay = BehaviorRelay.create<Boolean>().toSerialized().apply {
        accept(false)
    }

    // 초기화 중이면 GATT 가 연결돼 있어도 INITIALIZING 으로 보고한다. (공개 스트림은 BleDevice 가 callback 스레드로 넘긴다)
    final override fun connectionStateObservableInternal(): Observable<ConnectionState> {
        return Observable.combineLatest(
            super.connectionStateObservableInternal(),
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

    /**
     * [initializeCompletable]의 최대 대기 시간. 넘기면 연결을 끊고 [java.util.concurrent.TimeoutException]으로 실패한다.
     * (연결 타임아웃과 별개로, GATT 연결이 된 뒤부터 적용된다)
     */
    var initializeTimeoutMillis = DEFAULT_INITIALIZE_TIMEOUT_MILLIS

    final override fun connectCompletable(timeoutMillis: Long): Completable {
        return super.connectCompletable(timeoutMillis)
            .concatWith(getInitializeCompletable())
            // 초기화가 다른 scheduler(timer 등)에서 끝나도 결과는 callback 스레드로 전달한다
            .deliverToCallback()
    }

    private fun getInitializeCompletable(): Completable {
        return Completable.defer {
            // Completable.doOnDispose 는 끝난 뒤에 해제해도 불리므로, 끝나기 전에 해제한 경우만 구분한다
            val terminated = AtomicBoolean(false)
            initializeCompletable()
                .timeout(initializeTimeoutMillis, TimeUnit.MILLISECONDS)
                .doOnComplete {
                    initializeRelay.accept(true)
                }
                .doOnError {
                    // 초기화하지 못한 연결은 끊는다
                    L.error(Ble.Device, "getInitializeCompletable", it)
                    disconnect()
                }
                .doOnTerminate { terminated.set(true) }
                .doOnDispose {
                    // 초기화가 끝나기 전에 구독을 해제하면, 연결 중에 해제한 것과 같이 연결을 취소한다
                    if (!terminated.get()) {
                        disconnect()
                    }
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

        const val DEFAULT_INITIALIZE_TIMEOUT_MILLIS = 30_000L
    }
}