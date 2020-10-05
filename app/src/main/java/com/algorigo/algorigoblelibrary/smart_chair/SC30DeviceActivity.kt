package com.algorigo.algorigoblelibrary.smart_chair

import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import com.algorigo.algorigoble.BleDevice
import com.algorigo.algorigoble.BleManager
import com.algorigo.algorigoblelibrary.R
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.disposables.Disposable
import kotlinx.android.synthetic.main.activity_sc21device.*

class SC30DeviceActivity : AppCompatActivity() {

    private var seatBleDevice32: SC30Device? = null
    private var disposable: Disposable? = null
    private var lastTimestamp: Long? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_sc21device)

        val macAddress = intent.getStringExtra(NAME_MAC_ADDRESS)
        if (macAddress != null) {
            seatBleDevice32 = BleManager.getInstance().getDevice(macAddress) as? SC30Device
        } else {
            finish()
            return
        }
        amplificationEdit.setText(seatBleDevice32?.getAmplification().toString())
        potentiometerEdit.setText(seatBleDevice32?.getPotentiometer().toString())
        sensingIntervalEdit.setText(seatBleDevice32?.getSensingInterval().toString())
        commIntervalEdit.setText(seatBleDevice32?.getCommInterval().toString())
        offTimerEdit.setText(seatBleDevice32?.getOffTimer().toString())

        seatBleDevice32?.getConnectionStateObservable()
            ?.subscribe({
                Log.e("!!!", "getConnectionStateObservable: $it")
                if (it == BleDevice.ConnectionState.DISCONNECTED) {
                    finish()
                }
            }, {
                Log.e("!!!", "", it)
            })

        startBtn.setOnClickListener {
            if (disposable != null) {
                disposable?.dispose()
            } else {
                disposable = seatBleDevice32?.sendDataOn()
                    ?.doFinally {
                        disposable = null
                    }
                    ?.observeOn(AndroidSchedulers.mainThread())
                    ?.subscribe({ array ->
//                        Log.e("!!!", "data1:${array.toTypedArray().contentToString()}")
                        gridTextView.setData(array)
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
        amplificationSetBtn.setOnClickListener {
            amplificationEdit.text.toString().toInt().also {
                seatBleDevice32?.setAmplificationCompletable(it)
                    ?.subscribe({
                    }, {
                        Log.e(TAG, "setAmplificationCompletable", it)
                    })
            }
        }
        potentiometerSetBtn.setOnClickListener {
            potentiometerEdit.text.toString().toInt().also {
                seatBleDevice32?.setPotentiometerCompletable(it)
                    ?.subscribe({
                    }, {
                        Log.e(TAG, "setPotentiometerCompletable", Exception(it))
                    })
            }
        }
        sensingIntervalSetBtn.setOnClickListener {
            sensingIntervalEdit.text.toString().toInt().also {
                seatBleDevice32?.setSensingIntervalCompletable(it)
                    ?.subscribe({
                    }, {
                        Log.e(TAG, "setSensingIntervalCompletable", Exception(it))
                    })
            }
        }
        commIntervalSetBtn.setOnClickListener {
            commIntervalEdit.text.toString().toInt().also {
                seatBleDevice32?.setCommIntervalCompletable(it)
                    ?.subscribe({
                    }, {
                        Log.e(TAG, "setCommIntervalCompletable", Exception(it))
                    })
            }
        }
        offTimerSetBtn.setOnClickListener {
            offTimerEdit.text.toString().toInt().also {
                seatBleDevice32?.setOffTimerCompletable(it)
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
    }

    companion object {
        private val TAG = SC30DeviceActivity::class.java.simpleName

        const val NAME_MAC_ADDRESS = "NAME_MAC_ADDRESS"
    }
}
