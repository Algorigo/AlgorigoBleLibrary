package com.algorigo.algorigoble2.impl

/**
 * Android 는 앱이 30초 안에 startScan 을 5번 넘게 부르면 그 스캔을 조용히 무시한다.
 * (결과도 실패 콜백도 오지 않고 logcat 에만 "App ... is scanning too frequently" 가 남는다)
 * 이 프로세스에서 이 라이브러리가 시작한 스캔을 기록해서, 한도를 넘는 시작은 한도가 풀리는 시각까지 미룬다.
 * 같은 앱의 다른 코드가 부른 startScan 은 알 수 없으므로 막지 못한다.
 */
internal object ScanStartLimiter {

    const val MAX_STARTS = 5
    const val WINDOW_MILLIS = 30_000L
    // 타이머 지연과 시스템이 기록하는 시각의 차이를 고려한 여유
    const val MARGIN_MILLIS = 500L

    /** 예약된 스캔 시작. [startAtMillis]에 시작해야 한도를 넘지 않는다. */
    class Reservation(val startAtMillis: Long, val delayMillis: Long)

    // 시작 시각 순서대로 쌓인다 (미뤄진 예약은 미래 시각)
    private val reservations = mutableListOf<Reservation>()

    /** [nowMillis]에 시작하려는 스캔의 시작 시각을 예약한다. */
    @Synchronized
    fun reserve(nowMillis: Long): Reservation {
        // 창을 벗어난 기록은 더 이상 제한하지 않는다
        reservations.removeAll { it.startAtMillis + WINDOW_MILLIS + MARGIN_MILLIS <= nowMillis }
        val startAt = if (reservations.size < MAX_STARTS) {
            nowMillis
        } else {
            // 이번 시작을 포함해 창 안에 MAX_STARTS 번 이하가 되도록, 마지막 (MAX_STARTS - 1)번보다 앞선 시작이 창을 벗어난 뒤에 시작한다
            maxOf(nowMillis, reservations[reservations.size - MAX_STARTS].startAtMillis + WINDOW_MILLIS + MARGIN_MILLIS)
        }
        return Reservation(startAt, startAt - nowMillis).also { reservations.add(it) }
    }

    /** 시작하지 않은 예약(시작 전에 구독 해제, startScan 실패)을 지운다. */
    @Synchronized
    fun cancel(reservation: Reservation) {
        reservations.removeAll { it === reservation }
    }

    // 테스트마다 TestScheduler 의 시각이 0 부터 다시 시작하므로 기록을 지운다
    @Synchronized
    fun reset() {
        reservations.clear()
    }
}
