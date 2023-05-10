package com.algorigo.algorigoble2.impl

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.util.Log
import com.algorigo.algorigoble2.BleScanFilter
import com.algorigo.algorigoble2.BleScanSettings
import com.algorigo.algorigoble2.ScanException
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.subjects.PublishSubject

/**
 * Created by jaehongyoo on 2018. 2. 14..
 */

internal class BleScanner private constructor(private val bluetoothAdapter: BluetoothAdapter){

    private inner class BleScanCallback(val scanFilters: Array<out BleScanFilter>) : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            super.onScanResult(callbackType, result)
            result?.let {
                if (isOk(it)) {
                    scanSubject.onNext(it)
                }
            }
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>?) {
            super.onBatchScanResults(results)
            results?.let { scanResults ->
                scanResults.forEach {
                    if (isOk(it)) {
                        scanSubject.onNext(it)
                    }
                }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            super.onScanFailed(errorCode)
            scanSubject.onError(ScanException.fromValue(errorCode))
        }

        private fun isOk(result: ScanResult): Boolean {
            if (scanFilters.isEmpty()) return true
            scanFilters.forEach { if (it.isOk(result.device, result.rssi, result.scanRecord?.bytes)) return true }
            return false
        }
    }

    private val scanSubject = PublishSubject.create<ScanResult>()

    private fun startScanObservable(scanSettings: BleScanSettings, vararg scanFilters: BleScanFilter): Observable<ScanResult> {
        val scanCallback = BleScanCallback(scanFilters)
        return scanSubject
            .doOnSubscribe {
                startScan(scanCallback, scanSettings, *scanFilters) {
                    scanSubject.onError(it)
                }
            }
            .doFinally {
                stopScan(scanCallback)
            }
    }

    @SuppressLint("MissingPermission")
    private fun startScan(scanCallback: ScanCallback, bleScanSettings: BleScanSettings, vararg bleScanFilters: BleScanFilter, onError: ((Exception) -> Unit)? = null) {
        try {
            bluetoothAdapter.bluetoothLeScanner.startScan(
                BleScanOptionsConverter.convertScanFilters(bleScanFilters),
                BleScanOptionsConverter.convertScanSettings(bleScanSettings),
                scanCallback
            )
        } catch (e: Exception) {
            Log.e(LOG_TAG, "ScanException: $e")
            onError?.invoke(e)
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopScan(scanCallback: ScanCallback) {
        bluetoothAdapter.bluetoothLeScanner.stopScan(scanCallback)
    }

    companion object {

        private val LOG_TAG = BleScanner::class.java.simpleName

        internal fun scanObservable(bluetoothAdapter: BluetoothAdapter, bleScanSettings: BleScanSettings, vararg bleScanFilters: BleScanFilter): Observable<ScanResult> {
            return BleScanner(bluetoothAdapter)
                .startScanObservable(bleScanSettings, *bleScanFilters)
        }
    }
}
