package com.algorigo.algorigoble2.impl

import android.bluetooth.*
import android.content.Context
import android.util.Log
import com.algorigo.algorigoble2.BleDevice
import com.algorigo.algorigoble2.BleDeviceEngine
import com.algorigo.algorigoble2.BleManager
import com.algorigo.algorigoble2.BleSppSocket
import com.jakewharton.rxrelay3.BehaviorRelay
import com.jakewharton.rxrelay3.PublishRelay
import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.core.Flowable
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.schedulers.Schedulers
import java.util.UUID
import java.util.concurrent.TimeUnit

class BleDeviceEngineImpl(private val context: Context, private val bluetoothDevice: BluetoothDevice):
    BleDeviceEngine() {

    class CommunicationFailedException: Exception()
    class IllegalCharacteristicProperty(val property: Int, val type: Type): Exception()

    enum class Type {
        READ_CHARACTERISTIC,
        WRITE_CHARACTERISTIC,
        READ_DESCRIPTOR,
        WRITE_DESCRIPTOR,
    }

    data class ReplyData(val type: Type, val uuid: UUID, val byteArray: ByteArray) {
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
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    gatt?.discoverServices()
                }

                BluetoothProfile.STATE_DISCONNECTED -> {
                    gattRelay = BehaviorRelay.create()
                    connectionStateRelay.accept(BleDevice.ConnectionState.DISCONNECTED)
                    bleDevice.onDisconnected()
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
            super.onServicesDiscovered(gatt, status)
            gatt?.let {
                gattRelay.accept(it)
            }
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt?,
            characteristic: BluetoothGattCharacteristic?,
            status: Int
        ) {
            super.onCharacteristicRead(gatt, characteristic, status)
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
            characteristic?.let {
                replyRelay.accept(ReplyData(Type.WRITE_CHARACTERISTIC, it.uuid, it.value ?: byteArrayOf()))
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt?,
            characteristic: BluetoothGattCharacteristic?
        ) {
            super.onCharacteristicChanged(gatt, characteristic)
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
            descriptor?.let {
                replyRelay.accept(ReplyData(Type.WRITE_DESCRIPTOR, it.characteristic.uuid, it.value ?: byteArrayOf()))
            }
        }

        override fun onPhyUpdate(gatt: BluetoothGatt?, txPhy: Int, rxPhy: Int, status: Int) {
            super.onPhyUpdate(gatt, txPhy, rxPhy, status)
        }

        override fun onPhyRead(gatt: BluetoothGatt?, txPhy: Int, rxPhy: Int, status: Int) {
            super.onPhyRead(gatt, txPhy, rxPhy, status)
        }

        override fun onReliableWriteCompleted(gatt: BluetoothGatt?, status: Int) {
            super.onReliableWriteCompleted(gatt, status)
        }

        override fun onReadRemoteRssi(gatt: BluetoothGatt?, rssi: Int, status: Int) {
            super.onReadRemoteRssi(gatt, rssi, status)
        }

        override fun onMtuChanged(gatt: BluetoothGatt?, mtu: Int, status: Int) {
            super.onMtuChanged(gatt, mtu, status)
        }
    }

    private var gattRelay = BehaviorRelay.create<BluetoothGatt>()
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

    override fun connectCompletable(): Completable = gattRelay
        .doOnSubscribe {
            val gatt = bluetoothDevice.connectGatt(context, false, gattCallback)
            connectionStateRelay.accept(BleDevice.ConnectionState.CONNECTING)
        }
        .firstOrError()
        .ignoreElement()
        .doOnComplete {
            connectionStateRelay.accept(BleDevice.ConnectionState.CONNECTED)
        }
        .doOnError {
            connectionStateRelay.accept(BleDevice.ConnectionState.DISCONNECTED)
        }

    override fun disconnect() {
        gattRelay.blockingFirst().disconnect()
    }

    override fun getCharacteristicsSingle(): Single<List<BluetoothGattCharacteristic>> {
        return getServices()
            .map { services ->
                services.map { it.characteristics }.flatten()
            }
    }

    override fun readCharacteristicSingle(characteristicUuid: UUID): Single<ByteArray> {
        return getGattSingle()
            .flatMap { gatt ->
                getCharacteristic(characteristicUuid)
                    .doOnSuccess {
                        if (it.properties and BluetoothGattCharacteristic.PROPERTY_READ == 0) {
                            throw IllegalCharacteristicProperty(it.properties, Type.READ_CHARACTERISTIC)
                        }
                    }
                    .flatMap { characteristic ->
                        replyRelay
                            .doOnSubscribe {
                                if (!gatt.readCharacteristic(characteristic)) {
                                    throw CommunicationFailedException()
                                }
                            }
                            .filter { it.uuid == characteristicUuid && it.type == Type.READ_CHARACTERISTIC }
                            .map { it.byteArray }
                            .firstOrError()
                    }
                    .retryWhen {
                        it.take(5).flatMap { throwable ->
                            if (throwable is CommunicationFailedException) {
                                Flowable.timer(100, TimeUnit.MILLISECONDS)
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
                                    .doOnSubscribe {
                                        characteristic.value = byteArray
                                        if (!gatt.writeCharacteristic(characteristic)) {
                                            throw CommunicationFailedException()
                                        }
                                    }
                                    .filter { it.uuid == characteristicUuid && it.type == Type.WRITE_CHARACTERISTIC }
                                    .map { it.byteArray }
                                    .firstOrError()
                            }
                            characteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0 -> {
                                Single.defer {
                                    characteristic.value = byteArray
                                    if (gatt.writeCharacteristic(characteristic)) {
                                        Single.just(byteArrayOf())
                                    } else {
                                        Single.error(CommunicationFailedException())
                                    }
                                }
                            }
                            else -> {
                                Single.error(IllegalCharacteristicProperty(characteristic.properties, Type.WRITE_CHARACTERISTIC))
                            }
                        }
                    }
                    .retryWhen {
                        it.take(5).flatMap { throwable ->
                            if (throwable is CommunicationFailedException) {
                                Flowable.timer(100, TimeUnit.MILLISECONDS)
                            } else {
                                Flowable.error(throwable)
                            }
                        }
                    }
            }
    }

    override fun setupNotification(characteristicUuid: UUID, byteArray: ByteArray): Observable<Observable<ByteArray>> {
        var characteristic: BluetoothGattCharacteristic? = null
        return getGattSingle()
            .flatMapObservable { gatt ->
                getCharacteristic(characteristicUuid)
                    .flatMap {
                        characteristic = it
                        gatt.setCharacteristicNotification(it, true)
                        writeDescriptor(gatt, it, byteArray)
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

    private fun getGattSingle() = gattRelay
        .firstOrError()

    private fun getServices(): Single<List<BluetoothGattService>> {
        return Single.defer {
            if (serviceSingle != null) {
                serviceSingle
            } else {
                getGattSingle()
                    .flatMap { gatt ->
                        Single.fromCallable(gatt::getServices).cache().also {
                            serviceSingle = it
                        }
                    }
            }
        }
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
                Single.error(IllegalCharacteristicProperty(characteristic.properties, Type.WRITE_DESCRIPTOR))
            } else {
                replyRelay
                    .doOnSubscribe {
                        val descriptor = characteristic.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG_UUID)
                        descriptor.value = byteArray
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
                                Flowable.timer(100, TimeUnit.MILLISECONDS)
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
        return Observable.create<BleSppSocket> {
            connectionStateRelay.accept(BleDevice.ConnectionState.CONNECTING)
            val aUuid = uuid ?: bluetoothDevice.uuids.first().uuid
            BleSppSocket(bluetoothDevice.createRfcommSocketToServiceRecord(aUuid)).also { theSocket ->
                socket = theSocket
                connectionStateRelay.accept(BleDevice.ConnectionState.CONNECTED)
                it.onNext(theSocket)
            }
        }
            .doFinally {
                connectionStateRelay.accept(BleDevice.ConnectionState.DISCONNECTED)
                socket?.close()
            }
            .subscribeOn(Schedulers.io())
    }

    companion object {
        private val LOG_TAG = BleDeviceEngineImpl::class.java.simpleName

        private val CLIENT_CHARACTERISTIC_CONFIG_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}