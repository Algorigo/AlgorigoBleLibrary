package com.algorigo.algorigoble2

import android.bluetooth.BluetoothGatt
import com.algorigo.algorigoble2.logging.Ble
import com.algorigo.logger.L
import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.core.Flowable
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.observers.DisposableCompletableObserver
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * BLE 기기 하나. 사용하는 기기에 맞게 상속해서 쓴다.
 *
 * 스레드: 이 클래스가 돌려주는 Observable/Single/Completable 의 결과(연결 상태, 연결 완료, read/write/MTU 결과,
 * notification 등)는 GATT 바인더 스레드나 상태를 바꾼 스레드가 아니라 [io.reactivex.rxjava3.schedulers.Schedulers.io] 스레드에서 전달된다.
 * 구독마다 하나의 스레드에서 순서대로 전달되며, 구독자가 오래 걸리는 처리를 해도 다른 GATT 콜백이나 다음 작업이 지연되지 않는다.
 * 서로 다른 구독 사이의 순서(예: 마지막 notification 과 DISCONNECTED 상태)는 보장하지 않는다.
 * UI 를 바꾸려면 observeOn(AndroidSchedulers.mainThread()) 를 붙여야 한다. (스캔 결과는 Android 와 같이 main thread 에서 전달된다)
 */
open class BleDevice {

    // 기존 값의 순서(ordinal)를 바꾸지 않도록 새 상태는 끝에 추가한다
    enum class ConnectionState(val status: String) {
        CONNECTING("CONNECTING"),
        CONNECTED("CONNECTED"),
        DISCONNECTED("DISCONNECTED"),
        DISCONNECTING("DISCONNECTING"),
        SPP_CONNECTING("SPP CONNECTING"),
        SPP_CONNECTED("SPP CONNECTED"),
        /** [InitializableBleDevice]가 GATT 연결 후 initializeCompletable() 을 진행 중인 상태 */
        INITIALIZING("INITIALIZING"),
    }

    // CCCD 값. BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE / ENABLE_INDICATION_VALUE 와 동일 (Bluetooth Core Spec 정의값)
    enum class NotificationType(val byteArray: ByteArray) {
        NOTIFICATION(byteArrayOf(0x01, 0x00)),
        INDICATION(byteArrayOf(0x02, 0x00)),
    }

    /**
     * 연결 우선순위. 연결 간격(connection interval)을 정해서 처리량, 지연, 전력 사용에 영향을 준다.
     * 실제 간격은 Android 버전/기기와 상대 기기와의 협상에 따라 다르다.
     */
    enum class ConnectionPriority(val value: Int) {
        /** 기본값. 중간 정도의 연결 간격 */
        BALANCED(BluetoothGatt.CONNECTION_PRIORITY_BALANCED),
        /** 짧은 연결 간격. 처리량이 높고 지연이 짧지만 전력을 더 쓴다. 대량 전송(펌웨어 업데이트 등)에 쓴다 */
        HIGH(BluetoothGatt.CONNECTION_PRIORITY_HIGH),
        /** 긴 연결 간격. 전력을 아끼지만 처리량이 낮고 지연이 길다 */
        LOW_POWER(BluetoothGatt.CONNECTION_PRIORITY_LOW_POWER),
    }

    enum class WriteType {
        /** characteristic 이 WRITE 를 지원하면 응답 있는 쓰기, WRITE NO RESPONSE 만 지원하면 응답 없는 쓰기 */
        DEFAULT,
        /** 응답 있는 쓰기(Write Request). characteristic 이 WRITE 를 지원해야 한다 */
        WITH_RESPONSE,
        /** 응답 없는 쓰기(Write Command). characteristic 이 WRITE NO RESPONSE 를 지원해야 한다 */
        WITHOUT_RESPONSE,
    }

    internal lateinit var engine: BleDeviceEngine

    internal fun initEngine(engine: BleDeviceEngine) {
        engine.bleDevice = this
        this.engine = engine
    }

    val deviceId: String
        get() = engine.deviceId

    /** API 31 이상에서 BLUETOOTH_CONNECT 권한이 없으면 [BleManager.PermissionDeniedException] */
    val deviceName: String?
        get() = engine.deviceName

    /** API 31 이상에서 BLUETOOTH_CONNECT 권한이 없으면 [BleManager.PermissionDeniedException] */
    val bonded: Boolean
        get() = engine.bonded

