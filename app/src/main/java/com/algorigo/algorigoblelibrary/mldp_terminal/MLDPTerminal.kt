package com.algorigo.algorigoblelibrary.mldp_terminal

import android.util.Log
import com.algorigo.algorigoble.BleScanFilter
import com.algorigo.algorigoble.InitializableBleDevice
import com.jakewharton.rxrelay3.PublishRelay
import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.disposables.Disposable
import io.reactivex.rxjava3.subjects.PublishSubject
import io.reactivex.rxjava3.subjects.Subject
import java.nio.charset.Charset
import java.util.*
import java.util.concurrent.TimeUnit

class MLDPTerminal : InitializableBleDevice() {

    private lateinit var initializeSubject: Subject<ByteArray>
    private var dataDisposable: Disposable? = null
    private var relay = PublishRelay.create<ByteArray>()

    override fun initializeCompletable(): Completable {
        initializeSubject = PublishSubject.create<ByteArray>().toSerialized()
        return initializeSubject
            .doOnSubscribe {
                dataDisposable = dataNotification()?.subscribe({
                    initializeSubject.onComplete()
                    it.doFinally {
                        Completable.timer(100, TimeUnit.MILLISECONDS)
                            .andThen(initializeCompletable())
                            .subscribe({
                                Log.d(TAG, "initializeCompletable recomplete")
                            }, {
                                Log.e(TAG, "", it)
                            })
                    }.subscribe({
                        onCallback(it)
                    }, {
                        Log.e(TAG, "", it)
                    })
                }, {
                    Log.e(TAG, "", it)
                    if (!initializeSubject.hasComplete() && !initializeSubject.hasThrowable() && initializeSubject.hasObservers()) {
                        initializeSubject.onError(it)
                    }
                })
            }
            .ignoreElements()
    }

    override fun onDisconnected() {
        super.onDisconnected()
        dataDisposable?.dispose()
        dataDisposable = null
    }

    private fun dataNotification(): Observable<Observable<ByteArray>>? {
        return setupNotification(UUID.fromString(MLDP_DATA_PRIVATE_CHAR))
    }

    fun writeData(byteArray: ByteArray): Single<ByteArray>? {
        return writeCharacteristic(UUID.fromString(MLDP_DATA_PRIVATE_CHAR), byteArray)
    }

    fun getDataObservable(): Observable<ByteArray> {
        return relay
    }

    private fun onCallback(byteArray: ByteArray) {
        Log.e("!!!", "onCallback:${byteArray.toString(Charset.forName("utf-8"))}:${byteArray.map { it.toUInt() }.toTypedArray().contentToString()}")
        relay.accept(byteArray)
    }

    companion object {
        private val TAG = MLDPTerminal::class.java.simpleName

        const val BLE_NAME = "RN4020_FAED"
        const val MAC_ADDRESS = "00:1E:C0:1C:FA:ED"

        private val MLDP_PRIVATE_SERVICE = "00035b03-58e6-07dd-021a-08123a000300" //Private service for Microchip MLDP
        private val MLDP_DATA_PRIVATE_CHAR = "00035b03-58e6-07dd-021a-08123a000301" //Characteristic for MLDP Data, properties - notify, write
        private val MLDP_CONTROL_PRIVATE_CHAR = "00035b03-58e6-07dd-021a-08123a0003ff" //Characteristic for MLDP Control, properties - read, write

        fun getScanFilter(): BleScanFilter {
            return BleScanFilter.Builder().setDeviceAddress(MAC_ADDRESS).build()
        }
    }
}