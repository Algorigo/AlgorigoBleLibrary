package com.algorigo.algorigoble2.impl

import android.bluetooth.*
import android.content.Context
import android.util.Log
import com.algorigo.algorigoble2.BleDevice
import com.algorigo.algorigoble2.BleDeviceEngine
import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.schedulers.Schedulers
import io.reactivex.rxjava3.subjects.BehaviorSubject

class BleDeviceEngineImpl(private val context: Context, private val bluetoothDevice: BluetoothDevice):
    BleDeviceEngine() {

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
            super.onConnectionStateChange(gatt, status, newState)
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    gatt?.discoverServices()
                }

                BluetoothProfile.STATE_DISCONNECTED -> {
                    connectionStateRelay.accept(BleDevice.ConnectionState.DISCONNECTED)
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
            super.onServicesDiscovered(gatt, status)
            gatt?.let {
                gattSubject.onNext(it)
            }
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt?,
            characteristic: BluetoothGattCharacteristic?,
            status: Int
        ) {
            super.onCharacteristicRead(gatt, characteristic, status)
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt?,
            characteristic: BluetoothGattCharacteristic?,
            status: Int
        ) {
            super.onCharacteristicWrite(gatt, characteristic, status)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt?,
            characteristic: BluetoothGattCharacteristic?
        ) {
            super.onCharacteristicChanged(gatt, characteristic)
        }

        override fun onDescriptorRead(
            gatt: BluetoothGatt?,
            descriptor: BluetoothGattDescriptor?,
            status: Int
        ) {
            super.onDescriptorRead(gatt, descriptor, status)
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt?,
            descriptor: BluetoothGattDescriptor?,
            status: Int
        ) {
            super.onDescriptorWrite(gatt, descriptor, status)
        }

        override fun onPhyUpdate(gatt: BluetoothGatt?, txPhy: Int, rxPhy: Int, status: Int) {
            super.onPhyUpdate(gatt, txPhy, rxPhy, status)
        }

        override fun onPhyRead(gatt: BluetoothGatt?, txPhy: Int, rxPhy: Int, status: Int) {
            super.onPhyRead(gatt, txPhy, rxPhy, status)
        }

        override fun onReliableWriteCompleted(gatt: BluetoothGatt?, status: Int) {
            super.onReliableWriteCompleted(gatt, status)
        }

        override fun onReadRemoteRssi(gatt: BluetoothGatt?, rssi: Int, status: Int) {
            super.onReadRemoteRssi(gatt, rssi, status)
        }

        override fun onMtuChanged(gatt: BluetoothGatt?, mtu: Int, status: Int) {
            super.onMtuChanged(gatt, mtu, status)
        }
    }

    private val gattSubject = BehaviorSubject.create<BluetoothGatt>()

    override val deviceId: String
        get() = bluetoothDevice.address
    override val deviceName: String?
        get() = bluetoothDevice.name

    override fun connectCompletable(): Completable = gattSubject.firstOrError()
        .ignoreElement()
        .subscribeOn(Schedulers.io())
        .observeOn(Schedulers.io())
        .doOnSubscribe {
//            val bonded = bluetoothDevice.createBond()
//            Log.e("!!!", "bonded:$bonded,${bluetoothDevice.bondState}")
            val gatt = bluetoothDevice.connectGatt(context, false, gattCallback)
            Log.e("!!!", "connectGatt:$gatt")
            connectionStateRelay.accept(BleDevice.ConnectionState.CONNECTING)
        }
        .doOnComplete {
            connectionStateRelay.accept(BleDevice.ConnectionState.CONNECTED)
        }
        .doOnError {
            connectionStateRelay.accept(BleDevice.ConnectionState.DISCONNECTED)
        }

    override fun disconnect() {
        gattSubject.blockingFirst().disconnect()
    }
}