package com.algorigo.algorigoblelibrary

import android.content.Intent
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.AppCompatSpinner
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

    private lateinit var spinner: AppCompatSpinner
    private lateinit var passwordEditText: EditText

    private lateinit var statusTextView: TextView

    private var provisioningDisposable: Disposable? = null
    private var initialized: BleDevice.ProvisioningInitialized? = null
    private var scanWifiDisposable: Disposable? = null
    private var scanResults = listOf<BleDevice.ProvisioningScanResult>()
    private var adapter = object : BaseAdapter() {
        override fun getCount(): Int {
            return scanResults.size
        }

        override fun getItem(p0: Int): Any {
            return getScanResult(p0)
        }

        override fun getItemId(p0: Int): Long {
            return getScanResult(p0).ssid.hashCode().toLong()
        }

        override fun getView(p0: Int, p1: View?, p2: ViewGroup?): View {
            return (p1 ?: layoutInflater.inflate(android.R.layout.simple_spinner_item, p2, false)).apply {
                (this as TextView).text = getScanResult(p0).ssid ?: "Unknown SSID"
            }
        }

        fun getScanResult(position: Int): BleDevice.ProvisioningScanResult {
            return scanResults[position]
        }
    }
    private var clearDisposable: Disposable? = null

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
    }

    private fun startProvisioningDevice() {
        if (provisioningDisposable != null) {
            provisioningDisposable?.dispose()
        }
        provisioningDisposable = bleDevice?.processProvisioning()
            ?.observeOn(AndroidSchedulers.mainThread())
            ?.mapOptional { provisioningInfo ->
                Log.d(TAG, "Provisioning scan result: ${provisioningInfo.provisioningStatus}")
                statusTextView.text = provisioningInfo.provisioningStatus.toString()
                if (provisioningInfo is BleDevice.ProvisioningInitialized) {
                    Optional.of(provisioningInfo)
                } else  {
                    Optional.empty()
                }
            }
            ?.doFinally {
                provisioningDisposable = null
                initialized = null
            }
            ?.observeOn(AndroidSchedulers.mainThread())
            ?.subscribe({
                initialized = it
            }, {
                Log.e(TAG, "start Provisioning error: $it", it)
                Toast.makeText(this, "start Provisioning error: ${it.message}", Toast.LENGTH_SHORT).show()
            })
    }

    private fun startWifiScan() {
        if (scanWifiDisposable != null) {
            scanWifiDisposable?.dispose()
        } else {
            initialized
                ?.scan()
                ?.doFinally {
                    scanWifiDisposable = null
                }
                ?.observeOn(AndroidSchedulers.mainThread())
                ?.subscribe({
                    Log.e(TAG, "Provisioning scan completed: ${it.size} networks found")
                    scanResults = it
                    adapter.notifyDataSetChanged()
                }, {
                    Log.e(TAG, "Provisioning scan error: ${it.message}", it)
                })
                .let {
                    if (it != null) {
                        scanWifiDisposable = it
                    } else {
                        Log.e(TAG, "Provisioning scan failed: initialized is null")
                        Toast.makeText(this, "Provisioning scan failed: initialized is null", Toast.LENGTH_SHORT).show()
                    }
                }
        }
    }

    private fun onProvisioningButtonClicked() {
        val selectedPosition = spinner.selectedItemPosition
        val password = passwordEditText.text.toString()

        if (selectedPosition < 0 || password.isEmpty()) {
            Log.e(TAG, "SSID or password is empty")
            return
        }

        val scanResult = adapter.getScanResult(selectedPosition)

        scanResult.connectDelegate(password)
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe({
                Log.e(TAG, "Provisioning started successfully")
            }, { error ->
                Log.e(TAG, "Provisioning error: $error", error)
                Toast.makeText(this, "Provisioning error: ${error.message}", Toast.LENGTH_SHORT).show()
            })
    }

    private fun onCleanProvisioningButtonClicked() {
        if (clearDisposable != null) {
            clearDisposable?.dispose()
        } else {
            initialized?.clear()
                ?.observeOn(AndroidSchedulers.mainThread())
                ?.doFinally {
                    clearDisposable = null
                }
                ?.subscribe({
                    Log.e(TAG, "Provisioning cleared successfully")
                    Toast.makeText(this, "Provisioning cleared successfully", Toast.LENGTH_SHORT).show()
                }, { error ->
                    Log.e(TAG, "Failed to clear provisioning: ${error.message}", error)
                    Toast.makeText(this, "Failed to clear provisioning: ${error.message}", Toast.LENGTH_SHORT).show()
                })
                .let {
                    if (it != null) {
                        clearDisposable = it
                    } else {
                        Log.e(TAG, "Failed to clear provisioning: bleDevice is null")
                        Toast.makeText(this, "Failed to clear provisioning: bleDevice is null", Toast.LENGTH_SHORT).show()
                    }
                }
        }
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