package com.algorigo.algorigoble2.logging

abstract class Logger {
    abstract val logLevel: Int
    abstract fun logDebug(tag: String, log: String)
    abstract fun logInfo(tag: String, log: String)
    abstract fun logWarning(tag: String, log: String)
    abstract fun logError(tag: String, log: String)

    companion object {
        const val LOG_LEVEL_DEBUG = 3
        const val LOG_LEVEL_INFO = 2
        const val LOG_LEVEL_WARNING = 1
        const val LOG_LEVEL_ERROR = 0
    }
}