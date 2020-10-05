package com.algorigo.algorigoblelibrary.smart_chair

import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import com.algorigo.algorigoble.BleManager
import com.algorigo.algorigoblelibrary.R
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.disposables.Disposable
import kotlinx.android.synthetic.main.activity_sc21device.*

class SC21DeviceActivity : AppCompatActivity() {

    private var seatBleDevice21: SC21Device? = null
    private var disposable: Disposable? = null
    private var disposableVoltage: Disposable? = null
    private var lastTimestamp: Long? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_sc21device)

        val macAddress = intent.getStringExtra(NAME_MAC_ADDRESS)!!
        seatBleDevice21 = BleManager.getInstance().getDevice(macAddress) as? SC21Device
        amplificationEdit.setText(seatBleDevice21?.getAmplification().toString())
        potentiometerEdit.setText(seatBleDevice21?.getPotentiometer().toString())
        sensingIntervalEdit.setText(seatBleDevice21?.getSensingInterval().toString())
        commIntervalEdit.setText(seatBleDevice21?.getCommInterval().toString())
        offTimerEdit.setText(seatBleDevice21?.getOffTimer().toString())

        startBtn.setOnClickListener {
            if (disposable != null) {
                disposable?.dispose()
            } else {
                disposable = seatBleDevice21?.sendDataOn()
                    ?.doFinally {
                        disposable = null
                    }
                    ?.observeOn(AndroidSchedulers.mainThread())
                    ?.subscribe({ array ->
                        Log.e("!!!", "data1:${array.contentToString()}")
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
                seatBleDevice21?.setAmplificationCompletable(it)
                    ?.subscribe({
                    }, {
                        Log.e(TAG, "setAmplificationCompletable", it)
                    })
            }
        }
        potentiometerSetBtn.setOnClickListener {
            potentiometerEdit.text.toString().toInt().also {
                seatBleDevice21?.setPotentiometerCompletable(it)
                    ?.subscribe({
                    }, {
                        Log.e(TAG, "setPotentiometerCompletable", Exception(it))
                    })
            }
        }
        sensingIntervalSetBtn.setOnClickListener {
            sensingIntervalEdit.text.toString().toInt().also {
                seatBleDevice21?.setSensingIntervalCompletable(it)
                    ?.subscribe({
                    }, {
                        Log.e(TAG, "setSensingIntervalCompletable", Exception(it))
                    })
            }
        }
        commIntervalSetBtn.setOnClickListener {
            commIntervalEdit.text.toString().toInt().also {
                seatBleDevice21?.setCommIntervalCompletable(it)
                    ?.subscribe({
                    }, {
                        Log.e(TAG, "setCommIntervalCompletable", Exception(it))
                    })
            }
        }
        offTimerSetBtn.setOnClickListener {
            offTimerEdit.text.toString().toInt().also {
                seatBleDevice21?.setOffTimerCompletable(it)
                    ?.subscribe({
                    }, {
                        Log.e(TAG, "setOffTimerCompletable", Exception(it))
                    })
            }
        }
        disposableVoltage = seatBleDevice21?.getBatteryInfoObservable()
            ?.observeOn(AndroidSchedulers.mainThread())
            ?.subscribe({
                voltageView.setText(it.first.toString())
                capacityView.setText(it.second.toString())
                chargingView.setText(it.third.toString())
            }, {
                Log.e(TAG, "getBatteryObservable", Exception(it))
            })
    }

    override fun onPause() {
        super.onPause()
        disposable?.dispose()
        disposableVoltage?.dispose()
    }

    companion object {
        private val TAG = SC21DeviceActivity::class.java.simpleName

        const val NAME_MAC_ADDRESS = "NAME_MAC_ADDRESS"
    }
}
