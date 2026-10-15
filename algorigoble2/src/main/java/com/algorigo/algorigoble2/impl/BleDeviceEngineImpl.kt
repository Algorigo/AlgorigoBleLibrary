package com.algorigo.algorigoble2.impl

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothSocket
import android.content.Context
import com.algorigo.algorigoble2.BleCharacterisic
import com.algorigo.algorigoble2.BleDevice
import com.algorigo.algorigoble2.BleDeviceEngine
import com.algorigo.algorigoble2.BleManager
import com.algorigo.algorigoble2.BleSppSocket
import com.algorigo.algorigoble2.logging.Ble
import com.algorigo.logger.L
import com.jakewharton.rxrelay3.BehaviorRelay
import com.jakewharton.rxrelay3.PublishRelay
import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.core.Flowable
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.core.SingleEmitter
import io.reactivex.rxjava3.schedulers.Schedulers
import java.io.IOException
import java.util.*
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

@SuppressLint("MissingPermission")
internal class BleDeviceEngineImpl(private val context: Context, private val bluetoothDevice: BluetoothDevice):
    BleDeviceEngine() {

    class CommunicationFailedException: Exception()
    class IllegalCharacteristicProperty(val property: Int, val type: String): Exception()

    private sealed class State(val connectionState: BleDevice.ConnectionState) {
        // 각 상태는 disconnect() 가 정리할 수 있도록 진행 중인 gatt / 소켓을 담는다
        class CONNECTING(val gatt: BluetoothGatt) : State(BleDevice.ConnectionState.CONNECTING)
        class CONNECTED(val gatt: BluetoothGatt) : State(BleDevice.ConnectionState.CONNECTED)
        // cause: 연결 시도 중에 끊겼을 때 connectCompletable 이 낼 에러. (null 이면 DisconnectedException)
        class DISCONNECTED(val cause: Throwable? = null) : State(BleDevice.ConnectionState.DISCONNECTED)
        class DISCONNECTING(val gatt: BluetoothGatt) : State(BleDevice.ConnectionState.DISCONNECTING)
        class SPP_CONNECTING(val socket: BluetoothSocket) : State(BleDevice.ConnectionState.SPP_CONNECTING)
        class SPP_CONNECTED(val socket: BleSppSocket) : State(BleDevice.ConnectionState.SPP_CONNECTED)
    }

    private enum class Type {
        READ_CHARACTERISTIC,
        WRITE_CHARACTERISTIC,
        READ_DESCRIPTOR,
        WRITE_DESCRIPTOR,
    }

    private data class ReplyData(val type: Type, val uuid: UUID, val byteArray: ByteArray, val status: Int) {
        /** 콜백 status 가 실패이면 값 대신 [BleManager.GattOperationFailedException]을 던진다. */
        fun valueOrThrow(): ByteArray {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                throw BleManager.GattOperationFailedException(type.name, status)
            }
            return byteArray
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is ReplyData) return false
            if (type != other.type) return false
            if (uuid != other.uuid) return false
            if (!byteArray.contentEquals(other.byteArray)) return false
            if (status != other.status) return false
            return true
        }

        override fun hashCode(): Int {
            var result = type.hashCode()
            result = 31 * result + uuid.hashCode()
            result = 31 * result + byteArray.contentHashCode()
            result = 31 * result + status
            return result
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
            super.onConnectionStateChange(gatt, status, newState)
            L.debug(Ble.Device.Default, "${gatt?.device?.name}(${gatt?.device?.address}) : onConnectionStateChange $status -> $newState")
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
            L.debug(Ble.Device.Default, "${gatt?.device?.name}(${gatt?.device?.address}) : onServicesDiscovered $status")
            discoveringServicesRelay.accept(false)
            gatt?.let {
                val alreadyConnected = (stateRelay.value as? State.CONNECTED)?.gatt === it
                val connecting = (stateRelay.value as? State.CONNECTING)?.gatt === it
                if (!alreadyConnected && !connecting) {
                    // 연결 취소/타임아웃으로 이미 정리된 gatt 의 늦은 콜백은 무시한다
                    return
                }
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    if (alreadyConnected) {
                        // onServiceChanged 후 재탐색 실패: 연결은 유지한다. (서비스 목록이 비면 이후 작업은 characteristic 을 찾지 못해 실패한다)
                        L.warning(Ble.Device.Default, "${it.device?.name}(${it.device?.address}) : service rediscovery failed : $status")
                    } else {
                        // 첫 서비스 탐색 실패: 서비스 없이 CONNECTED 로 만들지 않고 연결을 정리한다
                        cancelConnecting(it, BleManager.GattOperationFailedException("DISCOVER_SERVICES", status))
                    }
                    return
                }
                // onServiceChanged 로 다시 탐색한 경우에는 이미 같은 gatt 로 CONNECTED 이므로 상태를 다시 내보내지 않는다.
                // (다시 내보내면 gattObservable 을 구독 중인 setupNotification 이 재구독되어 CCCD 를 다시 쓴다)
                if (!alreadyConnected) {
                    // 새 연결(새 BluetoothGatt)에는 처리 중인 요청이 없다
                    gattRequestPendingRelay.accept(false)
                    stateRelay.accept(State.CONNECTED(it))
                }
            }
        }

        override fun onServiceChanged(gatt: BluetoothGatt) {
            super.onServiceChanged(gatt)
            L.debug(Ble.Device.Default, "${gatt.device?.name}(${gatt.device?.address}) : onServiceChanged")
            if ((stateRelay.value as? State.CONNECTED)?.gatt !== gatt) {
                // 아직 첫 서비스 탐색 전이라면 그 탐색 결과에 변경 사항이 반영된다
                return
            }
            // BluetoothGatt.getServices() 는 discoverServices() 를 다시 호출해야 갱신된다. 탐색이 끝날 때까지 작업은 기다린다.
            discoveringServicesRelay.accept(true)
            if (!gatt.discoverServices()) {
                L.warning(Ble.Device.Default, "${gatt.device?.name}(${gatt.device?.address}) : discoverServices failed after onServiceChanged")
                discoveringServicesRelay.accept(false)
            }
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt?,
            characteristic: BluetoothGattCharacteristic?,
            status: Int
        ) {
            super.onCharacteristicRead(gatt, characteristic, status)
            L.debug(Ble.Device.Default, "${gatt?.device?.name}(${gatt?.device?.address}) : onCharacteristicRead:$status, ${characteristic?.uuid}:${characteristic?.value?.toHexString()}")
            characteristic?.let {
                gattRequestPendingRelay.accept(false)
                replyRelay.accept(ReplyData(Type.READ_CHARACTERISTIC, it.uuid, it.value ?: byteArrayOf(), status))
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt?,
            characteristic: BluetoothGattCharacteristic?,
            status: Int
        ) {
            super.onCharacteristicWrite(gatt, characteristic, status)
            L.debug(Ble.Device.Default, "${gatt?.device?.name}(${gatt?.device?.address}) : onCharacteristicWrite:$status, ${characteristic?.uuid}:${characteristic?.value?.toHexString()}")
            characteristic?.let {
                gattRequestPendingRelay.accept(false)
                replyRelay.accept(ReplyData(Type.WRITE_CHARACTERISTIC, it.uuid, it.value ?: byteArrayOf(), status))
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt?,
            characteristic: BluetoothGattCharacteristic?
        ) {
            super.onCharacteristicChanged(gatt, characteristic)
            L.debug(Ble.Device.Default, "${gatt?.device?.name}(${gatt?.device?.address}) : onCharacteristicChanged:${characteristic?.uuid}:${characteristic?.value?.toHexString()}")
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
            L.debug(Ble.Device.Default, "${gatt?.device?.name}(${gatt?.device?.address}) : onDescriptorRead:${descriptor?.characteristic?.uuid}:${descriptor?.value?.toHexString()}")
            descriptor?.let {
                gattRequestPendingRelay.accept(false)
                replyRelay.accept(ReplyData(Type.READ_DESCRIPTOR, it.characteristic.uuid, it.value ?: byteArrayOf(), status))
            }
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt?,
            descriptor: BluetoothGattDescriptor?,
            status: Int
        ) {
            super.onDescriptorWrite(gatt, descriptor, status)
            L.debug(Ble.Device.Default, "${gatt?.device?.name}(${gatt?.device?.address}) : onDescriptorWrite:${descriptor?.characteristic?.uuid}:${descriptor?.value?.toHexString()}")
            descriptor?.let {
                gattRequestPendingRelay.accept(false)
                replyRelay.accept(ReplyData(Type.WRITE_DESCRIPTOR, it.characteristic.uuid, it.value ?: byteArrayOf(), status))
            }
        }

        override fun onPhyUpdate(gatt: BluetoothGatt?, txPhy: Int, rxPhy: Int, status: Int) {
            super.onPhyUpdate(gatt, txPhy, rxPhy, status)
            L.debug(Ble.Device.Default, "${gatt?.device?.name}(${gatt?.device?.address}) : onPhyUpdate:$txPhy:$rxPhy:$status")
        }

        override fun onPhyRead(gatt: BluetoothGatt?, txPhy: Int, rxPhy: Int, status: Int) {
            super.onPhyRead(gatt, txPhy, rxPhy, status)
            L.debug(Ble.Device.Default, "${gatt?.device?.name}(${gatt?.device?.address}) : onPhyRead:$txPhy:$rxPhy:$status")
        }

        override fun onReliableWriteCompleted(gatt: BluetoothGatt?, status: Int) {
            super.onReliableWriteCompleted(gatt, status)
            L.debug(Ble.Device.Default, "${gatt?.device?.name}(${gatt?.device?.address}) : onReliableWriteCompleted:$status")
        }

        override fun onReadRemoteRssi(gatt: BluetoothGatt?, rssi: Int, status: Int) {
            super.onReadRemoteRssi(gatt, rssi, status)
            L.debug(Ble.Device.Default, "${gatt?.device?.name}(${gatt?.device?.address}) : onReadRemoteRssi:$rssi, $status")
        }

        override fun onMtuChanged(gatt: BluetoothGatt?, mtu: Int, status: Int) {
            super.onMtuChanged(gatt, mtu, status)
            L.debug(Ble.Device.Default, "${gatt?.device?.name}(${gatt?.device?.address}) : onMtuChanged:$mtu, $status")
            gattRequestPendingRelay.accept(false)
            mtuRelay.accept(Pair(mtu, status))
        }
    }

    private val stateRelay = BehaviorRelay.create<State>().apply {
        accept(State.DISCONNECTED())
    }
    // DISCONNECTED 에서 연결을 시작하는 전환(GATT/SPP)은 이 잠금 안에서만 한다. (startConnecting)
    private val connectLock = Any()
    private val gattObservable: Observable<BluetoothGatt>
        get() = stateRelay.map {
            if (it is State.CONNECTED) {
                it.gatt
            } else {
                throw BleManager.DisconnectedException()
            }
        }
    private val discoveringServicesRelay = BehaviorRelay.createDefault(false)
    private val replyRelay = PublishRelay.create<ReplyData>()
    private val notificationRelay = PublishRelay.create<Pair<UUID, ByteArray>>()
    private val mtuRelay = PublishRelay.create<Pair<Int, Int>>()
    private val operationQueue = PublishRelay.create<QueuedOperation<*>>().toSerialized()
    private val notificationSubscriptions = mutableMapOf<UUID, SharedNotification>()
    // GATT 요청을 보냈고 아직 콜백을 받지 못한 상태(Android BluetoothGatt 가 busy 인 상태)
    private val gattRequestPendingRelay = BehaviorRelay.createDefault(false)

    init {
        operationQueue
            .concatMapCompletable { it.execute() }
            .subscribe({}, { L.error(Ble.Device.Default, "GATT operation queue terminated", it) })
    }

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

    override fun connectCompletable(timeoutMillis: Long): Completable = Completable.defer {
        val gatt = startConnecting {
            L.debug(Ble.Device.Default, "${bluetoothDevice.name}(${bluetoothDevice.address}) : connectGatt call")
            // 블루투스가 꺼져 있으면 connectGatt 가 null 을 반환한다. 상태를 CONNECTING 으로 바꾸기 전에 실패시킨다.
            // transport 를 지정하지 않으면(AUTO) 듀얼모드 기기에서 BR/EDR 로 GATT 연결을 시도해 status 133 으로 실패할 수 있다
            val newGatt = bluetoothDevice.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
                ?: throw BleManager.ConnectFailedException("connectGatt returned null (bluetooth may be off)")
            State.CONNECTING(newGatt)
        }.gatt
        // CONNECTING 으로 바꾼 뒤에 구독하므로, 여기서 보이는 DISCONNECTED 는 이번 연결 시도가 끝났다는 뜻이다
        stateRelay
            .doOnNext {
                if (it is State.DISCONNECTED) {
                    throw it.cause ?: BleManager.DisconnectedException()
                }
            }
            .filter { it is State.CONNECTED }
            .firstOrError()
            .ignoreElement()
            .timeout(timeoutMillis, TimeUnit.MILLISECONDS)
            .doOnError {
                L.error(Ble.Device.Default, "${bluetoothDevice.name}(${bluetoothDevice.address}) : doOnError", it)
                if (it is TimeoutException) {
                    cancelConnecting(gatt)
                }
            }
            .doOnDispose {
                // 연결이 완료되기 전에 구독이 해제되면 진행 중인 연결을 취소한다. (연결 완료 후의 dispose 는 무시)
                if ((stateRelay.value as? State.CONNECTING)?.gatt === gatt) {
                    cancelConnecting(gatt)
                }
            }
    }

    /**
     * 현재 상태가 DISCONNECTED 일 때만 [start]로 연결을 시작하고 그 상태로 바꾼다. 아니면 [IllegalStateException].
     * 상태 확인과 전환을 한 번에 해서, 동시에 연결해도 gatt/소켓이 하나만 만들어지게 한다.
     */
    private fun <S : State> startConnecting(start: () -> S): S = synchronized(connectLock) {
        val current = stateRelay.value
        if (current !is State.DISCONNECTED) {
            throw IllegalStateException("Connection state is ${current?.connectionState} not ${BleDevice.ConnectionState.DISCONNECTED}")
        }
        start().also { stateRelay.accept(it) }
    }

    /** 진행 중인 연결을 정리한다. [cause]가 있으면 기다리던 connectCompletable 이 그 에러로 실패한다. (상태는 이미 DISCONNECTED 인 채로 전달된다) */
    private fun cancelConnecting(gatt: BluetoothGatt, cause: Throwable? = null) {
        L.debug(Ble.Device.Default, "${bluetoothDevice.name}(${bluetoothDevice.address}) : cancel connecting")
        gatt.disconnect()
        gatt.close()
        stateRelay.accept(State.DISCONNECTED(cause))
        bleDevice.onDisconnected()
    }

    private fun checkConnectionState(connectionState: BleDevice.ConnectionState): Completable {
        return stateRelay.doOnNext {
            if (it.connectionState != connectionState) {
                throw IllegalStateException("Connection state is $it not $connectionState")
            }
        }.firstOrError().ignoreElement()
    }

    override fun disconnect() {
        when (val state = stateRelay.value) {
            is State.CONNECTED -> {
                L.debug(Ble.Device.Default, "${bluetoothDevice.name}(${bluetoothDevice.address}) : disconnect")
                // DISCONNECTING 이 되면 진행 중인 작업과 notification 은 바로 DisconnectedException 으로 끝난다
                stateRelay.accept(State.DISCONNECTING(state.gatt))
                state.gatt.disconnect()
                // onConnectionStateChange(DISCONNECTED) 가 오지 않는 경우를 대비해 일정 시간 뒤 강제로 정리한다
                Completable.timer(DISCONNECT_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS).subscribe {
                    val current = stateRelay.value
                    if (current is State.DISCONNECTING && current.gatt === state.gatt) {
                        L.warning(Ble.Device.Default, "${bluetoothDevice.name}(${bluetoothDevice.address}) : disconnect callback did not arrive, closing gatt")
                        state.gatt.close()
                        stateRelay.accept(State.DISCONNECTED())
                        bleDevice.onDisconnected()
                    }
                }
            }
            // 연결 시도 중이면 취소한다. 기다리던 connectCompletable 은 DisconnectedException 으로 끝난다
            is State.CONNECTING -> cancelConnecting(state.gatt)
            is State.SPP_CONNECTING -> {
                // 소켓을 닫으면 진행 중인 connect() 가 IOException 으로 끝나고, connectSppSocket 이 상태를 정리한다
                try {
                    state.socket.close()
                } catch (exception: IOException) {
                    L.error(Ble, "spp socket close error", exception)
                }
            }
            is State.SPP_CONNECTED -> {
                state.socket.close()
                stateRelay.accept(State.DISCONNECTED())
            }
            else -> L.debug(Ble.Device.Default, "${bluetoothDevice.name}(${bluetoothDevice.address}) : disconnect ignored, state is ${state?.connectionState}")
        }
    }

    override fun getCharacteristicsSingle(): Single<List<BleCharacterisic>> {
        return getServices()
            .map { services ->
                services.map { it.characteristics }.flatten()
            }
            .map<List<BleCharacterisic>> {
                it.map { BleCharacteristicImpl(it) }
            }
            .guardOperation()
    }

    override fun readCharacteristicSingle(characteristicUuid: UUID): Single<ByteArray> {
        return enqueue(getGattSingle()
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
                                L.debug(Ble.Device.Default, "${gatt.device?.name}(${gatt.device?.address}) : readCharacteristic : $characteristicUuid")
                                sendGattRequest { gatt.readCharacteristic(characteristic) }
                            })
                            .filter { it.uuid == characteristicUuid && it.type == Type.READ_CHARACTERISTIC }
                            .map { it.valueOrThrow() }
                            .firstOrError()
                    }
                    .retryOnCommunicationFailure()
            })
    }

    override fun writeCharacteristicSingle(characteristicUuid: UUID, byteArray: ByteArray, writeType: BleDevice.WriteType): Single<ByteArray> {
        return enqueue(getGattSingle()
            .flatMap { gatt ->
                getCharacteristic(characteristicUuid)
                    .flatMap { characteristic ->
                        val gattWriteType = resolveWriteType(characteristic.properties, writeType)
                        if (gattWriteType == null) {
                            Single.error(IllegalCharacteristicProperty(characteristic.properties, "WRITE_CHARACTERISTIC($writeType)"))
                        } else {
                            // write-no-response 도 onCharacteristicWrite 가 와야 BluetoothGatt 가 다음 작업을 받으므로 응답을 기다린다
                            replyRelay
                                .mergeWith(Completable.fromCallable {
                                    // writeType 을 매번 지정한다. 지정하지 않으면 Android 는 WRITE NO RESPONSE 속성이 있는 characteristic 을 응답 없이 쓴다
                                    characteristic.writeType = gattWriteType
                                    characteristic.value = byteArray
                                    L.debug(Ble.Device.Default, "${gatt.device?.name}(${gatt.device?.address}) : writeCharacteristic : $characteristicUuid : ${byteArray.toHexString()} : $writeType")
                                    sendGattRequest { gatt.writeCharacteristic(characteristic) }
                                })
                                .filter { it.uuid == characteristicUuid && it.type == Type.WRITE_CHARACTERISTIC }
                                .map { it.valueOrThrow() }
                                .firstOrError()
                        }
                    }
                    .retryOnCommunicationFailure()
            })
    }

    /** 요청한 쓰기 타입을 BluetoothGattCharacteristic.WRITE_TYPE_* 로 바꾼다. characteristic 이 지원하지 않는 타입이면 null. */
    private fun resolveWriteType(properties: Int, writeType: BleDevice.WriteType): Int? {
        val withResponse = properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0
        val withoutResponse = properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0
        return when (writeType) {
            BleDevice.WriteType.DEFAULT -> when {
                withResponse -> BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                withoutResponse -> BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                else -> null
            }
            BleDevice.WriteType.WITH_RESPONSE -> if (withResponse) BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT else null
            BleDevice.WriteType.WITHOUT_RESPONSE -> if (withoutResponse) BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE else null
        }
    }

    /**
     * 같은 characteristic 의 notification 구독은 하나의 설정을 공유한다.
     * 첫 구독자가 CCCD 를 켜고, 마지막 구독자가 해제할 때 CCCD 를 끈다. (나중에 구독한 쪽은 설정 완료 결과를 바로 받는다)
     * 구독 중인 characteristic 에 다른 [BleDevice.NotificationType]을 요청하면 기존 구독이 끊기지 않도록 [IllegalStateException]으로 실패한다.
     */
    override fun setupNotification(type: BleDevice.NotificationType, characteristicUuid: UUID): Observable<Observable<ByteArray>> {
        return Observable.defer {
            val subscription = synchronized(notificationSubscriptions) {
                val existing = notificationSubscriptions[characteristicUuid]
                when {
                    existing == null || existing.subscriberCount == 0 ->
                        SharedNotification(type, createNotificationObservable(type, characteristicUuid).replay(1).refCount())
                            .also { notificationSubscriptions[characteristicUuid] = it }
                    existing.type == type -> existing
                    else -> return@defer Observable.error<Observable<ByteArray>>(
                        IllegalStateException("$characteristicUuid notification is already set up as ${existing.type}")
                    )
                }.also { it.subscriberCount++ }
            }
            subscription.observable
                .doFinally { synchronized(notificationSubscriptions) { subscription.subscriberCount-- } }
        }
    }

    private class SharedNotification(val type: BleDevice.NotificationType, val observable: Observable<Observable<ByteArray>>) {
        // notificationSubscriptions 로 동기화한다
        var subscriberCount = 0
    }

    private fun createNotificationObservable(type: BleDevice.NotificationType, characteristicUuid: UUID): Observable<Observable<ByteArray>> = Observable.defer {
        // refCount 로 다시 구독될 수 있으므로 구독마다 새로 만든다
        var characteristic: BluetoothGattCharacteristic? = null
        gattObservable
            .flatMap { gatt ->
                getCharacteristic(characteristicUuid)
                    .flatMap {
                        characteristic = it
                        L.debug(Ble.Device.Default, "${gatt.device?.name}(${gatt.device?.address}) : setCharacteristicNotification : $characteristicUuid : true")
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
                            L.debug(Ble.Device.Default, "${gatt.device?.name}(${gatt.device?.address}) : setCharacteristicNotification : $characteristicUuid : false")
                            gatt.setCharacteristicNotification(gattCharacteristic, false)
                            writeDescriptor(gatt, gattCharacteristic, DISABLE_NOTIFICATION_VALUE).subscribe({
                                L.debug(Ble, "DISABLE_NOTIFICATION_VALUE:${it.contentToString()}")
                            }, {
                                L.error(Ble, "DISABLE_NOTIFICATION_VALUE error", it)
                            })
                        }
                    }
            }
    }

    private fun getGattSingle() = gattObservable
        .firstOrError()

    private fun getServices(): Single<List<BluetoothGattService>> {
        // 서비스 목록을 캐시하지 않고 현재 연결된 gatt 에서 읽는다. (재연결/서비스 변경 후 이전 목록과 characteristic 객체를 쓰지 않도록)
        return checkConnectionState(BleDevice.ConnectionState.CONNECTED)
            .andThen(discoveringServicesRelay.filter { !it }.firstOrError())
            .flatMap { getGattSingle() }
            .map { it.services }
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
        return enqueue(Single.defer {
            if (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY == 0 &&
                characteristic.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE == 0) {
                Single.error(IllegalCharacteristicProperty(characteristic.properties, "WRITE_DESCRIPTOR"))
            } else {
                replyRelay
                    .doOnSubscribe {
                        val descriptor = characteristic.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG_UUID)
                        descriptor.value = byteArray
                        L.debug(Ble.Device.Default, "${gatt.device?.name}(${gatt.device?.address}) : writeDescriptor : ${characteristic.uuid} : ${byteArray.toHexString()}")
                        sendGattRequest { gatt.writeDescriptor(descriptor) }
                    }
                    .filter { it.uuid == characteristic.uuid && it.type == Type.WRITE_DESCRIPTOR }
                    .map { it.valueOrThrow() }
                    .firstOrError()
                    .retryOnCommunicationFailure()
            }
        })
    }

    override fun connectSppSocket(uuid: UUID?): Observable<BleSppSocket> = Observable.defer {
        var connecting: State.SPP_CONNECTING? = null
        var socket: BleSppSocket? = null
        Observable.create<BleSppSocket> { emitter ->
            val aUuid = uuid ?: bluetoothDevice.uuids.first().uuid
            // 연결 중에 disconnect() 가 불리면 소켓을 닫아 connect() 를 중단할 수 있도록 상태에 소켓을 담는다
            val state = startConnecting { State.SPP_CONNECTING(bluetoothDevice.createRfcommSocketToServiceRecord(aUuid)) }
            connecting = state
            BleSppSocket(state.socket).also { theSocket ->
                socket = theSocket
                stateRelay.accept(State.SPP_CONNECTED(theSocket))
                // disconnect() 로 SPP 연결이 끊기면 스트림을 끝낸다
                emitter.setDisposable(stateRelay.filter { it !is State.SPP_CONNECTED }.firstElement()
                    .subscribe { emitter.tryOnError(BleManager.DisconnectedException()) })
                emitter.onNext(theSocket)
            }
        }
            .doFinally {
                // 이 구독이 시작한 SPP 연결만 정리한다. (연결을 시작하지 못했다면 다른 연결의 상태를 건드리지 않는다)
                val current = stateRelay.value
                if ((current is State.SPP_CONNECTING && current === connecting) ||
                    (current is State.SPP_CONNECTED && current.socket === socket)) {
                    stateRelay.accept(State.DISCONNECTED())
                }
                socket?.close()
            }
            .subscribeOn(Schedulers.io())
    }

    override fun requestMtuSingle(mtu: Int): Single<Int> {
        return enqueue(getGattSingle()
            .flatMap { gatt ->
                mtuRelay
                    .mergeWith(Completable.fromCallable {
                        L.debug(Ble.Device.Default, "${gatt.device?.name}(${gatt.device?.address}) : requestMtu : $mtu")
                        sendGattRequest { gatt.requestMtu(mtu) }
                    })
                    .firstOrError()
                    .map { (negotiatedMtu, status) ->
                        if (status != BluetoothGatt.GATT_SUCCESS) {
                            throw BleManager.MtuChangeFailedException(status)
                        }
                        negotiatedMtu
                    }
                    .retryOnCommunicationFailure()
            })
    }

    /**
     * Android 는 BluetoothGatt 당 한 번에 하나의 GATT 작업만 처리한다. 그래서 read/write/descriptor write/MTU 요청을 기기별 큐로 직렬화한다.
     * 앞 작업이 콜백을 받거나 타임아웃/연결 끊김으로 끝나야 다음 작업이 시작된다.
     * 타임아웃과 연결 끊김 감지([guardOperation])는 큐에서 기다리는 시간이 아니라 작업이 실제로 시작된 시점부터 적용된다.
     */
    private fun <T : Any> enqueue(operation: Single<T>): Single<T> = Single.create { emitter ->
        operationQueue.accept(QueuedOperation(operation.guardOperation(), emitter))
    }

    private inner class QueuedOperation<T : Any>(private val operation: Single<T>, private val emitter: SingleEmitter<T>) {
        fun execute(): Completable = Completable.defer {
            if (emitter.isDisposed) {
                // 시작 전에 취소된 작업은 GATT 호출 없이 건너뛴다
                Completable.complete()
            } else {
                // 실행 중에 취소되더라도 작업이 끝날 때까지(콜백/타임아웃/연결 끊김) 다음 작업을 시작하지 않는다
                operation
                    .doOnSuccess { emitter.onSuccess(it) }
                    .doOnError { emitter.tryOnError(it) }
                    .ignoreElement()
                    .onErrorResumeNext { error ->
                        // 타임아웃은 호출한 쪽에 바로 알리되, 보낸 요청이 아직 처리 중이면 큐는 GATT 가 풀릴 때까지 기다린다
                        if (error is TimeoutException) waitForGattRelease() else Completable.complete()
                    }
            }
        }
    }

    /** GATT 요청을 보내고 콜백이 올 때까지 처리 중으로 표시한다. 요청이 거절되면(busy 등) [CommunicationFailedException]. */
    private fun sendGattRequest(request: () -> Boolean) {
        gattRequestPendingRelay.accept(true)
        if (!request()) {
            gattRequestPendingRelay.accept(false)
            throw CommunicationFailedException()
        }
    }

    /**
     * Android BluetoothGatt 는 콜백이 올 때까지 다음 요청을 거절한다. 그래서 타임아웃된 요청이 아직 처리 중이면
     * 늦은 콜백이나 연결 끊김까지 기다린다. Android 는 ATT transaction timeout(30초)에 링크를 끊으므로 그 이상은 기다리지 않는다.
     */
    private fun waitForGattRelease(): Completable {
        val released = gattRequestPendingRelay.filter { !it }.firstElement().ignoreElement()
        val disconnected = stateRelay.filter { it !is State.CONNECTED }.firstElement().ignoreElement()
        return Completable.ambArray(released, disconnected)
            .timeout(GATT_RELEASE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS, Completable.complete())
            .doOnSubscribe {
                if (gattRequestPendingRelay.value == true) {
                    L.warning(Ble.Device.Default, "${bluetoothDevice.name}(${bluetoothDevice.address}) : operation timed out, waiting for GATT release before next operation")
                }
            }
            .doFinally { gattRequestPendingRelay.accept(false) }
    }

    /** GATT 호출이 false 를 반환하면 잠시 후 다시 시도하고, 재시도 횟수를 넘기면 [CommunicationFailedException]으로 실패한다. */
    private fun <T : Any> Single<T>.retryOnCommunicationFailure(): Single<T> = retryWhen { errors ->
        var retryCount = 0
        errors.flatMap { throwable ->
            if (throwable is CommunicationFailedException && retryCount++ < MAX_RETRY_COUNT) {
                Flowable.timer(RETRY_DELAY_MILLIS, TimeUnit.MILLISECONDS)
            } else {
                Flowable.error(throwable)
            }
        }
    }

    /**
     * GATT 콜백을 기다리는 작업이 영원히 끝나지 않는 것을 막는다.
     * 작업 도중 연결이 끊기면 [BleManager.DisconnectedException], [BleDevice.operationTimeoutMillis]를 넘기면 [TimeoutException]으로 종료한다.
     * 이미 연결이 끊긴 상태라면 GATT 호출 없이 바로 실패하도록 연결 끊김 감지를 먼저 구독한다.
     */
    private fun <T : Any> Single<T>.guardOperation(): Single<T> {
        val disconnectedSingle = stateRelay
            .filter { it !is State.CONNECTED }
            .firstOrError()
            .flatMap { Single.error<T>(BleManager.DisconnectedException()) }
        return disconnectedSingle
            .ambWith(this)
            .timeout(bleDevice.operationTimeoutMillis, TimeUnit.MILLISECONDS)
    }

    internal fun onBluetoothDisabled() {
        getGattSingle()
            .subscribe({
                stateRelay.accept(State.DISCONNECTED())
                bleDevice.onDisconnected()
                it.close()
            }, {
                L.debug(Ble, "onBluetoothDisabled error:$deviceId", it)
            })
    }

    companion object {
        private val LOG_TAG = BleDeviceEngineImpl::class.java.simpleName

        private val CLIENT_CHARACTERISTIC_CONFIG_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private const val MAX_RETRY_COUNT = 5
        private const val RETRY_DELAY_MILLIS = 500L
        // ATT transaction timeout(30초) + 여유
        private const val GATT_RELEASE_TIMEOUT_MILLIS = 31_000L
        // disconnect() 후 onConnectionStateChange(DISCONNECTED) 를 기다리는 최대 시간
        private const val DISCONNECT_TIMEOUT_MILLIS = 5_000L
        // BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE 와 동일
        private val DISABLE_NOTIFICATION_VALUE = byteArrayOf(0x00, 0x00)
    }
}

fun ByteArray.toHexString(): String {
    return joinToString(separator = "") { String.format("%02x", it) }
}

fun BluetoothDevice.deleteBond(): Boolean {
    return javaClass.getMethod("removeBond").invoke(this) as Boolean
}
