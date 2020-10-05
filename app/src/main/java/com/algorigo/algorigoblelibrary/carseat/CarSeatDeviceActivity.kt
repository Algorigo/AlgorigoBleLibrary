package com.algorigo.algorigoblelibrary.carseat

import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.algorigo.algorigoble.BleManager
import com.algorigo.algorigoblelibrary.R
import io.reactivex.rxjava3.disposables.Disposable
import kotlinx.android.synthetic.main.activity_carseat.*

class CarSeatDeviceActivity : AppCompatActivity() {

    private var carSeatDevice: CarSeatDevice? = null
    private var disposable: Disposable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_carseat)

        val macAddress = intent.getStringExtra(NAME_MAC_ADDRESS)
        if (macAddress != null) {
            carSeatDevice = BleManager.getInstance().getDevice(macAddress) as? CarSeatDevice
        } else {
            finish()
            return
        }

        initView()
    }

    override fun onResume() {
        super.onResume()
//        var lastTime = 0L
//        disposable = carSeatDevice?.getNrfResponseObservable()
//            ?.doFinally {
//                disposable = null
//            }
//            ?.map { byteArray ->
//                IntArray(byteArray.size/2) {
//                    byteArray[it*2+1].toUByte().toInt() * 256 + byteArray[it*2].toUByte().toInt()
//                }
//            }
//            ?.observeOn(AndroidSchedulers.mainThread())
//            ?.subscribe({ intArray ->
//                temp1.text = intArray[0].toString()
//                temp2.text = intArray[1].toString()
//                temp3.text = intArray[2].toString()
//                temp4.text = intArray[3].toString()
//                temp5.text = intArray[4].toString()
//                val text = intArray.toList().subList(5, intArray.size)
//                    .mapIndexed { index, intValue -> String.format("%04d", intValue) + if (index%10 == 9) "\n" else "" }
//                    .toTypedArray()
//                    .contentToString()
////                Log.e("!!!","${text}")
//                responseTextView.text = text
//
//                val current = System.currentTimeMillis()
//                if (lastTime != 0L) {
//                    intervalView.setText("${current-lastTime} ms")
//                }
//                lastTime = current
//            }, {
//                Log.e(LOG_TAG, "", it)
//            })
    }

    override fun onPause() {
        super.onPause()
        disposable?.dispose()
    }

    private fun initView() {
        getBtn1.setOnClickListener {
            carSeatDevice?.getDataSingle(1)
                ?.subscribe({
                    Log.e("!!!", "get1 : $it")
                }, {
                    Log.e("!!!", "getBtn1", it)
                })
        }
        getBtn2.setOnClickListener {
            carSeatDevice?.getDataSingle(2)
                ?.subscribe({
                    Log.e("!!!", "get2 : $it")
                }, {
                    Log.e("!!!", "getBtn2", it)
                })
        }
        getBtn3.setOnClickListener {
            carSeatDevice?.getDataSingle(3)
                ?.subscribe({
                    Log.e("!!!", "get3 : $it")
                }, {
                    Log.e("!!!", "getBtn3", it)
                })
        }
        getBtn4.setOnClickListener {
            carSeatDevice?.getDataSingle(4)
                ?.subscribe({
                    Log.e("!!!", "get4 : $it")
                }, {
                    Log.e("!!!", "getBtn4", it)
                })
        }
        getBtn5.setOnClickListener {
            carSeatDevice?.getDataSingle(5)
                ?.subscribe({
                    Log.e("!!!", "get5 : $it")
                }, {
                    Log.e("!!!", "getBtn5", it)
                })
        }
        setBtn1.setOnClickListener {
            try {
                carSeatDevice?.setDataCompletable(1, ampEdit1.text.toString().toInt(), sensEdit1.text.toString().toInt(), 1)
                    ?.subscribe({
                        Log.e("!!!", "setBtn1")
                    }, {
                        Log.e("!!!", "setBtn1", it)
                    })
            } catch (e: Throwable) {
                Toast.makeText(this, "Amp is wrong:${e.localizedMessage}", Toast.LENGTH_SHORT).show()
            }
        }
        setBtn2.setOnClickListener {
            try {
                carSeatDevice?.setDataCompletable(2, ampEdit2.text.toString().toInt(), sensEdit2.text.toString().toInt(), 1)
                    ?.subscribe({
                        Log.e("!!!", "setBtn2")
                    }, {
                        Log.e("!!!", "setBtn2", it)
                    })
            } catch (e: Throwable) {
                Toast.makeText(this, "Amp is wrong:${e.localizedMessage}", Toast.LENGTH_SHORT).show()
            }
        }
        setBtn3.setOnClickListener {
            try {
                carSeatDevice?.setDataCompletable(3, ampEdit3.text.toString().toInt(), sensEdit3.text.toString().toInt(), 1)
                    ?.subscribe({
                        Log.e("!!!", "setBtn3")
                    }, {
                        Log.e("!!!", "setBtn3", it)
                    })
            } catch (e: Throwable) {
                Toast.makeText(this, "Amp is wrong:${e.localizedMessage}", Toast.LENGTH_SHORT).show()
            }
        }
        setBtn4.setOnClickListener {
            try {
                carSeatDevice?.setDataCompletable(4, ampEdit4.text.toString().toInt(), sensEdit4.text.toString().toInt(), 1)
                    ?.subscribe({
                        Log.e("!!!", "setBtn4")
                    }, {
                        Log.e("!!!", "setBtn4", it)
                    })
            } catch (e: Throwable) {
                Toast.makeText(this, "Amp is wrong:${e.localizedMessage}", Toast.LENGTH_SHORT).show()
            }
        }
        setBtn5.setOnClickListener {
            try {
                carSeatDevice?.setDataCompletable(5, ampEdit5.text.toString().toInt(), sensEdit5.text.toString().toInt(), 1)
                    ?.subscribe({
                        Log.e("!!!", "setBtn5")
                    }, {
                        Log.e("!!!", "setBtn5", it)
                    })
            } catch (e: Throwable) {
                Toast.makeText(this, "Amp is wrong:${e.localizedMessage}", Toast.LENGTH_SHORT).show()
            }
        }
        intervalEdit.setText(carSeatDevice?.intervalMillis?.toString() ?: "")
        intervalSetBtn.setOnClickListener {
            try {
                carSeatDevice?.intervalMillis = intervalEdit.text.toString().toLong()
            } catch (e: Throwable) {
                Toast.makeText(this, "interval is wrong:${e.localizedMessage}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    companion object {
        private val LOG_TAG = CarSeatDeviceActivity::class.java.simpleName

        const val NAME_MAC_ADDRESS = "NAME_MAC_ADDRESS"
    }
}