package com.algorigo.algorigoble2.impl

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
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

    /** 스캔 결과 하나. [lost]이면 기기가 더 이상 보이지 않는다는 결과(CALLBACK_TYPE_MATCH_LOST)이다. */
    internal class ScanEvent(val result: ScanResult, val lost: Boolean)

    private inner class BleScanCallback(val scanFilters: Array<out BleScanFilter>) : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            super.onScanResult(callbackType, result)
            result?.let {
                if (callbackType and ScanSettings.CALLBACK_TYPE_MATCH_LOST != 0) {
                    // 발견됐던 기기가 사라졌다는 알림이므로 rssi 등 필터와 관계없이 전달한다
                    L.debug(Ble.Engine.Default, "scan match lost : ${it.device?.address}")
                    scanSubject.onNext(ScanEvent(it, lost = true))
                } else if (isOk(it)) {
                    scanSubject.onNext(ScanEvent(it, lost = false))
                }
            }
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>?) {
            super.onBatchScanResults(results)
            results?.let { scanResults ->
                scanResults.forEach {
                    if (isOk(it)) {
                        scanSubject.onNext(ScanEvent(it, lost = false))
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

    private val scanSubject = PublishSubject.create<ScanEvent>()

    private fun startScanObservable(scanSettings: BleScanSettings, vararg scanFilters: BleScanFilter): Observable<ScanEvent> {
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
        internal fun scanObservable(bluetoothAdapter: BluetoothAdapter, bleScanSettings: BleScanSettings, vararg bleScanFilters: BleScanFilter): Observable<ScanEvent> {
            return BleScanner(bluetoothAdapter)
                .startScanObservable(bleScanSettings, *bleScanFilters)
        }
    }
}
