package com.algorigo.algorigoble2.rx_util

import io.reactivex.rxjava3.subjects.PublishSubject
import org.junit.Assert.assertEquals
import org.junit.Test

class RxCollectingListTest {

    private data class Item(val key: String, val value: Int)

    @Test
    fun keepsFirstSeenOrder_andReplacesValueOfSameKey() {
        val source = PublishSubject.create<Item>()
        val lists = source.collectListLastSortedIndex { it.key }.test()

        source.onNext(Item("a", 1))
        source.onNext(Item("b", 1))
        source.onNext(Item("a", 2))

        assertEquals(listOf(Item("a", 2), Item("b", 1)), lists.values().last())
        assertEquals(3, lists.values().size)
    }

    // 수정 전에는 목록 상태를 함수 호출 시점에 만들어서, 같은 Observable 을 다시 구독하면 이전 구독의 항목이 섞였다
    @Test
    fun eachSubscription_collectsIndependently() {
        val source = PublishSubject.create<Item>()
        val collected = source.collectListLastSortedIndex { it.key }

        val first = collected.test()
        source.onNext(Item("a", 1))
        first.dispose()
        val second = collected.test()
        source.onNext(Item("b", 1))

        assertEquals(listOf(Item("b", 1)), second.values().last())
    }
}
