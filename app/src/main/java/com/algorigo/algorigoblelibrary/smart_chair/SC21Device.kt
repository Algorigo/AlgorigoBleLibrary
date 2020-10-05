package com.algorigo.algorigoblelibrary.smart_chair

import android.bluetooth.BluetoothDevice
import android.util.Log
import com.algorigo.algorigoble.BleScanFilter
import com.algorigo.algorigoble.InitializableBleDevice
import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.disposables.Disposable
import io.reactivex.rxjava3.subjects.PublishSubject
import java.util.*
import java.util.concurrent.TimeUnit

class SC21Device : InitializableBleDevice() {
    private var amp = -1
    private var sens = -1
    private var sensingPeriod = -1
    private var commPeriod = -1
    private var vibrate = byteArrayOf()
    private var firmwareVersion = ""
    private var buildNum = -1
    private var offTimer = -1
    private var data = ByteArray(32)
    private var dataDisposable: Disposable? = null
    private var dataSubject = PublishSubject.create<IntArray>().toSerialized()
    private var heartRateDisposable: Disposable? = null

    override fun initializeCompletable(): Completable {
        return Completable.concatArray(getAmplificationSingle()!!.ignoreElement(),
            getPotentiometerSingle()!!.ignoreElement(),
            getSensingIntervalSingle()!!.ignoreElement(),
            getCommIntervalSingle()!!.ignoreElement(),
            getVibrateSingle()!!.ignoreElement(),
            getFirmwareVersionSingle()!!.ignoreElement(),
            getBuildNumberSingle()!!.ignoreElement(),
            getOffTimerSingle()!!.ignoreElement())
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
                    dataDisposable = setupNotification(UUID.fromString(UUID_SEND_DATA_ON))
                        ?.flatMap {
                            enableSensor(true)?.andThen(it)
                        }
                        ?.doFinally {
                            if (!dataSubject.hasObservers()) {
                                dataDisposable = null
                                enableSensor(false)
                                    ?.subscribe({
                                        Log.i(TAG, "complete")
                                    }, {
                                        Log.e(TAG, "disableSensor error", Exception(it))
                                    })
                            }
                        }
                        ?.subscribe({
                            onData(it)
                        }, {
                            Log.e(TAG, "enableSensor error", Exception(it))
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
        heartRateDisposable = Observable.interval(offTimer*2L/3, TimeUnit.SECONDS)
            .flatMapSingle { getChargerPlugSingle() }
            .subscribe({
            }, {
                Log.e(TAG, "heartBeatRead error", Exception(it))
            })
    }

    private fun enableSensor(enable: Boolean): Completable? {
        return writeCharacteristic(UUID.fromString(UUID_SENSOR_CONFIG), byteArrayOf(if (enable) 0x01.toByte() else 0x00.toByte()))
            ?.ignoreElement()
    }

    private fun getDeviceTypeSingle(): Single<String>? {
        return readCharacteristic(UUID.fromString(UUID_DEVICE_TYPE))
            ?.map {
                Log.i(TAG, "UUID_DEVICE_TYPE:${it.map { it.toUInt() }.toTypedArray().contentToString()}")
                String(it)
            }
    }

    private fun getModelNumberSingle(): Single<String>? {
        return readCharacteristic(UUID.fromString(UUID_MODEL_NUMBER))
            ?.map {
                Log.i(TAG, "UUID_MODEL_NUMBER:${it.map { it.toUInt() }.toTypedArray().contentToString()}")
                String(it)
            }
    }

    private fun getManufacturerIdSingle(): Single<String>? {
        return readCharacteristic(UUID.fromString(UUID_MANUFACTURER_ID))
            ?.map {
                Log.i(TAG, "UUID_MANUFACTURER_ID:${it.map { it.toUInt() }.toTypedArray().contentToString()}")
                String(it)
            }
    }

    private fun setManufacturerIdCompletable(manufacturerId: String): Completable? {
        return writeCharacteristic(UUID.fromString(UUID_MANUFACTURER_ID), manufacturerId.toByteArray())
            ?.doOnSuccess {
                Log.i(TAG, "UUID_MANUFACTURER_ID:${it.map { it.toUInt() }.toTypedArray().contentToString()}")
            }
            ?.ignoreElement()
    }

    private fun getMacAddressSingle(): Single<String>? {
        return readCharacteristic(UUID.fromString(UUID_MACADDRESS))
            ?.map {
                Log.i(TAG, "UUID_MACADDRESS:${it.map { it.toUInt() }.toTypedArray().contentToString()}")
                it.map { it.toInt() and 0xff }.let { String.format("%02x%02x%02x%02x%02x%02x", it[0], it[1], it[2], it[3], it[4], it[5]) }
            }
    }

    fun getFirmwareVersion(): String {
        return firmwareVersion
    }

    private fun getFirmwareVersionSingle(): Single<String>? {
        return readCharacteristic(UUID.fromString(UUID_FIRMWARE_VERSION))
            ?.map {
                Log.i(TAG, "UUID_FIRMWARE_VERSION:${it.map { it.toUInt() }.toTypedArray().contentToString()}")
                String.format("%d.%d", it[0].toInt() and 0xff, it[1].toInt() and 0xff)
            }
            ?.doOnSuccess { firmwareVersion = it }
    }

    fun getBuildNumber(): Int {
        return buildNum
    }

    private fun getBuildNumberSingle(): Single<Int>? {
        return readCharacteristic(UUID.fromString(UUID_FIRMWARE_BUILD_NUM))
            ?.map {
                Log.i(TAG, "UUID_FIRMWARE_BUILD_NUM:${it.map { it.toUInt() }.toTypedArray().contentToString()}")
                byteArrayToInt(it)
            }
            ?.doOnSuccess { buildNum = it }
    }

    fun getAmplification(): Int {
        return amp
    }

    private fun getAmplificationSingle(): Single<Int>? {
        return readCharacteristic(UUID.fromString(UUID_AMPLIFICATION))
            ?.map {
                Log.i(TAG, "UUID_AMPLIFICATION:${it.map { it.toUInt() }.toTypedArray().contentToString()}")
                byteArrayToInt(it)
            }
            ?.doOnSuccess { amp = it }
    }

    fun setAmplificationCompletable(amplification: Int): Completable? {
        return writeCharacteristic(UUID.fromString(UUID_AMPLIFICATION), byteArrayOf(amplification.toByte()))
            ?.doOnSuccess {
                Log.i(TAG, "UUID_AMPLIFICATION:${it.map { it.toUInt() }.toTypedArray().contentToString()}")
                amp = byteArrayToInt(it)
            }
            ?.ignoreElement()
    }

    fun getPotentiometer(): Int {
        return sens
    }

    private fun getPotentiometerSingle(): Single<Int>? {
        return readCharacteristic(UUID.fromString(UUID_POTENTIOMETER))
            ?.map {
                Log.i(TAG, "UUID_POTENTIOMETER:${it.map { it.toUInt() }.toTypedArray().contentToString()}")
                byteArrayToInt(it)
            }
            ?.doOnSuccess { sens = it }
    }

    fun setPotentiometerCompletable(potentiometer: Int): Completable? {
        return writeCharacteristic(UUID.fromString(UUID_POTENTIOMETER), byteArrayOf(potentiometer.toByte()))
            ?.doOnSuccess {
                Log.i(TAG, "UUID_POTENTIOMETER:${it.map { it.toUInt() }.toTypedArray().contentToString()}")
                sens = byteArrayToInt(it)
            }
            ?.ignoreElement()
    }

    private fun getVoltageSingle(): Single<Int>? {
        return readCharacteristic(UUID.fromString(UUID_VOLTAGE))
            ?.map {
                Log.i(TAG, "UUID_VOLTAGE:${it.map { it.toUInt() }.toTypedArray().contentToString()}")
                byteArrayToInt(it)
            }
    }

    private fun getCapacitySingle(): Single<Int>? {
        return readCharacteristic(UUID.fromString(UUID_CAPACITY))
            ?.map {
                Log.i(TAG, "UUID_CAPACITY:${it.map { it.toUInt() }.toTypedArray().contentToString()}")
                byteArrayToInt(it)
            }
    }

    private fun getChargerPlugSingle(): Single<Int>? {
        return readCharacteristic(UUID.fromString(UUID_CHARGER_PLUG))
            ?.map {
                Log.i(TAG, "UUID_CHARGER_PLUG:${it.map { it.toUInt() }.toTypedArray().contentToString()}")
                byteArrayToInt(it)
            }
    }

    fun getSensingInterval(): Int {
        return sensingPeriod
    }

    private fun getSensingIntervalSingle(): Single<Int>? {
        return readCharacteristic(UUID.fromString(UUID_SENSING_INTERVAL))
            ?.map {
                Log.i(TAG, "UUID_SENSING_INTERVAL:${it.map { it.toUInt() }.toTypedArray().contentToString()}")
                byteArrayToInt(it)
            }
            ?.doOnSuccess { sensingPeriod = it }
    }

    fun setSensingIntervalCompletable(sensingInterval: Int): Completable? {
        val byteArray = ByteArray(2)
        byteArray[0] = sensingInterval.toByte()
        byteArray[1] = (sensingInterval shr 8).toByte()

        return writeCharacteristic(UUID.fromString(UUID_SENSING_INTERVAL), byteArray)
            ?.doOnSuccess {
                Log.i(TAG, "UUID_SENSING_INTERVAL:${it.map { it.toUInt() }.toTypedArray().contentToString()}")
                sensingPeriod = byteArrayToInt(it)
            }
            ?.ignoreElement()
    }

    fun getCommInterval(): Int {
        return commPeriod
    }

    private fun getCommIntervalSingle(): Single<Int>? {
        return readCharacteristic(UUID.fromString(UUID_COMM_INTERVAL))
            ?.map {
                Log.i(TAG, "UUID_COMM_INTERVAL:${it.map { it.toUInt() }.toTypedArray().contentToString()}")
                byteArrayToInt(it)
            }
            ?.doOnSuccess { commPeriod = it }
    }

    fun setCommIntervalCompletable(commInterval: Int): Completable? {
        val byteArray = ByteArray(2)
        byteArray[0] = commInterval.toByte()
        byteArray[1] = (commInterval shr 8).toByte()

        return writeCharacteristic(UUID.fromString(UUID_COMM_INTERVAL), byteArray)
            ?.doOnSuccess {
                Log.i(TAG, "UUID_COMM_INTERVAL:${it.map { it.toUInt() }.toTypedArray().contentToString()}")
                commPeriod = byteArrayToInt(it)
            }
            ?.ignoreElement()
    }

    fun getVibrate(): ByteArray {
        return vibrate
    }

    private fun getVibrateSingle(): Single<ByteArray>? {
        return readCharacteristic(UUID.fromString(UUID_VIBRATION))
            ?.doOnSuccess {
                Log.i(TAG, "UUID_VIBRATION:${it.map { it.toUInt() }.toTypedArray().contentToString()}")
                vibrate = it
            }
    }

    fun setVibrateCompletable(vibrate: Int): Completable? {
        return writeCharacteristic(UUID.fromString(UUID_VIBRATION), byteArrayOf(vibrate.toByte()))
            ?.doOnSuccess {
                Log.i(TAG, "UUID_VIBRATION:${it.map { it.toUInt() }.toTypedArray().contentToString()}")
                this.vibrate = it
            }
            ?.ignoreElement()
    }

    fun getOffTimer(): Int {
        return offTimer
    }

    private fun getOffTimerSingle(): Single<Int>? {
        return readCharacteristic(UUID.fromString(UUID_OFF_TIMER))
            ?.map {
                Log.i(TAG, "UUID_OFF_TIMER:${it.map { it.toUInt() }.toTypedArray().contentToString()}")
                byteArrayToInt(it)
            }
            ?.doOnSuccess { offTimer = it }
    }

    fun setOffTimerCompletable(offTimer: Int): Completable? {
        return writeCharacteristic(UUID.fromString(UUID_OFF_TIMER), byteArrayOf(offTimer.toByte()))
            ?.doOnSuccess {
                Log.i(TAG, "UUID_OFF_TIMER:${it.map { it.toUInt() }.toTypedArray().contentToString()}")
                this.offTimer = byteArrayToInt(it)

                heartRateDisposable?.dispose()
                heartRateDisposable = null
                heartBeatRead()
            }
            ?.ignoreElement()
    }

    fun getBatteryObservable(): Observable<Int> {
        return Observable.interval(10, TimeUnit.SECONDS)
            .flatMapSingle { getVoltageSingle() }
    }

    fun getCapacityObservable(): Observable<Int> {
        return Observable.interval(10, TimeUnit.SECONDS)
            .flatMapSingle { getCapacitySingle() }
    }

    fun getChargingObservable(): Observable<Boolean> {
        return Observable.interval(10, TimeUnit.SECONDS)
            .flatMapSingle { getChargerPlugSingle() }
            .map { it > 0 }
    }

    fun getBatteryInfoObservable(): Observable<Triple<Int, Int, Boolean>> {
        return Observable.interval(10, TimeUnit.SECONDS)
            .flatMapSingle {
                Single.zip<Int, Int, Boolean, Triple<Int, Int, Boolean>>(
                    getVoltageSingle(),
                    getCapacitySingle(),
                    getChargerPlugSingle()!!.map { it > 0 },
                    { t1, t2, t3 -> Triple(t1, t2, t3) }
                )
            }
    }

    fun getCharging(): Boolean {
        return false
    }

    private fun byteArrayToInt(byteArray: ByteArray): Int {
        var value = 0
        for (byte in byteArray.reversed()) {
            value = (value shl 8) + ((byte.toInt() and 0xff))
        }
        return value
    }

    companion object {
        private val TAG = SC21Device::class.java.simpleName

        private const val BLE_NAME = "Algorigo_SC21"
        const val MAX_VALUE = 4085f

        fun isMatch(bluetoothDevice: BluetoothDevice): Boolean {
            return bluetoothDevice.name?.equals(BLE_NAME) ?: false
        }

        fun getScanFilter(): BleScanFilter {
            return BleScanFilter.Builder()
                .setDeviceName(BLE_NAME)
                .build()
        }

        private const val UUID_SERVICE =            "F000AA20-0451-4000-B000-000000000000"
        private const val UUID_SEND_DATA_ON =       "F000AA21-0451-4000-B000-000000000000"
        private const val UUID_SENSOR_CONFIG =      "F000AA22-0451-4000-B000-000000000000"
        private const val UUID_DEVICE_TYPE =        "00002B01-0000-1000-8000-00805F9B34FB"
        private const val UUID_MODEL_NUMBER =       "00002B02-0000-1000-8000-00805F9B34FB"
        private const val UUID_MANUFACTURER_ID =    "00002B03-0000-1000-8000-00805F9B34FB"
        private const val UUID_MACADDRESS =         "00002B04-0000-1000-8000-00805F9B34FB"
        private const val UUID_FIRMWARE_VERSION =   "00002B05-0000-1000-8000-00805F9B34FB"
        private const val UUID_FIRMWARE_BUILD_NUM = "00002B06-0000-1000-8000-00805F9B34FB"
        private const val UUID_AMPLIFICATION =      "00002B07-0000-1000-8000-00805F9B34FB"
        private const val UUID_POTENTIOMETER =      "00002B08-0000-1000-8000-00805F9B34FB"
        private const val UUID_VOLTAGE =            "00002B09-0000-1000-8000-00805F9B34FB"
        private const val UUID_CAPACITY =           "00002B0A-0000-1000-8000-00805F9B34FB"
        private const val UUID_CHARGER_PLUG =       "00002B0B-0000-1000-8000-00805F9B34FB"
        private const val UUID_SENSING_INTERVAL =   "00002B0C-0000-1000-8000-00805F9B34FB"
        private const val UUID_COMM_INTERVAL =      "00002B0D-0000-1000-8000-00805F9B34FB"
        private const val UUID_SENSING_CHECKING =   "00002B0E-0000-1000-8000-00805F9B34FB"
        private const val UUID_SENSOR_DATA_1 =      "00002B0F-0000-1000-8000-00805F9B34FB"
        private const val UUID_SENSOR_DATA_2 =      "00002B10-0000-1000-8000-00805F9B34FB"
        private const val UUID_SENSOR_DATA_3 =      "00002B11-0000-1000-8000-00805F9B34FB"
        private const val UUID_SENSOR_DATA_4 =      "00002B12-0000-1000-8000-00805F9B34FB"
        private const val UUID_VIBRATION =          "00002B13-0000-1000-8000-00805F9B34FB"
        private const val UUID_OFF_TIMER =          "00002B14-0000-1000-8000-00805F9B34FB"
    }
}