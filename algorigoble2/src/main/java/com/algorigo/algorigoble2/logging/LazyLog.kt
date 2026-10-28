package com.algorigo.algorigoble2.logging

import com.algorigo.logger.L
import com.algorigo.logger.Level
import com.algorigo.logger.LogManager
import com.algorigo.logger.Tag

// L.debug 등은 메시지 문자열을 받으므로, 로그가 꺼져 있어도 호출하는 쪽에서 문자열(hex 변환, 기기 이름 조회 IPC 등)을 만든다.
// 아래 함수는 L.log 와 같은 logger 의 레벨을 먼저 확인하고 남길 때만 메시지를 만든다. (남기는 결과는 L.debug/L.verbose 와 같다)

internal inline fun logDebug(tag: Tag, message: () -> String) {
    if (isLoggable(tag, Level.DEBUG)) {
        L.debug(tag, message())
    }
}

/** notification 처럼 자주 남기는 로그. DEBUG 보다 낮은 VERBOSE 레벨이다. */
internal inline fun logVerbose(tag: Tag, message: () -> String) {
    if (isLoggable(tag, Level.VERBOSE)) {
        L.verbose(tag, message())
    }
}

@PublishedApi
internal fun isLoggable(tag: Tag, level: Level): Boolean = LogManager.getLogger(tag).isLoggable(level.level)
