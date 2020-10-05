package com.algorigo.algorigoblelibrary.carseat

import android.bluetooth.BluetoothDevice
import android.util.Log
import com.algorigo.algorigoble.BleScanFilter
import com.algorigo.algorigoble.InitializableBleDevice
import com.algorigo.algorigoblelibrary.bruxweeper.BruxweeperBleDevice
import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.disposables.Disposable
import io.reactivex.rxjava3.subjects.PublishSubject
import io.reactivex.rxjava3.subjects.Subject
import java.util.*
import java.util.concurrent.TimeUnit

class CarSeatDevice: InitializableBleDevice() {

    private lateinit var initializeSubject: Subject<Any>
    private var nrfResponseDisposable: Disposable? = null
    private var nrfResponseSubject = PublishSubject.create<ByteArray>()
    private var getDisposable: Disposable? = null
    private val dataMap = mutableMapOf<Byte, BruxweeperBleDevice.Data>()

    public var intervalMillis = 500L
        set(value) {
            field = value
            if (isGet()) {
                stopGet()
                startGet()
            }
        }

    override fun initializeCompletable(): Completable {
        initializeSubject = PublishSubject.create<Any>().toSerialized()
        return initializeSubject
            .doOnSubscribe {
                var byteArrayAppend: ByteArray? = null
                nrfResponseDisposable = nrfResponseNotification()?.subscribe({
                    initializeSubject.onComplete()
                    it.subscribe({
                        Log.e("!!!", "nrfResponseNotification:${it.map { String.format("%x", it) }.joinToString("")}")
//                        if (byteArrayAppend == null || it.count() != NRF_REQUSET_SECOND_DATA_SIZE) {
//                            when (it[0]) {
//                                0x02.toByte() -> {
//                                    if (it.count() == NRF_REQUEST_FIRST_DATA_SIZE) {
//                                        byteArrayAppend = it
//                                    }
//                                }
//                                else -> {
////                                    onCallback(it)
//                                }
//                            }
//                        } else if (byteArrayAppend != null && it.count() == NRF_REQUSET_SECOND_DATA_SIZE) {
//                            byteArrayAppend?.let { byteArray ->
//                                val resultByteArray = byteArray + it
//                                nrfResponseSubject.onNext(resultByteArray.sliceArray(1 until resultByteArray.count()))
//                            }
//                            byteArrayAppend = null
//                        }
                    }, {
                        Log.e(LOG_TAG, "", it)
                    })
                }, {
                    Log.e(LOG_TAG, "", it)
                    if (!initializeSubject.hasComplete() && !initializeSubject.hasThrowable() && initializeSubject.hasObservers()) {
                        initializeSubject.onError(it)
                    }
                })
            }
            .ignoreElements()
    }

    override fun onDisconnected() {
        super.onDisconnected()
        nrfResponseDisposable?.dispose()
    }

    private fun nrfRequestCharacteristic(): Completable? {
        return writeCharacteristic(UUID.fromString(UUID_NRF_REQUEST), byteArrayOf(0x02.toByte())+ByteArray(19) { 0x00.toByte() })
            ?.ignoreElement()
    }

    private fun nrfResponseNotification(): Observable<Observable<ByteArray>>? {
        return setupNotification(UUID.fromString(UUID_NRF_RESPONSE))
    }

    fun getNrfResponseObservable(): Observable<ByteArray> {
        return nrfResponseSubject
            .doOnSubscribe {
                if (!nrfResponseSubject.hasObservers()) {
                    startGet()
                }
            }
            .doFinally {
                if (!nrfResponseSubject.hasObservers()) {
                    stopGet()
                }
            }
    }

    private fun startGet() {
        getDisposable = Observable.interval(intervalMillis, TimeUnit.MILLISECONDS)
            .flatMapSingle {
                nrfRequestCharacteristic()
                    ?.toSingleDefault(1)
                    ?: Single.error(NullPointerException("nrfRequestCharacteristic"))
            }
            .doFinally {
                getDisposable = null
            }
            .subscribe()
    }

    private fun stopGet() {
        getDisposable?.dispose()
    }

    private fun isGet(): Boolean {
        return getDisposable != null
    }

    fun getDataSingle(index: Int): Single<Triple<Int, Int, Int>>? {
        val data = byteArrayOf(0x02,
            0x04, 0x00,
            index.toByte(), 0x00,
            0x00, 0x00
        )
        val checksum = getChecksum(data, 1, data.count())
        val suffix = byteArrayOf(
            checksum.toByte(), checksum.shr(8).toByte(),
            0x03
        )
        var byteArray = data + suffix
//        byteArray += ByteArray(20 - byteArray.size) { 0x00.toByte() }
        Log.e("!!!", "getDataSingle ${byteArray.map { String.format("%x", it) }.joinToString("")}")
        return writeCharacteristic(UUID.fromString(UUID_NRF_REQUEST), byteArray)
            ?.map {
                Log.e("!!!", "${it.map { String.format("%x", it) }.joinToString("")}")
                Triple(0, 1, 2)
            }
    }

    fun setDataCompletable(index: Int, amp: Int, sens: Int, sensorType: Int): Completable? {
        val data = byteArrayOf(0x02,
            0x05, 0x00,
            index.toByte(), 0x00,
            amp.toByte(), 0x00,
            sens.toByte(), 0x00,
            0x00, 0x00, 0x00, 0x00,
            sensorType.toByte(), 0x00
        )
        val checksum = getChecksum(data, 1, data.count())
        val suffix = byteArrayOf(
            checksum.toByte(), checksum.shr(8).toByte(),
            0x03
        )
        var byteArray = data + suffix
//        byteArray += ByteArray(20 - byteArray.size) { 0x00.toByte() }
        Log.e("!!!", "setDataCompletable ${byteArray.map { String.format("%x", it) }.joinToString("")}")
        return writeCharacteristic(UUID.fromString(UUID_NRF_REQUEST), byteArray)
            ?.ignoreElement()
    }

    private fun getChecksum(byteArray: ByteArray, from: Int, to: Int): Int {
        var xorValue = byteArray[from].toUByte().toInt()
        for (index in from + 1 until to) {
            val value = byteArray[index].toUByte().toInt()
            xorValue = xorValue.xor(value)
        }
        return xorValue
    }

    companion object {
        private val LOG_TAG = CarSeatDevice::class.java.simpleName

        private const val BLE_NAME = "ALGORIGO"

        private const val NRF_REQUEST_FIRST_DATA_SIZE = 244
        private const val NRF_REQUSET_SECOND_DATA_SIZE = 123

        fun isMatch(bluetoothDevice: BluetoothDevice): Boolean {
            return bluetoothDevice.address.toUpperCase() == "FC:72:86:40:F4:45"
//            return bluetoothDevice.name?.equals(BLE_NAME) ?: false
        }

        fun getScanFilter(): BleScanFilter {
            return BleScanFilter.Builder()
                .setDeviceAddress("FC:72:86:40:F4:45")
//                .setDeviceName(BLE_NAME)
                .build()
        }

        private const val UUID_NRF_RESPONSE = "0000FFF1-0000-1000-8000-00805F9B34FB"
        private const val UUID_NRF_REQUEST =  "0000FFF2-0000-1000-8000-00805F9B34FB"
    }
}