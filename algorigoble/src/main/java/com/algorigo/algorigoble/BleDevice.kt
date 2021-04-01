package com.algorigo.algorigoble

import android.bluetooth.BluetoothDevice
import android.util.Log
import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.core.Single
import java.util.*

open class BleDevice {

    enum class ConnectionState(var status: String) {
        CONNECTING("CONNECTING"),
        CONNECTED("CONNECTED"),
        DISCONNECTED("DISCONNECTED"),
        DISCONNECTING("DISCONNECTING")
    }

    val bleDeviceEngine: BleDeviceEngine = BleManager.generateDeviceEngine().apply {
        bleDeviceCallback = object : BleDeviceEngine.BleDeviceCallback {
            override fun onDeviceReconnected() {
                this@BleDevice.onReconnected()
            }

            override fun onDeviceDisconnected() {
                this@BleDevice.onDisconnected()
            }
        }
    }

    val name: String?
        get() = bleDeviceEngine.name

    val macAddress: String
        get() = bleDeviceEngine.macAddress

    val bluetoothDevice: BluetoothDevice
        get() = bleDeviceEngine.bluetoothDevice

    open val connectionState: ConnectionState
        get() = bleDeviceEngine.connectionState

    open val connected: Boolean
        get() = connectionState == ConnectionState.CONNECTED

    fun connectCompletable(autoConnect: Boolean, milliSec: Long? = null): Completable {
        return if (milliSec != null) {
            connectCompletableImpl(autoConnect, milliSec)
        } else {
            connectCompletableImpl(autoConnect)
        }
    }

    internal open fun connectCompletableImpl(autoConnect: Boolean): Completable {
        return bleDeviceEngine.connectCompletableImpl(autoConnect)
    }

    internal open fun connectCompletableImpl(autoConnect: Boolean, milliSec: Long): Completable {
        return bleDeviceEngine.connectCompletableImpl(autoConnect, milliSec)
    }

    fun connect(autoConnect: Boolean) {
        connectCompletable(autoConnect).subscribe({
            Log.e(TAG, "connected")
        }, {
            Log.e(TAG, "conntection fail", it)
        })
    }

    fun disconnect() {
        bleDeviceEngine.disconnect()
    }

    open fun onReconnected() {

    }

    open fun onDisconnected() {

    }

    open fun getConnectionStateObservable(): Observable<ConnectionState> {
        return bleDeviceEngine.getConnectionStateObservable()
    }

    fun readCharacteristic(characteristicUuid: UUID): Single<ByteArray>? {
        return bleDeviceEngine.readCharacteristic(characteristicUuid)
    }

    fun writeCharacteristic(characteristicUuid: UUID, value: ByteArray): Single<ByteArray>? {
        return bleDeviceEngine.writeCharacteristic(characteristicUuid, value)
    }

    fun writeLongValue(characteristicUuid: UUID, value: ByteArray): Observable<ByteArray>? {
        return bleDeviceEngine.writeLongValue(characteristicUuid, value)
    }

    fun setupNotification(characteristicUuid: UUID): Observable<Observable<ByteArray>>? {
        return bleDeviceEngine.setupNotification(characteristicUuid)
    }

    fun setupIndication(characteristicUuid: UUID): Observable<Observable<ByteArray>>? {
        return bleDeviceEngine.setupIndication(characteristicUuid)
    }

    override fun toString(): String {
        return "${javaClass.simpleName} $name($macAddress)"
    }

    companion object {
        private val TAG = BleDevice::class.java.simpleName
    }
}