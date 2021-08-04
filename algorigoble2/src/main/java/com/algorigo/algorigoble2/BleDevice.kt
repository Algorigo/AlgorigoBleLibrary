package com.algorigo.algorigoble2

class BleDevice {

    enum class ConnectionState(var status: String) {
        CONNECTING("CONNECTING"),
        CONNECTED("CONNECTED"),
        DISCONNECTED("DISCONNECTED"),
        DISCONNECTING("DISCONNECTING")
    }

    private lateinit var engine: BleManagerEngine

}
