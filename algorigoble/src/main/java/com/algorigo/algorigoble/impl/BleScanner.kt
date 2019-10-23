package com.algorigo.algorigoble.impl

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.os.Build
import androidx.annotation.RequiresApi
import com.algorigo.algorigoble.BleScanFilter
import com.algorigo.algorigoble.BleScanSettings
import io.reactivex.Completable
import io.reactivex.Observable
import io.reactivex.subjects.PublishSubject
import java.util.concurrent.TimeUnit

/**
 * Created by jaehongyoo on 2018. 2. 14..
 */

internal class BleScanner private constructor(private val bluetoothAdapter: BluetoothAdapter){

    private abstract class FilteredLeScanCallback : BluetoothAdapter.LeScanCallback {

        var scanFilters: Array<out BleScanFilter> = arrayOf()

        override fun onLeScan(device: BluetoothDevice?, rssi: Int, scanRecord: ByteArray?) {
            if (scanFilters.size == 0) {
                onScanResult(device, rssi, scanRecord)
            } else {
                for (scanFilter in scanFilters) {
                    if (scanFilter.isOk(device, rssi, scanRecord)) {
                        onScanResult(device, rssi, scanRecord)
                        break
                    }
                }
            }
        }

        abstract fun onScanResult(device: BluetoothDevice?, rssi: Int, scanRecord: ByteArray?)
    }

    @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
    private lateinit var scanCallback : ScanCallback
    private lateinit var leCallback : FilteredLeScanCallback

    private val scanSubject = PublishSubject.create<BluetoothDevice>()

    init {
        initCallback()
    }

    private fun initCallback() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            initCallback21()
        } else {
            initCallback18()
        }
    }

    @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
    private fun initCallback21() {
        scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult?) {
                super.onScanResult(callbackType, result)
                result?.device?.let {
                    scanSubject.onNext(it)
                }
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>?) {
                super.onBatchScanResults(results)
                results?.let {
                    it.forEach {
                        scanSubject.onNext(it.device)
                    }
                }
            }

            override fun onScanFailed(errorCode: Int) {
                super.onScanFailed(errorCode)
                scanSubject.onError(IllegalStateException("onScanFailed:$errorCode"))
            }
        }
    }

    private fun initCallback18() {
        leCallback = object : FilteredLeScanCallback() {
            override fun onScanResult(device: BluetoothDevice?, rssi: Int, scanRecord: ByteArray?) {
                device?.let {
                    scanSubject.onNext(it)
                }
            }
        }
    }

    private fun startScanObservable(scanSettings: BleScanSettings, vararg scanFilters: BleScanFilter): Observable<BluetoothDevice> {
        return scanSubject
            .doOnSubscribe {
                startScan(scanSettings, *scanFilters)
            }
            .doFinally {
                stopScan()
            }
    }

    private fun startScan(scanSettings: BleScanSettings, vararg scanFilters: BleScanFilter) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            startScan21(scanSettings, *scanFilters)
        } else {
            startScan18(scanSettings, *scanFilters)
        }
    }

    private fun stopScan() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            stopScan21()
        } else {
            stopScan18()
        }
    }

    @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
    private fun startScan21(bleScanSettings: BleScanSettings, vararg bleScanFilters: BleScanFilter) {
        bluetoothAdapter.bluetoothLeScanner.startScan(
            BleScanOptionsConverter.convertScanFilters(bleScanFilters),
            BleScanOptionsConverter.convertScanSettings(bleScanSettings),
            scanCallback
        )
    }

    @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
    private fun stopScan21() {
        bluetoothAdapter.bluetoothLeScanner.stopScan(scanCallback)
    }

    @Suppress("deprecation")
    private fun startScan18(bleScanSettings: BleScanSettings, vararg bleScanFilters: BleScanFilter) {
        leCallback.scanFilters = bleScanFilters
        bluetoothAdapter.startLeScan(leCallback)
    }

    @Suppress("deprecation")
    private fun stopScan18() {
        bluetoothAdapter.stopLeScan(leCallback)
    }

    companion object {
        internal fun scanObservable(bluetoothAdapter: BluetoothAdapter, scanDuration: Long, bleScanSettings: BleScanSettings, vararg bleScanFilters: BleScanFilter): Observable<BluetoothDevice> {
            BleScanner(bluetoothAdapter).also { bleScanner ->
                return bleScanner.startScanObservable(bleScanSettings, *bleScanFilters)
                    .doOnSubscribe {
                        Completable.timer(scanDuration, TimeUnit.MILLISECONDS)
                            .subscribe {
                                bleScanner.scanSubject.onComplete()
                            }
                    }
            }
        }

        internal fun scanObservable(bluetoothAdapter: BluetoothAdapter, bleScanSettings: BleScanSettings, vararg bleScanFilters: BleScanFilter): Observable<BluetoothDevice> {
            return BleScanner(bluetoothAdapter)
                .startScanObservable(bleScanSettings, *bleScanFilters)
        }
    }
}
