package com.algorigo.algorigoble2

import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanRecord
import android.content.Context
import android.util.Log
import com.algorigo.algorigoble2.impl.BleManagerEngineImpl
import com.algorigo.algorigoble2.logging.Ble
import com.algorigo.algorigoble2.virtual.VirtualDevice
import com.algorigo.logger.L
import com.algorigo.logger.LogManager
import io.reactivex.rxjava3.core.Observable
import java.util.UUID
import java.util.logging.Logger

class BleManager(
    context: Context,
    private val delegate: BleDeviceDelegate = defaultBleDeviceDelegate,
    engine: Engine = Engine.ALGORIGO_BLE,
    virtualDevices: Array<Pair<VirtualDevice, BleDevice>> = arrayOf()
) {

    class BondFailedException : Exception()
    class DisconnectedException : Exception()
    class ConnectFailedException(message: String) : Exception(message)
    class MtuChangeFailedException(val status: Int) : Exception("MTU change failed with status $status")
    /** GATT 콜백이 실패 status 를 돌려준 경우. [status]는 BluetoothGatt.GATT_* 값이다. (예: 0x05 인증 부족, 0x0F 암호화 부족) */
    class GattOperationFailedException(val operation: String, val status: Int) :
        Exception("$operation failed with GATT status $status (0x${Integer.toHexString(status)})")
    /**
     * 작업에 필요한 런타임 권한이 없는 경우. [permissions]는 허용되지 않은 권한 이름이다. (예: android.permission.BLUETOOTH_CONNECT)
     * 플랫폼이 던지던 SecurityException 을 처리하던 코드도 그대로 동작하도록 SecurityException 을 상속한다.
     */
    class PermissionDeniedException(val permissions: List<String>) :
        SecurityException("Permission denied: ${permissions.joinToString()}")
    /**
     * 연결된 기기에서 characteristic 을 찾지 못한 경우. [serviceUuid]가 null 이면 모든 서비스에서 찾았다.
     * 이전에 던지던 IllegalStateException 을 처리하던 코드도 그대로 동작하도록 IllegalStateException 을 상속한다.
     */
    class CharacteristicNotFoundException(val serviceUuid: UUID?, val characteristicUuid: UUID) :
        IllegalStateException("Characteristic $characteristicUuid not found" + (serviceUuid?.let { " in service $it" } ?: ""))
    /**
     * characteristic 에 필요한 descriptor 가 없는 경우. 예를 들어 notification/indication 을 켜려는데 CCCD(0x2902)가 없을 때.
     * 같은 종류인 [CharacteristicNotFoundException]과 같이 IllegalStateException 을 상속한다.
     */
    class DescriptorNotFoundException(val characteristicUuid: UUID, val descriptorUuid: UUID) :
        IllegalStateException("Descriptor $descriptorUuid not found in characteristic $characteristicUuid")

    enum class Engine {
//        RX_ANDROID_BLE,
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

    data class ConnectionStateData(
        val bleDevice: BleDevice,
        val connectionState: BleDevice.ConnectionState,
    )

    private val engine: BleManagerEngine
    private val virtualDevices: Map<String, BleDevice>

    init {
        when (engine) {
//            Engine.RX_ANDROID_BLE -> this.engine = RxAndroidBleEngine(context.applicationContext, delegate)
            Engine.ALGORIGO_BLE -> this.engine = BleManagerEngineImpl(context.applicationContext, delegate)
        }
        this.virtualDevices = virtualDevices.associate {
            Pair(
                it.first.deviceId,
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
    fun getDevice(macAddress: String) = getDeviceInner<BleDevice>(macAddress)
    fun <T : BleDevice> getDevice(macAddress: String, clazz: Class<T>) = getDeviceInner(macAddress, clazz)
    private fun <T : BleDevice> getDeviceInner(macAddress: String, clazz: Class<T>? = null): T? {
        return (engine.getDevice(macAddress, clazz) as? T)
            ?: virtualDevices[macAddress] as? T
    }
    fun getBondedDevice(macAddress: String) = engine.getBondedDevice(macAddress)
        ?: virtualDevices[macAddress]
    fun getBondedDevices() = engine.getBondedDevices()
        .let { devices ->
            devices + virtualDevices.values.filter { virtual ->
                devices.none { it.deviceId == virtual.deviceId }
            }
        }
    fun getConnectedDevice(macAddress: String): BleDevice? {
        return engine.getConnectedDevice(macAddress)
            ?: virtualDevices[macAddress]?.let { if (it.connected) it else null }
    }
    fun getConnectedDevices(): List<BleDevice> {
        return engine.getConnectedDevices()
            .let { devices ->
                devices + virtualDevices.values.filter { virtual ->
                    virtual.connected && devices.none { it.deviceId == virtual.deviceId }
                }
            }
    }
    fun getConnectionStateObservable() = engine.getConnectionStateObservable()

    companion object {
        private val defaultBleDeviceDelegate = object : BleDeviceDelegate() {
            override fun createBleDevice(bluetoothDevice: BluetoothDevice, scanRecord: ScanRecord?): BleDevice {
                return BleDevice()
            }
        }

        fun getLogger(): Logger {
            return LogManager.getLogger(Ble)
        }
    }
}
