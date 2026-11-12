package com.algorigo.algorigoble2.virtual

import com.algorigo.algorigoble2.BleCharacteristic
import com.algorigo.algorigoble2.BleDevice
import com.algorigo.algorigoble2.BleDeviceEngine
import com.algorigo.algorigoble2.BleManager
import com.algorigo.algorigoble2.BleService
import com.algorigo.algorigoble2.BleSppSocket
import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.core.Single
import java.util.*
import java.util.concurrent.TimeUnit

internal class VirtualDeviceEngine(private val virtualDevice: VirtualDevice) : BleDeviceEngine() {
    override val deviceId: String
        get() = virtualDevice.deviceId
    override val deviceName: String?
        get() = virtualDevice.deviceName
    override val bonded: Boolean
        get() = false

    override fun bondCompletable(timeoutMillis: Long): Completable {
        return Completable.complete()
    }

    override fun unbondCompletable(timeoutMillis: Long): Completable {
        return Completable.complete()
    }

    override fun getConnectionStateObservable(): Observable<BleDevice.ConnectionState> {
        return virtualDevice.connectionStateRelay
    }

    // 가상 기기는 바로 연결되므로 autoConnect 는 쓰지 않는다
    override fun connectCompletable(timeoutMillis: Long, autoConnect: Boolean): Completable {
        val relay = virtualDevice.connectionStateRelay
        return Completable.timer(100, TimeUnit.MILLISECONDS)
            .doOnSubscribe {
                relay.accept(BleDevice.ConnectionState.CONNECTING)
            }
            .andThen(Completable.fromAction {
                synchronized(relay) {
                    // 연결 중에 disconnect() 가 불렸으면 실기기처럼 연결하지 않고 실패한다
                    if (relay.value != BleDevice.ConnectionState.CONNECTING) {
                        throw BleManager.DisconnectedException()
                    }
                    relay.accept(BleDevice.ConnectionState.CONNECTED)
                }
            })
            .doOnDispose {
                // 연결이 끝나기 전에 구독을 해제하면 연결 시도를 취소한다
                synchronized(relay) {
                    if (relay.value == BleDevice.ConnectionState.CONNECTING) {
                        relay.accept(BleDevice.ConnectionState.DISCONNECTED)
                    }
                }
            }
    }

    override fun disconnect() {
        val wasDisconnected = synchronized(virtualDevice.connectionStateRelay) {
            (virtualDevice.connectionStateRelay.value == BleDevice.ConnectionState.DISCONNECTED).also {
                if (!it) {
                    virtualDevice.connectionStateRelay.accept(BleDevice.ConnectionState.DISCONNECTED)
                }
            }
        }
        // 실기기 엔진처럼 연결이 끊기면 알린다. (InitializableBleDevice 는 여기서 초기화 상태를 되돌린다)
        if (!wasDisconnected) {
            bleDevice.notifyDisconnected()
        }
    }

    override fun getCharacteristicsSingle(): Single<List<BleCharacteristic>> {
        return virtualDevice.getCharacteristicsSingle()
    }

    // 실기기처럼 연결된 동안에만 서비스를 알 수 있다
    override fun getServicesSingle(): Single<List<BleService>> {
        return virtualDevice.connectionStateRelay
            .firstOrError()
            .flatMap {
                if (it == BleDevice.ConnectionState.CONNECTED) virtualDevice.getServicesSingle() else Single.error(BleManager.DisconnectedException())
            }
    }

    // 가상 기기는 서비스를 구분하지 않는다
    override fun readCharacteristicSingle(serviceUuid: UUID?, characteristicUuid: UUID): Single<ByteArray> {
        return virtualDevice.readCharacteristicSingleExternal(characteristicUuid)
    }

    // 가상 기기는 쓰기 타입을 구분하지 않는다
    override fun readDescriptorSingle(serviceUuid: UUID?, characteristicUuid: UUID, descriptorUuid: UUID): Single<ByteArray> {
        return virtualDevice.readDescriptorSingleExternal(characteristicUuid, descriptorUuid)
    }

    override fun writeDescriptorSingle(serviceUuid: UUID?, characteristicUuid: UUID, descriptorUuid: UUID, byteArray: ByteArray): Single<ByteArray> {
        if (descriptorUuid == CLIENT_CHARACTERISTIC_CONFIG_UUID) {
            return Single.error(cccdWriteError())
        }
        return virtualDevice.writeDescriptorSingleExternal(characteristicUuid, descriptorUuid, byteArray)
    }

    override fun writeCharacteristicSingle(serviceUuid: UUID?, characteristicUuid: UUID, byteArray: ByteArray, writeType: BleDevice.WriteType): Single<ByteArray> {
        return virtualDevice.writeCharacteristicSingleExternal(characteristicUuid, byteArray)
    }

    override fun setupNotification(type: BleDevice.NotificationType, serviceUuid: UUID?, characteristicUuid: UUID): Observable<Observable<ByteArray>> {
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
        return Observable.error(UnsupportedOperationException("Virtual device does not support spp socket"))
    }

    // 가상 기기에는 연결 간격이 없으므로 연결돼 있으면 바로 완료한다
    override fun requestConnectionPriorityCompletable(priority: BleDevice.ConnectionPriority): Completable {
        return virtualDevice.connectionStateRelay
            .firstOrError()
            .flatMapCompletable {
                if (it == BleDevice.ConnectionState.CONNECTED) Completable.complete() else Completable.error(BleManager.DisconnectedException())
            }
    }

    override fun readRssiSingle(): Single<Int> {
        return virtualDevice.connectionStateRelay
            .firstOrError()
            .map {
                if (it != BleDevice.ConnectionState.CONNECTED) {
                    throw BleManager.DisconnectedException()
                }
                virtualDevice.readRssi()
            }
    }

    override fun requestMtuSingle(mtu: Int): Single<Int> {
        return virtualDevice.connectionStateRelay
            .firstOrError()
            .flatMap {
                if (it == BleDevice.ConnectionState.CONNECTED) {
                    Single.just(mtu.coerceIn(MIN_MTU, MAX_MTU))
                } else {
                    Single.error(BleManager.DisconnectedException())
                }
            }
    }

    companion object {
        private const val MIN_MTU = 23
        private const val MAX_MTU = 517
    }
}