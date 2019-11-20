package com.algorigo.algorigoble.impl

import android.bluetooth.*
import android.content.Context
import android.util.Log
import com.algorigo.algorigoble.BleDevice
import com.algorigo.algorigoble.BleDeviceEngine
import com.algorigo.algorigoble.RetryWithDelay
import com.jakewharton.rxrelay2.PublishRelay
import io.reactivex.Completable
import io.reactivex.Observable
import io.reactivex.Single
import io.reactivex.schedulers.Schedulers
import io.reactivex.subjects.BehaviorSubject
import io.reactivex.subjects.PublishSubject
import io.reactivex.subjects.Subject
import java.util.*
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

class BleDeviceEngineImpl : BleDeviceEngine {

    class CommunicationError : RuntimeException("Android Gatt Process Failed")
    class DisconnectError : RuntimeException("Bluetooth is disconnected")

    sealed class PushData {
        data class ReadCharacteristicData(val engine: BleDeviceEngineImpl, val subject: Subject<ByteArray>, val characteristicUuid: UUID) : PushData()
        data class WriteCharacteristicData(val engine: BleDeviceEngineImpl, val subject: Subject<ByteArray>, val characteristicUuid: UUID, val value: ByteArray) : PushData()
        data class WriteDescripterData(val engine: BleDeviceEngineImpl, val subject: Subject<ByteArray>, val characteristicUuid: UUID, val value: ByteArray) : PushData()
    }

    private lateinit var context: Context
    private lateinit var device: BluetoothDevice
    private var status: BleDevice.ConnectionState = BleDevice.ConnectionState.DISCONNECTED
        set(value) {
            field = value
            connectionStateRelay.accept(value)
        }

