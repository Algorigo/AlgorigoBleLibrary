package com.algorigo.algorigoble.rxandroidble

import android.content.Context
import android.util.Log
import com.algorigo.algorigoble.BleDevice
import com.algorigo.algorigoble.BleManager
import com.algorigo.algorigoble.BleScanFilter
import com.algorigo.algorigoble.BleScanSettings
import com.jakewharton.rxrelay2.PublishRelay
import com.polidea.rxandroidble2.RxBleClient
import com.polidea.rxandroidble2.RxBleDevice
import com.polidea.rxandroidble2.exceptions.BleException
import com.polidea.rxandroidble2.scan.ScanFilter
import com.polidea.rxandroidble2.scan.ScanSettings
import io.reactivex.Completable
import io.reactivex.Observable
import io.reactivex.Observer
import io.reactivex.disposables.Disposable
import io.reactivex.exceptions.UndeliverableException
import io.reactivex.plugins.RxJavaPlugins
import io.reactivex.schedulers.Schedulers
import java.util.concurrent.TimeUnit

class BleManagerRxAndroidBle: BleManager() {

    private inner class DurationScanObservable(val scanDuration: Long, val scanSettings: ScanSettings, vararg val scanFilters: ScanFilter): Observable<List<BleDevice>>() {

        private lateinit var disposable: Disposable
        private var observer: Observer<in List<BleDevice>>? = null
        private val bleDeviceList = mutableListOf<BleDevice>()
        private var completed = false

        override fun subscribeActual(observer: Observer<in List<BleDevice>>?) {
            this.observer = observer

            disposable = rxAndroidBle.scanBleDevices(scanSettings, *scanFilters)
                .doFinally {
                    if (!completed) {
                        completed = true
                        observer?.onComplete()
                    }
                }
                .subscribe({
                    onDeviceFound(it.bleDevice)?.also {
                        if (!bleDeviceList.contains(it)) {
                            bleDeviceList.add(it)
                        }
                    }
                    observer?.onNext(bleDeviceList)
                }, {
                    if (!completed) {
                        completed = true
                        observer?.onError(it)
                    }
                })

            Completable.timer(scanDuration, TimeUnit.MILLISECONDS)
                .subscribe {
                    if (!disposable.isDisposed) {
                        disposable.dispose()
                    }
                }
        }

        fun stopScan() {
            if (!disposable.isDisposed) {
                disposable.dispose()
            }
        }
    }

    private lateinit var rxAndroidBle: RxBleClient
    private var connectionStateRelay = PublishRelay.create<ConnectionStateData>().toSerialized()

    override fun initialize(context: Context) {
        rxAndroidBle = RxBleClient.create(context.applicationContext)
        RxJavaPlugins.setErrorHandler { error ->
            if (error is UndeliverableException && error.cause is BleException) {
                return@setErrorHandler // ignore BleExceptions as they were surely delivered at least once
            }
            // add other custom handlers if needed
            throw error
        }
    }

    override fun scanObservable(scanDuration: Long, scanSettings: BleScanSettings, vararg scanFilters: BleScanFilter): Observable<List<BleDevice>> {
        DurationScanObservable(scanDuration, BleScanOptionsConverterRx.convertScanSettings(scanSettings), *BleScanOptionsConverterRx.convertScanFilters(scanFilters)).also {
            return it
                .subscribeOn(Schedulers.io())
                .doOnDispose {
                    it.stopScan()
                }
        }
    }

    override fun scanObservable(scanSettings: BleScanSettings, vararg scanFilters: BleScanFilter): Observable<List<BleDevice>> {
        val bleDeviceList = mutableListOf<BleDevice>()
        return rxAndroidBle.scanBleDevices(BleScanOptionsConverterRx.convertScanSettings(scanSettings), *BleScanOptionsConverterRx.convertScanFilters(scanFilters))
            .map {
                onDeviceFound(it.bleDevice)?.also {
                    if (!bleDeviceList.contains(it)) {
                        bleDeviceList.add(it)
                    }
                }
                bleDeviceList
            }
    }

    override fun scanObservable(scanDuration: Long): Observable<List<BleDevice>> {
        return scanObservable(scanDuration, bleDeviceDelegate.getBleScanSettings(), *bleDeviceDelegate.getBleScanFilters())
    }

    override fun scanObservable(): Observable<List<BleDevice>> {
        return scanObservable(bleDeviceDelegate.getBleScanSettings(), *bleDeviceDelegate.getBleScanFilters())
    }

    override fun getDevice(macAddress: String): BleDevice? {
        return getDevice(rxAndroidBle.getBleDevice(macAddress).bluetoothDevice)
    }

    override fun getConnectionStateObservable(): Observable<ConnectionStateData> {
        return connectionStateRelay
    }

    private fun onDeviceFound(rxBleDevice: RxBleDevice): BleDevice? {
        return onDeviceFound(rxBleDevice.bluetoothDevice)?.apply {
            (bleDeviceEngine as BleDeviceEngineRxAndroidBle).rxBleDevice = rxBleDevice
            getConnectionStateObservable()
                .subscribe({ state ->
                    connectionStateRelay.accept(ConnectionStateData(this, state))
                }, {
                    Log.e(TAG, "", it)
                })
        }
    }

    companion object {
        private val TAG = BleManagerRxAndroidBle::class.java.simpleName
    }
}