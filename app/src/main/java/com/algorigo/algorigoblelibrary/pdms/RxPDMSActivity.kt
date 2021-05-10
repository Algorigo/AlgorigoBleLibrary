package com.algorigo.algorigoblelibrary.pdms

import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import com.algorigo.algorigoble.BleManager
import com.algorigo.algorigoblelibrary.R
import com.algorigo.pressurego.RxPDMSDevice
import io.reactivex.rxjava3.disposables.Disposable
import kotlinx.android.synthetic.main.activity_rx_pdms.*

class RxPDMSActivity : AppCompatActivity() {

    private var pdmsDevice: RxPDMSDevice? = null
    private var disposable: Disposable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_rx_pdms)

        val macAddress = intent.getStringExtra(NAME_MAC_ADDRESS)!!
        pdmsDevice = BleManager.getInstance().getDevice(macAddress) as? RxPDMSDevice

        startBtn.setOnClickListener {
            if (disposable != null) {
                disposable?.dispose()
            } else {
                disposable = pdmsDevice?.sendDataOn()
                    ?.doFinally {
                        disposable = null
                    }
                    ?.subscribe({
                        Log.e(TAG, "intArray:${it.contentToString()}")
                    }, {
                        Log.e(TAG, "", it)
                    })
            }
        }

        batteryBtn.setOnClickListener {
            pdmsDevice?.getBatteryPercentSingle()
                ?.subscribe({
                    Log.e(TAG, "battery:$it")
                }, {
                    Log.e(TAG, "", it)
                })
        }

        firmwareVersionBtn.setOnClickListener {
            val firmwareVersion = pdmsDevice?.getFirmwareVersion()
            Log.e("!!!", "firmwareVersion:$firmwareVersion")
        }

        getAmpBtn.setOnClickListener {
            val amp = pdmsDevice?.getAmplification()
            Log.e("!!!", "amp:$amp")
        }

        setAmpBtn.setOnClickListener {
            ampEditText.text.toString().toIntOrNull()?.also {
                pdmsDevice?.setAmplificationCompletable(it)
                    ?.subscribe({
                        Log.e(TAG, "setAmp:$it")
                    }, {
                        Log.e(TAG, "", it)
                    })
            }
        }
    }

    companion object {
        private val TAG = RxPDMSActivity::class.java.simpleName

        const val NAME_MAC_ADDRESS = "NAME_MAC_ADDRESS"
    }
}