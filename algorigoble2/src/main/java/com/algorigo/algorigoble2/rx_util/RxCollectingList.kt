package com.algorigo.algorigoble2.rx_util

import io.reactivex.rxjava3.core.Observable

fun <T : Any> Observable<T>.collectList(): Observable<List<T>> {
    val list = mutableListOf<T>()
    return map {
        list.add(it)
        list.toList()
    }
}
