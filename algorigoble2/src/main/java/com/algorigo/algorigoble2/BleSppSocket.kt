package com.algorigo.algorigoble2

import android.annotation.SuppressLint
import android.bluetooth.BluetoothSocket
import com.algorigo.algorigoble2.logging.Ble
import com.algorigo.logger.L
import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.schedulers.Schedulers
import java.io.IOException

@SuppressLint("MissingPermission")
class BleSppSocket private constructor(val bluetoothSocket: BluetoothSocket, connect: Boolean) {

    /** 만들면서 연결한다(블로킹). 연결에 실패하면 소켓을 닫고 IOException 을 던진다. */
    constructor(bluetoothSocket: BluetoothSocket) : this(bluetoothSocket, connect = true)

    init {
        if (connect) {
            connect()
        }
    }

    /** 블로킹으로 연결한다. 실패하면 소켓을 닫고(누수 방지) IOException 을 던진다. */
    internal fun connect() {
        try {
            bluetoothSocket.connect()
        } catch (exception: IOException) {
            close()
            throw exception
        }
    }

    fun close() {
        try {
            bluetoothSocket.close()
        } catch (exception: Exception) {
            L.error(Ble, "", exception)
        }
    }

    /**
     * 이미 받은 데이터를 읽는다. [size]가 -1 이면 받은 만큼 읽고, 양수이면 그만큼 읽는다.
     * 받은 데이터가 없거나 [size]보다 적으면 [NoSuchElementException].
     */
    fun readSingle(size: Int = -1): Single<ByteArray> {
        if (size == 0 || size < -1) {
            return Single.error(IllegalArgumentException("size must be positive or -1"))
        }
        return Single.create<ByteArray> {
            val inputStream = bluetoothSocket.inputStream
            val byteArray = synchronized(inputStream) {
                val available = inputStream.available()
                if (available < size || available == 0) {
                    throw NoSuchElementException()
                }
                val count = if (size > 0) size else available
                val buffer = ByteArray(count)
                var read = 0
                // read() 는 요청한 길이보다 적게 읽을 수 있다. 크기를 지정했으면 다 채울 때까지, 아니면 한 번 읽은 만큼만 돌려준다
                do {
                    val length = inputStream.read(buffer, read, count - read)
                    if (length < 0) {
                        throw IOException("stream is closed")
                    }
                    read += length
                } while (size > 0 && read < count)
                if (read == count) buffer else buffer.copyOf(read)
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
        /** 연결하지 않은 채로 만든다. 연결 전에 할 일(검색 중지 등)을 한 뒤 [connect]를 부른다. */
        internal fun unconnected(bluetoothSocket: BluetoothSocket) = BleSppSocket(bluetoothSocket, connect = false)
    }
}
