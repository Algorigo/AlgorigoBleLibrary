package com.algorigo.algorigoble2.rx_util

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import io.reactivex.rxjava3.core.Observable

object RxBroadcastReceiver {

    fun broadCastReceiverObservable(context: Context, intentFilter: IntentFilter): Observable<Intent> {
        return Observable.create { emitter ->
            val broadcastReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    if (intent != null) {
                        emitter.onNext(intent)
                    }
                }
            }

            context.registerReceiver(broadcastReceiver, intentFilter)

            emitter.setCancellable {
                context.unregisterReceiver(broadcastReceiver)
            }
        }
    }
}
