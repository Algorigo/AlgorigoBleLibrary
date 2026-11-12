package com.algorigo.algorigoble2

import java.util.UUID

/**
 * 연결된 기기의 GATT 서비스 하나. [characteristics]는 이 서비스에 속한 characteristic 이다.
 * [isPrimary]가 false 이면 다른 서비스에 포함되어 쓰이는 secondary 서비스다.
 */
data class BleService(
    val uuid: UUID,
    val isPrimary: Boolean,
    val characteristics: List<BleCharacteristic>,
)
