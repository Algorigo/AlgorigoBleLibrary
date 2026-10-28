package com.algorigo.algorigoble2

import android.bluetooth.BluetoothGattCharacteristic
import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.core.Single
import java.util.UUID

internal abstract class BleDeviceEngine() {

    lateinit var bleDevice: BleDevice

    abstract val deviceId: String
    abstract val deviceName: String?
    abstract val bonded: Boolean

    abstract fun bondCompletable(): Completable
    abstract fun unbondCompletable(): Completable

    fun isConnected(): Boolean = getConnectionStateObservable().firstOrError().map { it == BleDevice.ConnectionState.CONNECTED }.blockingGet()
    abstract fun getConnectionStateObservable(): Observable<BleDevice.ConnectionState>

    abstract fun connectCompletable(timeoutMillis: Long): Completable

    abstract fun disconnect()

    abstract fun getCharacteristicsSingle(): Single<List<BleCharacterisic>>

    // serviceUuid 가 null 이면 모든 서비스에서 처음 찾은 characteristic 을 쓴다
    abstract fun readCharacteristicSingle(serviceUuid: UUID?, characteristicUuid: UUID): Single<ByteArray>
    abstract fun writeCharacteristicSingle(serviceUuid: UUID?, characteristicUuid: UUID, byteArray: ByteArray, writeType: BleDevice.WriteType): Single<ByteArray>
    abstract fun setupNotification(type: BleDevice.NotificationType, serviceUuid: UUID?, characteristicUuid: UUID): Observable<Observable<ByteArray>>

    abstract fun connectSppSocket(uuid: UUID? = null): Observable<BleSppSocket>

    abstract fun requestMtuSingle(mtu: Int): Single<Int>

    abstract fun readRssiSingle(): Single<Int>

    abstract fun requestConnectionPriorityCompletable(priority: BleDevice.ConnectionPriority): Completable
}
