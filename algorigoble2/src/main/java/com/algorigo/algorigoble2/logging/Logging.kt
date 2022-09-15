package com.algorigo.algorigoble2.logging

internal class Logging(val logger: Logger) {

    fun d(log: String) {
        d { log }
    }

    fun d(logBuilder: () -> String) {
        logger.logDebug(LOG_TAG, logBuilder)
    }

    fun i(log: String) {
        i { log }
    }

    fun i(logBuilder: () -> String) {
        logger.logInfo(LOG_TAG, logBuilder)
    }

    fun w(log: String) {
        w { log }
    }

    fun w(logBuilder: () -> String) {
        logger.logInfo(LOG_TAG, logBuilder)
    }

    fun e(log: String, throwable: Throwable? = null) {
        e({ log }, throwable)
    }

    fun e(logBuilder: () -> String, throwable: Throwable? = null) {
        logger.logInfo(LOG_TAG, logBuilder)
    }

    fun c(log: String, throwable: Throwable? = null) {
        c({ log }, throwable)
    }

    fun c(logBuilder: () -> String, throwable: Throwable? = null) {
        logger.logInfo(LOG_TAG, logBuilder)
    }


    companion object {
        private const val LOG_TAG = "AlgorigoBle2"
    }
}