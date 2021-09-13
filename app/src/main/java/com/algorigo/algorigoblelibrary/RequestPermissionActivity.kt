package com.algorigo.algorigoblelibrary

import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.schedulers.Schedulers
import io.reactivex.rxjava3.subjects.PublishSubject
import kotlin.math.roundToInt

open class RequestPermissionActivity : AppCompatActivity() {

    class PermissionRationaleRequiredException(val permissions: List<String>): Exception()
    class PermissionNotGranted(val permissions: Array<String>): Exception()

    val subjects = mutableMapOf<Int, PublishSubject<Any>>()

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        val subject = subjects[requestCode]
        if (subject != null) {
            val grantResultsMap = permissions
                .zip(grantResults.toTypedArray())
                .toMap()
                .filter { it.value != PackageManager.PERMISSION_GRANTED }
            if (grantResultsMap.isNotEmpty()) {
                subject.onError(PermissionNotGranted(grantResultsMap.keys.toTypedArray()))
            } else {
                subject.onComplete()
            }
        } else {
            super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        }
    }

    fun requestPermissionCompletable(permissions: Array<String>, rationaleExplained: Boolean = false): Completable {
        val applicationContext = applicationContext
        return Completable.create { emitter ->
            val notGrantedList = permissions.filter { ContextCompat.checkSelfPermission(applicationContext, it) != PackageManager.PERMISSION_GRANTED }
            if (notGrantedList.isNotEmpty()) {
                if (!rationaleExplained &&
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    val rationaleRequired = notGrantedList.filter { shouldShowRequestPermissionRationale(it) }
                    if (rationaleRequired.isNotEmpty()) {
                        emitter.onError(PermissionRationaleRequiredException(rationaleRequired))
                        return@create
                    }
                }
                emitter.onError(PermissionNotGranted(notGrantedList.toTypedArray()))
                return@create
            }
            emitter.onComplete()
        }
            .subscribeOn(Schedulers.io())
            .onErrorResumeNext { exception ->
                if (exception is PermissionNotGranted) {
                    val pair = generateRequestCode()
                    pair.second.ignoreElements()
                        .doOnSubscribe {
                            ActivityCompat.requestPermissions(this, exception.permissions, pair.first)
                        }
                } else {
                    Completable.error(exception)
                }
            }
    }

    private fun generateRequestCode(): Pair<Int, PublishSubject<Any>> {
        synchronized(true) {
            var random: Int
            do {
                random = (Math.random()*Int.MAX_VALUE).roundToInt()
            } while (subjects.containsKey(random))
            val subject = PublishSubject.create<Any>()
            subjects[random] = subject
            return Pair(random, subject)
        }
    }
}