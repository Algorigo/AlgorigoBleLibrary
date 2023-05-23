package com.algorigo.algorigoble2

import android.bluetooth.BluetoothGattDescriptor
import java.util.*

open class BleDevice {

    enum class ConnectionState(var status: String) {
        CONNECTING("CONNECTING"),
        CONNECTED("CONNECTED"),
        DISCONNECTED("DISCONNECTED"),
        DISCONNECTING("DISCONNECTING"),
        SPP_CONNECTING("SPP CONNECTING"),
        SPP_CONNECTED("SPP CONNECTED"),
    }

    enum class NotificationType(val byteArray: ByteArray) {
        NOTIFICATION(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE),
        INDICATION(BluetoothGattDescriptor.ENABLE_INDICATION_VALUE),
    }

    internal lateinit var engine: BleDeviceEngine

    internal fun initEngine(engine: BleDeviceEngine) {
        engine.bleDevice = this
        this.engine = engine
    }

    val deviceId: String
        get() = engine.deviceId

    val deviceName: String?
        get() = engine.deviceName

    val bonded: Boolean
        get() = engine.bonded

    val connected: Boolean
        get() = engine.isConnected()

    val connectionState: ConnectionState
        get() = getConnectionStateObservable().blockingFirst()

    var virtual = false
        internal set

    fun bondCompletable() = engine.bondCompletable()
    fun unbondCompletable() = engine.unbondCompletable()

    open fun getConnectionStateObservable() = engine.getConnectionStateObservable()
    open fun connectCompletable(timeoutMillis: Long = 10000L) = engine.connectCompletable(timeoutMillis)

    fun connect() {
        connectCompletable().subscribe({
            logging.i("connected")
        }, {
            logging.e("conntection fail", it)
        })
    }

    fun disconnect() = engine.disconnect()

    open fun onDisconnected() {

    }

    fun getCharacteristicsSingle() = engine.getCharacteristicsSingle()

    fun readCharacteristicSingle(characteristicUuid: UUID) =
        engine.readCharacteristicSingle(characteristicUuid)
    fun writeCharacteristicSingle(characteristicUuid: UUID, byteArray: ByteArray) =
        engine.writeCharacteristicSingle(characteristicUuid, byteArray)
    fun setupNotification(type: NotificationType, characteristicUuid: UUID) =
        engine.setupNotification(type, characteristicUuid)

    fun connectSppSocket(uuid: UUID? = null) = engine.connectSppSocket(uuid)

    override fun toString(): String {
        return "${javaClass.simpleName} $deviceName($deviceId)"
    }

    override fun equals(other: Any?): Boolean {
        return other is BleDevice && this.deviceId == other.deviceId
    }

    companion object {
        private val TAG = BleDevice::class.java.simpleName
    }
}
