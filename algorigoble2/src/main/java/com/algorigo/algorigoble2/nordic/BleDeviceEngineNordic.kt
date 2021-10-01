package com.algorigo.algorigoble2.nordic

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.content.Context
import com.algorigo.algorigoble2.BleDevice
import com.algorigo.algorigoble2.BleManager
import com.algorigo.algorigoble2.impl.BleDeviceEngineImpl
import com.jakewharton.rxrelay3.BehaviorRelay
import com.jakewharton.rxrelay3.PublishRelay
import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.core.Single
import java.util.UUID

internal class BleDeviceEngineNordic(context: Context, bluetoothDevice: BluetoothDevice):
    BleDeviceEngineImpl(context, bluetoothDevice) {

    private val bleManager = object : no.nordicsemi.android.ble.BleManager(context) {

        lateinit var characteristics: Map<UUID, BluetoothGattCharacteristic>

        private inner class GattCallback : BleManagerGattCallback() {
            override fun isRequiredServiceSupported(bluetoothGatt: BluetoothGatt): Boolean {
                characteristics = bluetoothGatt
                    .services
                    .map { it.characteristics }
                    .flatten().associateBy { it.uuid }
                stateRelay.accept(BleDevice.ConnectionState.CONNECTED)
                return true
            }

            override fun onServicesInvalidated() {
                stateRelay.accept(BleDevice.ConnectionState.DISCONNECTED)
            }

            fun readCharacteristic(uuid: UUID): Single<ByteArray> {
                return Single.create { emitter ->
                    readCharacteristic(characteristics[uuid])
                        .with { bluetoothDevice, data ->
                            data.value?.also {
                                emitter.onSuccess(it)
                            }
                        }
                        .fail { bluetoothDevice, i ->
                            emitter.onError(Exception("$uuid : $i"))
                        }
                        .enqueue()
                }
            }

            fun writeCharacteristic(uuid: UUID, byteArray: ByteArray): Single<ByteArray> {
                return Single.create { emitter ->
                    writeCharacteristic(characteristics[uuid], byteArray, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
                        .with { bluetoothDevice, data ->
                            data.value?.also {
                                emitter.onSuccess(it)
                            }
                        }
                        .fail { bluetoothDevice, i ->
                            emitter.onError(Exception("$uuid : $i"))
                        }
                        .enqueue()
                }
            }

            fun setupNotification(uuid: UUID): Observable<Observable<ByteArray>> {
                return Observable.create<Observable<ByteArray>> { emitter ->
                    val relay = PublishRelay.create<ByteArray>()
                    setNotificationCallback(characteristics[uuid])
                        .with { bluetoothDevice, data ->
                            data.value?.let {
                                relay.accept(it)
                            }
                        }
                    enableNotifications(characteristics[uuid])
                        .done {
                            emitter.onNext(relay)
                        }
                        .fail { bluetoothDevice, i ->
                            emitter.onError(Exception("$uuid : $i"))
                        }
                        .enqueue()
                }
                    .doFinally {
                        disableNotifications(characteristics[uuid]).enqueue()
                        removeNotificationCallback(characteristics[uuid])
                    }
            }

            fun setupIndication(uuid: UUID): Observable<Observable<ByteArray>> {
                return Observable.create<Observable<ByteArray>> { emitter ->
                    val relay = PublishRelay.create<ByteArray>()
                    setIndicationCallback(characteristics[uuid])
                        .with { bluetoothDevice, data ->
                            data.value?.let {
                                relay.accept(it)
                            }
                        }
                    enableIndications(characteristics[uuid])
                        .done {
                            emitter.onNext(relay)
                        }
                        .fail { bluetoothDevice, i ->
                            emitter.onError(Exception("$uuid : $i"))
                        }
                        .enqueue()
                }
                    .doFinally {
                        disableIndications(characteristics[uuid]).enqueue()
                        removeIndicationCallback(characteristics[uuid])
                    }
            }
        }

        override fun getGattCallback() = GattCallback()

        fun readCharacteristic(uuid: UUID): Single<ByteArray> =
            gattCallback.readCharacteristic(uuid)

        fun writeCharacteristic(uuid: UUID, byteArray: ByteArray) =
            gattCallback.writeCharacteristic(uuid, byteArray)

        fun setupNotification(uuid: UUID) =
            gattCallback.setupNotification(uuid)

        fun setupIndication(uuid: UUID) =
            gattCallback.setupIndication(uuid)
    }

    private val stateRelay = BehaviorRelay.create<BleDevice.ConnectionState>().apply {
        accept(BleDevice.ConnectionState.DISCONNECTED)
    }

    protected fun finalize() {
        bleManager.close()
    }

    override fun getConnectionStateObservable(): Observable<BleDevice.ConnectionState> {
        return stateRelay
    }

    override fun connectCompletable(): Completable {
        return Completable.create {
            stateRelay.accept(BleDevice.ConnectionState.CONNECTING)
            bleManager.connect(bluetoothDevice).enqueue()
            it.onComplete()
        }
            .andThen(Observable.zip(
                Observable.just(0, 1),
                stateRelay,
                { count, state ->
                    if (state == BleDevice.ConnectionState.DISCONNECTED && count != 0) {
                        throw BleManager.DisconnectedException()
                    } else {
                        state
                    }
                }
            ))
            .filter {
                it == BleDevice.ConnectionState.CONNECTED
            }
            .firstOrError()
            .ignoreElement()
    }

    override fun disconnect() {
        bleManager.disconnect().enqueue()
    }

    override fun getCharacteristicsSingle(): Single<List<BluetoothGattCharacteristic>> {
        return Single.just(bleManager.characteristics.values.toList())
    }

    override fun readCharacteristicSingle(characteristicUuid: UUID): Single<ByteArray> {
        return bleManager.readCharacteristic(characteristicUuid)
    }

    override fun writeCharacteristicSingle(
        characteristicUuid: UUID,
        byteArray: ByteArray
    ): Single<ByteArray> {
        return bleManager.writeCharacteristic(characteristicUuid, byteArray)
    }

    override fun setupNotification(
        type: BleDevice.NotificationType,
        characteristicUuid: UUID
    ): Observable<Observable<ByteArray>> {
        return when (type) {
            BleDevice.NotificationType.NOTIFICATION -> bleManager.setupNotification(characteristicUuid)
            BleDevice.NotificationType.INDICATION -> bleManager.setupIndication(characteristicUuid)
        }
    }
}