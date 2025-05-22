package com.algorigo.algorigoble2.logging

import com.algorigo.logger.Tag


object Ble : Tag() {
    internal object Engine : Tag() {
        object Rx : Tag()
        object Default : Tag()
    }
    internal object Device : Tag() {
        object Rx : Tag()
        object Default : Tag()
    }
}
