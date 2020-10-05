package com.algorigo.algorigoblelibrary.smart_chair

import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import com.algorigo.algorigoble.BleManager
import com.algorigo.algorigoblelibrary.R
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.disposables.Disposable
import kotlinx.android.synthetic.main.activity_sc01device.*

class SC01DeviceActivity : AppCompatActivity() {

    private var SC01Device: SC01Device? = null
    private var disposables = mutableListOf<Disposable>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_sc01device)

        val macAddress = intent.getStringExtra(NAME_MAC_ADDRESS)
        if (macAddress != null) {
            SC01Device = BleManager.getInstance().getDevice(macAddress) as? SC01Device
        } else {
            finish()
            return
        }

        ampEdit.setText(SC01Device?.getAmplification()?.toString())
        sensEdit.setText(SC01Device?.getPotentiometer()?.toString())
        periodEdit.setText(SC01Device?.getDataPeriod()?.toString())

        setAmpBtn.setOnClickListener {
            try {
                val value = ampEdit.text.toString().toInt()
                SC01Device?.setAmplificationSingle(value)
                    ?.observeOn(AndroidSchedulers.mainThread())
                    ?.subscribe({
                        ampEdit.setText(it.toString())
                    }, {
                        Log.e(TAG, "", it)
                    })
            } catch (e: Exception) {
                Log.e(TAG, "", e)
            }
        }
        getAmpBtn.setOnClickListener {
            ampEdit.setText(SC01Device?.getAmplification()?.toString())
        }
        setSensBtn.setOnClickListener {
            val value = sensEdit.text.toString().toInt()
            SC01Device?.setPotentiometerSingle(value)
                ?.observeOn(AndroidSchedulers.mainThread())
                ?.subscribe({
                    sensEdit.setText(it.toString())
                }, {
                    Log.e(TAG, "", it)
                })
        }
        getSensBtn.setOnClickListener {
            sensEdit.setText(SC01Device?.getPotentiometer()?.toString())
        }
        setPeriodBtn.setOnClickListener {
            val value = periodEdit.text.toString().toInt()
            SC01Device?.setDataPeriodSingle(value)
                ?.observeOn(AndroidSchedulers.mainThread())
                ?.subscribe({
                    periodEdit.setText(it.toString())
                }, {
                    Log.e(TAG, "", it)
                })
        }
        getPeriodBtn.setOnClickListener {
            periodEdit.setText(SC01Device?.getDataPeriod()?.toString())
        }
        getBatteryBtn.setOnClickListener {
            SC01Device?.getBatteryValueSingle()
                ?.observeOn(AndroidSchedulers.mainThread())
                ?.subscribe({
                    batteryView.setText("${it}")
                }, {
                    Log.e(TAG, "", it)
                })
        }
        sendOnBtn.setOnClickListener {
            SC01Device?.sendDataOn()
                ?.observeOn(AndroidSchedulers.mainThread())
                ?.subscribe({
                    gridTextView.setData(it)
                }, {
                    Log.e(TAG, "", it)
                })
                ?.let {
                    disposables.add(it)
                }
        }
        sendOffBtn.setOnClickListener {
            if (disposables.size > 0) {
                disposables.get(disposables.size-1).dispose()
                disposables.removeAt(disposables.size-1)
            }
        }
    }

    companion object {
        private val TAG = SC01DeviceActivity::class.java.simpleName

        const val NAME_MAC_ADDRESS = "NAME_MAC_ADDRESS"
    }
}
