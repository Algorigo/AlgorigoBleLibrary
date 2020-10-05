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

class SC01Device: InitializableBleDevice() {

    private var amp = -1
    private var sens = -1
    private var dataPeriod = -1
    private var data = IntArray(64)
    private var charging = false
    private var dataDisposable: Disposable? = null
    private var dataSubject = PublishSubject.create<IntArray>().toSerialized()
    private var dataObservable: Observable<IntArray>? = null

    override fun initializeCompletable(): Completable {
        return Completable.concatArray(getAmplificationSingle()!!.doOnSuccess { amp = it }.ignoreElement(),
            getPotentiometerSingle()!!.doOnSuccess { sens = it }.ignoreElement(),
            getDataPeriodSingle()!!.doOnSuccess { dataPeriod = it }.ignoreElement())
    }

    override fun onDisconnected() {
        super.onDisconnected()
        amp = -1
        sens = -1
        dataPeriod = -1
    }

    fun sendDataOn(): Observable<IntArray>? {
        return if (dataObservable != null) {
            dataObservable
        } else {
            dataSubject
                .doOnSubscribe {
                    dataDisposable = setupNotification(UUID.fromString(UUID_SEND_DATA_ON))
                        ?.flatMap { it }
                        ?.doFinally {
                            dataDisposable = null
                        }
                        ?.subscribe({
                            onData(it)
                        }, {
                            Log.e(TAG, "enableSensor error", Exception(it))
                            dataSubject.onError(it)
                        })
                }
                .doFinally {
                    if (!dataSubject.hasObservers()) {
                        dataDisposable?.dispose()
                        dataObservable = null
                    }
                }
                .doOnError {
                    dataSubject = PublishSubject.create<IntArray>().toSerialized()
                }
                .also {
                    dataObservable = it
                }
        }
    }

    private fun onData(byteArray: ByteArray) {
//        Log.e("!!!", "onData:${byteArray.contentToString()}")
        val lineIdx = byteArray[0].toInt()
        for (i in 1..16 step 2) {
            val aUpper = byteArray[i].toInt() and 0xff
            val aLower = byteArray[i+1].toInt() and 0xff
            val idx = lineIdx * 8 + i / 2
            data[idx] = (aUpper shl 8) + aLower
        }

        if (byteArray.size > 17) {
            charging = byteArray[17].toInt() == 1
        }

        if (lineIdx == 7) {
            dataSubject.onNext(data)
        }
    }

    fun setAmplificationSingle(value: Int): Single<Int>? {
        if (value < 0 || value > 255) {
            return Single.error(IllegalArgumentException("value must be 0~255"))
        }

        val byteArray = byteArrayOf(value.toByte())
        return writeCharacteristic(UUID.fromString(UUID_AMP), byteArray)
            ?.map { byteArrayToInt(it) }
            ?.doOnSuccess { amp = it }
    }

    fun getAmplificationSingle(): Single<Int>? {
        return readCharacteristic(UUID.fromString(UUID_AMP))
            ?.map {
                byteArrayToInt(it).also {
                    amp = it
                }
            }
    }

    fun getAmplification(): Int {
        return amp
    }

    fun setPotentiometerSingle(value: Int): Single<Int>? {
        if (value < 0 || value > 255) {
            return Single.error(IllegalArgumentException("value must be 0~255"))
        }

        val byteArray = byteArrayOf(value.toByte())
        return writeCharacteristic(UUID.fromString(UUID_SENS), byteArray)
            ?.map { byteArrayToInt(it) }
            ?.doOnSuccess { sens = it }
    }

    fun getPotentiometerSingle(): Single<Int>? {
        return readCharacteristic(UUID.fromString(UUID_SENS))
            ?.map {
                byteArrayToInt(it).also {
                    sens = it
                }
            }
    }

    fun getPotentiometer(): Int {
        return sens
    }

    fun setDataPeriodSingle(value: Int): Single<Int>? {
        if (value < 0 || value > 65535) {
            return Single.error(IllegalArgumentException("value must be 0~65535"))
        }

        val byteArray = ByteArray(2)
        byteArray[1] = value.toByte()
        byteArray[0] = (value shr 8).toByte()
        return writeCharacteristic(UUID.fromString(UUID_DATA_PERIOD), byteArray)
            ?.map { byteArrayToInt(it) }
            ?.doOnSuccess { dataPeriod = it }
    }

    fun getDataPeriodSingle(): Single<Int>? {
        return readCharacteristic(UUID.fromString(UUID_DATA_PERIOD))
            ?.map {
                byteArrayToInt(it).also {
                    dataPeriod = it
                }
            }
    }

    fun getDataPeriod(): Int {
        return dataPeriod
    }

    fun getBatteryValueSingle(): Single<Int>? {
        return readCharacteristic(UUID.fromString(UUID_BATTERY))
            ?.map {
                byteArrayToInt(it)
            }
    }

    private fun byteArrayToInt(byteArray: ByteArray): Int {
        var value = 0
        for (byte in byteArray) {
            value = (value shl 8) + (byte.toInt() and 0xff)
        }
        return value
    }

    companion object {
        private val TAG = SC01Device::class.java.simpleName

        private const val BLE_NAME = "Algorigo"
        private const val BLE_MANUFACTURER_ID = 89
        private val BLE_MANUFACTURER_SPECIFIC_DATA = byteArrayOf(2, 21, 31, 74, -26, -96, 0, 55, 64, 18, -126, 1, 39, 16, 102, 70, 0, 0, 0, -1, 0, -1, -61)

        fun isMatch(bluetoothDevice: BluetoothDevice): Boolean {
            return bluetoothDevice.name?.equals(BLE_NAME) ?: false
        }

        fun getScanFilter(): BleScanFilter {
            return BleScanFilter.Builder()
                .setDeviceName(BLE_NAME)
                .build()
        }

        private const val UUID_SEND_DATA_ON =   "6e400003-b5a3-f393-e0a9-e50e24dcca9e"
        private const val UUID_AMP =            "9fd42001-e46f-7c9a-57b1-2da365e18fa1"
        private const val UUID_SENS =           "9fd42002-e46f-7c9a-57b1-2da365e18fa1"
        private const val UUID_BATTERY =        "9fd42003-e46f-7c9a-57b1-2da365e18fa1"
        private const val UUID_DATA_PERIOD =    "9fd42004-e46f-7c9a-57b1-2da365e18fa1"
        private const val UUID_SAVE_PERIOD =    "9fd42005-e46f-7c9a-57b1-2da365e18fa1"
        private const val UUID_SLEEP =          "9fd42006-e46f-7c9a-57b1-2da365e18fa1"
    }
}