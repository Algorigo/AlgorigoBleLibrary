package com.algorigo.algorigoble2.rxandroidble

import android.content.Context
import android.util.Log
import com.algorigo.algorigoble2.*
import com.algorigo.algorigoble2.impl.BleScanOptionsConverter
import com.polidea.rxandroidble2.RxBleClient
import com.polidea.rxandroidble2.RxBleDevice
import hu.akarnokd.rxjava3.bridge.RxJavaBridge
import io.reactivex.rxjava3.core.Observable
import io.reactivex.schedulers.Schedulers

internal class RxAndroidBleEngine(private val context: Context, bleDeviceDelegate: BleManager.BleDeviceDelegate) : BleManagerEngine(bleDeviceDelegate) {

    private val rxBleClient: RxBleClient = RxBleClient.create(context)

    private val deviceMap: MutableMap<RxBleDevice, BleDevice> = mutableMapOf()

    override fun scanObservable(
        scanSettings: BleScanSettings,
        vararg scanFilters: BleScanFilter
    ): Observable<List<BleDevice>> {
        val bleDeviceList = mutableListOf<BleDevice>()
        return rxBleClient.scanBleDevices(
            BleScanOptionsConverter.convertRxScanSettings(scanSettings),
            *BleScanOptionsConverter.convertRxScanFilters(scanFilters)
        )
            .subscribeOn(Schedulers.io())
            .map { it.bleDevice }
            .map {
                getBleDevice(it)?.also {
                    if (!bleDeviceList.contains(it)) {
                        bleDeviceList.add(it)
                    }
                }
                bleDeviceList.toList()
            }
            .`as`(RxJavaBridge.toV3Observable())
    }

    override fun getDevices(): Collection<BleDevice> {
        return deviceMap.values
    }

    override fun getBondedDevices(): List<BleDevice> {
        return rxBleClient.bondedDevices.mapNotNull {
            getBleDevice(it)
        }
    }

    override fun getConnectedDevices(): List<BleDevice> {
        return getDevices().filter { it.connected }
    }

    override fun getDevice(macAddress: String): BleDevice? {
        return rxBleClient.getBleDevice(macAddress)?.let {
            getBleDevice(it)
        }
    }

    private fun getBleDevice(rxBleDevice: RxBleDevice): BleDevice? {
        return deviceMap[rxBleDevice] ?: (createBleDevice(rxBleDevice)?.also { device ->
            device.getConnectionStateObservable()
                .subscribe({
                    connectionStateRelay.accept(Pair(device, it))
                }, {})
        })
    }

    private fun createBleDevice(rxBleDevice: RxBleDevice): BleDevice? {
        return bleDeviceDelegate.createBleDevice(rxBleDevice.bluetoothDevice)?.also { device ->
            deviceMap[rxBleDevice] = device
        }?.apply {
            initEngine(RxBleDeviceEngine(context, rxBleDevice))
        }
    }
}