    private var gatt: BluetoothGatt? = null
    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
            super.onConnectionStateChange(gatt, status, newState)
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    gatt?.discoverServices()
                }

                BluetoothProfile.STATE_DISCONNECTED -> {
                    serviceSingle = null
                    this@BleDeviceEngineImpl.status = BleDevice.ConnectionState.DISCONNECTED
                    onDisconnected()
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
            super.onServicesDiscovered(gatt, status)
            this@BleDeviceEngineImpl.gatt = gatt
            this@BleDeviceEngineImpl.status = BleDevice.ConnectionState.CONNECTED
            if (connectionSubject?.hasComplete() == false) {
                connectionSubject?.onComplete()
            } else {
                onReconnected()
            }
        }

        override fun onCharacteristicRead(gatt: BluetoothGatt?, characteristic: BluetoothGattCharacteristic?, status: Int) {
            super.onCharacteristicRead(gatt, characteristic, status)
            characteristic?.let {
                characteristicMap[it.uuid]?.also { subject ->
                    characteristicMap.remove(it.uuid)
                    subject.onNext(it.value)
                }
            }
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt?, characteristic: BluetoothGattCharacteristic?, status: Int) {
            super.onCharacteristicWrite(gatt, characteristic, status)
            characteristic?.let {
                characteristicMap[it.uuid]?.also { subject ->
                    characteristicMap.remove(it.uuid)
                    subject.onNext(it.value)
                }
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt?, characteristic: BluetoothGattCharacteristic?) {
            super.onCharacteristicChanged(gatt, characteristic)
            characteristic?.let {
                notificationMap[characteristic.uuid]?.also { subject ->
                    subject.onNext(it.value)
                }
            }
        }

        override fun onDescriptorRead(gatt: BluetoothGatt?, descriptor: BluetoothGattDescriptor?, status: Int) {
            super.onDescriptorRead(gatt, descriptor, status)
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt?, descriptor: BluetoothGattDescriptor?, status: Int) {
            super.onDescriptorWrite(gatt, descriptor, status)
            descriptor?.let {
                characteristicMap[it.characteristic.uuid]?.also { subject ->
                    characteristicMap.remove(it.characteristic.uuid)
                    subject.onNext(it.value)
                }
            }
        }
    }

    private var connectionSubject: Subject<Int>? = null
    private val connectionStateRelay = PublishRelay.create<BleDevice.ConnectionState>().toSerialized()
    private var serviceSingle: Single<List<BluetoothGattService>>? = null
    private val characteristicMap = mutableMapOf<UUID, Subject<ByteArray>>()
    private val notificationObservableMap = mutableMapOf<UUID, Pair<Observable<Observable<ByteArray>>, Subject<Observable<ByteArray>>>>()
    private val notificationMap = mutableMapOf<UUID, Subject<ByteArray>>()
    private val indicationObservableMap = mutableMapOf<UUID, Pair<Observable<Observable<ByteArray>>, Subject<Observable<ByteArray>>>>()
    private val indicationMap = mutableMapOf<UUID, Subject<ByteArray>>()

    override val name: String?
        get() = device.name

    override val macAddress: String
        get() = device.address

    override val bluetoothDevice: BluetoothDevice
        get() = device

    override val connectionState: BleDevice.ConnectionState
        get() = status

    override var bleDeviceCallback: BleDeviceEngine.BleDeviceCallback? = null

    internal fun init(context: Context, device: BluetoothDevice) {
        this.context = context
        this.device = device
    }

    override fun connectCompletableImpl(autoConnect: Boolean): Completable {
        if (connectionState != BleDevice.ConnectionState.DISCONNECTED) {
            return Completable.error(IllegalStateException("Connection status is $status"))
        }

        connectionSubject = PublishSubject.create<Int>().toSerialized()
        return connectionSubject!!.doOnSubscribe {
            gatt?.disconnect()
            bluetoothDevice.connectGatt(context, autoConnect, gattCallback)
            status = BleDevice.ConnectionState.CONNECTING
        }.doOnComplete {
            connectionSubject = null
        }.ignoreElements()
    }

    override fun connectCompletableImpl(autoConnect: Boolean, milliSec: Long): Completable {
        if (connectionState != BleDevice.ConnectionState.DISCONNECTED) {
            return Completable.error(IllegalStateException("Connection status is $status"))
        }

        connectionSubject = PublishSubject.create<Int>().toSerialized()
        return connectionSubject!!.doOnSubscribe {
            gatt?.disconnect()
            bluetoothDevice.connectGatt(context, autoConnect, gattCallback)
            status = BleDevice.ConnectionState.CONNECTING
        }.doOnComplete {
            connectionSubject = null
        }.timeout(milliSec, TimeUnit.MILLISECONDS).ignoreElements()
    }

    override fun disconnect() {
        status = BleDevice.ConnectionState.DISCONNECTED
        gatt?.disconnect()
    }

    override fun onReconnected() {
        bleDeviceCallback?.onDeviceReconnected()
    }

    override fun onDisconnected() {
        for (pair in notificationObservableMap.values) {
            pair.second.onError(DisconnectError())
        }
        for (pair in indicationObservableMap.values) {
            pair.second.onError(DisconnectError())
        }
        bleDeviceCallback?.onDeviceDisconnected()
    }

    override fun getConnectionStateObservable(): Observable<BleDevice.ConnectionState> {
        return connectionStateRelay
    }

    override fun readCharacteristic(characteristicUuid: UUID): Single<ByteArray>? {
        val subject = BehaviorSubject.create<ByteArray>().toSerialized()
        return subject
            .doOnSubscribe {
                pushQueue.push(PushData.ReadCharacteristicData(this, subject, characteristicUuid))
                pushStart()
            }
            .doFinally {
                doPush()
            }
            .firstOrError()
    }

    override fun writeCharacteristic(characteristicUuid: UUID, value: ByteArray): Single<ByteArray>? {
        val subject = BehaviorSubject.create<ByteArray>().toSerialized()
        return subject
            .doOnSubscribe {
                pushQueue.push(PushData.WriteCharacteristicData(this, subject, characteristicUuid, value))
                pushStart()
            }
            .doFinally {
                doPush()
            }
            .firstOrError()
    }

    override fun writeLongValue(characteristicUuid: UUID, value: ByteArray): Observable<ByteArray>? {
        throw IllegalAccessException("not support yet")
    }

    override fun setupNotification(characteristicUuid: UUID): Observable<Observable<ByteArray>>? {
        if (!notificationObservableMap.containsKey(characteristicUuid)) {
            var isFirst = true
            BehaviorSubject.create<Observable<ByteArray>>().also { subject ->
                notificationObservableMap[characteristicUuid] = Pair(
                    subject.doOnSubscribe {
                        if (isFirst) {
                            isFirst = false
                            processNotificationEnableData(characteristicUuid, subject)
                        }
                    }.doFinally {
                        if (!subject.hasObservers()) {
                            disableNotification(characteristicUuid)
                        }
                    }, subject)
            }
        }
        return notificationObservableMap[characteristicUuid]!!.first
    }

    override fun setupIndication(characteristicUuid: UUID): Observable<Observable<ByteArray>>? {
        if (!indicationObservableMap.containsKey(characteristicUuid)) {
            var isFirst = true
            BehaviorSubject.create<Observable<ByteArray>>().also { subject ->
                indicationObservableMap[characteristicUuid] = Pair(
                    subject.doOnSubscribe {
                        if (isFirst) {
                            isFirst = false
                            processIndicationEnableData(characteristicUuid, subject)
                        }
                    }.doFinally {
                        if (!subject.hasObservers()) {
                            disableIndication(characteristicUuid)
                        }
                    },
                    subject)
            }
        }
        return indicationObservableMap[characteristicUuid]!!.first
    }

    private fun disableNotification(characteristicUuid: UUID) {
        notificationObservableMap.remove(characteristicUuid)
        notificationMap.remove(characteristicUuid)
        processNotificationDisableData(characteristicUuid)
    }

    private fun disableIndication(characteristicUuid: UUID) {
        indicationObservableMap.remove(characteristicUuid)
        indicationMap.remove(characteristicUuid)
        processNotificationDisableData(characteristicUuid)
    }

    private fun writeDescriptor(characteristicUuid: UUID, value: ByteArray): Single<ByteArray>? {
        val subject = BehaviorSubject.create<ByteArray>().toSerialized()
        return subject
            .doOnSubscribe {
                pushQueue.push(PushData.WriteDescripterData(this, subject, characteristicUuid, value))
                pushStart()
            }
            .doFinally {
                doPush()
            }
            .firstOrError()
    }

    private fun processReadCharacteristicData(pushData: PushData.ReadCharacteristicData) {
        if (connectionState != BleDevice.ConnectionState.CONNECTED) {
            pushData.subject.onError(DisconnectError())
            return
        }

        getCharacteristic(pushData.characteristicUuid)
            .flatMap { characteristic ->
                val subject = BehaviorSubject.create<ByteArray>()
                characteristicMap.put(pushData.characteristicUuid, subject)
                Completable.create {
                    if (readCharacteristicInner(characteristic)) {
                        it.onComplete()
                    } else {
                        it.onError(CommunicationError())
                    }
                }
                    .andThen(subject.firstOrError())
                    .timeout(TIMEOUT_VALUE, TIMEOUT_UNIT)
                    .retryWhen(RetryWithDelay(RETRY_COUNT, RETRY_INTERVAL, TimeoutException::class, CommunicationError::class))
                    .doOnError {
                        characteristicMap.remove(pushData.characteristicUuid)
                    }
            }
            .subscribe({
                pushData.subject.onNext(it)
            }, {
                Log.e(TAG, "", Exception(it))
                pushData.subject.onError(Exception(it))
            })
    }

    private fun processWriteCharacteristicData(pushData: PushData.WriteCharacteristicData) {
        if (connectionState != BleDevice.ConnectionState.CONNECTED) {
            pushData.subject.onError(DisconnectError())
            return
        }

        getCharacteristic(pushData.characteristicUuid)
            .flatMap { characteristic ->
                val subject = BehaviorSubject.create<ByteArray>()
                characteristicMap.put(pushData.characteristicUuid, subject)
                Completable.create {
                    if (writeCharacteristicInner(characteristic, pushData.value)) {
                        it.onComplete()
                    } else {
                        it.onError(CommunicationError())
                    }
                }
                    .andThen(subject.firstOrError())
                    .timeout(TIMEOUT_VALUE, TIMEOUT_UNIT)
                    .retryWhen(RetryWithDelay(RETRY_COUNT, RETRY_INTERVAL, TimeoutException::class, CommunicationError::class))
                    .doOnError {
                        characteristicMap.remove(pushData.characteristicUuid)
                    }
            }
            .subscribe({
                pushData.subject.onNext(it)
            }, {
                Log.e(TAG, "", Exception(it))
                pushData.subject.onError(Exception(it))
            })
    }

    private fun processNotificationEnableData(characteristicUuid: UUID, subject: Subject<Observable<ByteArray>>) {
        if (connectionState != BleDevice.ConnectionState.CONNECTED) {
            subject.onError(DisconnectError())
            return
        }

        checkNotificationAvailable(characteristicUuid)
            .andThen(getCharacteristic(characteristicUuid))
            .flatMap { characteristic ->
                Completable.create {
                    if (setCharacteristicNotificationInner(characteristic, true)) {
                        it.onComplete()
                    } else {
                        it.onError(CommunicationError())
                    }
                }
                    .andThen(writeDescriptor(characteristicUuid, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE))
            }
            .subscribe({
                val dataSubject = PublishSubject.create<ByteArray>().toSerialized()
                notificationMap.put(characteristicUuid, dataSubject)
                subject.onNext(dataSubject)
            }, {
                Log.e(TAG, "", Exception(it))
                subject.onError(Exception(it))
            })
    }

    private fun processNotificationDisableData(characteristicUuid: UUID) {
        getCharacteristic(characteristicUuid)
            .flatMap { characteristic ->
                Completable.create {
                    if (setCharacteristicNotificationInner(characteristic, false)) {
                        it.onComplete()
                    } else {
                        it.onError(CommunicationError())
                    }
                }
                    .andThen(writeDescriptor(characteristicUuid, BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE))
            }
            .subscribe({
            }, {
                Log.e(TAG, "", Exception(it))
            })
    }

    private fun processIndicationEnableData(characteristicUuid: UUID, subject: Subject<Observable<ByteArray>>) {
        if (connectionState != BleDevice.ConnectionState.CONNECTED) {
            subject.onError(DisconnectError())
            return
        }

        checkIndicationAvailable(characteristicUuid)
            .andThen(getCharacteristic(characteristicUuid))
            .flatMap { characteristic ->
                Completable.create {
                    if (setCharacteristicNotificationInner(characteristic, true)) {
                        it.onComplete()
                    } else {
                        it.onError(CommunicationError())
                    }
                }
                    .andThen(writeDescriptor(characteristicUuid, BluetoothGattDescriptor.ENABLE_INDICATION_VALUE))
            }
            .subscribe({
                val dataSubject = PublishSubject.create<ByteArray>().toSerialized()
                indicationMap.put(characteristicUuid, dataSubject)
                subject.onNext(dataSubject)
            }, {
                Log.e(TAG, "", Exception(it))
                subject.onError(Exception(it))
            })
    }

    private fun processWriteDescripterData(pushData: PushData.WriteDescripterData) {
        if (connectionState != BleDevice.ConnectionState.CONNECTED) {
            pushData.subject.onError(DisconnectError())
            return
        }

        getCharacteristic(pushData.characteristicUuid)
            .flatMap { characteristic ->
                val subject = BehaviorSubject.create<ByteArray>()
                characteristicMap.put(pushData.characteristicUuid, subject)
                Completable.create {
                    if (writeDescriptorInner(characteristic, pushData.value)) {
                        it.onComplete()
                    } else {
                        it.onError(CommunicationError())
                    }
                }
                    .andThen(subject.firstOrError())
                    .timeout(TIMEOUT_VALUE, TIMEOUT_UNIT)
                    .retryWhen(RetryWithDelay(RETRY_COUNT, RETRY_INTERVAL, TimeoutException::class, CommunicationError::class))
                    .doOnError {
                        characteristicMap.remove(pushData.characteristicUuid)
                    }
            }
            .subscribe({
                pushData.subject.onNext(it)
            }, {
                Log.e(TAG, "", Exception(it))
                pushData.subject.onError(Exception(it))
            })
    }

    private fun checkNotificationAvailable(characteristicUuid: UUID): Completable {
        return Completable.defer {
            Completable.create {
                if (indicationObservableMap.containsKey(characteristicUuid)) {
                    it.onError(IllegalStateException())
                } else {
                    it.onComplete()
                }
            }
        }
    }

    private fun checkIndicationAvailable(characteristicUuid: UUID): Completable {
        return Completable.defer {
            Completable.create {
                if (notificationObservableMap.containsKey(characteristicUuid)) {
                    it.onError(IllegalStateException())
                } else {
                    it.onComplete()
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

    @Synchronized
    private fun getServices(): Single<List<BluetoothGattService>> {
        if (serviceSingle == null) {
            serviceSingle = Single.fromCallable(gatt!!::getServices)
                .cache()
        }
        return serviceSingle!!
    }

    private fun readCharacteristicInner(characteristic: BluetoothGattCharacteristic): Boolean {
        return gatt?.readCharacteristic(characteristic) ?: false
    }

    private fun writeCharacteristicInner(characteristic: BluetoothGattCharacteristic, value: ByteArray): Boolean {
        val charaProp = characteristic.properties
        if (charaProp or BluetoothGattCharacteristic.PROPERTY_WRITE > 0) {
            characteristic.value = value
            return gatt?.writeCharacteristic(characteristic) ?: false
        }
        return false
    }

    private fun setCharacteristicNotificationInner(characteristic: BluetoothGattCharacteristic, enabled: Boolean): Boolean {
        return gatt?.setCharacteristicNotification(characteristic, enabled) ?: false
    }

    private fun writeDescriptorInner(characteristic: BluetoothGattCharacteristic, value: ByteArray): Boolean {
        val descriptor = characteristic.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG_UUID)
        descriptor.value = value
        return gatt?.writeDescriptor(descriptor) ?: false
    }

    companion object {
        private val TAG = BleDeviceEngineImpl::class.java.simpleName

        private const val TIMEOUT_VALUE = 500L
        private val TIMEOUT_UNIT = TimeUnit.MILLISECONDS
        private const val RETRY_COUNT = 5
        private const val RETRY_INTERVAL = 500L
        private val CLIENT_CHARACTERISTIC_CONFIG_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        private val pushQueue = ArrayDeque<PushData>()
        private var pushing = false

        @Synchronized
        private fun pushStart() {
            if (!pushing) {
                doPush()
            }
        }

        private fun doPush() {
            if (pushQueue.size > 0) {
                pushing = true
                val pushData = pushQueue.pop()
                when (pushData) {
                    is PushData.ReadCharacteristicData -> {
                        pushData.engine.processReadCharacteristicData(pushData)
                    }
                    is PushData.WriteCharacteristicData -> {
                        pushData.engine.processWriteCharacteristicData(pushData)
                    }
                    is PushData.WriteDescripterData -> {
                        pushData.engine.processWriteDescripterData(pushData)
                    }
                }
            } else {
                pushing = false
            }
        }

    }
}