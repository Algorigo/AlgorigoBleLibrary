package com.algorigo.algorigoble

class BleScanSettings {

    enum class ScanMode {
        SCAN_MODE_OPPORTUNISTIC,
        SCAN_MODE_LOW_POWER,
        SCAN_MODE_BALANCED,
        SCAN_MODE_LOW_LATENCY
    }

    enum class CallbackType {
        CALLBACK_TYPE_ALL_MATCHES,
        CALLBACK_TYPE_FIRST_MATCH,
        CALLBACK_TYPE_MATCH_LOST
    }

    internal lateinit var scanMode: ScanMode
    internal lateinit var callbackType: CallbackType

    final class Builder {
        private var scanMode = ScanMode.SCAN_MODE_LOW_POWER
        private var callbackType = CallbackType.CALLBACK_TYPE_ALL_MATCHES

        fun setScanMode(scanMode: ScanMode): Builder {
            this.scanMode = scanMode
            return this
        }

        fun setCallbackType(callbackType: CallbackType): Builder {
            this.callbackType = callbackType
            return this
        }

        fun build(): BleScanSettings {
            return BleScanSettings().also {
                it.scanMode = scanMode
                it.callbackType = callbackType
            }
        }
    }
}