import io.reactivex.rxjava3.core.Observable

fun <T : Any> Observable<T>.enumerated(): Observable<Pair<Int, T>> {
    var index = 0
    return serialize()
        .map { Pair(index++, it) }
}