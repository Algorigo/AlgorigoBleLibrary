package com.algorigo.algorigoble2

import com.algorigo.algorigoble2.virtual.VirtualDevice
import io.reactivex.rxjava3.core.Single
import java.util.UUID

class TestVirtualDevice : VirtualDevice(ADDRESS, "TestVirtualDevice") {
    override fun getCharacteristicsSingle(): Single<List<BleCharacterisic>> = Single.just(listOf())
    override fun readCharacteristicSingle(characteristicUuid: UUID): Single<ByteArray> = Single.just(byteArrayOf())
    override fun writeCharacteristicSingle(characteristicUuid: UUID, byteArray: ByteArray): Single<ByteArray> = Single.just(byteArray)
    override fun onNotificationStarted(type: BleDevice.NotificationType, characteristicUuid: UUID) {}
    override fun onNotificationStop(characteristicUuid: UUID) {}

    companion object {
        const val ADDRESS = "12:34:56:78:90:ab"
    }
}
