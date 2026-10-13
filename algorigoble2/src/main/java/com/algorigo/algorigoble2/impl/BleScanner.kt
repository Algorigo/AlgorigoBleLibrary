package com.algorigo.algorigoble2.impl

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import com.algorigo.algorigoble2.BleScanFilter
import com.algorigo.algorigoble2.BleScanSettings
import com.algorigo.algorigoble2.exception.SystemServiceException
import com.algorigo.algorigoble2.logging.Ble
import com.algorigo.logger.L
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
            scanSubject.onError(IllegalStateException("onScanFailed:$errorCode"))
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
                startScan(scanCallback, scanSettings, *scanFilters)
            }
            .doFinally {
                stopScan(scanCallback)
            }
    }

    @SuppressLint("MissingPermission")
    private fun startScan(scanCallback: ScanCallback, bleScanSettings: BleScanSettings, vararg bleScanFilters: BleScanFilter) {
        // 블루투스가 켜져 있지 않으면 bluetoothLeScanner 는 null 이다
        val scanner = bluetoothAdapter.bluetoothLeScanner
            ?: throw SystemServiceException.BluetoothUnavailableException("BluetoothLeScanner is unavailable (bluetooth may be off)")
        scanner.startScan(
            BleScanOptionsConverter.convertScanFilters(bleScanFilters),
            BleScanOptionsConverter.convertScanSettings(bleScanSettings),
            scanCallback
        )
    }

    @SuppressLint("MissingPermission")
    private fun stopScan(scanCallback: ScanCallback) {
        // doFinally 에서 호출되므로 여기서 던진 예외는 RxJavaPlugins.onError 로 전달되어 앱이 크래시날 수 있다.
        // 블루투스가 꺼지는 중에는 scanner 가 null 이거나 stopScan 이 IllegalStateException 을 던지므로 무시한다.
        try {
            bluetoothAdapter.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (exception: Exception) {
            L.warning(Ble.Engine.Default, "stopScan failed", exception)
        }
    }

    companion object {
        internal fun scanObservable(bluetoothAdapter: BluetoothAdapter, bleScanSettings: BleScanSettings, vararg bleScanFilters: BleScanFilter): Observable<ScanResult> {
            return BleScanner(bluetoothAdapter)
                .startScanObservable(bleScanSettings, *bleScanFilters)
        }
    }
}
