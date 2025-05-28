package com.algorigo.algorigoble2

import android.bluetooth.BluetoothGattDescriptor
import com.algorigo.algorigoble2.logging.Ble
import com.algorigo.logger.L
import io.reactivex.rxjava3.core.Single
import no.nordicsemi.android.wifi.provisioner.ble.internal.ConnectionStatus
import no.nordicsemi.kotlin.wifi.provisioner.domain.ScanRecordDomain
import okio.ByteString
import java.util.UUID
import java.util.concurrent.TimeUnit

open class BleDevice {

    enum class ConnectionState(var status: String) {
        CONNECTING("CONNECTING"),
        CONNECTED("CONNECTED"),
        DISCONNECTED("DISCONNECTED"),
        DISCONNECTING("DISCONNECTING"),
        SPP_CONNECTING("SPP CONNECTING"),
        SPP_CONNECTED("SPP CONNECTED"),
    }

    enum class NotificationType(val byteArray: ByteArray) {
        NOTIFICATION(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE),
        INDICATION(BluetoothGattDescriptor.ENABLE_INDICATION_VALUE),
    }

    data class ProvisioningStatus(
        val version: Int,
        val status: ConnectionStatus?,
        val ssid: String? = null,
        val bssid: String? = null,
        val auth: String? = null,
        val channel: Int? = null,
    )

    class ProvisioningScanResult(
        internal val scanRecord: ScanRecordDomain,
    ) {
        val rssi: Int?
            get() = scanRecord.rssi
        val ssid: String?
            get() = scanRecord.wifiInfo?.ssid
        val bssid: ByteString?
            get() = scanRecord.wifiInfo?.bssid
        val bandId: Int?
            get() = scanRecord.wifiInfo?.band?.id
        val channel: Int?
            get() = scanRecord.wifiInfo?.channel
        val authModeDomainId: Int?
            get() = scanRecord.wifiInfo?.authModeDomain?.id
        val macAddress: String?
            get() = scanRecord.wifiInfo?.macAddress
    }

    internal lateinit var engine: BleDeviceEngine

    internal fun initEngine(engine: BleDeviceEngine) {
        engine.bleDevice = this
        this.engine = engine
    }

    val deviceId: String
        get() = engine.deviceId

    val deviceName: String?
        get() = engine.deviceName

    val bonded: Boolean
        get() = engine.bonded

    val connected: Boolean
        get() = engine.isConnected()

    val connectionState: ConnectionState
        get() = getConnectionStateObservable().blockingFirst()

    var virtual = false
        internal set

    fun bondCompletable() = engine.bondCompletable()
    fun unbondCompletable() = engine.unbondCompletable()

    open fun getConnectionStateObservable() = engine.getConnectionStateObservable()
    open fun connectCompletable(timeoutMillis: Long = 10000L) =
        engine.connectCompletable(timeoutMillis)

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

    fun getCharacteristicsSingle() = engine.getCharacteristicsSingle()

    fun readCharacteristicSingle(characteristicUuid: UUID) =
        engine.readCharacteristicSingle(characteristicUuid)

    fun writeCharacteristicSingle(characteristicUuid: UUID, byteArray: ByteArray) =
        engine.writeCharacteristicSingle(characteristicUuid, byteArray)

    fun setupNotification(type: NotificationType, characteristicUuid: UUID) =
        engine.setupNotification(type, characteristicUuid)

    fun connectSppSocket(uuid: UUID? = null) = engine.connectSppSocket(uuid)

    fun initializeProvisioning() = engine.initializeProvisioning()
    fun scanWifiList() = engine.scanWifiList()
        .scan(listOf<ProvisioningScanResult>()) { acc, scanRecordDomain ->
            L.verbose(Ble.Device.Provisioning, "scanRecordDomain: $scanRecordDomain")
            if (acc.firstOrNull { it.scanRecord.wifiInfo?.ssid == scanRecordDomain.wifiInfo?.ssid } != null ||
                scanRecordDomain.wifiInfo?.ssid.isNullOrEmpty()) {
                acc // Skip duplicates or records without SSID
            } else {
                acc + ProvisioningScanResult(scanRecordDomain)
            }
        }
        .filter { it.isNotEmpty() }
        .debounce(500, TimeUnit.MILLISECONDS)
        .firstOrError()
        .doFinally {
            engine.stopScanWifiList()
                .subscribe({
                    L.debug(Ble.Device.Provisioning, "Scan stopped successfully")
                }, {
                    L.warning(Ble.Device.Provisioning, "Failed to stop scan", it)
                })
        }
    fun startProvisioning(scanResult: ProvisioningScanResult, password: String) =
        Single.just(scanResult)
            .map { it.scanRecord.wifiInfo!! }
            .flatMapCompletable {
                engine.startProvisioning(it, password)
            }
    fun cleanProvisioning() = engine.cleanProvisioning()
    fun getProvisioningStatus() = engine.getProvisioningStatus()
        .map { map ->
            L.verbose(Ble.Device.Provisioning, "Provisioning status map: $map")
            val statusMap = map["status"] as Map<String, *>
            val state = (statusMap["state"] as? String)
                ?.let { ConnectionStatus.valueOf(it.uppercase()) }
            val provisioningInfo = statusMap["provisioningInfo"] as? Map<String, *>
            provisioningInfo?.let {
                ProvisioningStatus(
                    version = map["version"] as Int,
                    status = state,
                    ssid = it["ssid"] as? String,
                    bssid = it["bssid"] as? String,
                    auth = it["auth"] as? String,
                    channel = it["channel"] as? Int,
                )
            } ?: ProvisioningStatus(
                version = map["version"] as Int,
                status = state,
            )
        }

    override fun toString(): String {
        return "${javaClass.simpleName} $deviceName($deviceId)"
    }

    override fun equals(other: Any?): Boolean {
        return other is BleDevice && this.deviceId == other.deviceId
    }

    companion object {
        private val TAG = BleDevice::class.java.simpleName
    }
}
