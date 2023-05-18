package com.algorigo.algorigoble2.exception

sealed class SystemServiceException(message: String) : Exception(message) {
    class AllUnavailableException(message: String): SystemServiceException(message)
    class BluetoothUnavailableException(message: String) : SystemServiceException(message)
    class LocationUnavailableException(message: String) : SystemServiceException(message)
}
