package com.algorigo.algorigoblelibrary

import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.schedulers.Schedulers
import io.reactivex.rxjava3.subjects.PublishSubject
import kotlin.math.roundToInt

open class RequestPermissionActivity : AppCompatActivity() {

    class PermissionRationaleRequiredException(val permission: String): Exception()
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
            if (grantResultsMap.size > 0) {
                subject.onError(PermissionNotGranted(grantResultsMap.keys.toTypedArray()))
            } else {
                subject.onComplete()
            }
        } else {
            super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        }
    }

    fun requestPermissionCompletable(permissions: Array<String>): Completable {
        val applicationContext = applicationContext
        return Completable.create {
            var allGranted = true
            for (permission in permissions) {
                allGranted = allGranted && (ContextCompat.checkSelfPermission(applicationContext, permission) == PackageManager.PERMISSION_GRANTED)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && shouldShowRequestPermissionRationale(permission)) {
                    it.onError(PermissionRationaleRequiredException(permission))
                    return@create
                }
            }
            if (!allGranted) {
                it.onError(PermissionNotGranted(arrayOf()))
                return@create
            }
            it.onComplete()
        }
            .subscribeOn(Schedulers.io())
            .onErrorResumeNext {
                if (it is PermissionNotGranted) {
                    val pair = generateRequestCode()
                    pair.second.ignoreElements()
                        .doOnSubscribe {
                            ActivityCompat.requestPermissions(this, permissions, pair.first)
                        }
                } else {
                    Completable.error(it)
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