package com.algorigo.algorigoble2

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanRecord
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.algorigo.algorigoble2.impl.BleManagerEngineImpl
import com.algorigo.algorigoble2.impl.BlePermissions
import com.algorigo.algorigoble2.logging.Ble
import com.algorigo.algorigoble2.virtual.VirtualDevice
import com.algorigo.logger.L
import com.algorigo.logger.LogManager
import io.reactivex.rxjava3.core.Observable
import java.io.Closeable
import java.util.UUID
import java.util.logging.Logger

/**
 * 스캔, 기기 조회, 연결 상태를 관리한다.
 *
 * 라이브러리 manifest 가 앱에 합쳐지는 항목과 앱에서 바꾸는 방법:
 * - `uses-feature android.hardware.bluetooth_le` 는 required="false" 다. BLE 가 없는 기기에 설치되지 않게 하려면 앱 manifest 에
 *   `<uses-feature android:name="android.hardware.bluetooth_le" android:required="true" />` 를 선언한다.
 * - API 31+ 의 BLUETOOTH_SCAN 에 `usesPermissionFlags="neverForLocation"` 이 있어 위치 권한 없이 스캔하지만, Android 가 비콘 등 일부 광고를 걸러 낸다.
 *   스캔 결과로 위치를 알아내야 하는 앱은 플래그를 지우고 위치 권한을 API 31+ 에서도 선언한다. (그 경우 ACCESS_FINE_LOCATION 도 요청해야 한다)
 *   ```
 *   <uses-permission android:name="android.permission.BLUETOOTH_SCAN" tools:remove="android:usesPermissionFlags" />
 *   <uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" tools:remove="android:maxSdkVersion" />
 *   ```
 */
