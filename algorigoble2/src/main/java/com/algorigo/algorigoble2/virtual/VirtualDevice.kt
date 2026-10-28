package com.algorigo.algorigoble2.virtual

import com.algorigo.algorigoble2.BleCharacterisic
import com.algorigo.algorigoble2.BleDevice
import com.algorigo.algorigoble2.BleManager
import com.jakewharton.rxrelay3.BehaviorRelay
import com.jakewharton.rxrelay3.PublishRelay
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.schedulers.Schedulers
import java.util.*

abstract class VirtualDevice(
    val deviceId: String,
    val deviceName: String?,
) {

    internal val connectionStateRelay = BehaviorRelay
        .create<BleDevice.ConnectionState>().apply {
        accept(BleDevice.ConnectionState.DISCONNECTED)
    }

    private val notificationRelay = PublishRelay.create<Pair<UUID, ByteArray>>()
    private val notificationCountMap = mutableMapOf<UUID, Int>()

    fun readCharacteristicSingleExternal(characteristicUuid: UUID): Single<ByteArray> = connectionStateRelay
        .firstOrError()
        .flatMap {
            if (it == BleDevice.ConnectionState.CONNECTED) {
                readCharacteristicSingle(characteristicUuid)
            } else {
                Single.error(BleManager.DisconnectedException())
            }
        }

    fun writeCharacteristicSingleExternal(characteristicUuid: UUID, byteArray: ByteArray): Single<ByteArray> = connectionStateRelay
        .firstOrError()
        .flatMap {
            if (it == BleDevice.ConnectionState.CONNECTED) {
                writeCharacteristicSingle(characteristicUuid, byteArray)
            } else {
                Single.error(BleManager.DisconnectedException())
            }
        }

    fun readDescriptorSingleExternal(characteristicUuid: UUID, descriptorUuid: UUID): Single<ByteArray> = connectionStateRelay
        .firstOrError()
        .flatMap {
            if (it == BleDevice.ConnectionState.CONNECTED) {
                readDescriptorSingle(characteristicUuid, descriptorUuid)
            } else {
                Single.error(BleManager.DisconnectedException())
            }
        }

    fun writeDescriptorSingleExternal(characteristicUuid: UUID, descriptorUuid: UUID, byteArray: ByteArray): Single<ByteArray> = connectionStateRelay
        .firstOrError()
        .flatMap {
            if (it == BleDevice.ConnectionState.CONNECTED) {
                writeDescriptorSingle(characteristicUuid, descriptorUuid, byteArray)
            } else {
                Single.error(BleManager.DisconnectedException())
            }
        }

    fun setupNotification(type: BleDevice.NotificationType, characteristicUuid: UUID): Observable<Observable<ByteArray>> = connectionStateRelay
        .switchMap {
            if (it == BleDevice.ConnectionState.CONNECTED) {
                Observable.create<Observable<ByteArray>> {
                    it.onNext(
                        notificationRelay
                            .filter { it.first == characteristicUuid }
                            .map { it.second }
                    )
                }
            } else {
                Observable.error(BleManager.DisconnectedException())
            }
        }
        .doOnSubscribe {
            ((notificationCountMap[characteristicUuid] ?: 0) + 1).also {
                notificationCountMap[characteristicUuid] = it
                if (it == 1) {
                    onNotificationStarted(type, characteristicUuid)
                }
            }
        }
        .doFinally {
            (notificationCountMap[characteristicUuid]!! - 1).also {
                notificationCountMap[characteristicUuid] = it
                if (it == 0) {
                    onNotificationStop(characteristicUuid)
                }
            }
        }

    // notification 을 호출한 스레드와 분리해서 전달하되, 실기기처럼 보낸 순서대로 전달한다 (worker 는 작업을 순서대로 하나씩 실행한다)
    private val notificationWorker = Schedulers.io().createWorker()

    protected fun notifyByteArray(characteristicUuid: UUID, byteArray: ByteArray) {
        notificationWorker.schedule {
            notificationRelay.accept(Pair(characteristicUuid, byteArray))
        }
    }

    /** 연결된 동안 [BleDevice.readRssiSingle]이 돌려줄 RSSI(dBm). 기본값은 스캔 결과와 같은 0 이다. */
    open fun readRssi(): Int = 0

    /** 연결된 동안 [BleDevice.readDescriptorSingle]이 부른다. 기본은 descriptor 가 없다. ([BleManager.DescriptorNotFoundException]) */
    open fun readDescriptorSingle(characteristicUuid: UUID, descriptorUuid: UUID): Single<ByteArray> =
        Single.error(BleManager.DescriptorNotFoundException(characteristicUuid, descriptorUuid))

    /** 연결된 동안 [BleDevice.writeDescriptorSingle]이 부른다. (CCCD 는 오지 않는다) 기본은 descriptor 가 없다. */
    open fun writeDescriptorSingle(characteristicUuid: UUID, descriptorUuid: UUID, byteArray: ByteArray): Single<ByteArray> =
        Single.error(BleManager.DescriptorNotFoundException(characteristicUuid, descriptorUuid))

    abstract fun getCharacteristicsSingle(): Single<List<BleCharacterisic>>
    abstract fun readCharacteristicSingle(characteristicUuid: UUID): Single<ByteArray>
    abstract fun writeCharacteristicSingle(characteristicUuid: UUID, byteArray: ByteArray): Single<ByteArray>
    abstract fun onNotificationStarted(type: BleDevice.NotificationType, characteristicUuid: UUID)
    abstract fun onNotificationStop(characteristicUuid: UUID)

}