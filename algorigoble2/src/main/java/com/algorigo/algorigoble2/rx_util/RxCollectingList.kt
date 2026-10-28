package com.algorigo.algorigoble2.rx_util

import io.reactivex.rxjava3.core.Observable

/**
 * 받은 값을 key 별로 모아, 처음 받은 순서를 유지한 목록으로 내보낸다. 같은 key 의 값은 마지막 값으로 바뀐다.
 */
fun <T : Any, K> Observable<T>.collectListLastSortedIndex(keySelector: (T) -> K): Observable<List<T>> = Observable.defer {
    // 구독마다 새 목록을 쓴다. (같은 Observable 을 다시 구독해도 이전 구독의 값이 섞이지 않게)
    val list = mutableListOf<K>()
    val map = mutableMapOf<K, T>()
    concatMap { value ->
        val key = keySelector.invoke(value)
        if (!list.contains(key)) {
            list.add(key)
        }
        map[key] = value
        Observable.just(list.mapNotNull { map[it] })
    }
}
