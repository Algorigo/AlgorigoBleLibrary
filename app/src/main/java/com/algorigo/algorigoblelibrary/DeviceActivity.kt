package com.algorigo.algorigoblelibrary

import android.content.Intent
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.util.Log
import android.widget.EditText
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.algorigo.algorigoble2.BleDevice
import com.algorigo.library.rx.Rx2ServiceBindingFactory
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.disposables.Disposable
import java.util.*

class DeviceActivity : AppCompatActivity(), CharacteristicAdapter.Callback {

    private lateinit var deviceMacAddress: String

    private lateinit var resultTextView: TextView
    private lateinit var characteristicRecyclerView: RecyclerView

    private var notificationDisposables = mutableMapOf<UUID, Disposable>()
    private val characteristicAdapter = CharacteristicAdapter(this)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        intent.getStringExtra(DEVICE_MAC_ADDRESS)?.let {
            deviceMacAddress = it
        }?: finish()

        initView()

        getDeviceObservable()
            .flatMapSingle { it.getCharacteristicsSingle() }
            .firstOrError()
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe({
                characteristicAdapter.characteristics = it.toMutableList()
                characteristicAdapter.notifyDataSetChanged()
            }, {
                Log.e("!!!", "", it)
            })
    }

    private fun initView() {
        setContentView(R.layout.activity_device)
        resultTextView = findViewById(R.id.result_text_view)
        characteristicRecyclerView = findViewById(R.id.characteristic_list_view)
        characteristicRecyclerView.adapter = characteristicAdapter
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

    override fun onReadCharacteristicBtn(uuid: UUID) {
        getDeviceObservable()
            .flatMapSingle { device ->
                device.readCharacteristicSingle(uuid)
            }
            .map { byteArray ->
                "$uuid read complete : 0x"+byteArray.joinToString(" ") { String.format("%02x", it) }
            }
            .firstOrError()
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe({
                resultTextView.text = it
            }, {
                Log.e("!!!", "", it)
            })
    }

    override fun onWriteCharacteristicBtn(uuid: UUID, byteArray: ByteArray) {
        getDeviceObservable()
            .flatMapSingle { device ->
                device.writeCharacteristicSingle(uuid, byteArray)
            }
            .map { byteArray ->
                "$uuid write complete : 0x"+byteArray.joinToString(" ") { String.format("%02x", it) }
            }
            .firstOrError()
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe({
                resultTextView.text = it
            }, {
                Log.e("!!!", "", it)
            })
    }

    override fun onNotifyBtn(uuid: UUID) {
        if (!notificationDisposables.containsKey(uuid)) {
            getDeviceObservable()
                .flatMap { device ->
                    device.setupNotification(BleDevice.NotificationType.NOTIFICATION, uuid)
                }
                .flatMap { it }
                .map { byteArray ->
                    "$uuid notify : 0x"+byteArray.joinToString(" ") { String.format("%02x", it) }
                }
                .doFinally {
                    notificationDisposables.remove(uuid)
                }
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe({
                    resultTextView.text = it
                }, {
                    Log.e("!!!", "onError", it)
                })
                .also {
                    notificationDisposables[uuid] = it
                }
        } else {
            notificationDisposables[uuid]?.dispose()
        }
    }
}