    val connected: Boolean
        get() = engine.isConnected()

    val connectionState: ConnectionState
        get() = connectionStateObservableInternal().blockingFirst()

    var virtual = false
        internal set

    /**
     * read/write/notification 설정/MTU 요청 등 GATT 작업 하나의 최대 대기 시간.
     * 초과하면 [BleManager.OperationTimeoutException], 작업 중 연결이 끊기면 [BleManager.DisconnectedException]으로 종료된다.
     */
    var operationTimeoutMillis = DEFAULT_OPERATION_TIMEOUT_MILLIS

    /**
     * true 이면 [connectCompletable]이 BluetoothDevice.connectGatt 의 autoConnect 로 연결한다.
     * Android 가 기기가 보일 때까지 백그라운드에서 낮은 전력으로 기다렸다가 연결하므로, 바로 연결하는 것보다 오래 걸릴 수 있다.
     * (connectCompletable 의 timeoutMillis 는 그대로 적용되므로 넉넉하게 준다)
     * 본딩하지 않은 기기가 resolvable private address(주기적으로 바뀌는 주소)로 광고하면 Android 가 autoConnect 를 받지 않아
     * 바로 [BleManager.DisconnectedException](status 135, 0x87)으로 실패한다. 이런 기기는 본딩하거나 autoConnect 없이 연결한다.
     */
    var autoConnect = false

    /**
     * [connectCompletable]이 연결 도중 링크가 끊겨(status 133, 직전 연결이 정리되는 중 등) 실패했을 때 다시 시도할 횟수. 기본값 0 은 다시 시도하지 않는다.
     * 연결 타임아웃, 블루투스 꺼짐, 권한 없음, 이미 연결된 상태 등은 다시 시도하지 않는다.
     */
    var connectRetryCount = 0

    /** 연결을 다시 시도하기 전, 그리고 [autoReconnect]로 다시 연결하기 전에 기다리는 시간 */
    var connectRetryDelayMillis = DEFAULT_CONNECT_RETRY_DELAY_MILLIS

    /**
     * true 이면 [connectCompletable]로 연결된 뒤 [disconnect]를 부르지 않았는데 연결이 끊겼을 때(상대 기기가 끊음, 신호 약함 등) 다시 연결한다.
     * 마지막 connectCompletable 과 같은 timeoutMillis 와 [autoConnect]로 연결하고, 실패하면 [connectRetryDelayMillis]마다 계속 다시 시도한다.
     * [InitializableBleDevice]는 다시 연결할 때도 initializeCompletable() 을 실행한다. (connectCompletable 을 override 해서 추가한 단계는 실행하지 않는다)
     *
     * 재연결은 [disconnect], [connectCompletable] 호출, false 로 바꾸기, BleManager.close() 로 멈춘다.
     * 블루투스가 꺼지면 기다렸다가 다시 켜지면 연결한다. 권한이 없는 등 다시 시도해도 소용없는 에러면 멈춘다.
     * 재연결 중의 상태는 [getConnectionStateObservable]에서 DISCONNECTED/CONNECTING 으로 보인다.
     */
    var autoReconnect = false
        set(value) {
            field = value
            if (!value) {
                cancelReconnect(keepTarget = true)
            }
        }

    private val reconnectLock = Any()
    // 다시 연결할 연결의 timeoutMillis. connectCompletable 로 연결되면 정해지고, disconnect() 하면 지운다
    private var reconnectTimeoutMillis: Long? = null
    private var reconnectTask: DisposableCompletableObserver? = null

    fun bondCompletable() = engine.bondCompletable().deliverToCallback()
    fun unbondCompletable() = engine.unbondCompletable().deliverToCallback()

    /** 상태를 바꾼 스레드에서 바로 전달되는 상태 스트림. 라이브러리 내부에서만 쓴다. */
    internal open fun connectionStateObservableInternal(): Observable<ConnectionState> = engine.getConnectionStateObservable()

    open fun getConnectionStateObservable(): Observable<ConnectionState> = connectionStateObservableInternal().deliverToCallback()

