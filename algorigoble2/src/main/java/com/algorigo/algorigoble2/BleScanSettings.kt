package com.algorigo.algorigoble2

import android.bluetooth.le.ScanSettings
import android.os.Build
import androidx.annotation.RequiresApi

class BleScanSettings private constructor(
    val scanMode: ScanMode,
    val reportDelayMillis: Long,
    val numOfMatches: MatchNumber,
    val callbackType: CallbackType,
    val matchMode: MatchMode,
    val legacy: Boolean,
    val phy: Int,
    /** 이 시간 동안 다시 보이지 않은 기기를 스캔 목록에서 뺀다. 0 이면 빼지 않는다. */
    val deviceLostTimeoutMillis: Long) {

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
        private var deviceLostTimeoutMillis = 0L

        @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
        fun setScanMode(scanMode: ScanMode): Builder {
            this.scanMode = scanMode
            return this
        }

        @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
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

        /**
         * 마지막으로 보인 뒤 [timeoutMillis] 동안 다시 보이지 않은 기기를 스캔 목록에서 뺀다. 기본값 0 은 빼지 않는다.
         * 기기의 광고 주기와 스캔 모드(LOW_POWER 는 듬성듬성 스캔한다), reportDelay 보다 충분히 길게 준다.
         * 목록에서 빠진 뒤에 다시 보이면 목록 끝에 다시 추가된다. (CallbackType 의 MATCH_LOST 와 함께 쓸 수 있다)
         */
        fun setDeviceLostTimeout(timeoutMillis: Long): Builder {
            require(timeoutMillis >= 0) { "deviceLostTimeout must be >= 0" }
            deviceLostTimeoutMillis = timeoutMillis
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
                mPhy,
                deviceLostTimeoutMillis,
            )
        }
    }
}