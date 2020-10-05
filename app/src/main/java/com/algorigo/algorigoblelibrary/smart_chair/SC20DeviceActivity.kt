package com.algorigo.algorigoblelibrary.smart_chair

import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import com.algorigo.algorigoble.BleManager
import com.algorigo.algorigoblelibrary.R
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.disposables.Disposable
import kotlinx.android.synthetic.main.activity_sc20device.*

class SC20DeviceActivity : AppCompatActivity() {

    private var SC20Device: SC20Device? = null
    private var disposable: Disposable? = null
    private var disposable2: Disposable? = null
    private var lastTimestamp: Long? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_sc20device)

        val macAddress = intent.getStringExtra(NAME_MAC_ADDRESS)
        if (macAddress != null) {
            SC20Device = BleManager.getInstance().getDevice(macAddress) as? SC20Device
        } else {
            finish()
            return
        }
        amplificationEdit.setText(SC20Device?.getAmplification().toString())
        potentiometerEdit.setText(SC20Device?.getPotentiometer().toString())
        sensingIntervalEdit.setText(SC20Device?.getSensingInterval().toString())
        commIntervalEdit.setText(SC20Device?.getCommInterval().toString())
        offTimerEdit.setText(SC20Device?.getOffTimer().toString())

        startBtn.setOnClickListener {
            if (disposable != null) {
                disposable?.dispose()
            } else {
                disposable = SC20Device?.sendDataOn()
                    ?.doFinally {
                        disposable = null
                    }
                    ?.observeOn(AndroidSchedulers.mainThread())
                    ?.subscribe({ array ->
//                        Log.e("!!!", "data1:${array.toTypedArray().contentToString()}")
                        dataView.setText(Array(array.size){ if (it%10 == 9) array[it].toString()+"\n" else array[it].toString() }.contentToString())
                        System.currentTimeMillis().let { timestamp ->
                            lastTimestamp?.let {
                                intervalView.setText((timestamp-it).toString())
                            }
                            lastTimestamp = timestamp
                        }
                    }, {
                        Log.e(TAG, "stream1 error", Exception(it))
                    })
            }
        }
        startBtn2.setOnClickListener {
            if (disposable2 != null) {
                disposable2?.dispose()
            } else {
                disposable2 = SC20Device?.sendDataOn()
                    ?.doFinally {
                        disposable2 = null
                    }
                    ?.observeOn(AndroidSchedulers.mainThread())
                    ?.subscribe({ array ->
//                        Log.e("!!!", "data2:${array.toTypedArray().contentToString()}")
                        dataView.setText(Array(array.size){ if (it%10 == 9) array[it].toString()+"\n" else array[it].toString() }.contentToString())
                        System.currentTimeMillis().let { timestamp ->
                            lastTimestamp?.let {
                                intervalView.setText((timestamp-it).toString())
                            }
                            lastTimestamp = timestamp
                        }
                    }, {
                        Log.e(TAG, "stream2 error", Exception(it))
                    })
            }
        }
        amplificationSetBtn.setOnClickListener {
            amplificationEdit.text.toString().toInt().also {
                SC20Device?.setAmplificationCompletable(it)
                        ?.subscribe({
                        }, {
                            Log.e(TAG, "setAmplificationCompletable", it)
                        })
            }
        }
        potentiometerSetBtn.setOnClickListener {
            potentiometerEdit.text.toString().toInt().also {
                SC20Device?.setPotentiometerCompletable(it)
                        ?.subscribe({
                        }, {
                            Log.e(TAG, "setPotentiometerCompletable", Exception(it))
                        })
            }
        }
        sensingIntervalSetBtn.setOnClickListener {
            sensingIntervalEdit.text.toString().toInt().also {
                SC20Device?.setSensingIntervalCompletable(it)
                        ?.subscribe({
                        }, {
                            Log.e(TAG, "setSensingIntervalCompletable", Exception(it))
                        })
            }
        }
        commIntervalSetBtn.setOnClickListener {
            commIntervalEdit.text.toString().toInt().also {
                SC20Device?.setCommIntervalCompletable(it)
                        ?.subscribe({
                        }, {
                            Log.e(TAG, "setCommIntervalCompletable", Exception(it))
                        })
            }
        }
        offTimerSetBtn.setOnClickListener {
            offTimerEdit.text.toString().toInt().also {
                SC20Device?.setOffTimerCompletable(it)
                        ?.subscribe({
                        }, {
                            Log.e(TAG, "setOffTimerCompletable", Exception(it))
                        })
            }
        }
    }

    override fun onPause() {
        super.onPause()
        disposable?.dispose()
        disposable2?.dispose()
    }

    companion object {
        private val TAG = SC20DeviceActivity::class.java.simpleName

        const val NAME_MAC_ADDRESS = "NAME_MAC_ADDRESS"
    }
}
