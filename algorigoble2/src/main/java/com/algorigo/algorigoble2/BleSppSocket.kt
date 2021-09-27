package com.algorigo.algorigoble2

import android.bluetooth.BluetoothSocket
import android.util.Log
import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.schedulers.Schedulers

class BleSppSocket(val bluetoothSocket: BluetoothSocket) {

    init {
        bluetoothSocket.connect()
    }

    fun close() {
        try {
            bluetoothSocket.close()
        } catch (exception: Exception) {
            Log.e(LOG_TAG, "", exception)
        }
    }

    fun readSingle(size: Int = -1): Single<ByteArray> {
        if (size == 0 || size < -2) {
            return Single.error(IllegalArgumentException("size must be positive or -1"))
        }
        return Single.create<ByteArray> {
            val available = bluetoothSocket.inputStream.available()
            if (available < size || available == 0) {
                throw NoSuchElementException()
            }

            val count = if (size > 0) size else available
            val byteArray = ByteArray(count)
            synchronized(bluetoothSocket.inputStream) {
                bluetoothSocket.inputStream.read(byteArray)
            }

            it.onSuccess(byteArray)
        }.subscribeOn(Schedulers.io())
    }

    fun writeSingle(data: ByteArray): Completable {
        return Completable.create {
            synchronized(bluetoothSocket.outputStream) {
                bluetoothSocket.outputStream.write(data)
            }
            it.onComplete()
        }.subscribeOn(Schedulers.io())
    }

    companion object {
        private val LOG_TAG = BleSppSocket::class.java.simpleName
    }
}
