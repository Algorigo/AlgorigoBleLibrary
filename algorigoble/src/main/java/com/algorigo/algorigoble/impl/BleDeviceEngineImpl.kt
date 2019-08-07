package com.algorigo.algorigoble.impl

import android.bluetooth.*
import android.content.Context
import android.util.Log
import com.algorigo.algorigoble.BleDevice
import com.algorigo.algorigoble.BleDeviceEngine
import com.jakewharton.rxrelay2.BehaviorRelay
import com.jakewharton.rxrelay2.PublishRelay
import com.jakewharton.rxrelay2.Relay
import io.reactivex.Completable
import io.reactivex.Observable
import io.reactivex.Single
import io.reactivex.schedulers.Schedulers
import io.reactivex.subjects.BehaviorSubject
import io.reactivex.subjects.PublishSubject
import io.reactivex.subjects.Subject
import java.util.*
import java.util.concurrent.TimeUnit

class BleDeviceEngineImpl : BleDeviceEngine {

    sealed class PushData {
        data class ReadCharacteristicData(val subject: Subject<ByteArray>, val characteristicUuid: UUID) : PushData()
        data class WriteCharacteristicData(val subject: Subject<ByteArray>, val characteristicUuid: UUID, val value: ByteArray) : PushData()
        data class NotificationEnableData(val relay: Relay<Observable<ByteArray>>, val characteristicUuid: UUID): PushData()
        data class NotificationDisableData(val characteristicUuid: UUID): PushData()
        data class IndicationEnableData(val relay: Relay<Observable<ByteArray>>, val characteristicUuid: UUID): PushData()
        data class IndicationDisableData(val characteristicUuid: UUID): PushData()
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
                    this@BleDeviceEngineImpl.gatt = null
                    this@BleDeviceEngineImpl.status = BleDevice.ConnectionState.DISCONNECTED
                    onDisconnected()
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
            super.onServicesDiscovered(gatt, status)
            this@BleDeviceEngineImpl.gatt = gatt
            this@BleDeviceEngineImpl.status = BleDevice.ConnectionState.CONNECTED
            connectionSubject?.onComplete()
        }

        override fun onCharacteristicRead(gatt: BluetoothGatt?, characteristic: BluetoothGattCharacteristic?, status: Int) {
            super.onCharacteristicRead(gatt, characteristic, status)
            characteristic?.let {
                characteristicMap.get(it.uuid)?.also { subject ->
                    subject.onNext(it.value)
                    subject.onComplete()
                    characteristicMap.remove(it.uuid)
                }
            }
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt?, characteristic: BluetoothGattCharacteristic?, status: Int) {
            super.onCharacteristicWrite(gatt, characteristic, status)
            characteristic?.let {
                characteristicMap.get(it.uuid)?.also { subject ->
                    subject.onNext(it.value)
                    subject.onComplete()
                    characteristicMap.remove(it.uuid)
                }
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt?, characteristic: BluetoothGattCharacteristic?) {
            super.onCharacteristicChanged(gatt, characteristic)
            characteristic?.let {
                notificationMap.get(characteristic.uuid)?.also { subject ->
                    subject.onNext(it.value)
                }
            }
        }

        override fun onDescriptorRead(gatt: BluetoothGatt?, descriptor: BluetoothGattDescriptor?, status: Int) {
            super.onDescriptorRead(gatt, descriptor, status)
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt?, descriptor: BluetoothGattDescriptor?, status: Int) {
            super.onDescriptorWrite(gatt, descriptor, status)
        }
    }