    /**
     * 연결한다. [timeoutMillis]는 연결 시도 한 번의 최대 대기 시간이다. ([connectRetryCount]로 다시 시도하면 시도마다 적용된다)
     * 진행 중인 자동 재연결([autoReconnect])이 있으면 멈추고 새로 연결한다. 연결에 실패하면 자동 재연결도 하지 않는다.
     */
    open fun connectCompletable(timeoutMillis: Long = 10000L): Completable = Completable.defer {
        val previousTimeoutMillis = cancelReconnect(keepTarget = false)
        connectWithRetry(timeoutMillis, autoConnect, connectRetryCount, connectRetryDelayMillis)
            .doOnComplete {
                synchronized(reconnectLock) {
                    reconnectTimeoutMillis = timeoutMillis
                }
            }
            .doOnError {
                // 이미 연결돼 있어서 실패했다면 기존 연결은 그대로이므로 그 연결의 재연결 설정을 되돌린다
                if (it is BleManager.InvalidConnectionStateException && previousTimeoutMillis != null) {
                    synchronized(reconnectLock) {
                        if (reconnectTimeoutMillis == null) {
                            reconnectTimeoutMillis = previousTimeoutMillis
                        }
                    }
                }
            }
    }.deliverToCallback()

    /** 연결 시도 한 번. [InitializableBleDevice]는 여기에 초기화를 붙인다. */
    internal open fun connectOnce(timeoutMillis: Long, autoConnect: Boolean): Completable =
        engine.connectCompletable(timeoutMillis, autoConnect)

    private fun connectWithRetry(timeoutMillis: Long, autoConnect: Boolean, retryCount: Int, retryDelayMillis: Long): Completable {
        var retried = 0
        return connectOnce(timeoutMillis, autoConnect)
            .retryWhen { errors ->
                errors.flatMap { error ->
                    if (isConnectRetryable(error) && retried++ < retryCount) {
                        L.warning(Ble.Device, "$this : connect failed, retry ${retried}/$retryCount", error)
                        waitBeforeConnect(retryDelayMillis)
                    } else {
                        Flowable.error(error)
                    }
                }
            }
    }

    // 이전 연결이 완전히 끊긴 뒤(DISCONNECTING 이 끝난 뒤) 잠시 기다린다
    private fun waitBeforeConnect(delayMillis: Long): Flowable<Long> =
        connectionStateObservableInternal()
            .filter { it == ConnectionState.DISCONNECTED }
            .firstElement()
            .ignoreElement()
            .andThen(Flowable.timer(delayMillis, TimeUnit.MILLISECONDS))

    /** 엔진이 연결이 끊겼다고(연결 시도 실패 포함) 알린다. */
    internal fun notifyDisconnected() {
        onDisconnected()
        startReconnect()
    }

    /** BleManager 가 블루투스가 다시 켜졌다고 알린다. 블루투스가 꺼져서 멈춘 재연결을 다시 시작한다. */
    internal fun notifyBluetoothEnabled() {
        startReconnect()
    }

    /** 자동 재연결을 기다리거나 진행 중인지. BleManager.close() 가 멈출 기기를 고를 때 쓴다. */
    internal val reconnectPending: Boolean
        get() = synchronized(reconnectLock) { reconnectTimeoutMillis != null }

    private fun startReconnect() {
        val task = synchronized(reconnectLock) {
            val timeoutMillis = reconnectTimeoutMillis
            if (!autoReconnect || timeoutMillis == null || reconnectTask != null) {
                return
            }
            val delayMillis = connectRetryDelayMillis
            val autoConnect = autoConnect
            L.info(Ble.Device, "$this : reconnect after ${delayMillis}ms")
            val completable = waitBeforeConnect(delayMillis).ignoreElements()
                .andThen(Completable.defer { connectOnce(timeoutMillis, autoConnect) }
                    .retryWhen { errors ->
                        errors.flatMap { error ->
                            if (isReconnectRetryable(error)) {
                                L.warning(Ble.Device, "$this : reconnect failed, retry after ${delayMillis}ms", error)
                                waitBeforeConnect(delayMillis)
                            } else {
                                Flowable.error(error)
                            }
                        }
                    })
            object : DisposableCompletableObserver() {
                override fun onComplete() {
                    L.info(Ble.Device, "${this@BleDevice} : reconnected")
                    finishReconnect(this, keepTarget = true)
                }

                override fun onError(e: Throwable) {
                    // 블루투스가 다시 켜지면 이어서 연결한다
                    val bluetoothDisabled = e is BleManager.BluetoothDisabledException
                    L.error(Ble.Device, "${this@BleDevice} : reconnect stopped${if (bluetoothDisabled) " until bluetooth is enabled" else ""}", e)
                    finishReconnect(this, keepTarget = bluetoothDisabled)
                }
            }.also { reconnectTask = it }
                .let { it to completable }
        }
        // 잠금 밖에서 구독한다. (연결 취소가 엔진의 상태 잠금을 잡으므로 잠금 순서가 뒤집히지 않게 한다)
        task.second.subscribe(task.first)
    }

