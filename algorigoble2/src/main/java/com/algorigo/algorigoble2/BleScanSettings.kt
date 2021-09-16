package com.algorigo.algorigoble2

import android.bluetooth.le.ScanSettings
import android.os.Build
import androidx.annotation.RequiresApi

class BleScanSettings(
    val scanMode: ScanMode,
    val reportDelayMillis: Long,
    val numOfMatches: MatchNumber,
    val callbackType: CallbackType,
    val matchMode: MatchMode,
    val legacy: Boolean,
    val phy: Int) {

    enum class ScanMode(val value: Int) {
        OPPORTUNISTIC(ScanSettings.SCAN_MODE_OPPORTUNISTIC),
        LOW_POWER(ScanSettings.SCAN_MODE_LOW_POWER),
        BALANCED(ScanSettings.SCAN_MODE_BALANCED),
        LOW_LATENCY(ScanSettings.SCAN_MODE_LOW_LATENCY),
    }

    enum class CallbackType(val value: Int) {
        ALL_MATCHES(ScanSettings.CALLBACK_TYPE_ALL_MATCHES),
        FIRST_MATCH(ScanSettings.CALLBACK_TYPE_FIRST_MATCH),
        MATCH_LOST(ScanSettings.CALLBACK_TYPE_MATCH_LOST),
        FIRST_MATCH_AND_MATCH_LOST(ScanSettings.CALLBACK_TYPE_FIRST_MATCH or ScanSettings.CALLBACK_TYPE_MATCH_LOST),
    }

    enum class MatchNumber(val value: Int) {
        ONE_ADVERTISEMENT(ScanSettings.MATCH_NUM_ONE_ADVERTISEMENT),
        FEW_ADVERTISEMENT(ScanSettings.MATCH_NUM_FEW_ADVERTISEMENT),
        MAX_ADVERTISEMENT(ScanSettings.MATCH_NUM_MAX_ADVERTISEMENT),
    }

    enum class MatchMode(val value: Int) {
        AGGRESSIVE(ScanSettings.MATCH_MODE_AGGRESSIVE),
        STICKY(ScanSettings.MATCH_MODE_STICKY),
    }

    class Builder {
        private var scanMode = ScanMode.LOW_POWER
        private var reportDelayMillis: Long = 0
        private var numOfMatchesPerFilter = MatchNumber.MAX_ADVERTISEMENT
        private var callbackType = CallbackType.ALL_MATCHES
        private var matchMode = MatchMode.AGGRESSIVE
        private var mLegacy = true
        private var mPhy = ScanSettings.PHY_LE_ALL_SUPPORTED

        fun setScanMode(scanMode: ScanMode): Builder {
            this.scanMode = scanMode
            return this
        }

        fun setReportDelay(reportDelayMillis: Long): Builder {
            require(reportDelayMillis >= 0) { "reportDelay must be > 0" }
            this.reportDelayMillis = reportDelayMillis
            return this
        }

        @RequiresApi(Build.VERSION_CODES.M)
        fun setNumOfMatches(numOfMatches: MatchNumber): Builder {
            numOfMatchesPerFilter = numOfMatches
            return this
        }

        @RequiresApi(Build.VERSION_CODES.M)
        fun setCallbackType(callbackType: CallbackType): Builder {
            this.callbackType = callbackType
            return this
        }

        @RequiresApi(Build.VERSION_CODES.M)
        fun setMatchMode(matchMode: MatchMode): Builder {
            this.matchMode = matchMode
            return this
        }

        @RequiresApi(Build.VERSION_CODES.O)
        fun setLegacy(legacy: Boolean): Builder {
            mLegacy = legacy
            return this
        }

        @RequiresApi(Build.VERSION_CODES.O)
        fun setPhy(phy: Int): Builder {
            mPhy = phy
            return this
        }

        fun build(): BleScanSettings {
            return BleScanSettings(
                scanMode,
                reportDelayMillis,
                numOfMatchesPerFilter,
                callbackType,
                matchMode,
                mLegacy,
                mPhy
            )
        }
    }
}