package com.algorigo.algorigoble2.rxandroidble

import android.content.Context
import android.util.Log
import com.algorigo.algorigoble2.*
import com.polidea.rxandroidble2.RxBleClient
import com.polidea.rxandroidble2.RxBleDevice
import com.polidea.rxandroidble2.exceptions.BleException
import com.polidea.rxandroidble2.scan.ScanSettings
import hu.akarnokd.rxjava3.bridge.RxJavaBridge.toV3Observable
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.exceptions.UndeliverableException
import io.reactivex.rxjava3.plugins.RxJavaPlugins

internal class RxAndroidBleEngine(private val context: Context, bleDeviceDelegate: BleManager.BleDeviceDelegate) : BleManagerEngine(bleDeviceDelegate) {

    private val rxAndroidBle: RxBleClient

    init {
        rxAndroidBle = RxBleClient.create(context)
        RxJavaPlugins.setErrorHandler { error ->
            if (error is UndeliverableException && error.cause is BleException) {
                return@setErrorHandler // ignore BleExceptions as they were surely delivered at least once
            }
            // add other custom handlers if needed
            throw error
        }
    }

    override fun scanObservable(
        scanSettings: BleScanSettings,
        vararg scanFilters: BleScanFilter
    ): Observable<List<BleDevice>> {
        val bleDeviceList = mutableListOf<BleDevice>()
        return rxAndroidBle.scanBleDevices(ScanSettings.Builder().build()).`as`(toV3Observable())
            .map {
                getBleDevice(it.bleDevice)?.also {
                    if (!bleDeviceList.contains(it)) {
                        bleDeviceList.add(it)
                    }
                }
                bleDeviceList
            }
    }

    override fun getDevice(macAddress: String): BleDevice? {
        TODO("Not yet implemented")
    }

    override fun getBondedDevice(macAddress: String): BleDevice? {
        TODO("Not yet implemented")
    }

    override fun getBondedDevices(): List<BleDevice> {
        TODO("Not yet implemented")
    }

    override fun getConnectedDevice(macAddress: String): BleDevice? {
        TODO("Not yet implemented")
    }

    override fun getConnectedDevices(): List<BleDevice> {
        TODO("Not yet implemented")
    }

    override fun getConnectionStateObservable(): Observable<BleManager.ConnectionStateData> {
        TODO("Not yet implemented")
    }

    private fun getBleDevice(rxBleDevice: RxBleDevice): BleDevice? {
        return getBleDevice(rxBleDevice.bluetoothDevice)?.apply {
//            (bleDeviceEngine as BleDeviceEngineRxAndroidBle).rxBleDevice = rxBleDevice
//            getConnectionStateObservable()
//                .subscribe({ state ->
//                    connectionStateRelay.accept(ConnectionStateData(this, state))
//                }, {
//                    Log.e(TAG, "", it)
//                })
        }
    }
}