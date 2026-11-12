package com.algorigo.algorigoble2.testutil

import com.algorigo.algorigoble2.impl.ScanStartLimiter
import io.reactivex.rxjava3.android.plugins.RxAndroidPlugins
import io.reactivex.rxjava3.plugins.RxJavaPlugins
import io.reactivex.rxjava3.schedulers.Schedulers
import io.reactivex.rxjava3.schedulers.TestScheduler
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/**
 * computation scheduler(timer, timeout, delay)와 main thread scheduler 는 [testScheduler]로 시간을 직접 제어하고,
 * io scheduler는 trampoline으로 바꿔 동기적으로 실행한다.
 * RxJavaPlugins 로 전달된 undeliverable 에러는 [undeliverableErrors]에 모은다.
 */
class RxTestSchedulerRule : TestWatcher() {

    val testScheduler = TestScheduler()
    val undeliverableErrors = mutableListOf<Throwable>()

    override fun starting(description: Description) {
        ScanStartLimiter.reset()
        // 스캔 목록의 시간 처리(main thread)도 같은 시간으로 제어한다
        RxAndroidPlugins.setInitMainThreadSchedulerHandler { testScheduler }
        RxAndroidPlugins.setMainThreadSchedulerHandler { testScheduler }
        RxJavaPlugins.setComputationSchedulerHandler { testScheduler }
        RxJavaPlugins.setIoSchedulerHandler { Schedulers.trampoline() }
        RxJavaPlugins.setErrorHandler { undeliverableErrors.add(it) }
    }

    override fun finished(description: Description) {
        RxJavaPlugins.reset()
        RxAndroidPlugins.reset()
        ScanStartLimiter.reset()
    }
}
