package com.algorigo.algorigoblelibrary

import android.util.Log
import java.nio.ByteBuffer

object ByteUtil {

    fun getIntOfBytes(upper: Byte, lower: Byte): Int {
        return ByteBuffer.wrap(byteArrayOf(0x00.toByte(), 0x00.toByte(), upper, lower)).int
    }

}