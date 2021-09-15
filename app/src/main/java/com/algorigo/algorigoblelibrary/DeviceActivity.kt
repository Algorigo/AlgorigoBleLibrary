package com.algorigo.algorigoblelibrary

import android.content.Intent
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import com.algorigo.algorigoble2.BleDevice
import com.algorigo.library.rx.Rx2ServiceBindingFactory
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.disposables.Disposable
import java.util.*

class DeviceActivity : AppCompatActivity() {

    private lateinit var deviceMacAddress: String

    private lateinit var uuidEditText: EditText
    private lateinit var readCharacteristicButton: Button
    private lateinit var writeEditText: EditText
    private lateinit var writeCharacteristicButton: Button
    private lateinit var setupNotifyButton: Button
    private lateinit var resultTextView: TextView

    private var notificationDisposable: Disposable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        intent.getStringExtra(DEVICE_MAC_ADDRESS)?.let {
            deviceMacAddress = it
        }?: finish()

        initView()
    }

    private fun initView() {
        setContentView(R.layout.activity_device)

        uuidEditText = findViewById(R.id.uuid_edit_text)
        readCharacteristicButton = findViewById(R.id.read_characteristic_btn)
        writeEditText = findViewById(R.id.write_edit_text)
        writeCharacteristicButton = findViewById(R.id.write_characteristic_btn)
        setupNotifyButton = findViewById(R.id.notify_btn)
        resultTextView = findViewById(R.id.result_text_view)

        readCharacteristicButton.setOnClickListener {
            readCharacteristic()
        }
        writeCharacteristicButton.setOnClickListener {
            writeCharacteristic()
        }
        setupNotifyButton.setOnClickListener {
            if (notificationDisposable == null) {
                setupNotify()
            } else {
                disableNotify()
            }
        }
    }

    private fun readCharacteristic() {
        getDeviceObservable()
            .flatMapSingle { device ->
                val uuid = UUID.fromString(uuidEditText.text.toString())
                device.readCharacteristicSingle(uuid)
            }
            .map { byteArray ->
                "read complete : 0x"+byteArray.joinToString(" ") { String.format("%02x", it) }
            }
            .firstOrError()
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe({
                resultTextView.text = it
            }, {
                Log.e("!!!", "", it)
            })
    }

    private fun writeCharacteristic() {
        getDeviceObservable()
            .flatMapSingle { device ->
                val uuid = UUID.fromString(uuidEditText.text.toString())
                val byteArray = writeEditText.text.toString().chunked(2).map { it.toInt(16).toByte() }.toByteArray()
                device.writeCharacteristicSingle(uuid, byteArray)
            }
            .map { byteArray ->
                "write complete : 0x"+byteArray.joinToString(" ") { String.format("%02x", it) }
            }
            .firstOrError()
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe({
                resultTextView.text = it
            }, {
                Log.e("!!!", "", it)
            })
    }

    private fun setupNotify() {
        notificationDisposable = getDeviceObservable()
            .flatMap { device ->
                val uuid = UUID.fromString(uuidEditText.text.toString())
                device.setupNotification(BleDevice.NotificationType.NOTIFICATION, uuid)
            }
            .flatMap { it }
            .map { byteArray ->
                "notify : 0x"+byteArray.joinToString(" ") { String.format("%02x", it) }
            }
            .doFinally {
                notificationDisposable = null
            }
            .subscribe({
                resultTextView.text = it
            }, {
                Log.e("!!!", "onError", it)
            })
    }

    private fun disableNotify() {
        notificationDisposable?.dispose()
    }

    private fun getDeviceObservable() =
        Rx2ServiceBindingFactory.bind<BluetoothService.BluetoothBinder>(
            this,
            Intent(this, BluetoothService::class.java)
        )
            .map {
                it.getService().bleManager.getDevice(deviceMacAddress)
                    ?: throw IllegalStateException("Device is not exist")
            }

    companion object {
        const val DEVICE_MAC_ADDRESS = "DEVICE_MAC_ADDRESS"
    }
}