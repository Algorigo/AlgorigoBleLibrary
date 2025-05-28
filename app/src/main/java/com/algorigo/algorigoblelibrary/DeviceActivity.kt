package com.algorigo.algorigoblelibrary

import android.content.Intent
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.widget.AppCompatSpinner
import androidx.recyclerview.widget.RecyclerView
import com.algorigo.algorigoble2.BleDevice
import com.algorigo.library.rx.Rx2ServiceBindingFactory
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.disposables.Disposable
import io.reactivex.rxjava3.schedulers.Schedulers
import java.util.*

class DeviceActivity : AppCompatActivity(), CharacteristicAdapter.Callback {

    private lateinit var deviceMacAddress: String

    private lateinit var resultTextView: TextView
    private lateinit var characteristicRecyclerView: RecyclerView

    private lateinit var spinner: AppCompatSpinner
    private lateinit var passwordEditText: EditText

    private lateinit var statusTextView: TextView

    private val wifiInfoList = mutableListOf<BleDevice.ProvisioningScanResult>()
    private val wifiSsids = mutableListOf<String>()
    private lateinit var adapter: ArrayAdapter<String>

    private var notificationDisposables = mutableMapOf<UUID, Disposable>()
    private val characteristicAdapter = CharacteristicAdapter(this)

    private var bleDevice: BleDevice? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        intent.getStringExtra(DEVICE_MAC_ADDRESS)?.let {
            deviceMacAddress = it
        }?: finish()

        initView()

        getDeviceObservable()
            .flatMapSingle {
                bleDevice = it
                it.getCharacteristicsSingle()
            }
            .firstOrError()
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe({
                characteristicAdapter.characteristics = it.toMutableList()
                characteristicAdapter.notifyDataSetChanged()
            }, {
                Log.e(TAG, "", it)
            })
    }

    private fun initView() {
        setContentView(R.layout.activity_device)
        resultTextView = findViewById(R.id.result_text_view)
        characteristicRecyclerView = findViewById(R.id.characteristic_list_view)
        characteristicRecyclerView.adapter = characteristicAdapter

        spinner = findViewById(R.id.wifi_networks_spinner)
        passwordEditText = findViewById(R.id.wifi_password_edit_text)

        // 어댑터 초기화 (빈 리스트로 시작)
        adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, wifiSsids)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinner.adapter = adapter

        // 스피너 아이템 선택 리스너
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long
            ) {
                passwordEditText.text.clear()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {
            }
        }

        statusTextView = findViewById(R.id.status_text_view)

        val startButton: View = findViewById(R.id.start_button)
        val startScanButton: View = findViewById(R.id.scan_wifi_button)
        val provisionButton: View = findViewById(R.id.provisioning_button)
        val cleanProvisionButton: View = findViewById(R.id.clean_provisioning_button)
        val getStatusButton: View = findViewById(R.id.status_button)

        startButton.setOnClickListener {
            startProvisioningDevice()
        }

        startScanButton.setOnClickListener {
            startWifiScan()
        }

        provisionButton.setOnClickListener {
            onProvisioningButtonClicked()
        }

        cleanProvisionButton.setOnClickListener {
            onCleanProvisioningButtonClicked()
        }

        getStatusButton.setOnClickListener {
            getStatus()
        }
    }

    private fun getStatus() {
        bleDevice?.getProvisioningStatus()
            ?.observeOn(AndroidSchedulers.mainThread())
            ?.subscribe({ status ->
                statusTextView.text = status.toString()
            }, { error ->
                Log.e("BLE-Test", "Get status error: ${error.message}", error)
            })
    }

    private fun startProvisioningDevice() {
        bleDevice?.initializeProvisioning()
            ?.andThen(bleDevice!!.getProvisioningStatus())
            ?.observeOn(AndroidSchedulers.mainThread())
            ?.subscribe({ status ->
                statusTextView.text = status.toString()
            }, { error ->
                Log.e("BLE-Test", "Start error: ${error.message}", error)
            })
    }

    private fun startWifiScan() {
        bleDevice?.scanWifiList()
            ?.subscribeOn(Schedulers.io())
            ?.observeOn(AndroidSchedulers.mainThread())
            ?.subscribe({ scanResults ->
                wifiInfoList.clear()
                wifiSsids.clear()

                scanResults.filter { it.ssid != null }
                    .distinctBy { it.ssid!! }  // 중복 SSID 제거
                    .forEach {
                        wifiInfoList.add(it)
                        wifiSsids.add(it.ssid!!)
                    }
                adapter.notifyDataSetChanged()
            }, { error ->
                Log.e("BLE-Test", "Wi-Fi scan error: ${error.localizedMessage}")
            })
    }

    private fun onProvisioningButtonClicked() {
        val selectedSsid = spinner.selectedItem as? String
        val password = passwordEditText.text.toString()

        if (selectedSsid.isNullOrEmpty() || password.isEmpty()) {
            Log.e("BLE-Test", "SSID or password is empty")
            return
        }

        val wifiInfo = wifiInfoList.find { it.ssid == selectedSsid }
        if (wifiInfo == null) {
            Log.e("BLE-Test", "Selected SSID not found in scan results")
            return
        }

        bleDevice?.startProvisioning(wifiInfo, password)
            ?.andThen(bleDevice!!.getProvisioningStatus())
            ?.observeOn(AndroidSchedulers.mainThread())
            ?.subscribe({ status ->
                statusTextView.text = status.toString()
                Log.d("BLE-Test", "Provisioning started successfully")
            }, { error ->
                statusTextView.text = "Provisioning failed: ${error.localizedMessage}"
                Log.e("BLE-Test", "Provisioning error: ${error.localizedMessage}")
            })
    }

    private fun onCleanProvisioningButtonClicked() {
        bleDevice?.cleanProvisioning()
            ?.andThen(bleDevice!!.getProvisioningStatus())
            ?.observeOn(AndroidSchedulers.mainThread())
            ?.subscribe({
                Log.d("BLE-Test", "Provisioning cleaned successfully")
                statusTextView.text = it.toString()
            }, { error ->
                Log.e("BLE-Test", "Clean provisioning error: ${error.localizedMessage}")
            })
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