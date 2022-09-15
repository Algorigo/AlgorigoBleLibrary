package com.algorigo.algorigoble2.logging

import android.util.Log

interface Logger {
    fun logDebug(tag: String, logBuilder: () -> String)
    fun logInfo(tag: String, logBuilder: () -> String)
    fun logWarning(tag: String, logBuilder: () -> String)
    fun logError(tag: String, logBuilder: () -> String, throwable: Throwable?)
    fun logCritical(tag: String, logBuilder: () -> String, throwable: Throwable?)
}

internal class DefaultLogger : Logger {
    override fun logDebug(tag: String, logBuilder: () -> String) {}

    override fun logInfo(tag: String, logBuilder: () -> String) {}

    override fun logWarning(tag: String, logBuilder: () -> String) {
        Log.w(tag, logBuilder.invoke())
    }

    override fun logError(tag: String, logBuilder: () -> String, throwable: Throwable?) {
        Log.e(tag, logBuilder.invoke())
    }

    override fun logCritical(tag: String, logBuilder: () -> String, throwable: Throwable?) {
        Log.e(tag, logBuilder.invoke())
    }
}