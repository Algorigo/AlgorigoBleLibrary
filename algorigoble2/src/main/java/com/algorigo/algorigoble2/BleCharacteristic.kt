package com.algorigo.algorigoble2

import android.bluetooth.BluetoothGattCharacteristic
import java.util.*

/**
 * 연결된 기기의 characteristic. 가상 기기는 이 클래스를 상속해서 만든다.
 * ([serviceUuid], [properties], [descriptorUuids]는 기본값이 있으므로 가상 기기에서 필요할 때만 override 한다)
 */
abstract class BleCharacteristic {

    abstract val uuid: UUID

    abstract fun isReadable(): Boolean
    abstract fun isWritable(): Boolean
    abstract fun isNotifyAvailable(): Boolean

    /** 이 characteristic 이 속한 서비스의 UUID. 알 수 없으면(가상 기기에서 지정하지 않음) null */
    open val serviceUuid: UUID?
        get() = null

    /**
     * BluetoothGattCharacteristic.PROPERTY_* 의 조합. (READ, WRITE, WRITE_NO_RESPONSE, NOTIFY, INDICATE 등)
     * 기본값은 [isReadable], [isWritable], [isNotifyAvailable]로 만든 READ/WRITE/NOTIFY 이다.
     */
    open val properties: Int
        get() = (if (isReadable()) BluetoothGattCharacteristic.PROPERTY_READ else 0) or
                (if (isWritable()) BluetoothGattCharacteristic.PROPERTY_WRITE else 0) or
                (if (isNotifyAvailable()) BluetoothGattCharacteristic.PROPERTY_NOTIFY else 0)

    /** 이 characteristic 의 descriptor UUID 목록. (CCCD 0x2902 포함) */
    open val descriptorUuids: List<UUID>
        get() = emptyList()

    override fun toString(): String =
        "BleCharacteristic($uuid, service $serviceUuid, properties 0x${Integer.toHexString(properties)}, descriptors $descriptorUuids)"
}

/** 이전 이름(오타). [BleCharacteristic]을 쓴다. */
@Deprecated("Renamed to BleCharacteristic", ReplaceWith("BleCharacteristic", "com.algorigo.algorigoble2.BleCharacteristic"))
typealias BleCharacterisic = BleCharacteristic
