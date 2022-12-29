package com.algorigo.algorigoblelibrary

import android.util.Log
import com.algorigo.algorigoble2.BleCharacterisic
import com.algorigo.algorigoble2.BleDevice
import com.algorigo.algorigoble2.virtual.VirtualDevice
import com.algorigo.library.toByteArray
import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.disposables.Disposable
import java.util.*
import java.util.concurrent.TimeUnit

class VirtualBleDevice : VirtualDevice("12:34:56:78:90:ab", "Virtual Device") {

    private var notifyDisposable: Disposable? = null

    private var byteArray: ByteArray = byteArrayOf(0x00.toByte())

    override fun getCharacteristicsSingle(): Single<List<BleCharacterisic>> {
        return Single.just(listOf(
            object : BleCharacterisic() {
                override val uuid = CHARACTERISTIC_UUID
                override fun isReadable() = true
                override fun isWritable() = true
                override fun isNotifyAvailable() = true
            }
        ))
    }

    override fun readCharacteristicSingle(characteristicUuid: UUID): Single<ByteArray> {
        return Single.fromCallable { byteArray }
    }

    override fun writeCharacteristicSingle(characteristicUuid: UUID, byteArray: ByteArray): Single<ByteArray> {
        return Single.fromCallable {
            this.byteArray = byteArray
            byteArray
        }
    }

    override fun onNotificationStarted(type: BleDevice.NotificationType, characteristicUuid: UUID) {
        notifyDisposable = Observable.interval(1, TimeUnit.SECONDS)
            .doFinally { notifyDisposable = null }
            .subscribe({
                notifyByteArray(characteristicUuid, it.toByteArray())
            }, {
                Log.e("!!!", "notify error", it)
            })
    }

    override fun onNotificationStop(characteristicUuid: UUID) {
        notifyDisposable?.dispose()
    }

    companion object {
        private val CHARACTERISTIC_UUID = UUID.randomUUID()
    }
}