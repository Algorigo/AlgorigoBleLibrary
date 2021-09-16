package com.algorigo.algorigoble2

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.os.Build
import android.os.ParcelUuid
import androidx.annotation.RequiresApi
import java.util.regex.Pattern

class BleScanFilter(val name: String?, val namePattern: Pattern?,
                    val deviceAddress: String?,
                    val uuid: ParcelUuid?, val uuidMask: ParcelUuid?,
                    val solicitationUuid: ParcelUuid?, val solicitationUuidMask: ParcelUuid?,
                    val serviceDataUuid: ParcelUuid?, val serviceData: ByteArray?, val serviceDataMask: ByteArray?,
                    val manufacturerId: Int?, val manufacturerData: ByteArray?, val manufacturerDataMask: ByteArray?,
                    private val rssiThreshold: Int?) {

    class Builder {
        private var deviceName: String? = null
        private var deviceNamePattern: Pattern? = null

        private var deviceAddress: String? = null

        private var serviceUuid: ParcelUuid? = null
        private var uuidMask: ParcelUuid? = null

        private var serviceSolicitationUuid: ParcelUuid? = null
        private var serviceSolicitationUuidMask: ParcelUuid? = null

        private var serviceDataUuid: ParcelUuid? = null
        private var serviceData: ByteArray? = null
        private var serviceDataMask: ByteArray? = null

        private var manufacturerId: Int? = null
        private var manufacturerData: ByteArray? = null
        private var manufacturerDataMask: ByteArray? = null

        private var rssiThreshold: Int? = null

        fun setDeviceName(deviceName: String?): Builder {
            this.deviceName = deviceName
            deviceNamePattern = null
            return this
        }

        fun setDeviceNamePattern(deviceNamePattern: Pattern): Builder {
            deviceName = null
            this.deviceNamePattern = deviceNamePattern
            return this
        }

        fun setDeviceAddress(deviceAddress: String): Builder {
            require(BluetoothAdapter.checkBluetoothAddress(deviceAddress)) { "invalid device address $deviceAddress" }
            this.deviceAddress = deviceAddress
            return this
        }

        fun setServiceUuid(serviceUuid: ParcelUuid?): Builder {
            this.serviceUuid = serviceUuid
            uuidMask = null // clear uuid mask
            return this
        }

        fun setServiceUuid(serviceUuid: ParcelUuid?, uuidMask: ParcelUuid?): Builder {
            require(!(this.uuidMask != null && this.serviceUuid == null)) { "uuid is null while uuidMask is not null!" }
            this.serviceUuid = serviceUuid
            this.uuidMask = uuidMask
            return this
        }

        @RequiresApi(Build.VERSION_CODES.Q)
        fun setServiceSolicitationUuid(
            serviceSolicitationUuid: ParcelUuid?
        ): Builder {
            this.serviceSolicitationUuid = serviceSolicitationUuid
            if (serviceSolicitationUuid == null) {
                serviceSolicitationUuidMask = null
            }
            return this
        }

        @RequiresApi(Build.VERSION_CODES.Q)
        fun setServiceSolicitationUuid(
            serviceSolicitationUuid: ParcelUuid?,
            solicitationUuidMask: ParcelUuid?
        ): Builder {
            require(!(solicitationUuidMask != null && serviceSolicitationUuid == null)) { "SolicitationUuid is null while SolicitationUuidMask is not null!" }
            this.serviceSolicitationUuid = serviceSolicitationUuid
            serviceSolicitationUuidMask = solicitationUuidMask
            return this
        }

        fun setServiceData(
            serviceDataUuid: ParcelUuid,
            serviceData: ByteArray?
        ): Builder {
            this.serviceDataUuid = serviceDataUuid
            this.serviceData = serviceData
            serviceDataMask = null // clear service data mask
            return this
        }

        fun setServiceData(
            serviceDataUuid: ParcelUuid,
            serviceData: ByteArray, serviceDataMask: ByteArray
        ): Builder {
            if (this.serviceDataMask != null) {
                requireNotNull(this.serviceData) { "serviceData is null while serviceDataMask is not null" }
                // Since the mServiceDataMask is a bit mask for mServiceData, the lengths of the two
                // byte array need to be the same.
                require(this.serviceData!!.size == this.serviceDataMask!!.size) { "size mismatch for service data and service data mask" }
            }
            this.serviceDataUuid = serviceDataUuid
            this.serviceData = serviceData
            this.serviceDataMask = serviceDataMask
            return this
        }

        fun setManufacturerData(
            manufacturerId: Int,
            manufacturerData: ByteArray?
        ): Builder {
            require(!(manufacturerData != null && manufacturerId < 0)) { "invalid manufacture id" }
            this.manufacturerId = manufacturerId
            this.manufacturerData = manufacturerData
            manufacturerDataMask = null // clear manufacturer data mask
            return this
        }

        fun setManufacturerData(
            manufacturerId: Int,
            manufacturerData: ByteArray?,
            manufacturerDataMask: ByteArray
        ): Builder {
            require(!(manufacturerData != null && manufacturerId < 0)) { "invalid manufacture id" }
            if (this.manufacturerDataMask != null) {
                requireNotNull(this.manufacturerData) { "manufacturerData is null while manufacturerDataMask is not null" }
                // Since the mManufacturerDataMask is a bit mask for mManufacturerData, the lengths
                // of the two byte array need to be the same.
                require(this.manufacturerData!!.size == this.manufacturerDataMask!!.size) { "size mismatch for manufacturerData and manufacturerDataMask" }
            }
            this.manufacturerId = manufacturerId
            this.manufacturerData = manufacturerData
            this.manufacturerDataMask = manufacturerDataMask
            return this
        }

        fun setRssiThreshold(
            rssiThreshold: Int?
        ): Builder {
            this.rssiThreshold = rssiThreshold
            return this
        }

        fun build(): BleScanFilter {
            return BleScanFilter(
                deviceName, deviceNamePattern, deviceAddress,
                serviceUuid, uuidMask, serviceSolicitationUuid,
                serviceSolicitationUuidMask,
                serviceDataUuid, serviceData, serviceDataMask,
                manufacturerId, manufacturerData, manufacturerDataMask,
                rssiThreshold
            )
        }
    }

    fun isOk(device: BluetoothDevice, rssi: Int, scanRecord: ByteArray?): Boolean {
        namePattern?.also {
            if (device.name == null || !it.matcher(device.name).matches()) {
                return false
            }
        }
        rssiThreshold?.also {
            if (rssi < it) {
                return false
            }
        }
        return true
    }
}
