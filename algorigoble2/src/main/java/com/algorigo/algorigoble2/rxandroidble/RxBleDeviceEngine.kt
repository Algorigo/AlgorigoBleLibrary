package com.algorigo.algorigoble2.rxandroidble

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGattCharacteristic
import android.content.Context
import com.algorigo.algorigoble2.BleDevice
import com.algorigo.algorigoble2.BleDeviceEngine
import com.algorigo.algorigoble2.BleManager
import com.algorigo.algorigoble2.BleSppSocket
import com.algorigo.algorigoble2.impl.BleDeviceEngineImpl
import com.jakewharton.rxrelay2.BehaviorRelay
import com.polidea.rxandroidble2.RxBleConnection
import com.polidea.rxandroidble2.RxBleDevice
import hu.akarnokd.rxjava3.bridge.RxJavaBridge
import io.reactivex.disposables.Disposable
import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.schedulers.Schedulers
import java.util.*

class RxBleDeviceEngine(private val context: Context, private val rxBleDevice: RxBleDevice):
    BleDeviceEngine() {

    private sealed class ConnectionWrapper {
        class CONNECTED(val rxBleConnection: RxBleConnection) : ConnectionWrapper()
        object DISCONNECTED : ConnectionWrapper()
    }

    override val deviceId: String
        get() = rxBleDevice.macAddress
    override val deviceName: String?
        get() = rxBleDevice.name
    override val bonded: Boolean
        get() = rxBleDevice.bluetoothDevice.bondState == BluetoothDevice.BOND_BONDED

    private var establishDisposable: Disposable? = null

    private var connectionRelay = BehaviorRelay.create<ConnectionWrapper>()
    private val connectionObservable
        get() = connectionRelay
            .map {
                if (it is ConnectionWrapper.CONNECTED) {
                    it.rxBleConnection
                } else {
                    throw BleManager.DisconnectedException()
                }
            }

    override fun bondCompletable(): Completable {
        TODO("Not yet implemented")
    }

    override fun getConnectionStateObservable(): Observable<BleDevice.ConnectionState> {
        return rxBleDevice.observeConnectionStateChanges()
            .map {
                when (it) {
                    RxBleConnection.RxBleConnectionState.CONNECTED -> BleDevice.ConnectionState.CONNECTED
                    RxBleConnection.RxBleConnectionState.CONNECTING -> BleDevice.ConnectionState.CONNECTING
                    RxBleConnection.RxBleConnectionState.DISCONNECTED -> BleDevice.ConnectionState.DISCONNECTED
                    RxBleConnection.RxBleConnectionState.DISCONNECTING -> BleDevice.ConnectionState.DISCONNECTING
                }
            }
            .`as`(RxJavaBridge.toV3Observable())
    }

    override fun connectCompletable(): Completable {
        return Completable.create { emitter ->
            if (establishDisposable == null) {
                establishDisposable = rxBleDevice.establishConnection(false)
                    .doFinally {
                        establishDisposable = null
                        connectionRelay.accept(ConnectionWrapper.DISCONNECTED)
                    }
                    .subscribe({ connection ->
                        connectionRelay.accept(ConnectionWrapper.CONNECTED(connection))
                        emitter.onComplete()
                    }, {
                        emitter.onError(it)
                    })
            } else {
                emitter.onComplete()
            }
        }.doOnDispose {
            establishDisposable?.dispose()
        }
    }

    override fun disconnect() {
        establishDisposable?.dispose()
    }

    override fun getCharacteristicsSingle(): Single<List<BluetoothGattCharacteristic>> {
        return connectionObservable
            .firstOrError()
            .flatMap {
                it.discoverServices()
            }
            .map {
                it.bluetoothGattServices.map { service ->
                    service.characteristics
                }.flatten()
            }
            .`as`(RxJavaBridge.toV3Single())
    }

    override fun readCharacteristicSingle(characteristicUuid: UUID): Single<ByteArray> {
        return connectionObservable
            .firstOrError()
            .flatMap {
                it.readCharacteristic(characteristicUuid)
            }
            .`as`(RxJavaBridge.toV3Single())
    }

    override fun writeCharacteristicSingle(
        characteristicUuid: UUID,
        byteArray: ByteArray
    ): Single<ByteArray> {
        return connectionObservable
            .firstOrError()
            .flatMap {
                it.writeCharacteristic(characteristicUuid, byteArray)
            }
            .`as`(RxJavaBridge.toV3Single())
    }

    override fun setupNotification(
        type: BleDevice.NotificationType,
        characteristicUuid: UUID
    ): Observable<Observable<ByteArray>> {
        return connectionObservable
            .firstOrError()
            .flatMapObservable {
                when (type) {
                    BleDevice.NotificationType.NOTIFICATION -> it.setupNotification(characteristicUuid)
                    BleDevice.NotificationType.INDICATION -> it.setupIndication(characteristicUuid)
                }
            }
            .map {
                it.`as`(RxJavaBridge.toV3Observable())
            }
            .`as`(RxJavaBridge.toV3Observable())
    }

    override fun connectSppSocket(uuid: UUID?): Observable<BleSppSocket> {
        var socket: BleSppSocket? = null
        return Observable.create<BleSppSocket> {
            val bluetoothDevice = rxBleDevice.bluetoothDevice
                val aUuid = uuid ?: bluetoothDevice.uuids.first().uuid
                BleSppSocket(bluetoothDevice.createRfcommSocketToServiceRecord(aUuid)).also { theSocket ->
                    socket = theSocket
                    it.onNext(theSocket)
                }
            }
            .doFinally {
                socket?.close()
            }
            .subscribeOn(Schedulers.io())
    }
}
