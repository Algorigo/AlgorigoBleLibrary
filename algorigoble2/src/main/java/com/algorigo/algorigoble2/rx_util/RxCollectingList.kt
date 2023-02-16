package com.algorigo.algorigoble2.rx_util

import io.reactivex.rxjava3.core.Observable

fun <T, K> Observable<T>.collectListLastSortedIndex(keySelector: (T) -> K): Observable<List<T>> {
    val list = mutableListOf<K>()
    val map = mutableMapOf<K, T>()
    return concatMap { value ->
        val key = keySelector.invoke(value)
        if (!list.contains(key)) {
            list.add(key)
        }
        map[key] = value
        Observable.just(list.mapNotNull { map[it] })
    }
}