    private fun finishReconnect(task: DisposableCompletableObserver, keepTarget: Boolean) {
        synchronized(reconnectLock) {
            if (reconnectTask === task) {
                reconnectTask = null
                if (!keepTarget) {
                    reconnectTimeoutMillis = null
                }
            }
        }
    }

    /**
     * 진행 중인 재연결을 멈춘다. [keepTarget]이 false 이면 이후에 끊겨도 다시 연결하지 않는다.
     * 지우기 전의 재연결 대상(timeoutMillis)을 돌려준다.
     */
    private fun cancelReconnect(keepTarget: Boolean): Long? {
        val (task, timeoutMillis) = synchronized(reconnectLock) {
            val previous = reconnectTask to reconnectTimeoutMillis
            reconnectTask = null
            if (!keepTarget) {
                reconnectTimeoutMillis = null
            }
            previous
        }
        // 연결 중이던 재연결을 해제하면 엔진이 연결을 취소한다. 잠금 밖에서 해제한다
        task?.dispose()
        return timeoutMillis
    }

    fun connect() {
        connectCompletable().subscribe({
            L.info(Ble.Device, "connected")
        }, {
            L.error(Ble.Device, "conntection fail", it)
        })
    }

    /** 연결을 끊는다. 자동 재연결([autoReconnect])도 멈춘다. */
    fun disconnect() {
        cancelReconnect(keepTarget = false)
        engine.disconnect()
    }

    open fun onDisconnected() {

    }

    fun getCharacteristicsSingle() = engine.getCharacteristicsSingle().deliverToCallback()

    // characteristic 을 UUID 로만 찾으면 여러 서비스에 같은 UUID 가 있을 때 처음 찾은 것을 쓴다. 서비스를 지정하려면 serviceUuid 를 받는 함수를 쓴다.
    // 찾지 못하면 BleManager.CharacteristicNotFoundException 으로 실패한다

    fun readCharacteristicSingle(characteristicUuid: UUID) =
        engine.readCharacteristicSingle(null, characteristicUuid).deliverToCallback()

    fun readCharacteristicSingle(serviceUuid: UUID, characteristicUuid: UUID) =
        engine.readCharacteristicSingle(serviceUuid, characteristicUuid).deliverToCallback()

    @JvmOverloads
    fun writeCharacteristicSingle(characteristicUuid: UUID, byteArray: ByteArray, writeType: WriteType = WriteType.DEFAULT) =
        engine.writeCharacteristicSingle(null, characteristicUuid, byteArray, writeType).deliverToCallback()

    @JvmOverloads
    fun writeCharacteristicSingle(serviceUuid: UUID, characteristicUuid: UUID, byteArray: ByteArray, writeType: WriteType = WriteType.DEFAULT) =
        engine.writeCharacteristicSingle(serviceUuid, characteristicUuid, byteArray, writeType).deliverToCallback()

    fun setupNotification(type: NotificationType, characteristicUuid: UUID) =
        deliverNotification(engine.setupNotification(type, null, characteristicUuid))

    fun setupNotification(type: NotificationType, serviceUuid: UUID, characteristicUuid: UUID) =
        deliverNotification(engine.setupNotification(type, serviceUuid, characteristicUuid))

    // 설정 완료(바깥)와 notification 값(안쪽) 모두 callback 스레드로 전달한다
    private fun deliverNotification(setup: Observable<Observable<ByteArray>>): Observable<Observable<ByteArray>> =
        setup.map { it.deliverToCallback() }.deliverToCallback()

    fun connectSppSocket(uuid: UUID? = null) = engine.connectSppSocket(uuid).deliverToCallback()

    /**
     * MTU 변경을 요청하고, 기기와 협상된 MTU 값을 반환한다.
     * 협상된 값은 요청한 값과 다를 수 있다.
     */
    fun requestMtuSingle(mtu: Int = 517) = engine.requestMtuSingle(mtu).deliverToCallback()

