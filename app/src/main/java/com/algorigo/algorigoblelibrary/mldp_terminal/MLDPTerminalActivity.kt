package com.algorigo.algorigoblelibrary.mldp_terminal

import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import com.algorigo.algorigoble.BleManager
import com.algorigo.algorigoblelibrary.R
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.disposables.Disposable
import kotlinx.android.synthetic.main.activity_mldpterminal.*
import java.nio.charset.Charset

class MLDPTerminalActivity : AppCompatActivity() {

    private var mldpTerminal: MLDPTerminal? = null
    private var disposable: Disposable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_mldpterminal)

        val macAddress = intent.getStringExtra(NAME_MAC_ADDRESS)
        if (macAddress != null) {
            mldpTerminal = BleManager.getInstance().getDevice(macAddress) as? MLDPTerminal
        } else {
            finish()
            return
        }

        sendBtn.setOnClickListener {
            sendDataEdit.text.toString().toByteArray(Charset.forName("utf-8")).let {
                mldpTerminal?.writeData(it)
                    ?.observeOn(AndroidSchedulers.mainThread())
                    ?.doOnSubscribe {
                        sendDataEdit.isEnabled = false
                        sendBtn.isEnabled = false
                    }
                    ?.subscribe({
                        sendDataEdit.isEnabled = true
                        sendBtn.isEnabled = true
                        sendDataEdit.setText("")
                    }, {
                        Log.e(TAG, "write error", it)
                    })
            }
        }
    }

    override fun onResume() {
        super.onResume()

        disposable = mldpTerminal?.getDataObservable()
            ?.doFinally {
                disposable = null
            }
            ?.observeOn(AndroidSchedulers.mainThread())
            ?.subscribe({
                receiveView.setText(it.toString(Charset.forName("utf-8")))
            })
    }

    override fun onPause() {
        super.onPause()
        disposable?.dispose()
    }

    companion object {
        private val TAG = MLDPTerminalActivity::class.java.simpleName

        const val NAME_MAC_ADDRESS = "NAME_MAC_ADDRESS"
    }
}
