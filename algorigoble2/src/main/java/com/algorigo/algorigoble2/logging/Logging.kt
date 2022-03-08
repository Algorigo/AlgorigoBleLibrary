package com.algorigo.algorigoble2.logging

internal class Logging(logger: Logger?) {

    internal val d: (String) -> Unit
    internal val i: (String) -> Unit
    internal val w: (String) -> Unit
    internal val e: (String) -> Unit



    init {
        if (logger != null) {
            d = if (logger.logLevel >= Logger.LOG_LEVEL_DEBUG) {
                { log: String ->
                    logger.logDebug(LOG_TAG, log)
                }
            } else {
                NOT_LOG
            }
            i = if (logger.logLevel >= Logger.LOG_LEVEL_INFO) {
                { log: String ->
                    logger.logInfo(LOG_TAG, log)
                }
            } else {
                NOT_LOG
            }
            w = if (logger.logLevel >= Logger.LOG_LEVEL_WARNING) {
                { log: String ->
                    logger.logWarning(LOG_TAG, log)
                }
            } else {
                NOT_LOG
            }
            e = if (logger.logLevel >= Logger.LOG_LEVEL_ERROR) {
                { log: String ->
                    logger.logError(LOG_TAG, log)
                }
            } else {
                NOT_LOG
            }
        } else {
            d = NOT_LOG
            i = NOT_LOG
            w = NOT_LOG
            e = NOT_LOG
        }
    }

    companion object {
        private const val LOG_TAG = "AlgorigoBle2"
        private val NOT_LOG = { _: String -> }
    }
}