    fun setMtuCompletable(mtu: Int = 517) = requestMtuSingle(mtu).ignoreElement()

    /**
     * 연결된 기기의 현재 신호 세기(RSSI, dBm)를 읽는다. 다른 GATT 작업과 같은 큐에서 하나씩 처리된다.
     * 연결되지 않았으면 [BleManager.DisconnectedException], 실패 status 로 끝나면 [BleManager.GattOperationFailedException].
     */
    fun readRssiSingle(): Single<Int> = engine.readRssiSingle().deliverToCallback()

    /**
     * 연결 우선순위(연결 간격) 변경을 요청한다. Android 가 요청을 받으면 완료된다.
     * 새 간격은 상대 기기와 협상한 뒤에 적용되므로 완료 직후에는 아직 이전 간격일 수 있다.
     * (긴 간격에서 짧은 간격으로 바꿀 때 실기기에서 수 초가 걸렸다)
     * 연결되지 않았으면 [BleManager.DisconnectedException], Android 가 거절하면 [BleManager.GattRequestRejectedException].
     */
    fun requestConnectionPriorityCompletable(priority: ConnectionPriority): Completable =
        engine.requestConnectionPriorityCompletable(priority).deliverToCallback()

    // engine 은 생성 후 initEngine() 으로 붙는다. 그 전에 equals/hashCode/toString 이 불려도 예외가 나지 않게 한다
    private val deviceIdOrNull: String?
        get() = if (::engine.isInitialized) engine.deviceId else null

    override fun toString(): String {
        if (!::engine.isInitialized) {
            return "${javaClass.simpleName}(engine not initialized)"
        }
        // 로그나 문자열 템플릿에서 쓰이므로 이름을 읽을 권한(API 31+ BLUETOOTH_CONNECT)이 없어도 예외를 내지 않는다
        val name = try {
            deviceName
        } catch (e: BleManager.PermissionDeniedException) {
            "(permission denied)"
        } catch (e: SecurityException) {
            "(permission denied)"
        }
        return "${javaClass.simpleName} $name($deviceId)"
    }

    /** 같은 기기(deviceId)이면 같다. engine 이 붙기 전에는 자기 자신과만 같다. */
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is BleDevice) return false
        val id = deviceIdOrNull ?: return false
        return id == other.deviceIdOrNull
    }

    // equals 와 같은 기준이어야 HashSet/HashMap/distinct() 에서 같은 기기가 하나로 취급된다.
    // engine 이 붙기 전에 hash 기반 컬렉션에 넣으면 붙은 뒤 hash 가 바뀌므로 그 전에 넣지 않아야 한다
    override fun hashCode(): Int {
        return deviceIdOrNull?.hashCode() ?: System.identityHashCode(this)
    }

    companion object {
        private val TAG = BleDevice::class.java.simpleName

        const val DEFAULT_OPERATION_TIMEOUT_MILLIS = 10_000L
        const val DEFAULT_CONNECT_RETRY_DELAY_MILLIS = 1_000L

        // GATT_ILLEGAL_PARAMETER. Android 가 연결 요청 자체를 받지 않은 경우 (예: autoConnect 로 본딩하지 않은 RPA 기기에 연결)
        private const val GATT_ILLEGAL_PARAMETER = 0x87

        // 연결 도중 링크가 끊겨 실패한 경우. (status 133, 직전 연결이 정리되는 중 등) 다시 시도하면 연결될 수 있다
        internal fun isConnectRetryable(error: Throwable): Boolean = when (error) {
            is BleManager.BluetoothDisabledException -> false
            // 같은 요청은 다시 해도 같은 이유로 바로 실패한다
            is BleManager.DisconnectedException -> error.status != GATT_ILLEGAL_PARAMETER
            // discoverServices 호출/서비스 탐색 실패. (초기화 중의 read/write 실패 status 는 다시 해도 같으므로 제외한다)
            is BleManager.ConnectFailedException -> true
            is BleManager.GattOperationFailedException -> error.operation == BleDeviceEngine.OPERATION_DISCOVER_SERVICES
            else -> false
        }

        // 재연결은 기기가 다시 보일 때까지 계속하므로 연결 타임아웃도 다시 시도한다
        internal fun isReconnectRetryable(error: Throwable): Boolean =
            isConnectRetryable(error) || error is BleManager.OperationTimeoutException
    }
}
