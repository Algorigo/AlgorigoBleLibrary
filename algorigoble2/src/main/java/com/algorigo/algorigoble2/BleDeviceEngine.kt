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

    /** [autoConnect]는 BluetoothDevice.connectGatt 의 autoConnect 이다. */
    abstract fun connectCompletable(timeoutMillis: Long, autoConnect: Boolean = false): Completable

    abstract fun disconnect()

    abstract fun getCharacteristicsSingle(): Single<List<BleCharacterisic>>

    // serviceUuid 가 null 이면 모든 서비스에서 처음 찾은 characteristic 을 쓴다
    abstract fun readCharacteristicSingle(serviceUuid: UUID?, characteristicUuid: UUID): Single<ByteArray>
    abstract fun writeCharacteristicSingle(serviceUuid: UUID?, characteristicUuid: UUID, byteArray: ByteArray, writeType: BleDevice.WriteType): Single<ByteArray>
    abstract fun readDescriptorSingle(serviceUuid: UUID?, characteristicUuid: UUID, descriptorUuid: UUID): Single<ByteArray>
    /** CCCD 는 쓸 수 없다. ([cccdWriteError]) */
    abstract fun writeDescriptorSingle(serviceUuid: UUID?, characteristicUuid: UUID, descriptorUuid: UUID, byteArray: ByteArray): Single<ByteArray>
    abstract fun setupNotification(type: BleDevice.NotificationType, serviceUuid: UUID?, characteristicUuid: UUID): Observable<Observable<ByteArray>>

    abstract fun connectSppSocket(uuid: UUID? = null): Observable<BleSppSocket>

    abstract fun requestMtuSingle(mtu: Int): Single<Int>

    abstract fun readRssiSingle(): Single<Int>

    abstract fun requestConnectionPriorityCompletable(priority: BleDevice.ConnectionPriority): Completable

    companion object {
        const val OPERATION_DISCOVER_SERVICES = "DISCOVER_SERVICES"
        val CLIENT_CHARACTERISTIC_CONFIG_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        // CCCD 를 직접 쓰면 setupNotification 이 관리하는 notification 상태와 어긋나므로 받지 않는다
        fun cccdWriteError() = IllegalArgumentException("CCCD(0x2902) cannot be written directly. Use setupNotification() to enable or disable notification/indication")
    }
}
