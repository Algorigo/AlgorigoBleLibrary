package com.algorigo.algorigoble

import android.bluetooth.BluetoothDevice
import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.core.Single
import java.util.*

interface BleDeviceEngine {

    interface BleDeviceCallback {
        fun onDeviceReconnected()
        fun onDeviceDisconnected()
    }

    val name: String?
    val macAddress: String
    val bluetoothDevice: BluetoothDevice
    val connectionState: BleDevice.ConnectionState
    var bleDeviceCallback: BleDeviceCallback?

    fun connectCompletableImpl(autoConnect: Boolean): Completable
    fun connectCompletableImpl(autoConnect: Boolean, milliSec: Long): Completable
    fun disconnect()
    fun onReconnected()
    fun onDisconnected()
    fun getConnectionStateObservable(): Observable<BleDevice.ConnectionState>
    fun readCharacteristic(characteristicUuid: UUID): Single<ByteArray>?
    fun writeCharacteristic(characteristicUuid: UUID, value: ByteArray): Single<ByteArray>?
    fun writeLongValue(characteristicUuid: UUID, value: ByteArray): Observable<ByteArray>?
    fun setupNotification(characteristicUuid: UUID): Observable<Observable<ByteArray>>?
    fun setupIndication(characteristicUuid: UUID): Observable<Observable<ByteArray>>?

}