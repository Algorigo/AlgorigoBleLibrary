package com.algorigo.algorigoblelibrary

import android.content.Intent
import android.location.LocationManager
import android.provider.Settings
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.core.Observable

fun FragmentActivity.startActivityAndGrantedObservable(
    intent: Intent,
    isGranted: () -> Boolean,
): Observable<Boolean> {
    var counter = 0
    return Observable.create { emitter ->
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                counter++
                if (counter >= 2) {
                    if (isGranted()) {
                        emitter.onNext(true)
                    } else {
                        emitter.onError(IllegalStateException("Request is not granted."))
                    }
                }
            }
        }

        lifecycle.addObserver(observer)

        if (!isGranted()) {
            startActivity(intent)
        } else {
            emitter.onNext(true)
        }

        emitter.setCancellable {
            lifecycle.removeObserver(observer)
        }
    }
}

fun FragmentActivity.openGPSSettingsAndResultObservable(): Observable<Boolean> {
    return startActivityAndGrantedObservable(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) {
        val locationManager = getSystemService(AppCompatActivity.LOCATION_SERVICE) as LocationManager
        locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
    }
}

fun FragmentActivity.showDialogCompletable(title: String, message: String): Completable {
    return Completable.create { emitter ->
        AlertDialog
            .Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(getString(R.string.confirm)) { dialog, _ ->
                dialog.dismiss()
                emitter.onComplete()
            }
            .setNegativeButton(getString(R.string.cancel)) { dialog, _ ->
                dialog.dismiss()
                emitter.onError(IllegalStateException("Dialog canceled"))
            }
            .setCancelable(false)
            .show()
    }
}
