package com.algorigo.algorigoble2

import android.bluetooth.BluetoothDevice
import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.core.Single
import no.nordicsemi.android.wifi.provisioner.ble.internal.ConnectionStatus
import no.nordicsemi.kotlin.wifi.provisioner.domain.ScanRecordDomain
import no.nordicsemi.kotlin.wifi.provisioner.domain.WifiInfoDomain
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

    abstract fun readCharacteristicSingle(characteristicUuid: UUID): Single<ByteArray>
    abstract fun writeCharacteristicSingle(characteristicUuid: UUID, byteArray: ByteArray): Single<ByteArray>
    abstract fun setupNotification(type: BleDevice.NotificationType, characteristicUuid: UUID): Observable<Observable<ByteArray>>

    abstract fun connectSppSocket(uuid: UUID? = null): Observable<BleSppSocket>

    abstract fun start(): Completable
    abstract fun scanWifiList(): Observable<ScanRecordDomain>
    abstract fun stopScanWifiList(): Completable
    abstract fun startProvisioning(wifiInfoDomain: WifiInfoDomain, password: String): Single<Boolean>
    abstract fun cleanProvisioning(): Completable
    abstract fun getDeviceStatus(): Single<Map<String, Any>>
}
