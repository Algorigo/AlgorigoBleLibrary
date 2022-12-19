package com.algorigo.algorigoble2.virtual

import com.algorigo.algorigoble2.BleCharacterisic
import com.algorigo.algorigoble2.BleDevice
import com.algorigo.algorigoble2.BleManager
import com.jakewharton.rxrelay3.BehaviorRelay
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.core.Single
import java.util.*

abstract class VirtualDevice(
    val deviceId: String,
    val deviceName: String?,
) {

    internal val connectionStateRelay = BehaviorRelay
        .create<BleDevice.ConnectionState>().apply {
        accept(BleDevice.ConnectionState.DISCONNECTED)
    }

    fun readCharacteristicSingle(characteristicUuid: UUID) = connectionStateRelay
        .firstOrError()
        .flatMap {
            if (it == BleDevice.ConnectionState.CONNECTED) {
                readCharacteristicSingleInner(characteristicUuid)
            } else {
                Single.error(BleManager.DisconnectedException())
            }
        }

    fun writeCharacteristicSingle(characteristicUuid: UUID, byteArray: ByteArray) = connectionStateRelay
        .firstOrError()
        .flatMap {
            if (it == BleDevice.ConnectionState.CONNECTED) {
                writeCharacteristicSingleInner(characteristicUuid, byteArray)
            } else {
                Single.error(BleManager.DisconnectedException())
            }
        }

    fun setupNotification(type: BleDevice.NotificationType, characteristicUuid: UUID) = connectionStateRelay
        .switchMap {
            if (it == BleDevice.ConnectionState.CONNECTED) {
                setupNotificationInner(type, characteristicUuid)
            } else {
                Observable.error(BleManager.DisconnectedException())
            }
        }

    abstract fun getCharacteristicsSingle(): Single<List<BleCharacterisic>>
    abstract fun readCharacteristicSingleInner(characteristicUuid: UUID): Single<ByteArray>
    abstract fun writeCharacteristicSingleInner(characteristicUuid: UUID, byteArray: ByteArray): Single<ByteArray>
    abstract fun setupNotificationInner(type: BleDevice.NotificationType, characteristicUuid: UUID): Observable<Observable<ByteArray>>

}