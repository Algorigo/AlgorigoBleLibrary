package com.algorigo.algorigoble2.exception

import com.algorigo.algorigoble2.BleException

/** 스캔에 필요한 시스템 서비스(블루투스, API 30 이하의 위치)가 꺼져 있는 경우 */
sealed class SystemServiceException(message: String) : BleException(message) {
    class AllUnavailableException(message: String): SystemServiceException(message)
    class BluetoothUnavailableException(message: String) : SystemServiceException(message)
    class LocationUnavailableException(message: String) : SystemServiceException(message)
}
