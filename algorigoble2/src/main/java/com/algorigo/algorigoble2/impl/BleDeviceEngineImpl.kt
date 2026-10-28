package com.algorigo.algorigoble2.impl

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import androidx.annotation.ChecksSdkIntAtLeast
import androidx.annotation.Keep
import androidx.annotation.RequiresApi
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
import java.util.*
import java.util.concurrent.TimeUnit

/**
 * @param sdkInt 값을 인자로 주고받는 GATT API(API 33+)를 쓸지 정하는 SDK 버전. 테스트에서 두 경로를 모두 확인할 수 있도록 주입한다.
 */
@SuppressLint("MissingPermission")
internal class BleDeviceEngineImpl(
    private val context: Context,
    private val bluetoothDevice: BluetoothDevice,
    private val sdkInt: Int = Build.VERSION.SDK_INT,
) : BleDeviceEngine() {


    private sealed class State(val connectionState: BleDevice.ConnectionState) {
        // 각 상태는 disconnect() 가 정리할 수 있도록 진행 중인 gatt / 소켓을 담는다
        class CONNECTING(val gatt: BluetoothGatt) : State(BleDevice.ConnectionState.CONNECTING)
        class CONNECTED(val gatt: BluetoothGatt) : State(BleDevice.ConnectionState.CONNECTED)
        // cause: 이 상태 때문에 끝나는 연결 시도/작업/notification 이 낼 에러. (null 이면 사유를 모르는 DisconnectedException)
        class DISCONNECTED(val cause: Throwable? = null) : State(BleDevice.ConnectionState.DISCONNECTED)
        class DISCONNECTING(val gatt: BluetoothGatt) : State(BleDevice.ConnectionState.DISCONNECTING)
    }

    private enum class Type {
        READ_CHARACTERISTIC,
        WRITE_CHARACTERISTIC,
        READ_DESCRIPTOR,
        WRITE_DESCRIPTOR,
    }

    // 쓰기 응답은 값을 담지 않는다. (쓴 값은 요청한 쪽이 알고 있고, characteristic.value 는 다른 콜백이 덮어쓸 수 있다)
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
            L.debug(Ble.Device.Default, "$logLabel : onConnectionStateChange $status -> $newState")
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    // false 는 gatt 가 이미 해제됐거나 Bluetooth 서비스와 통신하지 못한 경우라 다시 시도해도 소용없다.
                    // 연결 타임아웃까지 기다리지 않고 바로 연결 시도를 정리한다
                    if (gatt != null && !gatt.discoverServices()) {
                        synchronized(stateLock) {
                            if ((stateRelay.value as? State.CONNECTING)?.gatt === gatt) {
                                L.warning(Ble.Device.Default, "$logLabel : discoverServices failed")
                                cancelConnecting(gatt, BleManager.ConnectFailedException("discoverServices returned false"))
                            }
                        }
                    }
                }

                BluetoothProfile.STATE_DISCONNECTED -> {
                    // 끊긴 사유(status)를 진행 중이던 작업과 notification 의 에러로 전달한다
                    setState(State.DISCONNECTED(BleManager.DisconnectedException(status)))
                    bleDevice.onDisconnected()
                    gatt?.close()
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
            super.onServicesDiscovered(gatt, status)
            L.debug(Ble.Device.Default, "$logLabel : onServicesDiscovered $status")
            discoveringServicesRelay.accept(false)
            // 상태 확인과 CONNECTED 전환 사이에 타임아웃/취소로 gatt 가 정리되지 않도록 잠금 안에서 처리한다
            gatt?.let { synchronized(stateLock) {
                val alreadyConnected = (stateRelay.value as? State.CONNECTED)?.gatt === it
                val connecting = (stateRelay.value as? State.CONNECTING)?.gatt === it
                if (!alreadyConnected && !connecting) {
                    // 연결 취소/타임아웃으로 이미 정리된 gatt 의 늦은 콜백은 무시한다
                    return
                }
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    if (alreadyConnected) {
                        // onServiceChanged 후 재탐색 실패: 연결은 유지한다. (서비스 목록이 비면 이후 작업은 characteristic 을 찾지 못해 실패한다)
                        L.warning(Ble.Device.Default, "$logLabel : service rediscovery failed : $status")
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
                    setState(State.CONNECTED(it))
                }
            } }
        }

        override fun onServiceChanged(gatt: BluetoothGatt) {
            super.onServiceChanged(gatt)
            L.debug(Ble.Device.Default, "$logLabel : onServiceChanged")
            if ((stateRelay.value as? State.CONNECTED)?.gatt !== gatt) {
                // 아직 첫 서비스 탐색 전이라면 그 탐색 결과에 변경 사항이 반영된다
                return
            }
            // BluetoothGatt.getServices() 는 discoverServices() 를 다시 호출해야 갱신된다. 탐색이 끝날 때까지 작업은 기다린다.
            discoveringServicesRelay.accept(true)
            if (!gatt.discoverServices()) {
                L.warning(Ble.Device.Default, "$logLabel : discoverServices failed after onServiceChanged")
                discoveringServicesRelay.accept(false)
            }
        }

        // API 33 이상에서는 값을 인자로 받는 콜백이 호출된다. 공유된 characteristic.value / descriptor.value 를 읽지 않으므로
        // 그 사이에 들어온 다른 콜백(notification 등)이 값을 덮어써도 영향이 없다.
        // super 를 호출하지 않는다. 기본 구현이 아래의 deprecated 콜백을 다시 호출해서 이벤트가 두 번 전달된다.
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int
        ) {
            handleCharacteristicRead(gatt, characteristic, value, status)
        }

        @Deprecated("API 32 이하에서만 호출된다")
        override fun onCharacteristicRead(
            gatt: BluetoothGatt?,
            characteristic: BluetoothGattCharacteristic?,
            status: Int
        ) {
            if (usesValueGattApi()) {
                return
            }
            characteristic?.let { handleCharacteristicRead(gatt, it, it.value ?: byteArrayOf(), status) }
        }

        private fun handleCharacteristicRead(gatt: BluetoothGatt?, characteristic: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            L.debug(Ble.Device.Default, "$logLabel : onCharacteristicRead:$status, ${characteristic.uuid}:${value.toHexString()}")
            gattRequestPendingRelay.accept(false)
            replyRelay.accept(ReplyData(Type.READ_CHARACTERISTIC, characteristic.uuid, value, status))
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt?,
            characteristic: BluetoothGattCharacteristic?,
            status: Int
        ) {
            super.onCharacteristicWrite(gatt, characteristic, status)
            // characteristic.value 는 쓴 값이 아닐 수 있으므로(API 33+ 에서는 갱신되지 않고, 그 전에는 notification 이 덮어씀) 남기지 않는다
            L.debug(Ble.Device.Default, "$logLabel : onCharacteristicWrite:$status, ${characteristic?.uuid}")
            characteristic?.let {
                gattRequestPendingRelay.accept(false)
                replyRelay.accept(ReplyData(Type.WRITE_CHARACTERISTIC, it.uuid, byteArrayOf(), status))
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            handleCharacteristicChanged(gatt, characteristic, value)
        }

        @Deprecated("API 32 이하에서만 호출된다")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt?,
            characteristic: BluetoothGattCharacteristic?
        ) {
            if (usesValueGattApi()) {
                return
            }
            characteristic?.let { handleCharacteristicChanged(gatt, it, it.value ?: byteArrayOf()) }
        }

        private fun handleCharacteristicChanged(gatt: BluetoothGatt?, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            L.debug(Ble.Device.Default, "$logLabel : onCharacteristicChanged:${characteristic.uuid}:${value.toHexString()}")
            notificationRelay.accept(Pair(characteristic, value))
        }

        override fun onDescriptorRead(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int,
            value: ByteArray
        ) {
            handleDescriptorRead(gatt, descriptor, value, status)
        }

        @Deprecated("API 32 이하에서만 호출된다")
        override fun onDescriptorRead(
            gatt: BluetoothGatt?,
            descriptor: BluetoothGattDescriptor?,
            status: Int
        ) {
            if (usesValueGattApi()) {
                return
            }
            descriptor?.let { handleDescriptorRead(gatt, it, it.value ?: byteArrayOf(), status) }
        }

        private fun handleDescriptorRead(gatt: BluetoothGatt?, descriptor: BluetoothGattDescriptor, value: ByteArray, status: Int) {
            L.debug(Ble.Device.Default, "$logLabel : onDescriptorRead:${descriptor.characteristic?.uuid}:${value.toHexString()}")
            gattRequestPendingRelay.accept(false)
            replyRelay.accept(ReplyData(Type.READ_DESCRIPTOR, descriptor.characteristic.uuid, value, status))
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt?,
            descriptor: BluetoothGattDescriptor?,
            status: Int
        ) {
            super.onDescriptorWrite(gatt, descriptor, status)
            L.debug(Ble.Device.Default, "$logLabel : onDescriptorWrite:$status, ${descriptor?.characteristic?.uuid}")
            descriptor?.let {
                gattRequestPendingRelay.accept(false)
                replyRelay.accept(ReplyData(Type.WRITE_DESCRIPTOR, it.characteristic.uuid, byteArrayOf(), status))
            }
        }

        override fun onPhyUpdate(gatt: BluetoothGatt?, txPhy: Int, rxPhy: Int, status: Int) {
            super.onPhyUpdate(gatt, txPhy, rxPhy, status)
            L.debug(Ble.Device.Default, "$logLabel : onPhyUpdate:$txPhy:$rxPhy:$status")
        }

        override fun onPhyRead(gatt: BluetoothGatt?, txPhy: Int, rxPhy: Int, status: Int) {
            super.onPhyRead(gatt, txPhy, rxPhy, status)
            L.debug(Ble.Device.Default, "$logLabel : onPhyRead:$txPhy:$rxPhy:$status")
        }

        override fun onReliableWriteCompleted(gatt: BluetoothGatt?, status: Int) {
            super.onReliableWriteCompleted(gatt, status)
            L.debug(Ble.Device.Default, "$logLabel : onReliableWriteCompleted:$status")
        }

        override fun onReadRemoteRssi(gatt: BluetoothGatt?, rssi: Int, status: Int) {
            super.onReadRemoteRssi(gatt, rssi, status)
            L.debug(Ble.Device.Default, "$logLabel : onReadRemoteRssi:$rssi, $status")
        }

        override fun onMtuChanged(gatt: BluetoothGatt?, mtu: Int, status: Int) {
            super.onMtuChanged(gatt, mtu, status)
            L.debug(Ble.Device.Default, "$logLabel : onMtuChanged:$mtu, $status")
            gattRequestPendingRelay.accept(false)
            mtuRelay.accept(Pair(mtu, status))
        }

        /**
         * 연결 파라미터가 바뀌면 불린다. BluetoothGattCallback 의 숨겨진(@hide) 콜백(API 26+)이라 override 표시 없이 같은 시그니처로 선언한다.
         * 연결 우선순위 요청의 결과를 확인할 수 있도록 로그만 남긴다. (interval 1.25ms 단위, timeout 10ms 단위)
         */
        @Keep
        @Suppress("unused")
        fun onConnectionUpdated(gatt: BluetoothGatt, interval: Int, latency: Int, timeout: Int, status: Int) {
            L.debug(Ble.Device.Default, "$logLabel : onConnectionUpdated: interval ${interval * 1.25}ms, latency $latency, timeout ${timeout * 10}ms, status $status")
        }
    }

    private val stateRelay = BehaviorRelay.create<State>().apply {
        accept(State.DISCONNECTED())
    }
    // 상태 전환과 그 조건 확인은 이 잠금 안에서 한다. 관찰자는 상태를 한 번에 하나씩, 바뀐 순서대로 받는다.
    // (여러 스레드가 동시에 accept 하면 BehaviorRelay 는 관찰자에게 동시에, 순서가 뒤바뀐 채로 전달할 수 있다)
    // toSerialized() 는 다른 스레드가 전달 중이면 값을 큐에 넣고 바로 반환해서, 직후에 읽은 stateRelay.value 가 이전 값일 수 있으므로 쓰지 않는다
    private val stateLock = Any()
    // Classic(RFCOMM/SPP) 연결. GATT 와 상태를 따로 두고, 같은 잠금으로 전환해서 공개 상태에서 순서가 섞이지 않게 한다
    private val sppConnection = SppConnection(context, bluetoothDevice, stateLock) { logLabel }
    private val gattObservable: Observable<BluetoothGatt>
        get() = stateRelay.map {
            if (it is State.CONNECTED) {
                it.gatt
            } else {
                throw it.disconnectedError()
            }
        }
    private val discoveringServicesRelay = BehaviorRelay.createDefault(false)
    private val replyRelay = PublishRelay.create<ReplyData>()
    // 어느 서비스의 characteristic 인지 구분할 수 있도록 characteristic 을 함께 전달한다
    private val notificationRelay = PublishRelay.create<Pair<BluetoothGattCharacteristic, ByteArray>>()
    private val mtuRelay = PublishRelay.create<Pair<Int, Int>>()
    private val operationQueue = PublishRelay.create<QueuedOperation<*>>().toSerialized()
    private val notificationSubscriptions = mutableMapOf<NotificationKey, SharedNotification>()
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
        get() {
            BlePermissions.checkConnect(context, sdkInt)
            return bluetoothDevice.name
        }
    override val bonded: Boolean
        get() {
            BlePermissions.checkConnect(context, sdkInt)
            return bluetoothDevice.bondState == BluetoothDevice.BOND_BONDED
        }

    // 로그용 "이름(주소)". 권한이 없어 이름을 읽을 수 없어도 로그 때문에 작업이 실패하지 않게 한다
    private val logLabel: String
        get() {
            val name = try {
                bluetoothDevice.name
            } catch (e: SecurityException) {
                "?"
            }
            return "$name(${bluetoothDevice.address})"
        }

    override fun bondCompletable(): Completable {
        return Completable.defer {
            BlePermissions.checkConnect(context, sdkInt)
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
                            else -> throw BleManager.BondFailedException("Unexpected bond state ${bluetoothDevice.bondState}")
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
            BlePermissions.checkConnect(context, sdkInt)
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
                            else -> throw BleManager.BondFailedException("Unexpected bond state ${bluetoothDevice.bondState}")
                        }
                    }
                    .filter { it }
                    .firstOrError()
                    .ignoreElement()
            }
        }
    }

    /**
     * 기기의 공개 연결 상태. GATT 상태가 DISCONNECTED 일 때만 SPP 연결 상태(SPP_CONNECTING/SPP_CONNECTED)를 보여 준다.
     * (둘은 동시에 연결되지 않는다) 두 상태는 같은 잠금(stateLock) 안에서 바뀌므로 바뀐 순서대로 전달된다.
     */
    override fun getConnectionStateObservable(): Observable<BleDevice.ConnectionState> {
        return Observable.combineLatest(stateRelay, sppConnection.stateObservable) { gattState, sppState ->
            if (gattState is State.DISCONNECTED) sppState.connectionState else gattState.connectionState
        }
            .distinctUntilChanged()
    }

    override fun connectCompletable(timeoutMillis: Long): Completable = Completable.defer {
        // 권한이 없으면 상태를 바꾸기 전에 실패시킨다
        BlePermissions.checkConnect(context, sdkInt)
        val gatt = startConnecting {
            L.debug(Ble.Device.Default, "$logLabel : connectGatt call")
            // 블루투스가 꺼져 있으면 connectGatt 가 null 을 반환한다. 상태를 CONNECTING 으로 바꾸기 전에 실패시킨다.
            // transport 를 지정하지 않으면(AUTO) 듀얼모드 기기에서 BR/EDR 로 GATT 연결을 시도해 status 133 으로 실패할 수 있다
            val newGatt = bluetoothDevice.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
                ?: throw BleManager.BluetoothDisabledException()
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
            .timeout(timeoutMillis, TimeUnit.MILLISECONDS, Completable.error(BleManager.OperationTimeoutException(OPERATION_CONNECT, timeoutMillis)))
            .doOnError {
                L.error(Ble.Device.Default, "$logLabel : doOnError", it)
                if (it is BleManager.OperationTimeoutException) {
                    cancelConnecting(gatt)
                }
            }
            .doOnDispose {
                // 연결이 완료되기 전에 구독이 해제되면 진행 중인 연결을 취소한다. (연결 완료 후의 dispose 는 무시)
                synchronized(stateLock) {
                    if ((stateRelay.value as? State.CONNECTING)?.gatt === gatt) {
                        cancelConnecting(gatt)
                    }
                }
            }
    }

    /**
     * 현재 상태가 DISCONNECTED 일 때만 [start]로 연결을 시작하고 그 상태로 바꾼다. 아니면 [BleManager.InvalidConnectionStateException].
     * 상태 확인과 전환을 한 번에 해서, 동시에 연결해도 gatt/소켓이 하나만 만들어지게 한다.
     */
    private fun <S : State> startConnecting(start: () -> S): S = synchronized(stateLock) {
        // 처음부터 DISCONNECTED 로 시작하므로 null 이 아니다
        val current = stateRelay.value!!
        if (current !is State.DISCONNECTED) {
            throw BleManager.InvalidConnectionStateException(current.connectionState, BleDevice.ConnectionState.DISCONNECTED)
        }
        // 같은 기기의 SPP 연결과는 동시에 쓰지 않는다
        val spp = sppConnection.state
        if (spp !is SppConnection.State.DISCONNECTED) {
            throw BleManager.InvalidConnectionStateException(spp.connectionState, BleDevice.ConnectionState.DISCONNECTED)
        }
        start().also { setState(it) }
    }

    private fun setState(state: State) = synchronized(stateLock) {
        stateRelay.accept(state)
    }

    /** 진행 중인 연결을 정리한다. [cause]가 있으면 기다리던 connectCompletable 이 그 에러로 실패한다. (상태는 이미 DISCONNECTED 인 채로 전달된다) */
    private fun cancelConnecting(gatt: BluetoothGatt, cause: Throwable? = null) {
        L.debug(Ble.Device.Default, "$logLabel : cancel connecting")
        synchronized(stateLock) {
            gatt.disconnect()
            gatt.close()
            setState(State.DISCONNECTED(cause))
        }
        bleDevice.onDisconnected()
    }

    private fun checkConnectionState(connectionState: BleDevice.ConnectionState): Completable {
        return stateRelay.doOnNext {
            if (it.connectionState != connectionState) {
                throw BleManager.InvalidConnectionStateException(it.connectionState, connectionState)
            }
        }.firstOrError().ignoreElement()
    }

    override fun disconnect() {
        // 상태를 읽고 바꾸는 사이에 콜백이 상태를 바꾸지 않도록 잠금 안에서 처리한다
        synchronized(stateLock) {
            when (val state = stateRelay.value) {
                is State.CONNECTED -> {
                    L.debug(Ble.Device.Default, "$logLabel : disconnect")
                    // DISCONNECTING 이 되면 진행 중인 작업과 notification 은 바로 DisconnectedException 으로 끝난다
                    setState(State.DISCONNECTING(state.gatt))
                    state.gatt.disconnect()
                    // onConnectionStateChange(DISCONNECTED) 가 오지 않는 경우를 대비해 일정 시간 뒤 강제로 정리한다
                    Completable.timer(DISCONNECT_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS).subscribe {
                        val closed = synchronized(stateLock) {
                            val current = stateRelay.value
                            (current is State.DISCONNECTING && current.gatt === state.gatt).also { stillDisconnecting ->
                                if (stillDisconnecting) {
                                    L.warning(Ble.Device.Default, "$logLabel : disconnect callback did not arrive, closing gatt")
                                    state.gatt.close()
                                    setState(State.DISCONNECTED())
                                }
                            }
                        }
                        if (closed) {
                            bleDevice.onDisconnected()
                        }
                    }
                }
                // 연결 시도 중이면 취소한다. 기다리던 connectCompletable 은 DisconnectedException 으로 끝난다
                is State.CONNECTING -> cancelConnecting(state.gatt)
                is State.DISCONNECTED -> {
                    // GATT 가 연결되지 않았으면 SPP 연결을 끊는다
                    if (!sppConnection.disconnect()) {
                        L.debug(Ble.Device.Default, "$logLabel : disconnect ignored, state is ${state.connectionState}")
                    }
                }
                else -> L.debug(Ble.Device.Default, "$logLabel : disconnect ignored, state is ${state?.connectionState}")
            }
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
            .guardOperation(OPERATION_GET_CHARACTERISTICS)
    }

    override fun readCharacteristicSingle(serviceUuid: UUID?, characteristicUuid: UUID): Single<ByteArray> {
        return enqueue(Type.READ_CHARACTERISTIC.name, getGattSingle()
            .flatMap { gatt ->
                getCharacteristic(serviceUuid, characteristicUuid)
                    .doOnSuccess {
                        if (it.properties and BluetoothGattCharacteristic.PROPERTY_READ == 0) {
                            throw BleManager.IllegalCharacteristicPropertyException(characteristicUuid, it.properties, Type.READ_CHARACTERISTIC.name)
                        }
                    }
                    .flatMap { characteristic ->
                        replyRelay
                            .mergeWith(Completable.fromCallable {
                                L.debug(Ble.Device.Default, "$logLabel : readCharacteristic : $characteristicUuid")
                                sendGattRequest(Type.READ_CHARACTERISTIC.name) { gatt.readCharacteristic(characteristic) }
                            })
                            .filter { it.uuid == characteristicUuid && it.type == Type.READ_CHARACTERISTIC }
                            .map { it.valueOrThrow() }
                            .firstOrError()
                    }
                    .retryOnCommunicationFailure()
            })
    }

    override fun writeCharacteristicSingle(serviceUuid: UUID?, characteristicUuid: UUID, byteArray: ByteArray, writeType: BleDevice.WriteType): Single<ByteArray> {
        return enqueue(Type.WRITE_CHARACTERISTIC.name, getGattSingle()
            .flatMap { gatt ->
                getCharacteristic(serviceUuid, characteristicUuid)
                    .flatMap { characteristic ->
                        val gattWriteType = resolveWriteType(characteristic.properties, writeType)
                        if (gattWriteType == null) {
                            Single.error(BleManager.IllegalCharacteristicPropertyException(
                                characteristicUuid, characteristic.properties, "${Type.WRITE_CHARACTERISTIC.name}($writeType)"
                            ))
                        } else {
                            // write-no-response 도 onCharacteristicWrite 가 와야 BluetoothGatt 가 다음 작업을 받으므로 응답을 기다린다
                            replyRelay
                                .mergeWith(Completable.fromCallable {
                                    L.debug(Ble.Device.Default, "$logLabel : writeCharacteristic : $characteristicUuid : ${byteArray.toHexString()} : $writeType")
                                    sendGattRequest(Type.WRITE_CHARACTERISTIC.name) {
                                        if (usesValueGattApi()) {
                                            // 값과 쓰기 타입을 인자로 넘기므로, 같은 characteristic 으로 들어온 notification 이 쓰려던 값을 덮어쓰지 못한다
                                            gatt.writeCharacteristic(characteristic, byteArray, gattWriteType).isGattRequestAccepted("writeCharacteristic")
                                        } else {
                                            // writeType 을 매번 지정한다. 지정하지 않으면 Android 는 WRITE NO RESPONSE 속성이 있는 characteristic 을 응답 없이 쓴다
                                            characteristic.writeType = gattWriteType
                                            characteristic.value = byteArray
                                            gatt.writeCharacteristic(characteristic)
                                        }
                                    }
                                })
                                .filter { it.uuid == characteristicUuid && it.type == Type.WRITE_CHARACTERISTIC }
                                .map { it.valueOrThrow(); byteArray }
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
     * 구독 중인 characteristic 에 다른 [BleDevice.NotificationType]을 요청하면 기존 구독이 끊기지 않도록 [BleManager.NotificationTypeConflictException]으로 실패한다.
     * 같은 characteristic 인지는 서비스까지 보고 판단한다. (여러 서비스에 같은 UUID 의 characteristic 이 있을 수 있다)
     */
    override fun setupNotification(type: BleDevice.NotificationType, serviceUuid: UUID?, characteristicUuid: UUID): Observable<Observable<ByteArray>> {
        return Observable.defer {
            // 서비스를 지정하지 않은 구독도 지정한 구독과 같은 characteristic 이면 설정을 공유하도록, 현재 연결의 서비스 목록으로 서비스를 정한다
            val key = NotificationKey(serviceUuid ?: findServiceUuidOf(characteristicUuid), characteristicUuid)
            val subscription = synchronized(notificationSubscriptions) {
                val existing = notificationSubscriptions[key]
                when {
                    existing == null || existing.subscriberCount == 0 ->
                        SharedNotification(type, createNotificationObservable(type, serviceUuid, characteristicUuid).replay(1).refCount())
                            .also { notificationSubscriptions[key] = it }
                    existing.type == type -> existing
                    else -> return@defer Observable.error<Observable<ByteArray>>(
                        BleManager.NotificationTypeConflictException(characteristicUuid, existing.type, type)
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

    // serviceUuid 가 null 이면 연결되지 않아 서비스를 알 수 없는 경우다. (이때 구독은 DisconnectedException 으로 끝난다)
    private data class NotificationKey(val serviceUuid: UUID?, val characteristicUuid: UUID)

    /** 현재 연결에서 [characteristicUuid]를 가진 첫 서비스의 UUID. 연결되지 않았거나 찾지 못하면 null. */
    private fun findServiceUuidOf(characteristicUuid: UUID): UUID? {
        val gatt = (stateRelay.value as? State.CONNECTED)?.gatt ?: return null
        return gatt.services.firstOrNull { it.getCharacteristic(characteristicUuid) != null }?.uuid
    }

    private fun createNotificationObservable(type: BleDevice.NotificationType, serviceUuid: UUID?, characteristicUuid: UUID): Observable<Observable<ByteArray>> = Observable.defer {
        // refCount 로 다시 구독될 수 있으므로 구독마다 새로 만든다
        var characteristic: BluetoothGattCharacteristic? = null
        gattObservable
            .flatMap { gatt ->
                getCharacteristic(serviceUuid, characteristicUuid)
                    .flatMap {
                        // 켤 수 없는 characteristic 이면 setCharacteristicNotification 을 부르기 전에 실패한다 (정리할 것도 없게)
                        notificationSetupError(it)?.let { error -> return@flatMap Single.error(error) }
                        characteristic = it
                        L.debug(Ble.Device.Default, "$logLabel : setCharacteristicNotification : $characteristicUuid : true")
                        gatt.setCharacteristicNotification(it, true)
                        writeDescriptor(gatt, it, type.byteArray).map { _ -> it }
                    }
                    .toObservable()
                    .concatWith(Observable.never())
                    .map { enabled ->
                        // 다른 서비스의 같은 UUID characteristic 에서 온 notification 은 전달하지 않는다
                        val serviceOfEnabled = enabled.service?.uuid
                        notificationRelay
                            .filter { it.first.uuid == characteristicUuid && it.first.service?.uuid == serviceOfEnabled }
                            .map { it.second }
                    }
                    .doFinally {
                        characteristic?.let { gattCharacteristic ->
                            L.debug(Ble.Device.Default, "$logLabel : setCharacteristicNotification : $characteristicUuid : false")
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

    /** [serviceUuid]가 null 이면 모든 서비스에서 처음 찾은 characteristic. 찾지 못하면 [BleManager.CharacteristicNotFoundException]. */
    private fun getCharacteristic(serviceUuid: UUID?, characteristicUuid: UUID): Single<BluetoothGattCharacteristic> {
        return getServices()
            .flatMap { services ->
                return@flatMap Single.fromCallable {
                    for (service in services) {
                        if (serviceUuid != null && service.uuid != serviceUuid) {
                            continue
                        }
                        val characteristic = service.getCharacteristic(characteristicUuid)
                        if (characteristic != null) {
                            return@fromCallable characteristic
                        }
                    }
                    throw BleManager.CharacteristicNotFoundException(serviceUuid, characteristicUuid)
                }
            }
            .subscribeOn(Schedulers.io())
    }

    /** notification/indication 을 켤 수 없으면 그 이유. (NOTIFY/INDICATE 속성이 없거나 CCCD 가 없음) */
    private fun notificationSetupError(characteristic: BluetoothGattCharacteristic): Throwable? = when {
        characteristic.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY == 0 &&
                characteristic.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE == 0 ->
            BleManager.IllegalCharacteristicPropertyException(characteristic.uuid, characteristic.properties, "NOTIFY/INDICATE")
        // CCCD 가 없으면 원격 기기에 notification 을 켜 달라고 요청할 방법이 없다
        characteristic.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG_UUID) == null ->
            BleManager.DescriptorNotFoundException(characteristic.uuid, CLIENT_CHARACTERISTIC_CONFIG_UUID)
        else -> null
    }

    private fun writeDescriptor(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, byteArray: ByteArray): Single<ByteArray> {
        return enqueue(Type.WRITE_DESCRIPTOR.name, Single.defer {
            val error = notificationSetupError(characteristic)
            if (error != null) {
                Single.error(error)
            } else {
                replyRelay
                    .doOnSubscribe {
                        val descriptor = characteristic.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG_UUID)
                        L.debug(Ble.Device.Default, "$logLabel : writeDescriptor : ${characteristic.uuid} : ${byteArray.toHexString()}")
                        sendGattRequest(Type.WRITE_DESCRIPTOR.name) {
                            if (usesValueGattApi()) {
                                gatt.writeDescriptor(descriptor, byteArray).isGattRequestAccepted("writeDescriptor")
                            } else {
                                descriptor.value = byteArray
                                gatt.writeDescriptor(descriptor)
                            }
                        }
                    }
                    .filter { it.uuid == characteristic.uuid && it.type == Type.WRITE_DESCRIPTOR }
                    .map { it.valueOrThrow(); byteArray }
                    .firstOrError()
                    .retryOnCommunicationFailure()
            }
        })
    }

    override fun connectSppSocket(uuid: UUID?): Observable<BleSppSocket> = Observable.defer {
        BlePermissions.checkConnect(context, sdkInt)
        sppConnection.connect(uuid) {
            // sppConnection 이 stateLock 안에서 부른다. 같은 기기의 GATT 연결과는 동시에 쓰지 않는다
            val gattState = stateRelay.value!!
            if (gattState !is State.DISCONNECTED) {
                throw BleManager.InvalidConnectionStateException(gattState.connectionState, BleDevice.ConnectionState.DISCONNECTED)
            }
        }
    }

    /**
     * 연결 우선순위(연결 간격) 변경을 요청한다. ATT 작업이 아니라 링크 파라미터 요청이므로 작업 큐를 거치지 않는다.
     * Android 가 요청을 받으면 완료되며, 실제 연결 간격은 상대 기기와의 협상으로 정해진다.
     */
    override fun requestConnectionPriorityCompletable(priority: BleDevice.ConnectionPriority): Completable =
        getGattSingle().flatMapCompletable { gatt ->
            BlePermissions.checkConnect(context, sdkInt)
            L.debug(Ble.Device.Default, "$logLabel : requestConnectionPriority : $priority")
            if (gatt.requestConnectionPriority(priority.value)) {
                Completable.complete()
            } else {
                Completable.error(BleManager.GattRequestRejectedException(OPERATION_REQUEST_CONNECTION_PRIORITY))
            }
        }

    override fun requestMtuSingle(mtu: Int): Single<Int> {
        return enqueue(OPERATION_REQUEST_MTU, getGattSingle()
            .flatMap { gatt ->
                mtuRelay
                    .mergeWith(Completable.fromCallable {
                        L.debug(Ble.Device.Default, "$logLabel : requestMtu : $mtu")
                        sendGattRequest(OPERATION_REQUEST_MTU) { gatt.requestMtu(mtu) }
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
    private fun <T : Any> enqueue(name: String, operation: Single<T>): Single<T> = Single.create { emitter ->
        operationQueue.accept(QueuedOperation(operation.guardOperation(name), emitter))
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
                        if (error is BleManager.OperationTimeoutException) waitForGattRelease() else Completable.complete()
                    }
            }
        }
    }

    /**
     * API 33 부터 쓰기 값을 인자로 넘기고, 콜백도 값을 인자로 받는다. (`characteristic.value`/`descriptor.value` 와 그 setter 는 deprecated)
     * 공유된 value 필드는 notification 등 다른 콜백이 덮어쓸 수 있으므로, 쓸 수 있으면 새 API 를 쓴다.
     */
    @ChecksSdkIntAtLeast(api = Build.VERSION_CODES.TIRAMISU)
    private fun usesValueGattApi() = sdkInt >= Build.VERSION_CODES.TIRAMISU

    /** API 33 이상의 GATT 쓰기 호출은 Boolean 대신 BluetoothStatusCodes 를 반환한다. 거절 사유를 남기고 성공 여부로 바꾼다. */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun Int.isGattRequestAccepted(operation: String): Boolean {
        if (this != BluetoothStatusCodes.SUCCESS) {
            // ERROR_GATT_WRITE_REQUEST_BUSY(201) 는 이전 요청이 아직 처리 중이라는 뜻이며, 다른 거절과 같이 재시도한다
            L.warning(Ble.Device.Default, "$logLabel : $operation rejected : $this")
            return false
        }
        return true
    }

    /** GATT 요청을 보내고 콜백이 올 때까지 처리 중으로 표시한다. 요청이 거절되면(busy 등) [BleManager.GattRequestRejectedException]. */
    private fun sendGattRequest(operation: String, request: () -> Boolean) {
        gattRequestPendingRelay.accept(true)
        if (!request()) {
            gattRequestPendingRelay.accept(false)
            throw BleManager.GattRequestRejectedException(operation)
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
                    L.warning(Ble.Device.Default, "$logLabel : operation timed out, waiting for GATT release before next operation")
                }
            }
            .doFinally { gattRequestPendingRelay.accept(false) }
    }

    /** GATT 호출이 거절되면 잠시 후 다시 시도하고, 재시도 횟수를 넘기면 [BleManager.GattRequestRejectedException]으로 실패한다. */
    private fun <T : Any> Single<T>.retryOnCommunicationFailure(): Single<T> = retryWhen { errors ->
        var retryCount = 0
        errors.flatMap { throwable ->
            if (throwable is BleManager.GattRequestRejectedException && retryCount++ < MAX_RETRY_COUNT) {
                Flowable.timer(RETRY_DELAY_MILLIS, TimeUnit.MILLISECONDS)
            } else {
                Flowable.error(throwable)
            }
        }
    }

    /**
     * GATT 콜백을 기다리는 작업이 영원히 끝나지 않는 것을 막는다.
     * 작업 도중 연결이 끊기면 [BleManager.DisconnectedException](끊긴 사유 포함), [BleDevice.operationTimeoutMillis]를 넘기면
     * [BleManager.OperationTimeoutException]으로 종료한다.
     * 이미 연결이 끊긴 상태라면 GATT 호출 없이 바로 실패하도록 연결 끊김 감지를 먼저 구독한다.
     */
    private fun <T : Any> Single<T>.guardOperation(operation: String): Single<T> {
        val disconnectedSingle = stateRelay
            .filter { it !is State.CONNECTED }
            .firstOrError()
            .flatMap { Single.error<T>(it.disconnectedError()) }
        val timeoutMillis = bleDevice.operationTimeoutMillis
        return disconnectedSingle
            .ambWith(this)
            .timeout(timeoutMillis, TimeUnit.MILLISECONDS, Single.error(BleManager.OperationTimeoutException(operation, timeoutMillis)))
    }

    /** 연결되지 않은 상태 때문에 끝나는 작업/notification 의 에러. 끊긴 사유가 있으면 그것을 쓴다. (DISCONNECTING 은 이 앱이 끊는 중) */
    private fun State.disconnectedError(): Throwable = (this as? State.DISCONNECTED)?.cause ?: BleManager.DisconnectedException()

    /** 블루투스가 꺼지면 연결 콜백이 오지 않을 수 있으므로, 어떤 상태든 기다리지 않고 바로 정리한다. */
    internal fun onBluetoothDisabled() {
        val closed = synchronized(stateLock) {
            val state = stateRelay.value
            L.debug(Ble.Device.Default, "$logLabel : bluetooth disabled, state is ${state?.connectionState}")
            when (state) {
                is State.CONNECTED -> state.gatt
                // disconnect() 의 fallback 타이머를 기다리지 않는다. (타이머는 상태가 바뀌었으므로 아무것도 하지 않는다)
                is State.DISCONNECTING -> state.gatt
                is State.CONNECTING -> {
                    // 기다리던 connectCompletable 은 연결 타임아웃 전에 바로 실패한다
                    cancelConnecting(state.gatt, BleManager.BluetoothDisabledException())
                    null
                }
                else -> {
                    sppConnection.disconnect()
                    null
                }
            }?.also { gatt ->
                gatt.close()
                // 진행 중이던 작업과 notification 은 BluetoothDisabledException 으로 끝난다
                setState(State.DISCONNECTED(BleManager.BluetoothDisabledException()))
            }
        }
        if (closed != null) {
            bleDevice.onDisconnected()
        }
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

        // OperationTimeoutException 등에 쓰는 작업 이름 (나머지는 Type 의 이름을 쓴다)
        private const val OPERATION_CONNECT = "CONNECT"
        private const val OPERATION_REQUEST_MTU = "REQUEST_MTU"
        private const val OPERATION_REQUEST_CONNECTION_PRIORITY = "REQUEST_CONNECTION_PRIORITY"
        private const val OPERATION_GET_CHARACTERISTICS = "GET_CHARACTERISTICS"
    }
}

fun ByteArray.toHexString(): String {
    return joinToString(separator = "") { String.format("%02x", it) }
}

fun BluetoothDevice.deleteBond(): Boolean {
    return javaClass.getMethod("removeBond").invoke(this) as Boolean
}