class BleManager(
    context: Context,
    private val delegate: BleDeviceDelegate = defaultBleDeviceDelegate,
    virtualDevices: Array<Pair<VirtualDevice, BleDevice>> = arrayOf()
) : Closeable {

    /** 엔진을 고르던 이전 생성자. 엔진은 하나뿐이라 [engine]은 쓰지 않는다. */
    @Deprecated("BleManager has only one engine. Remove the engine argument.", ReplaceWith("BleManager(context, delegate, virtualDevices)"))
    @Suppress("UNUSED_PARAMETER", "DEPRECATION")
    constructor(
        context: Context,
        delegate: BleDeviceDelegate,
        engine: Engine,
        virtualDevices: Array<Pair<VirtualDevice, BleDevice>> = arrayOf(),
    ) : this(context, delegate, virtualDevices)

    // 라이브러리가 던지는 예외. 모두 [BleException]의 하위 타입이다

    /**
     * 연결이 끊겨서 작업이 끝난 경우. 연결 중에 끊긴 connectCompletable, 진행 중이던 작업, notification 스트림이 이 예외로 끝난다.
     * [status]는 연결이 끊길 때 Android 가 알려 준 사유(onConnectionStateChange 의 status)다.
     * 예: 8 연결 타임아웃(supervision timeout), 19 상대 기기가 끊음, 22 이 기기(호스트)가 끊음, 62 연결 수립 실패, 133 GATT_ERROR.
     * 이 앱이 disconnect() 로 끊었거나 사유를 알 수 없으면 null 이다.
     */
    open class DisconnectedException @JvmOverloads constructor(val status: Int? = null, message: String? = null) :
        BleException(message ?: ("Disconnected" + (status?.let { " (status $it, 0x${Integer.toHexString(it)})" } ?: "")))
    /** 블루투스가 꺼져 있거나 꺼져서 연결/작업이 끝난 경우. 연결이 끊긴 경우의 하나이므로 [DisconnectedException]의 하위 타입이다. */
    class BluetoothDisabledException : DisconnectedException(null, "Bluetooth is disabled")
    /** 연결을 시작하지 못했거나 연결 과정(서비스 탐색 등)이 실패한 경우 */
    class ConnectFailedException(message: String) : BleException(message)
    /** 현재 연결 상태에서 할 수 없는 요청. 예: 이미 연결 중인데 connect, GATT 연결 중에 SPP 연결 */
    class InvalidConnectionStateException(val state: BleDevice.ConnectionState, val expected: BleDevice.ConnectionState) :
        BleException("Connection state is $state not $expected")
    /** GATT 콜백이 실패 status 를 돌려준 경우. [status]는 BluetoothGatt.GATT_* 값이다. (예: 0x05 인증 부족, 0x0F 암호화 부족) */
    open class GattOperationFailedException(val operation: String, val status: Int) :
        BleException("$operation failed with GATT status $status (0x${Integer.toHexString(status)})")
    /** MTU 변경 요청이 실패 status 로 끝난 경우 */
    class MtuChangeFailedException(status: Int) : GattOperationFailedException("REQUEST_MTU", status)
    /** Android BluetoothGatt 가 요청을 받지 않은 경우. (이전 요청이 처리 중이거나 연결이 정리되는 중) 여러 번 다시 시도한 뒤에도 거절되면 난다. */
    class GattRequestRejectedException(val operation: String) : BleException("$operation request was rejected by BluetoothGatt")
    /** 작업이 정해진 시간 안에 끝나지 않은 경우. [operation]은 CONNECT, INITIALIZE, READ_CHARACTERISTIC 등이다. */
    class OperationTimeoutException(val operation: String, val timeoutMillis: Long) :
        BleException("$operation timed out after ${timeoutMillis}ms")
    /** 연결된 기기에서 characteristic 을 찾지 못한 경우. [serviceUuid]가 null 이면 모든 서비스에서 찾았다. */
    class CharacteristicNotFoundException(val serviceUuid: UUID?, val characteristicUuid: UUID) :
        BleException("Characteristic $characteristicUuid not found" + (serviceUuid?.let { " in service $it" } ?: ""))
    /** characteristic 에 필요한 descriptor 가 없는 경우. 예를 들어 notification/indication 을 켜려는데 CCCD(0x2902)가 없을 때. */
    class DescriptorNotFoundException(val characteristicUuid: UUID, val descriptorUuid: UUID) :
        BleException("Descriptor $descriptorUuid not found in characteristic $characteristicUuid")
    /** characteristic 이 요청한 작업을 지원하지 않는 경우. [properties]는 BluetoothGattCharacteristic.PROPERTY_* 의 조합이다. */
    class IllegalCharacteristicPropertyException(val characteristicUuid: UUID, val properties: Int, val operation: String) :
        BleException("Characteristic $characteristicUuid does not support $operation (properties 0x${Integer.toHexString(properties)})")
    /** 이미 다른 종류로 구독 중인 characteristic 에 notification/indication 을 요청한 경우. 기존 구독은 유지된다. */
    class NotificationTypeConflictException(val characteristicUuid: UUID, val existing: BleDevice.NotificationType, val requested: BleDevice.NotificationType) :
        BleException("$characteristicUuid notification is already set up as $existing (requested $requested)")
    /** 작업에 필요한 런타임 권한이 없는 경우. [permissions]는 허용되지 않은 권한 이름이다. (예: android.permission.BLUETOOTH_CONNECT) */
    class PermissionDeniedException(val permissions: List<String>) :
        BleException("Permission denied: ${permissions.joinToString()}")
    /**
     * 본딩(페어링)이나 본딩 해제에 실패한 경우. [reason]은 Android 가 알려 준 실패 사유(숨겨진 BluetoothDevice.EXTRA_REASON)다.
     * 예: 1 인증 실패, 2 상대 기기가 거절, 3 사용자가 취소, 4 상대 기기 응답 없음, 6 인증 시간 초과. 알 수 없으면 null 이다.
     */
    class BondFailedException @JvmOverloads constructor(message: String = "Bond failed", val reason: Int? = null) : BleException(message)
    /** 스캔을 시작하지 못한 경우. [errorCode]는 ScanCallback.SCAN_FAILED_* 값이다. */
    class ScanFailedException(val errorCode: Int) : BleException("Scan failed with error code $errorCode")

    /** 이전에 엔진(RxAndroidBle/자체 구현)을 고르던 값. 지금은 자체 구현 하나뿐이다. */
    @Deprecated("BleManager has only one engine")
    enum class Engine {
        ALGORIGO_BLE,
    }

    abstract class BleDeviceDelegate {

        abstract fun createBleDevice(bluetoothDevice: BluetoothDevice, scanRecord: ScanRecord?): BleDevice?

        open fun getBleScanSettings(): BleScanSettings {
            return BleScanSettings
                .Builder()
                .build()
        }

        open fun getBleScanFilters(): Array<BleScanFilter> {
            return arrayOf()
        }
    }

    private val engine: BleManagerEngine = BleManagerEngineImpl(context.applicationContext, delegate)
    // 키는 대문자 MAC. 가상 기기는 MAC 대소문자와 관계없이 찾는다
    private val virtualDevices: Map<String, BleDevice>

    init {
        this.virtualDevices = virtualDevices.associate {
            Pair(
                normalizeMacAddress(it.first.deviceId),
                this.engine.initVirtualDevice(it.first, it.second.apply { virtual = true })
            )
        }
        L.debug(Ble, "virtualDevices: ${virtualDevices.joinToString { it.second.deviceId }}")
    }

    fun scanObservable(scanSettings: BleScanSettings, vararg scanFilters: BleScanFilter): Observable<List<Pair<BleDevice, ScanInfo>>> {
        val virtuals = virtualDevices.values.filter { device ->
            scanFilters.isEmpty() ||
                    scanFilters.firstOrNull { it.isOk(device.deviceName, device.deviceId) } != null
        }
        return engine.scanObservable(scanSettings, *scanFilters)
            .map { devices ->
                devices + virtuals.filter { virtual ->
                    devices.firstOrNull { it.first.deviceId == virtual.deviceId } == null
                }.map {
                    Pair(it, ScanInfo(0))
                }
            }
    }

    fun scanObservable(): Observable<List<Pair<BleDevice, ScanInfo>>> {
        return scanObservable(delegate.getBleScanSettings(), *delegate.getBleScanFilters())
    }
    // MAC 주소는 대소문자, 구분자(":", "-", 없음)와 관계없이 받는다. (Android 는 "AA:BB:CC:DD:EE:FF" 형식만 받는다)
    // 형식이 맞지 않는 주소이면 null 이다.

    fun getDevice(macAddress: String) = getDeviceInner<BleDevice>(macAddress)
    fun <T : BleDevice> getDevice(macAddress: String, clazz: Class<T>) = getDeviceInner(macAddress, clazz)
    // 가상 기기를 먼저 찾는다. 엔진을 먼저 조회하면 가상 기기의 MAC 이 유효한 주소일 때 존재하지 않는 실제 기기를 만들어 돌려주고,
    // 유효하지 않은 주소이면 조회할 때마다 getRemoteDevice 에러 로그가 남는다
    private fun virtualDevice(macAddress: String) = virtualDevices[normalizeMacAddress(macAddress)]

    private fun <T : BleDevice> getDeviceInner(macAddress: String, clazz: Class<T>? = null): T? {
        virtualDevice(macAddress)?.let { return it as? T }
        return engine.getDevice(normalizeMacAddress(macAddress), clazz) as? T
    }
    fun getBondedDevice(macAddress: String) = virtualDevice(macAddress)
        ?: engine.getBondedDevice(normalizeMacAddress(macAddress))
    fun getBondedDevices() = engine.getBondedDevices()
        .let { devices ->
            devices + virtualDevices.values.filter { virtual ->
                devices.none { it.deviceId == virtual.deviceId }
            }
        }
    fun getConnectedDevice(macAddress: String): BleDevice? {
        virtualDevice(macAddress)?.let { return if (it.connected) it else null }
        return engine.getConnectedDevice(normalizeMacAddress(macAddress))
    }
    fun getConnectedDevices(): List<BleDevice> {
        return engine.getConnectedDevices()
            .let { devices ->
                devices + virtualDevices.values.filter { virtual ->
                    virtual.connected && devices.none { it.deviceId == virtual.deviceId }
                }
            }
    }
    /** 이 BleManager 가 관리하는 기기들의 상태 변화. [BleDevice]와 같이 callback 스레드(io)에서 전달된다. */
    fun getConnectionStateObservable() = engine.getConnectionStateObservable().deliverToCallback()

    /** 블루투스가 켜져 있는지. 켜지는 중이나 꺼지는 중이면 false 다. 꺼져 있으면 [createEnableBluetoothIntent]로 켜 달라고 요청할 수 있다. */
    val isBluetoothEnabled: Boolean
        get() = engine.isBluetoothEnabled

    /**
     * 스캔에 필요한 시스템 서비스(블루투스, API 30 이하에서는 위치) 상태. 구독하면 현재 상태를 먼저 내보내고, 바뀔 때마다 내보낸다.
     * 에러로 끝나지 않는다. ([scanObservable]은 스캔할 수 없는 상태가 되면 [com.algorigo.algorigoble2.exception.SystemServiceException]으로 끝난다)
     * callback 스레드(io)에서 전달된다.
     */
    fun getScanAvailabilityObservable(): Observable<ScanAvailability> = engine.getScanAvailabilityObservable().deliverToCallback()

    /**
     * 블루투스 상태를 감지하려고 등록한 receiver 를 해제하고, 이 BleManager 가 연결한 기기(가상 기기 포함)를 끊는다.
     * BleManager 를 더 이상 쓰지 않을 때 부른다. 여러 번 불러도 된다.
     * (부르지 않고 버린 BleManager 도 GC 되며, 그 receiver 는 다음 블루투스 상태 broadcast 때 해제된다)
     */
    override fun close() {
        engine.close()
        virtualDevices.values
            .filter { it.connectionState != BleDevice.ConnectionState.DISCONNECTED || it.reconnectPending }
            .forEach { it.disconnect() }
    }

    companion object {
        private val defaultBleDeviceDelegate = object : BleDeviceDelegate() {
            override fun createBleDevice(bluetoothDevice: BluetoothDevice, scanRecord: ScanRecord?): BleDevice {
                return BleDevice()
            }
        }

        fun getLogger(): Logger {
            return LogManager.getLogger(Ble)
        }

        private val MAC_HEX_DIGITS = Regex("[0-9A-Fa-f]{12}")

        /**
         * "aa:bb:cc:dd:ee:ff", "AABBCCDDEEFF", "aa-bb-cc-dd-ee-ff" 를 Android 가 받는 "AA:BB:CC:DD:EE:FF" 로 바꾼다.
         * MAC 형식이 아니면 공백만 지우고 대문자로 바꾼다. (가상 기기의 id 등)
         */
        internal fun normalizeMacAddress(macAddress: String): String {
            val trimmed = macAddress.trim()
            val hex = trimmed.replace(":", "").replace("-", "")
            return if (MAC_HEX_DIGITS.matches(hex)) {
                hex.uppercase().chunked(2).joinToString(":")
            } else {
                trimmed.uppercase()
            }
        }

        /**
         * 이 기기(Android 버전)에서 스캔과 연결에 필요한 런타임 권한. 앱에서 요청할 때 쓴다.
         * (API 31+ BLUETOOTH_SCAN, BLUETOOTH_CONNECT / API 29~30 ACCESS_FINE_LOCATION / 그 이하 ACCESS_COARSE_LOCATION)
         * 권한이 없으면 해당 작업이 [PermissionDeniedException]으로 실패한다.
         */
        @JvmStatic
        fun getRequiredPermissions(): List<String> = requiredPermissions(Build.VERSION.SDK_INT)

        internal fun requiredPermissions(sdkInt: Int): List<String> =
            BlePermissions.requiredScanPermissions(sdkInt) + BlePermissions.requiredConnectPermissions(sdkInt)

        /** [getRequiredPermissions] 중 아직 허용되지 않은 권한. 비어 있으면 스캔과 연결에 필요한 권한이 모두 있다. */
        @JvmStatic
        fun getMissingPermissions(context: Context): List<String> = missingPermissions(context, Build.VERSION.SDK_INT)

        internal fun missingPermissions(context: Context, sdkInt: Int): List<String> =
            BlePermissions.missingScanPermissions(context, sdkInt) + BlePermissions.missingConnectPermissions(context, sdkInt)

        /**
         * 사용자에게 블루투스를 켜 달라고 묻는 화면의 Intent. ActivityResultLauncher(StartActivityForResult) 등으로 연다.
         * API 31 이상에서는 BLUETOOTH_CONNECT 권한이 있어야 열 수 있다. (앱이 블루투스를 직접 켜는 BluetoothAdapter.enable() 은 API 33 부터 막혀 있다)
         */
        @JvmStatic
        fun createEnableBluetoothIntent(): Intent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)

        /** 위치 설정 화면의 Intent. API 30 이하에서 위치가 꺼져 있으면([ScanAvailability.locationEnabled]가 false) 스캔할 수 없다. */
        @JvmStatic
        fun createLocationSettingsIntent(): Intent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
    }
}
