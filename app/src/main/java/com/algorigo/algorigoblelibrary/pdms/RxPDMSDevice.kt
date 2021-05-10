package com.algorigo.pressurego

import android.bluetooth.BluetoothDevice
import android.util.Log
import com.algorigo.algorigoble.*
import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.disposables.Disposable
import io.reactivex.rxjava3.subjects.PublishSubject
import java.util.*
import java.util.concurrent.TimeUnit

class RxPDMSDevice : InitializableBleDevice() {

    private var deviceName = ""
    private var manufactureName = ""
    private var hardwareVersion = ""
    private var firmwareVersion = ""
    private var amp = -1
    private var sens = -1
    private var data = ByteArray(32)
    private var dataDisposable: Disposable? = null
    private var dataSubject = PublishSubject.create<IntArray>().toSerialized()
    private var heartRateDisposable: Disposable? = null

    override fun initializeCompletable(): Completable {
        return getDeviceNameSingle()!!.ignoreElement()
            .andThen(getManufacturerNameSingle()!!.ignoreElement())
            .andThen(getHardwareVersionSingle()!!.ignoreElement())
            .andThen(getFirmwareVersionSingle()!!.ignoreElement())
//            .andThen(getAmplificationSingle()!!.ignoreElement())
            .doOnComplete {
                heartBeatRead()
            }
    }

    override fun onDisconnected() {
        super.onDisconnected()
        heartRateDisposable?.dispose()
        heartRateDisposable = null
    }

    fun sendDataOn(): Observable<IntArray>? {
        return dataSubject
            .doOnSubscribe {
                if (dataDisposable == null) {
                    heartRateDisposable?.dispose()
                    heartRateDisposable = null
                    dataDisposable = setupNotification(UUID.fromString(PDMSUtil.UUID_DATA_NOTIFICATION))
                        ?.flatMap { it }
                        ?.doFinally {
                            if (!dataSubject.hasObservers()) {
                                dataDisposable = null
                            }
                        }
                        ?.subscribe({
                            onData(it)
                        }, {
                            Log.e(LOG_TAG, "enableSensor error", Exception(it))
                            dataSubject.onError(it)
                        })
                }
            }
            .doFinally {
                if (!dataSubject.hasObservers()) {
                    dataDisposable?.dispose()
                    heartBeatRead()
                }
            }
            .doOnError {
                dataSubject = PublishSubject.create<IntArray>().toSerialized()
            }
    }

    private fun onData(byteArray: ByteArray) {
//        Log.i(LOG_TAG, "onData:${byteArray.contentToString()}")
        val lineIdx = byteArray[0].toInt()
        byteArray.copyInto(data, 16*lineIdx, 1)

        if (lineIdx == 1) {
            val intArray = IntArray(16) {
                val aLower = data[it*2].toInt() and 0xff
                val aUpper = data[it*2+1].toInt() and 0xff
                (aUpper shl 8) + aLower
            }
            dataSubject.onNext(intArray)
        }
    }

    private fun heartBeatRead() {
        heartRateDisposable = Observable.interval(1, TimeUnit.MINUTES)
            .flatMapSingle { getBatteryPercentSingle() }
            .subscribe({
            }, {
                Log.e(LOG_TAG, "heartBeatRead error", Exception(it))
            })
    }

    fun getDeviceName(): String {
        return deviceName
    }

    private fun getDeviceNameSingle(): Single<String>? {
        return readCharacteristic(UUID.fromString(PDMSUtil.UUID_DEVICE_NAME))
            ?.map {
                Log.i(LOG_TAG, "UUID_DEVICE_TYPE:${it.map { it.toUByte().toUInt() }.toTypedArray().contentToString()}")
                String(it)
            }
            ?.doOnSuccess { deviceName = it }
    }

    fun getManufactureName(): String {
        return manufactureName
    }

