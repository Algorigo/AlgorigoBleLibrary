package com.algorigo.algorigoble2.impl

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import com.algorigo.algorigoble2.BleScanFilter
import com.algorigo.algorigoble2.BleScanSettings
import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.subjects.PublishSubject
import java.util.concurrent.TimeUnit

/**
 * Created by jaehongyoo on 2018. 2. 14..
 */

internal class BleScanner private constructor(private val bluetoothAdapter: BluetoothAdapter){

    @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
    private inner class BleScanCallback(val scanFilters: Array<out BleScanFilter>) : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            super.onScanResult(callbackType, result)
            Log.e("!!!", "onScanResult:${result}")
            result?.let {
                if (isOk(it)) {
                    scanSubject.onNext(it.device)
                }
            }
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>?) {
            super.onBatchScanResults(results)
            Log.e("!!!", "onBatchScanResults:${results}")
            results?.let {
                it.forEach {
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
            if (scanFilters.size == 0) return true
            scanFilters.forEach { if (it.isOk(result.device, result.rssi, result.scanRecord?.bytes)) return true }
            return false
        }
    }

    @Suppress("deprecation")
    private inner class BleLeScanCallback(val scanFilters: Array<out BleScanFilter>) : BluetoothAdapter.LeScanCallback {
        override fun onLeScan(device: BluetoothDevice?, rssi: Int, scanRecord: ByteArray?) {
            Log.e("!!!", "onLeScan:${device}")
            if (isOk(device, rssi, scanRecord)) {
                device?.let {
                    scanSubject.onNext(it)
                }
            }
        }

        private fun isOk(device: BluetoothDevice?, rssi: Int, scanRecord: ByteArray?): Boolean {
            if (scanFilters.size == 0) return true
            scanFilters.forEach { if (it.isOk(device, rssi, scanRecord)) return true }
            return false
        }
    }

    private val scanSubject = PublishSubject.create<BluetoothDevice>()

    private fun startScanObservable(scanSettings: BleScanSettings, vararg scanFilters: BleScanFilter): Observable<BluetoothDevice> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            startScanObservable21(scanSettings, *scanFilters)
        } else {
            startScanObservable18(scanSettings, *scanFilters)
        }
    }

    @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
    private fun startScanObservable21(scanSettings: BleScanSettings, vararg scanFilters: BleScanFilter): Observable<BluetoothDevice> {
        val scanCallback = BleScanCallback(scanFilters)
        return scanSubject
            .doOnSubscribe {
                Log.e("!!!", "startScanObservable21 doOnSubscribe")
                startScan21(scanCallback, scanSettings, *scanFilters)
            }
            .doFinally {
                stopScan21(scanCallback)
            }
    }

    @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
    private fun startScan21(scanCallback: ScanCallback, bleScanSettings: BleScanSettings, vararg bleScanFilters: BleScanFilter) {
        bluetoothAdapter.bluetoothLeScanner.startScan(
            BleScanOptionsConverter.convertScanFilters(bleScanFilters),
            BleScanOptionsConverter.convertScanSettings(bleScanSettings),
            scanCallback
        )
    }

    @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
    private fun stopScan21(scanCallback: ScanCallback) {
        bluetoothAdapter.bluetoothLeScanner.stopScan(scanCallback)
    }

    @Suppress("deprecation")
    private fun startScanObservable18(scanSettings: BleScanSettings, vararg scanFilters: BleScanFilter): Observable<BluetoothDevice> {
        val leCallback = BleLeScanCallback(scanFilters)
        return scanSubject
            .doOnSubscribe {
                startScan18(leCallback, scanSettings, *scanFilters)
            }
            .doFinally {
                stopScan18(leCallback)
            }
    }

    @Suppress("deprecation")
    private fun startScan18(leCallback: BleLeScanCallback, bleScanSettings: BleScanSettings, vararg bleScanFilters: BleScanFilter) {
        bluetoothAdapter.startLeScan(leCallback)
    }

    @Suppress("deprecation")
    private fun stopScan18(leCallback: BleLeScanCallback) {
        bluetoothAdapter.stopLeScan(leCallback)
    }

    companion object {
        internal fun scanObservable(bluetoothAdapter: BluetoothAdapter, bleScanSettings: BleScanSettings, vararg bleScanFilters: BleScanFilter): Observable<BluetoothDevice> {
            return BleScanner(bluetoothAdapter)
                .startScanObservable(bleScanSettings, *bleScanFilters)
        }
    }
}
