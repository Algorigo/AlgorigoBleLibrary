package com.algorigo.algorigoblelibrary

import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import com.algorigo.algorigoble.BleManager
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.disposables.Disposable
import kotlinx.android.synthetic.main.activity_sample_ble_device.*

class SampleBleDeviceActivity : AppCompatActivity() {

    private var sampleBleDevice: SampleBleDevice? = null
    private var disposable: Disposable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_sample_ble_device)

        val macAddress = intent.getStringExtra(NAME_MAC_ADDRESS)
        sampleBleDevice = BleManager.getInstance().getDevice(macAddress) as? SampleBleDevice

        dataText.setText("name:${sampleBleDevice?.name}\naddress:${sampleBleDevice?.macAddress}")
    }

    companion object {
        private val TAG = SampleBleDeviceActivity::class.java.simpleName

        const val NAME_MAC_ADDRESS = "NAME_MAC_ADDRESS"
    }
}