    private var connectionSubject: Subject<Int>? = null
    private val connectionStateRelay = PublishRelay.create<BleDevice.ConnectionState>().toSerialized()
    private val pushQueue = ArrayDeque<PushData>()
    private var pushing = false
    private val characteristicMap = mutableMapOf<UUID, Subject<ByteArray>>()
    private val notificationMap = mutableMapOf<UUID, Subject<ByteArray>>()
    private val indicationMap = mutableMapOf<UUID, Subject<ByteArray>>()
    private var serviceSingle: Single<List<BluetoothGattService>>? = null

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
            gatt = bluetoothDevice.connectGatt(context, autoConnect, gattCallback)
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
            gatt = bluetoothDevice.connectGatt(context, autoConnect, gattCallback)
            status = BleDevice.ConnectionState.CONNECTING
        }.doOnComplete {
            connectionSubject = null
        }.timeout(milliSec, TimeUnit.MILLISECONDS).ignoreElements()
    }

    override fun disconnect() {
        status = BleDevice.ConnectionState.DISCONNECTING
        gatt?.disconnect()
    }

    override fun onDisconnected() {

    }

    override fun getConnectionStateObservable(): Observable<BleDevice.ConnectionState> {
        return connectionStateRelay
    }

    override fun readCharacteristic(characteristicUuid: UUID): Single<ByteArray>? {
        val subject = BehaviorSubject.create<ByteArray>().toSerialized()
        return subject.doOnSubscribe {
            pushQueue.push(PushData.ReadCharacteristicData(subject, characteristicUuid))
            pushStart()
        }.doOnError {
            Log.e(TAG, "", it)
        }.timeout(TIMEOUT_VALUE, TIMEOUT_UNIT).firstOrError()
    }

    override fun writeCharacteristic(characteristicUuid: UUID, value: ByteArray): Single<ByteArray>? {
        val subject = BehaviorSubject.create<ByteArray>().toSerialized()
        return subject.doOnSubscribe {
            pushQueue.push(PushData.WriteCharacteristicData(subject, characteristicUuid, value))
            pushStart()
        }.doOnError {
            Log.e(TAG, "", it)
        }.timeout(TIMEOUT_VALUE, TIMEOUT_UNIT).firstOrError()
    }

    override fun writeLongValue(characteristicUuid: UUID, value: ByteArray): Observable<ByteArray>? {
        throw IllegalAccessException("not support yet")
    }

    override fun setupNotification(characteristicUuid: UUID): Observable<Observable<ByteArray>>? {
        return if (notificationMap.containsKey(characteristicUuid)) {
            Observable.just(notificationMap.get(characteristicUuid))
        } else {
            var relay = PublishRelay.create<Observable<ByteArray>>().toSerialized()
            relay.doOnSubscribe {
                pushQueue.push(PushData.NotificationEnableData(relay, characteristicUuid))
                pushStart()
            }.doFinally {
                if (!relay.hasObservers()) {
                    disableNotification(characteristicUuid)
                }
            }.doOnError {
                Log.e(TAG, "", it)
            }
        }
    }

    override fun setupIndication(characteristicUuid: UUID): Observable<Observable<ByteArray>>? {
        return if (notificationMap.containsKey(characteristicUuid)) {
            Observable.just(notificationMap.get(characteristicUuid))
        } else {
            var relay = PublishRelay.create<Observable<ByteArray>>().toSerialized()
            relay.doOnSubscribe {
                pushQueue.push(PushData.IndicationEnableData(relay, characteristicUuid))
                pushStart()
            }.doFinally {
                if (!relay.hasObservers()) {
                    disableNotification(characteristicUuid)
                }
            }.doOnError {
                Log.e(TAG, "", it)
            }
        }
    }

    private fun disableNotification(characteristicUuid: UUID) {
        pushQueue.push(PushData.NotificationDisableData(characteristicUuid))
        pushStart()
    }

    private fun disableIndication(characteristicUuid: UUID) {
        pushQueue.push(PushData.IndicationDisableData(characteristicUuid))
        pushStart()
    }

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
                    processReadCharacteristicData(pushData)
                }
                is PushData.WriteCharacteristicData -> {
                    processWriteCharacteristicData(pushData)
                }
                is PushData.NotificationEnableData -> {
                    processNotificationEnableData(pushData)
                }
                is PushData.NotificationDisableData -> {
                    processNotificationDisableData(pushData)
                }
                is PushData.IndicationEnableData -> {
                    processIndicationEnableData(pushData)
                }
                is PushData.IndicationDisableData -> {
                    processIndicationDisableData(pushData)
                }
            }
        } else {
            pushing = false
        }
    }

    private fun processReadCharacteristicData(pushData: PushData.ReadCharacteristicData) {
        checkCharacteristicAvailable(pushData.characteristicUuid)
            .andThen(getCharacteristic(pushData.characteristicUuid))
            .doFinally {
                doPush()
            }
            .subscribe({
                characteristicMap.put(pushData.characteristicUuid, pushData.subject)
                if (!readCharacteristicInner(it)) {
                    pushData.subject.onError(IllegalStateException())
                    characteristicMap.remove(pushData.characteristicUuid)
                }
            }, {
                Log.e(TAG, "", it)
                pushData.subject.onError(it)
            })
    }

    private fun processWriteCharacteristicData(pushData: PushData.WriteCharacteristicData) {
        checkCharacteristicAvailable(pushData.characteristicUuid)
            .andThen(getCharacteristic(pushData.characteristicUuid))
            .doFinally {
                doPush()
            }
            .subscribe({
                characteristicMap.put(pushData.characteristicUuid, pushData.subject)
                it.value = pushData.value
                if (!writeCharacteristicInner(it)) {
                    pushData.subject.onError(IllegalStateException())
                    characteristicMap.remove(pushData.characteristicUuid)
                }
            }, {
                Log.e(TAG, "", it)
                pushData.subject.onError(it)
            })
    }

    private fun processNotificationEnableData(pushData: PushData.NotificationEnableData) {
        checkNotificationAvailable(pushData.characteristicUuid)
            .andThen(getCharacteristic(pushData.characteristicUuid))
            .doFinally {
                doPush()
            }
            .subscribe({
                if (setCharacteristicNotificationInner(it, true) && setDescriptorNotificationInner(it)) {
                    val subject = PublishSubject.create<ByteArray>().toSerialized()
                    notificationMap.put(pushData.characteristicUuid, subject)
                    pushData.relay.accept(subject)
                }
            }, {
                Log.e(TAG, "", it)
            })
    }

    private fun processNotificationDisableData(pushData: PushData.NotificationDisableData) {
        getCharacteristic(pushData.characteristicUuid)
            .doFinally {
                doPush()
            }
            .subscribe({
                setCharacteristicNotificationInner(it, false)
                setDescriptorDisable(it)
                notificationMap.remove(pushData.characteristicUuid)
            }, {
                Log.e(TAG, "", it)
            })
    }

    private fun processIndicationEnableData(pushData: PushData.IndicationEnableData) {
        checkIndicationAvailable(pushData.characteristicUuid)
            .andThen(getCharacteristic(pushData.characteristicUuid))
            .doFinally {
                doPush()
            }
            .subscribe({
                if (setCharacteristicNotificationInner(it, true) && setDescriptorIndicationInner(it)) {
                    val subject = PublishSubject.create<ByteArray>().toSerialized()
                    notificationMap.put(pushData.characteristicUuid, subject)
                    pushData.relay.accept(subject)
                }
            }, {
                Log.e(TAG, "", it)
            })
    }

    private fun processIndicationDisableData(pushData: PushData.IndicationDisableData) {
        getCharacteristic(pushData.characteristicUuid)
            .doFinally {
                doPush()
            }
            .subscribe({
                setCharacteristicNotificationInner(it, false)
                setDescriptorDisable(it)
                notificationMap.remove(pushData.characteristicUuid)
            }, {
                Log.e(TAG, "", it)
            })
    }

    private fun checkCharacteristicAvailable(characteristicUuid: UUID): Completable {
        return Completable.defer {
            Completable.create { emitter ->
                if (characteristicMap.containsKey(characteristicUuid)) {
                    emitter.onError(IllegalStateException())
                } else {
                    emitter.onComplete()
                }
            }
        }
            .retry(5)
            .subscribeOn(Schedulers.io())
    }

    private fun checkNotificationAvailable(characteristicUuid: UUID): Completable {
        return Completable.defer {
            Completable.create { emitter ->
                if (indicationMap.containsKey(characteristicUuid)) {
                    emitter.onError(IllegalStateException())
                } else {
                    emitter.onComplete()
                }
            }
        }
    }

    private fun checkIndicationAvailable(characteristicUuid: UUID): Completable {
        return Completable.defer {
            Completable.create { emitter ->
                if (notificationMap.containsKey(characteristicUuid)) {
                    emitter.onError(IllegalStateException())
                } else {
                    emitter.onComplete()
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
        if (serviceSingle != null) {
            return serviceSingle!!
        } else {
            serviceSingle = Single.fromCallable({
                gatt!!.getServices()
            })
                .cache()
                .also {
                    return it
                }
        }
    }

    private fun readCharacteristicInner(characteristic: BluetoothGattCharacteristic): Boolean {
        return gatt?.readCharacteristic(characteristic) ?: false
    }

    private fun writeCharacteristicInner(characteristic: BluetoothGattCharacteristic): Boolean {
        val charaProp = characteristic.properties
        if (charaProp or BluetoothGattCharacteristic.PROPERTY_WRITE > 0) {
            return gatt?.writeCharacteristic(characteristic) ?: false
        }
        return false
    }

    private fun setCharacteristicNotificationInner(characteristic: BluetoothGattCharacteristic, enabled: Boolean): Boolean {
        return gatt?.setCharacteristicNotification(characteristic, enabled) ?: false
    }

    private fun setDescriptorNotificationInner(characteristic: BluetoothGattCharacteristic): Boolean {
        val descriptor = characteristic.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG_UUID)
        descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        return gatt?.writeDescriptor(descriptor) ?: false
    }

    protected fun setDescriptorIndicationInner(characteristic: BluetoothGattCharacteristic): Boolean {
        val descriptor = characteristic.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG_UUID)
        descriptor.value = BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        return gatt?.writeDescriptor(descriptor) ?: false
    }

    protected fun setDescriptorDisable(characteristic: BluetoothGattCharacteristic): Boolean {
        val descriptor = characteristic.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG_UUID)
        descriptor.value = BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
        return gatt?.writeDescriptor(descriptor) ?: false
    }

    companion object {
        private val TAG = BleDeviceEngineImpl::class.java.simpleName

        private const val TIMEOUT_VALUE = 1000L
        private val TIMEOUT_UNIT = TimeUnit.MILLISECONDS
        private val CLIENT_CHARACTERISTIC_CONFIG_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}