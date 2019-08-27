package com.algorigo.algorigoble

import android.util.Log
import io.reactivex.Flowable
import io.reactivex.functions.Function
import org.reactivestreams.Publisher
import java.util.concurrent.TimeUnit
import kotlin.reflect.KClass

class RetryWithDelay(private val maxRetries: Int, private val retryDelayMillis: Long, private vararg val exceptionFileters: KClass<*>) : Function<Flowable<out Throwable>, Publisher<*>> {
    private var retryCount: Int = 0

    init {
        this.retryCount = 0
    }

    @Throws(Exception::class)
    override fun apply(flowable: Flowable<out Throwable>): Publisher<*> {
        return flowable
            .flatMap { throwable ->
                Log.e("!!!", "RetryWithDelay flatmap")
                if (exceptionFileters.isNotEmpty()) {
                    var filtered = false
                    for (exceptionFilter in exceptionFileters) {
                        if (exceptionFilter.isInstance(throwable)) {
                            filtered = true
                            break
                        }
                    }
                    if (!filtered) {
                        Log.e("!!!", "RetryWithDelay not filtered")
                        throw throwable
                    }
                    Log.e("!!!", "RetryWithDelay filtered")
                }

                if (++retryCount < maxRetries) {
                    // When this Observable calls onNext, the original
                    // Observable will be retried (i.e. re-subscribed).
                    Log.e("!!!", "RetryWithDelay $retryCount < $maxRetries")
                    Flowable.timer(retryDelayMillis, TimeUnit.MILLISECONDS)
                } else {
                    Log.e("!!!", "RetryWithDelay $retryCount >= $maxRetries")
                    throw throwable
                }
                // Max retries hit. Just pass the error along.
            }
    }
}
