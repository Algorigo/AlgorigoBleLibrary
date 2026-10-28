package com.algorigo.algorigoble2

import com.algorigo.algorigoble2.logging.Ble
import com.algorigo.logger.L
import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.core.Observable
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
     * 초과하면 [java.util.concurrent.TimeoutException], 작업 중 연결이 끊기면 [BleManager.DisconnectedException]으로 종료된다.
     */
    var operationTimeoutMillis = DEFAULT_OPERATION_TIMEOUT_MILLIS

    fun bondCompletable() = engine.bondCompletable().deliverToCallback()
    fun unbondCompletable() = engine.unbondCompletable().deliverToCallback()

    /** 상태를 바꾼 스레드에서 바로 전달되는 상태 스트림. 라이브러리 내부에서만 쓴다. */
    internal open fun connectionStateObservableInternal(): Observable<ConnectionState> = engine.getConnectionStateObservable()

    open fun getConnectionStateObservable(): Observable<ConnectionState> = connectionStateObservableInternal().deliverToCallback()
    open fun connectCompletable(timeoutMillis: Long = 10000L): Completable =
        engine.connectCompletable(timeoutMillis).deliverToCallback()

    fun connect() {
        connectCompletable().subscribe({
            L.info(Ble.Device, "connected")
        }, {
            L.error(Ble.Device, "conntection fail", it)
        })
    }

    fun disconnect() = engine.disconnect()

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
    }
}
