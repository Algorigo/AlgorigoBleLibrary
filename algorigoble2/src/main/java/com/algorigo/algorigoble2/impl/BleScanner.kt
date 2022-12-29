package com.algorigo.algorigoble2.impl

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import com.algorigo.algorigoble2.BleScanFilter
import com.algorigo.algorigoble2.BleScanSettings
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.subjects.PublishSubject

/**
 * Created by jaehongyoo on 2018. 2. 14..
 */

internal class BleScanner private constructor(private val bluetoothAdapter: BluetoothAdapter){

    private inner class BleScanCallback(val scanFilters: Array<out BleScanFilter>) : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            super.onScanResult(callbackType, result)
            result?.let {
                if (isOk(it)) {
                    scanSubject.onNext(it.device)
                }
            }
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>?) {
            super.onBatchScanResults(results)
            results?.let { scanResults ->
                scanResults.forEach {
                    if (isOk(it)) {
                        scanSubject.onNext(it.device)
                    }
                }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            super.onScanFailed(errorCode)
            scanSubject.onError(IllegalStateException("onScanFailed:$errorCode"))
        }

        private fun isOk(result: ScanResult): Boolean {
            if (scanFilters.isEmpty()) return true
            scanFilters.forEach { if (it.isOk(result.device, result.rssi, result.scanRecord?.bytes)) return true }
            return false
        }
    }

    private val scanSubject = PublishSubject.create<BluetoothDevice>()

    private fun startScanObservable(scanSettings: BleScanSettings, vararg scanFilters: BleScanFilter): Observable<BluetoothDevice> {
        val scanCallback = BleScanCallback(scanFilters)
        return scanSubject
            .doOnSubscribe {
                startScan(scanCallback, scanSettings, *scanFilters)
            }
            .doFinally {
                stopScan(scanCallback)
            }
    }

    @SuppressLint("MissingPermission")
    private fun startScan(scanCallback: ScanCallback, bleScanSettings: BleScanSettings, vararg bleScanFilters: BleScanFilter) {
        bluetoothAdapter.bluetoothLeScanner.startScan(
            BleScanOptionsConverter.convertScanFilters(bleScanFilters),
            BleScanOptionsConverter.convertScanSettings(bleScanSettings),
            scanCallback
        )
    }

    @SuppressLint("MissingPermission")
    private fun stopScan(scanCallback: ScanCallback) {
        bluetoothAdapter.bluetoothLeScanner.stopScan(scanCallback)
    }

    companion object {
        internal fun scanObservable(bluetoothAdapter: BluetoothAdapter, bleScanSettings: BleScanSettings, vararg bleScanFilters: BleScanFilter): Observable<BluetoothDevice> {
            return BleScanner(bluetoothAdapter)
                .startScanObservable(bleScanSettings, *bleScanFilters)
        }
    }
}
