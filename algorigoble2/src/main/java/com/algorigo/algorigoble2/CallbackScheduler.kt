package com.algorigo.algorigoble2

import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.core.Scheduler
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.schedulers.Schedulers

/**
 * 라이브러리가 사용자 코드에 결과를 전달하는 스레드.
 *
 * GATT 콜백은 바인더 스레드에서, 상태 변경은 상태를 바꾼 스레드(상태 잠금 안)에서 일어난다. 그 스레드에서 사용자 코드를 바로 실행하면
 * 사용자가 오래 걸리는 처리를 할 때 그 기기의 다음 GATT 콜백과 작업, 다른 스레드의 상태 변경(disconnect 등)이 함께 멈춘다.
 * 그래서 공개 API 의 결과는 이 scheduler 로 넘겨서 전달한다. 구독마다 하나의 worker 에서 순서대로 전달된다.
 */
internal object CallbackScheduler {

    // 테스트에서 전달 시점을 제어하기 위해 바꾼다
    @Volatile
    var override: Scheduler? = null

    // RxJavaPlugins 의 io scheduler 설정이 적용되도록 매번 읽는다
    val scheduler: Scheduler
        get() = override ?: Schedulers.io()
}

// 값 스트림은 에러보다 먼저 받은 값을 모두 전달한다 (delayError). 예를 들어 연결이 끊기기 직전에 받은 notification 을 잃지 않는다
internal fun <T : Any> Observable<T>.deliverToCallback(): Observable<T> = observeOn(CallbackScheduler.scheduler, true)

internal fun <T : Any> Single<T>.deliverToCallback(): Single<T> = observeOn(CallbackScheduler.scheduler)

internal fun Completable.deliverToCallback(): Completable = observeOn(CallbackScheduler.scheduler)