    private fun getManufacturerNameSingle(): Single<String>? {
        return readCharacteristic(UUID.fromString(PDMSUtil.UUID_MANUFACTURER_NAME))
            ?.map {
                Log.i(LOG_TAG, "UUID_MANUFACTURER_ID:${it.map { it.toUByte().toUInt() }.toTypedArray().contentToString()}")
                String(it)
            }
            ?.doOnSuccess { manufactureName = it }
    }

    fun getFirmwareVersion(): String {
        return firmwareVersion
    }

    private fun getFirmwareVersionSingle(): Single<String>? {
        return readCharacteristic(UUID.fromString(PDMSUtil.UUID_FIRMWARE_REVISION))
            ?.map {
                Log.i(LOG_TAG, "UUID_FIRMWARE_VERSION:${it.map { it.toUByte().toUInt() }.toTypedArray().contentToString()}")
                String(it)
            }
            ?.doOnSuccess { firmwareVersion = it }
    }

    fun getHardwareVersion(): String {
        return hardwareVersion
    }

    private fun getHardwareVersionSingle(): Single<String>? {
        return readCharacteristic(UUID.fromString(PDMSUtil.UUID_HARDWARE_REVISION))
            ?.map {
                Log.i(LOG_TAG, "UUID_FIRMWARE_VERSION:${it.map { it.toUByte().toUInt() }.toTypedArray().contentToString()}")
                String(it)
            }
            ?.doOnSuccess { hardwareVersion = it }
    }

    fun getAmplification(): Int {
        return amp
    }

    private fun getAmplificationSingle(): Single<Int>? {
        return readCharacteristic(UUID.fromString(PDMSUtil.UUID_AMPLIFICATION))
            ?.map {
                Log.i(LOG_TAG, "UUID_AMPLIFICATION:${it.map { it.toUByte().toUInt() }.toTypedArray().contentToString()}")
                it.toInt()
            }
            ?.doOnSuccess { amp = it }
    }

    fun setAmplificationCompletable(amplification: Int): Completable? {
        return writeCharacteristic(UUID.fromString(PDMSUtil.UUID_AMPLIFICATION), byteArrayOf(amplification.toByte()))
            ?.doOnSuccess {
                Log.i(LOG_TAG, "UUID_AMPLIFICATION:${it.map { it.toUByte().toUInt() }.toTypedArray().contentToString()}")
                amp = it.toInt()
            }
            ?.ignoreElement()
    }

    fun getBatteryPercentSingle(): Single<Int>? {
        return readCharacteristic(UUID.fromString(PDMSUtil.UUID_BATTERY_LEVEL))
            ?.map {
                Log.i(LOG_TAG, "UUID_VOLTAGE:${it.map { it.toUByte().toUInt() }.toTypedArray().contentToString()}")
                it.toInt()
            }
    }

    class DeviceDelegate : BleManager.BleDeviceDelegate() {
        override fun createBleDevice(bluetoothDevice: BluetoothDevice): BleDevice? {
            return when {
                isMatch(bluetoothDevice) -> RxPDMSDevice()
                else -> null
            }
        }

        override fun getBleScanFilters(): Array<BleScanFilter> {
            return arrayOf(
                getScanFilter()
            )
        }

        override fun getBleScanSettings(): BleScanSettings {
            return BleScanSettings.Builder().build()
        }
    }

    companion object {
        private val LOG_TAG = RxPDMSDevice::class.java.simpleName

        fun isMatch(bluetoothDevice: BluetoothDevice): Boolean {
            return bluetoothDevice.name?.equals(PDMSUtil.BLE_NAME) ?: false
        }

        fun getScanFilter(): BleScanFilter {
            return BleScanFilter.Builder()
                .setDeviceName(PDMSUtil.BLE_NAME)
                .build()
        }
    }
}

fun ByteArray.toInt(): Int {
    var value = 0
    for (byte in reversed()) {
        value = (value shl 8) + ((byte.toInt() and 0xff))
    }
    return value
}
