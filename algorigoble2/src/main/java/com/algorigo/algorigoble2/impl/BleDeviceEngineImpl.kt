package com.algorigo.algorigoble2.impl

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothProfile
import android.util.Log
import com.algorigo.algorigoble2.BleCharacterisic
import com.algorigo.algorigoble2.BleDevice
import com.algorigo.algorigoble2.BleDeviceEngine
import com.algorigo.algorigoble2.BleManager
import com.algorigo.algorigoble2.BleSppSocket
import com.algorigo.algorigoble2.applicationContext
import com.algorigo.algorigoble2.logging
import com.jakewharton.rxrelay3.BehaviorRelay
import com.jakewharton.rxrelay3.PublishRelay
import enumerated
import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.core.Flowable
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.schedulers.Schedulers
import java.util.*
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

@SuppressLint("MissingPermission")
internal class BleDeviceEngineImpl(
    private val bluetoothDevice: BluetoothDevice
) : BleDeviceEngine() {

    class CommunicationFailedException: Exception()
    class IllegalCharacteristicProperty(val property: Int, val type: String): Exception()

    private sealed class State(val connectionState: BleDevice.ConnectionState) {
        class CONNECTING : State(BleDevice.ConnectionState.CONNECTING)
        class CONNECTED(val gatt: BluetoothGatt) : State(BleDevice.ConnectionState.CONNECTED)
        class DISCONNECTED : State(BleDevice.ConnectionState.DISCONNECTED)
        class DISCONNECTING : State(BleDevice.ConnectionState.DISCONNECTING)
        class SPP_CONNECTING : State(BleDevice.ConnectionState.SPP_CONNECTING)
        class SPP_CONNECTED : State(BleDevice.ConnectionState.SPP_CONNECTED)
    }

    private enum class Type {
        READ_CHARACTERISTIC,
        WRITE_CHARACTERISTIC,
        READ_DESCRIPTOR,
        WRITE_DESCRIPTOR,
    }

    private data class ReplyData(val type: Type, val uuid: UUID, val byteArray: ByteArray) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is ReplyData) return false
            if (type != other.type) return false
            if (uuid != other.uuid) return false
            if (!byteArray.contentEquals(other.byteArray)) return false
            return true
        }

        override fun hashCode(): Int {
            var result = type.hashCode()
            result = 31 * result + uuid.hashCode()
            result = 31 * result + byteArray.contentHashCode()
            return result
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
            super.onConnectionStateChange(gatt, status, newState)
            logging.d { "${gatt?.device?.name}(${gatt?.device?.address}) : onConnectionStateChange $status -> $newState" }
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    gatt?.discoverServices()
                }

                BluetoothProfile.STATE_DISCONNECTED -> {
                    stateRelay.accept(State.DISCONNECTED())
                    bleDevice.onDisconnected()
                    gatt?.close()
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
            super.onServicesDiscovered(gatt, status)
            logging.d { "${gatt?.device?.name}(${gatt?.device?.address}) : onServicesDiscovered $status" }
            gatt?.let {
                stateRelay.accept(State.CONNECTED(it))
            }
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt?,
            characteristic: BluetoothGattCharacteristic?,
            status: Int
        ) {
            super.onCharacteristicRead(gatt, characteristic, status)
            logging.d { "${gatt?.device?.name}(${gatt?.device?.address}) : onCharacteristicRead:$status, ${characteristic?.uuid}:${characteristic?.value?.toHexString()}" }
            characteristic?.let {
                replyRelay.accept(ReplyData(Type.READ_CHARACTERISTIC, it.uuid, it.value ?: byteArrayOf()))
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt?,
            characteristic: BluetoothGattCharacteristic?,
            status: Int
        ) {
            super.onCharacteristicWrite(gatt, characteristic, status)
            logging.d { "${gatt?.device?.name}(${gatt?.device?.address}) : onCharacteristicWrite:$status, ${characteristic?.uuid}:${characteristic?.value?.toHexString()}" }
            characteristic?.let {
                replyRelay.accept(ReplyData(Type.WRITE_CHARACTERISTIC, it.uuid, it.value ?: byteArrayOf()))
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt?,
            characteristic: BluetoothGattCharacteristic?
        ) {
            super.onCharacteristicChanged(gatt, characteristic)
            logging.d { "${gatt?.device?.name}(${gatt?.device?.address}) : onCharacteristicChanged:${characteristic?.uuid}:${characteristic?.value?.toHexString()}" }
            characteristic?.let {
                notificationRelay.accept(Pair(it.uuid, it.value))
            }
        }

        override fun onDescriptorRead(
            gatt: BluetoothGatt?,
            descriptor: BluetoothGattDescriptor?,
            status: Int
        ) {
            super.onDescriptorRead(gatt, descriptor, status)
            logging.d { "${gatt?.device?.name}(${gatt?.device?.address}) : onDescriptorRead:${descriptor?.characteristic?.uuid}:${descriptor?.value?.toHexString()}" }
            descriptor?.let {
                replyRelay.accept(ReplyData(Type.READ_DESCRIPTOR, it.characteristic.uuid, it.value ?: byteArrayOf()))
            }
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt?,
            descriptor: BluetoothGattDescriptor?,
            status: Int
        ) {
            super.onDescriptorWrite(gatt, descriptor, status)
            logging.d { "${gatt?.device?.name}(${gatt?.device?.address}) : onDescriptorWrite:${descriptor?.characteristic?.uuid}:${descriptor?.value?.toHexString()}" }
            descriptor?.let {
                replyRelay.accept(ReplyData(Type.WRITE_DESCRIPTOR, it.characteristic.uuid, it.value ?: byteArrayOf()))
            }
        }

        override fun onPhyUpdate(gatt: BluetoothGatt?, txPhy: Int, rxPhy: Int, status: Int) {
            super.onPhyUpdate(gatt, txPhy, rxPhy, status)
            logging.d { "${gatt?.device?.name}(${gatt?.device?.address}) : onPhyUpdate:$txPhy:$rxPhy:$status" }
        }

        override fun onPhyRead(gatt: BluetoothGatt?, txPhy: Int, rxPhy: Int, status: Int) {
            super.onPhyRead(gatt, txPhy, rxPhy, status)
            logging.d { "${gatt?.device?.name}(${gatt?.device?.address}) : onPhyRead:$txPhy:$rxPhy:$status" }
        }

        override fun onReliableWriteCompleted(gatt: BluetoothGatt?, status: Int) {
            super.onReliableWriteCompleted(gatt, status)
            logging.d { "${gatt?.device?.name}(${gatt?.device?.address}) : onReliableWriteCompleted:$status" }
        }

        override fun onReadRemoteRssi(gatt: BluetoothGatt?, rssi: Int, status: Int) {
            super.onReadRemoteRssi(gatt, rssi, status)
            logging.d { "${gatt?.device?.name}(${gatt?.device?.address}) : onReadRemoteRssi:$rssi, $status" }
        }

        override fun onMtuChanged(gatt: BluetoothGatt?, mtu: Int, status: Int) {
            super.onMtuChanged(gatt, mtu, status)
            logging.d { "${gatt?.device?.name}(${gatt?.device?.address}) : onMtuChanged:$mtu, $status" }
        }
    }

    private val stateRelay = BehaviorRelay.create<State>().apply {
        accept(State.DISCONNECTED())
    }
    private val gattObservable: Observable<BluetoothGatt>
        get() = stateRelay.map {
            if (it is State.CONNECTED) {
                it.gatt
            } else {
                throw BleManager.DisconnectedException()
            }
        }
    private var serviceSingle: Single<List<BluetoothGattService>>? = null
    private val replyRelay = PublishRelay.create<ReplyData>()
    private val notificationRelay = PublishRelay.create<Pair<UUID, ByteArray>>()

    override val deviceId: String
        get() = bluetoothDevice.address
    override val deviceName: String?
        get() = bluetoothDevice.name
    override val bonded: Boolean
        get() = bluetoothDevice.bondState == BluetoothDevice.BOND_BONDED

    override fun bondCompletable(): Completable {
        return Completable.defer {
            if (bluetoothDevice.bondState == BluetoothDevice.BOND_BONDED) {
                Completable.complete()
            } else {
                Observable.interval(1, TimeUnit.SECONDS)
                    .doOnSubscribe {
                        if (bluetoothDevice.bondState == BluetoothDevice.BOND_NONE &&
                            !bluetoothDevice.createBond()) {
                            throw BleManager.BondFailedException()
                        }
                    }
                    .map {
                        when (bluetoothDevice.bondState) {
                            BluetoothDevice.BOND_BONDED -> true
                            BluetoothDevice.BOND_BONDING -> false
                            BluetoothDevice.BOND_NONE -> throw BleManager.BondFailedException()
                            else -> throw IllegalStateException("bond state is wrong:${bluetoothDevice.bondState}")
                        }
                    }
                    .filter { it }
                    .firstOrError()
                    .ignoreElement()
            }
        }
    }

    override fun unbondCompletable(): Completable {
        return Completable.defer {
            if (bluetoothDevice.bondState == BluetoothDevice.BOND_NONE) {
                Completable.complete()
            } else {
                Observable.interval(1, TimeUnit.SECONDS)
                    .doOnSubscribe {
                        if (bluetoothDevice.bondState == BluetoothDevice.BOND_BONDED &&
                            !bluetoothDevice.deleteBond()) {
                            throw BleManager.BondFailedException()
                        }
                    }
                    .map {
                        when (bluetoothDevice.bondState) {
                            BluetoothDevice.BOND_BONDED -> throw BleManager.BondFailedException()
                            BluetoothDevice.BOND_BONDING -> false
                            BluetoothDevice.BOND_NONE -> true
                            else -> throw IllegalStateException("bond state is wrong:${bluetoothDevice.bondState}")
                        }
                    }
                    .filter { it }
                    .firstOrError()
                    .ignoreElement()
            }
        }
    }

    override fun getConnectionStateObservable(): Observable<BleDevice.ConnectionState> {
        return stateRelay.map { it.connectionState }
    }

    override fun connectCompletable(timeoutMillis: Long): Completable = checkConnectionState(BleDevice.ConnectionState.DISCONNECTED)
        .andThen(Completable.defer {
            var gatt: BluetoothGatt? = null
            stateRelay
                .enumerated()
                .doOnNext {
                    if (it.second is State.DISCONNECTED) {
                        if (it.first == 0) {
                            logging.d { "${gatt?.device?.name}(${gatt?.device?.address}) : connectGatt call" }
                            gatt = bluetoothDevice.connectGatt(applicationContext, false, gattCallback)
                            stateRelay.accept(State.CONNECTING())
                        } else {
                            throw BleManager.DisconnectedException()
                        }
                    }
                }
                .filter { it.second is State.CONNECTED }
                .firstOrError()
                .ignoreElement()
                .timeout(timeoutMillis, TimeUnit.MILLISECONDS)
                .doOnError {
                    logging.e({ "${gatt?.device?.name}(${gatt?.device?.address}) : doOnError" }, it)
                    if (it is TimeoutException) {
                        gatt?.also {
                            it.disconnect()
                            it.close()
                            stateRelay.accept(State.DISCONNECTED())
                            bleDevice.onDisconnected()
                        }
                    }
                }
        })

    private fun checkConnectionState(connectionState: BleDevice.ConnectionState): Completable {
        return stateRelay.doOnNext {
            if (it.connectionState != connectionState) {
                throw IllegalStateException("Connection state is $it not $connectionState")
            }
        }.firstOrError().ignoreElement()
    }

    override fun disconnect() {
        try {
            checkConnectionState(BleDevice.ConnectionState.CONNECTED)
                .andThen(gattObservable)
                .blockingFirst()
                .disconnect()
        } catch (exception: Exception) {
            Log.e(LOG_TAG, "Device Disconnect Error", exception)
        }
    }

    override fun getCharacteristicsSingle(): Single<List<BleCharacterisic>> {
        return getServices()
            .map { services ->
                services.map { it.characteristics }.flatten()
            }
            .map {
                it.map { BleCharacteristicImpl(it) }
            }
    }

    override fun readCharacteristicSingle(characteristicUuid: UUID): Single<ByteArray> {
        return getGattSingle()
            .flatMap { gatt ->
                getCharacteristic(characteristicUuid)
                    .doOnSuccess {
                        if (it.properties and BluetoothGattCharacteristic.PROPERTY_READ == 0) {
                            throw IllegalCharacteristicProperty(it.properties, "READ_CHARACTERISTIC")
                        }
                    }
                    .flatMap { characteristic ->
                        replyRelay
                            .mergeWith(Completable.fromCallable {
                                logging.d { "${gatt.device?.name}(${gatt.device?.address}) : readCharacteristic : $characteristicUuid" }
                                if (!gatt.readCharacteristic(characteristic)) {
                                    throw CommunicationFailedException()
                                }
                            })
                            .filter { it.uuid == characteristicUuid && it.type == Type.READ_CHARACTERISTIC }
                            .map { it.byteArray }
                            .firstOrError()
                    }
                    .retryWhen {
                        it.take(5).flatMap { throwable ->
                            if (throwable is CommunicationFailedException) {
                                Flowable.timer(500, TimeUnit.MILLISECONDS)
                            } else {
                                Flowable.error(throwable)
                            }
                        }
                    }
            }
    }

    override fun writeCharacteristicSingle(characteristicUuid: UUID, byteArray: ByteArray): Single<ByteArray> {
        return getGattSingle()
            .flatMap { gatt ->
                getCharacteristic(characteristicUuid)
                    .flatMap { characteristic ->
                        when {
                            characteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0 -> {
                                replyRelay
                                    .mergeWith(Completable.fromCallable {
                                        characteristic.value = byteArray
                                        if (!gatt.writeCharacteristic(characteristic)) {
                                            throw CommunicationFailedException()
                                        }
                                    })
                                    .filter { it.uuid == characteristicUuid && it.type == Type.WRITE_CHARACTERISTIC }
                                    .map { it.byteArray }
                                    .firstOrError()
                            }
                            characteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0 -> {
                                Single.defer {
                                    characteristic.value = byteArray
                                    logging.d { "${gatt.device?.name}(${gatt.device?.address}) : writeCharacteristic : $characteristicUuid : ${byteArray.toHexString()}" }
                                    if (gatt.writeCharacteristic(characteristic)) {
                                        Single.just(byteArrayOf())
                                    } else {
                                        Single.error(CommunicationFailedException())
                                    }
                                }
                            }
                            else -> {
                                Single.error(IllegalCharacteristicProperty(characteristic.properties, "WRITE_CHARACTERISTIC"))
                            }
                        }
                    }
                    .retryWhen {
                        it.take(5).flatMap { throwable ->
                            if (throwable is CommunicationFailedException) {
                                Flowable.timer(500, TimeUnit.MILLISECONDS)
                            } else {
                                Flowable.error(throwable)
                            }
                        }
                    }
            }
    }

    override fun setupNotification(type: BleDevice.NotificationType, characteristicUuid: UUID): Observable<Observable<ByteArray>> {
        var characteristic: BluetoothGattCharacteristic? = null
        return gattObservable
            .flatMap { gatt ->
                getCharacteristic(characteristicUuid)
                    .flatMap {
                        characteristic = it
                        logging.d { "${gatt.device?.name}(${gatt.device?.address}) : setCharacteristicNotification : $characteristicUuid : true" }
                        gatt.setCharacteristicNotification(it, true)
                        writeDescriptor(gatt, it, type.byteArray)
                    }
                    .toObservable()
                    .concatWith(Observable.never())
                    .map {
                        notificationRelay
                            .filter { it.first == characteristicUuid }
                            .map { it.second }
                    }
                    .doFinally {
                        characteristic?.let { gattCharacteristic ->
                            logging.d { "${gatt.device?.name}(${gatt.device?.address}) : setCharacteristicNotification : $characteristicUuid : false" }
                            gatt.setCharacteristicNotification(gattCharacteristic, false)
                            writeDescriptor(gatt, gattCharacteristic, BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE).subscribe({
                                Log.d(LOG_TAG, "DISABLE_NOTIFICATION_VALUE:${it.contentToString()}")
                            }, {
                                Log.e(LOG_TAG, "DISABLE_NOTIFICATION_VALUE error", it)
                            })
                        }
                    }
            }
    }

    private fun getGattSingle() = gattObservable
        .firstOrError()

    private fun getServices(): Single<List<BluetoothGattService>> {
        return checkConnectionState(BleDevice.ConnectionState.CONNECTED)
            .andThen(Single.defer {
                serviceSingle
                    ?: getGattSingle()
                        .flatMap { gatt ->
                            Single.fromCallable(gatt::getServices).cache().also {
                                serviceSingle = it
                            }
                        }
            })
    }

    private fun getCharacteristic(characteristicUuid: UUID): Single<BluetoothGattCharacteristic> {
        return getServices()
            .flatMap { services ->
                return@flatMap Single.fromCallable {
                    for (service in services) {
                        val characteristic = service.getCharacteristic(characteristicUuid)
                        if (characteristic != null) {
                            return@fromCallable characteristic
                        }
                    }
                    throw IllegalStateException()
                }
            }
            .subscribeOn(Schedulers.io())
    }

    private fun writeDescriptor(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, byteArray: ByteArray): Single<ByteArray> {
        return Single.defer {
            if (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY == 0 &&
                characteristic.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE == 0) {
                Single.error(IllegalCharacteristicProperty(characteristic.properties, "WRITE_DESCRIPTOR"))
            } else {
                replyRelay
                    .doOnSubscribe {
                        val descriptor = characteristic.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG_UUID)
                        descriptor.value = byteArray
                        logging.d { "${gatt.device?.name}(${gatt.device?.address}) : writeDescriptor : ${characteristic.uuid} : ${byteArray.toHexString()}" }
                        if (!gatt.writeDescriptor(descriptor)) {
                            throw CommunicationFailedException()
                        }
                    }
                    .filter { it.uuid == characteristic.uuid && it.type == Type.WRITE_DESCRIPTOR }
                    .map { it.byteArray }
                    .firstOrError()
                    .retryWhen {
                        it.take(5).flatMap { throwable ->
                            if (throwable is CommunicationFailedException) {
                                Flowable.timer(500, TimeUnit.MILLISECONDS)
                            } else {
                                Flowable.error(throwable)
                            }
                        }
                    }
            }
        }
    }

    override fun connectSppSocket(uuid: UUID?): Observable<BleSppSocket> {
        var socket: BleSppSocket? = null
        return checkConnectionState(BleDevice.ConnectionState.DISCONNECTED)
            .andThen(Observable.create<BleSppSocket> {
                stateRelay.accept(State.SPP_CONNECTING())
                val aUuid = uuid ?: bluetoothDevice.uuids.first().uuid
                BleSppSocket(bluetoothDevice.createRfcommSocketToServiceRecord(aUuid)).also { theSocket ->
                    socket = theSocket
                    stateRelay.accept(State.SPP_CONNECTED())
                    it.onNext(theSocket)
                }
            })
            .doFinally {
                stateRelay.accept(State.DISCONNECTED())
                socket?.close()
            }
            .subscribeOn(Schedulers.io())
    }

    internal fun onBluetoothDisabled() {
        getGattSingle()
            .subscribe({
                stateRelay.accept(State.DISCONNECTED())
                bleDevice.onDisconnected()
                it.close()
            }, {
                Log.d(LOG_TAG, "onBluetoothDisabled error:$deviceId", it)
            })
    }

    companion object {
        private val LOG_TAG = BleDeviceEngineImpl::class.java.simpleName

        private val CLIENT_CHARACTERISTIC_CONFIG_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}

fun ByteArray.toHexString(): String {
    return joinToString(separator = "") { String.format("%02x", it) }
}

fun BluetoothDevice.deleteBond(): Boolean {
    return javaClass.getMethod("removeBond").invoke(this) as Boolean
}
