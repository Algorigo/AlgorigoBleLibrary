package com.algorigo.algorigoblelibrary

import android.content.Intent
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.recyclerview.widget.RecyclerView
import com.algorigo.algorigoble2.BleDevice
import com.algorigo.library.rx.Rx2ServiceBindingFactory
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.disposables.Disposable
import java.util.*

class DeviceActivity : AppCompatActivity(), CharacteristicAdapter.Callback {

    private lateinit var deviceMacAddress: String

    private lateinit var resultTextView: TextView
    private lateinit var characteristicRecyclerView: RecyclerView

    // main thread 에서만 바꾼다
    private val notificationDisposables = mutableMapOf<UUID, Disposable>()
    // 특성 조회, read, write. 화면이 닫히면 notification 구독과 함께 해제한다
    private val disposables = CompositeDisposable()
    private val characteristicAdapter = CharacteristicAdapter(this)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
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
                Log.e(TAG, "", it)
            })
            .also { disposables.add(it) }
    }

    override fun onDestroy() {
        super.onDestroy()
        disposables.dispose()
        // 해제하면 doFinally 에서 map 에서 지우므로 복사본으로 돈다
        notificationDisposables.values.toList().forEach { it.dispose() }
    }

    private fun initView() {
        setContentView(R.layout.activity_device)
        findViewById<View>(R.id.main).applySystemBarsPadding()
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
                Log.e(TAG, "", it)
            })
            .also { disposables.add(it) }
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
                Log.e(TAG, "", it)
            })
            .also { disposables.add(it) }
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
                .observeOn(AndroidSchedulers.mainThread())
                // observeOn 뒤에 두어 map 을 main thread 에서만 바꾼다
                .doFinally {
                    notificationDisposables.remove(uuid)
                }
                .subscribe({
                    resultTextView.text = it
                }, {
                    Log.e(TAG, "onError", it)
                })
                .also {
                    notificationDisposables[uuid] = it
                }
        } else {
            notificationDisposables[uuid]?.dispose()
        }
    }

    companion object {
        private final val TAG = DeviceActivity::class.java.simpleName

        const val DEVICE_MAC_ADDRESS = "DEVICE_MAC_ADDRESS"
    }
}