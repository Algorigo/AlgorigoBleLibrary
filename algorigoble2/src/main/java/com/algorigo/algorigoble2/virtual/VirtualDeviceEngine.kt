package com.algorigo.algorigoble2.virtual

import com.algorigo.algorigoble2.BleCharacterisic
import com.algorigo.algorigoble2.BleDevice
import com.algorigo.algorigoble2.BleDeviceEngine
import com.algorigo.algorigoble2.BleManager
import com.algorigo.algorigoble2.BleSppSocket
import com.algorigo.algorigoble2.logging.Logging
import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.core.Single
import java.util.*
import java.util.concurrent.TimeUnit

internal class VirtualDeviceEngine(private val virtualDevice: VirtualDevice, logging: Logging) : BleDeviceEngine(logging) {
    override val deviceId: String
        get() = virtualDevice.deviceId
    override val deviceName: String?
        get() = virtualDevice.deviceName
    override val bonded: Boolean
        get() = false

    override fun bondCompletable(): Completable {
        return Completable.complete()
    }

    override fun getConnectionStateObservable(): Observable<BleDevice.ConnectionState> {
        return virtualDevice.connectionStateRelay
    }

    override fun connectCompletable(timeoutMillis: Long): Completable {
        return Completable.timer(100, TimeUnit.MILLISECONDS)
            .doOnSubscribe {
                virtualDevice.connectionStateRelay.accept(BleDevice.ConnectionState.CONNECTING)
            }
            .doOnComplete {
                virtualDevice.connectionStateRelay.accept(BleDevice.ConnectionState.CONNECTED)
            }
    }

    override fun disconnect() {
        virtualDevice.connectionStateRelay.accept(BleDevice.ConnectionState.DISCONNECTED)
    }

    override fun getCharacteristicsSingle(): Single<List<BleCharacterisic>> {
        return virtualDevice.getCharacteristicsSingle()
    }

    override fun readCharacteristicSingle(characteristicUuid: UUID): Single<ByteArray> {
        return virtualDevice.readCharacteristicSingleExternal(characteristicUuid)
    }

    override fun writeCharacteristicSingle(characteristicUuid: UUID, byteArray: ByteArray): Single<ByteArray> {
        return virtualDevice.writeCharacteristicSingleExternal(characteristicUuid, byteArray)
    }

    override fun setupNotification(type: BleDevice.NotificationType, characteristicUuid: UUID): Observable<Observable<ByteArray>> {
        return virtualDevice.setupNotification(type, characteristicUuid)
            .map {
                it
                    .mergeWith(
                        virtualDevice.connectionStateRelay
                            .filter { it != BleDevice.ConnectionState.CONNECTED }
                            .firstOrError()
                            .flatMapObservable {
                                Observable.error(BleManager.DisconnectedException())
                            }
                    )
            }
    }

    override fun connectSppSocket(uuid: UUID?): Observable<BleSppSocket> {
        return Observable.error(RuntimeException("Virtual device does not support spp socket"))
    }
}