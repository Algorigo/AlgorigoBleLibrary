package com.algorigo.algorigoblelibrary

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import com.algorigo.algorigoble.BleDevice
import com.algorigo.algorigoble.BleManager
import com.algorigo.algorigoblelibrary.bruxweeper.BruxweeperActivity
import com.algorigo.algorigoblelibrary.bruxweeper.BruxweeperBleDevice
import com.algorigo.algorigoblelibrary.carseat.CarSeatDevice
import com.algorigo.algorigoblelibrary.carseat.CarSeatDeviceActivity
import com.algorigo.algorigoblelibrary.mldp_terminal.MLDPTerminal
import com.algorigo.algorigoblelibrary.mldp_terminal.MLDPTerminalActivity
import com.algorigo.algorigoblelibrary.pdms.RxPDMSActivity
import com.algorigo.algorigoblelibrary.smart_chair.*
import com.algorigo.algorigoblelibrary.util.PermissionUtil
import com.algorigo.pressurego.RxPDMSDevice
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.disposables.Disposable
import kotlinx.android.synthetic.main.activity_main.*

class MainActivity : AppCompatActivity() {

    private lateinit var bleManager: BleManager
    private val adapter = BleRecyclerAdapter(object : BleRecyclerAdapter.BleRecyclerListener {
        override fun onSelect(bleDevice: BleDevice) {
            if (bleDevice.connected) {
                when (bleDevice) {
                    is SC01Device -> {
                        Intent(this@MainActivity, SC01DeviceActivity::class.java).apply {
                            putExtra(SC01DeviceActivity.NAME_MAC_ADDRESS, bleDevice.macAddress)
                            startActivity(this)
                        }
                    }
                    is SC20Device -> {
                        Intent(this@MainActivity, SC20DeviceActivity::class.java).apply {
                            putExtra(SC20DeviceActivity.NAME_MAC_ADDRESS, bleDevice.macAddress)
                            startActivity(this)
                        }
                    }
                    is SC21Device -> {
                        Intent(this@MainActivity, SC21DeviceActivity::class.java).apply {
                            putExtra(SC21DeviceActivity.NAME_MAC_ADDRESS, bleDevice.macAddress)
                            startActivity(this)
                        }
                    }
                    is SC30Device -> {
                        Intent(this@MainActivity, SC30DeviceActivity::class.java).apply {
                            putExtra(SC30DeviceActivity.NAME_MAC_ADDRESS, bleDevice.macAddress)
                            startActivity(this)
                        }
                    }
                    is RxPDMSDevice -> {
                        Intent(this@MainActivity, RxPDMSActivity::class.java).apply {
                            putExtra(RxPDMSActivity.NAME_MAC_ADDRESS, bleDevice.macAddress)
                            startActivity(this)
                        }
                    }
                    is BruxweeperBleDevice -> {
                        Intent(this@MainActivity, BruxweeperActivity::class.java).apply {
                            putExtra(BruxweeperActivity.NAME_MAC_ADDRESS, bleDevice.macAddress)
                            startActivity(this)
                        }
                    }
                    is MLDPTerminal -> {
                        Intent(this@MainActivity, MLDPTerminalActivity::class.java).apply {
                            putExtra(MLDPTerminalActivity.NAME_MAC_ADDRESS, bleDevice.macAddress)
                            startActivity(this)
                        }
                    }
                    is CarSeatDevice -> {
                        Intent(this@MainActivity, CarSeatDeviceActivity::class.java).apply {
                            putExtra(CarSeatDeviceActivity.NAME_MAC_ADDRESS, bleDevice.macAddress)
                            startActivity(this)
                        }
                    }
                }
            }
        }

        override fun onButton(bleDevice: BleDevice) {
            when (bleDevice.connectionState) {
                BleDevice.ConnectionState.CONNECTED -> {
                    bleDevice.disconnect()
                }
                BleDevice.ConnectionState.DISCONNECTED -> {
                    bleDevice.connect(false)
                }
                else -> {}
            }
        }
    })

    private var disposable: Disposable? = null
    private var connectionStateDisposable: Disposable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        initView()

        BleManager.init(this)
        bleManager = BleManager.getInstance().apply {
            bleDeviceDelegate = SampleBleDeviceDelegate()
        }
    }

    override fun onResume() {
        super.onResume()
        connectionStateDisposable = bleManager.getConnectionStateObservable()
            .observeOn(AndroidSchedulers.mainThread())
            .doFinally {
                connectionStateDisposable = null
            }
            .subscribe({
                adapter.notifyDataSetChanged()
            }, {
                Log.e(TAG, "it")
                connectionStateDisposable = null
            })
    }

    override fun onPause() {
        super.onPause()
        connectionStateDisposable?.dispose()
    }

    private fun initView() {
        setContentView(R.layout.activity_main)
        bleRecycler.adapter = adapter
        startBtn.setOnClickListener {
            if (PermissionUtil.checkPermissions(this, Manifest.permission.ACCESS_COARSE_LOCATION)) {
                // 권한이 있음 ->> 원하는 기능 및 시나리오 사용
                startScan()
            } else {
                // 권한이 없음 -> 퍼미션을 요구
                PermissionUtil.requestExternalPermissions(
                    this,
                    arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                    REQUEST_CODE
                )
            }
        }
        stopBtn.setOnClickListener {
            disposable?.dispose()
        }
    }

    private fun startScan() {
        disposable = bleManager.scanObservable(30000)
            .observeOn(AndroidSchedulers.mainThread())
            .doOnSubscribe {
                disposable = it
                startBtn.isEnabled = false
                adapter.bleDeviceList = listOf()
            }
            .doOnDispose {
                startBtn.isEnabled = true
            }
            .subscribe({
                adapter.bleDeviceList = it
            }, {
                Log.e(TAG, "", it)
                startBtn.isEnabled = true
                if (true) {
                    val intent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
                    startActivityForResult(intent, 1)
                }
            }, {
                startBtn.isEnabled = true
            })
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        if (requestCode == REQUEST_CODE) {
            if (PermissionUtil.verifyPermission(grantResults)) {
                // 요청한 권한을 얻었으므로 원하는 메소드를 사용
                startScan()
            }
        } else {
            super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        }
    }

    companion object {
        private val TAG = MainActivity::class.java.simpleName

        private const val REQUEST_CODE = 1
    }
}
