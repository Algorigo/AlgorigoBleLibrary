package com.algorigo.algorigoble2

import java.util.*

abstract class BleCharacterisic {

    abstract val uuid: UUID

    abstract fun isReadable(): Boolean
    abstract fun isWritable(): Boolean
    abstract fun isNotifyAvailable(): Boolean
}