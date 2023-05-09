package com.algorigo.algorigoble2

import android.bluetooth.le.ScanCallback
import java.lang.Exception

class ScanException(message: String) : Exception(message) {

    companion object {

        fun fromValue(value: Int): ScanException {
            return when (value) {

                /**
                 * Fails to start scan as BLE scan with the same settings is already started by the app.
                 */
                ScanCallback.SCAN_FAILED_ALREADY_STARTED -> ScanException("errorCode: $value, Failed to start scan: BLE scan with the same settings is already started by the app")

                /**
                 * Fails to start scan as app cannot be registered.
                 */
                ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> ScanException("errorCode: $value, Failed to start scan: app cannot be registered")

                /**
                 * Fails to start scan due an internal error
                 */
                ScanCallback.SCAN_FAILED_INTERNAL_ERROR -> ScanException("errorCode: $value, Failed to start scan: internal error")

                /**
                 * Fails to start power optimized scan as this feature is not supported.
                 */
                ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED -> ScanException("errorCode: $value, Failed to start scan: power optimized scan feature is not supported")

                /**
                 * Fails to start scan as it is out of hardware resources.
                 */
                5 -> ScanException("errorCode: $value, Failed to start scan: out of hardware resources")

                /**
                 * Fails to start scan as application tries to scan too frequently.
                 */
                6 -> ScanException("errorCode: $value, Failed to start scan: application tries to scan too frequently")

                /**
                 * Fails to start scan with unknown error
                 */
                else -> ScanException("errorCode: $value, Failed to start scan with unknown error")
            }
        }
    }
}