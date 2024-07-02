package com.algorigo.algorigoble2.logging

import com.algorigo.logger.Tag


object Ble : Tag() {
    object Engine : Tag() {
        object Rx : Tag()
        object Default : Tag()
    }
    object Device : Tag() {
        object Rx : Tag()
        object Default : Tag()
    }
}
