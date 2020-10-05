package com.algorigo.algorigoblelibrary.bruxweeper

import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import com.algorigo.algorigoble.BleManager
import com.algorigo.algorigoblelibrary.R
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.disposables.Disposable
import kotlinx.android.synthetic.main.activity_bruxweeper.*

class BruxweeperActivity : AppCompatActivity() {

    private var bruxweeperBleDevice: BruxweeperBleDevice? = null
    private var disposable: Disposable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_bruxweeper)

        val macAddress = intent.getStringExtra(NAME_MAC_ADDRESS)
        if (macAddress != null) {
            bruxweeperBleDevice = BleManager.getInstance().getDevice(macAddress) as? BruxweeperBleDevice
        } else {
            finish()
            return
        }

        initView()
    }

    private fun byteToBinaryString(byte: Byte): String {
        return "0b${if (byte.toInt() and 0b10000000 > 0) 1 else 0}"
                .plus("${if (byte.toInt() and 0b1000000 > 0) 1 else 0}")
                .plus("${if (byte.toInt() and 0b100000 > 0) 1 else 0}")
                .plus("${if (byte.toInt() and 0b10000 > 0) 1 else 0}")
                .plus("${if (byte.toInt() and 0b1000 > 0) 1 else 0}")
                .plus("${if (byte.toInt() and 0b100 > 0) 1 else 0}")
                .plus("${if (byte.toInt() and 0b10 > 0) 1 else 0}")
                .plus("${if (byte.toInt() and 0b1 > 0) 1 else 0}")
    }

    private fun initView() {
        startStreamBtn.setOnClickListener {
            disposable = bruxweeperBleDevice?.startStream()
                ?.observeOn(AndroidSchedulers.mainThread())
                ?.subscribe({
                    when (it) {
                        is BruxweeperBleDevice.Emg1ChStreamDataPacket -> {
                            Log.e("!!!", "stream:${it.emgData.toTypedArray().contentToString()}")
                            streamText.text = "${it.emgData.toTypedArray().contentToString()}"
                        }
                        is BruxweeperBleDevice.NormalStreamDataPacket -> {
                            Log.e("!!!", "stream:${it.emgEnv}")
                            streamText.text = "${String.format("%05d:[%03.1f, %03.1f, %03.1f]", it.emgEnv, it.acceleration[0], it.acceleration[1], it.acceleration[2])}"
                        }
                    }
                }, {
                    Log.e("!!!", "", it)
                })
        }
        stopStreamBtn.setOnClickListener {
            disposable?.dispose()
            disposable = null
        }
        motorOnBtn.setOnClickListener {
            bruxweeperBleDevice?.motorOn()
                    ?.subscribe({
                        Log.e("!!!", "motor on Success")
                    }, {
                        Log.e(TAG, "", it)
                    })
        }
        pulseBtn.setOnClickListener {
            bruxweeperBleDevice?.pulse()
                    ?.subscribe({
                        Log.e("!!!", "pulse on Success")
                    }, {
                        Log.e(TAG, "", it)
                    })
        }
        getTimerSetBtn.setOnClickListener {
            val timerSet = bruxweeperBleDevice?.getTimerSetSec()
            timerSetEdit.setText(timerSet.toString())
        }
        setTimerSetBtn.setOnClickListener {
            val timerSet = timerSetEdit.text.toString().toFloat()
            bruxweeperBleDevice?.setTimerSetSec(timerSet)
                    ?.subscribe({
                        Log.e("!!!", "setTimerSetSec Success")
                    }, {
                        Log.e(TAG, "", it)
                    })
        }
        getCurrentShapeBtn.setOnClickListener {
            val currentShapre = bruxweeperBleDevice?.getCurrentShape()
            loadCurrentEdit.setText(currentShapre?.loadCurrent?.toString())
            pulseDurationEdit.setText(currentShapre?.pulseDuration?.toString())
        }
        setCurrentShapeBtn.setOnClickListener {
            val loadCurrent = loadCurrentEdit.text.toString().toInt()
            val pulseDuration = pulseDurationEdit.text.toString().toInt()
            bruxweeperBleDevice?.setCurrentShape(loadCurrent, pulseDuration)
                    ?.subscribe({
                        Log.e("!!!", "setCurrentShape Success")
                    }, {
                        Log.e(TAG, "", it)
                    })
        }
        getRampUpTypeBtn.setOnClickListener {
            val rampUpType = bruxweeperBleDevice?.getRampUpType()
            rampUpTypeEdit.setText(rampUpType?.value?.toString())
        }
        setRampUpTypeBtn.setOnClickListener {
            val rampUpType = BruxweeperBleDevice.RampUpType.valueOf("TYPE_${rampUpTypeEdit.text}")
            bruxweeperBleDevice?.setRampUpType(rampUpType)
                    ?.subscribe({
                        Log.e("!!!", "setRampUpType Success")
                    }, {
                        Log.e(TAG, "", it)
                    })
        }
        getStmdetTimeBtn.setOnClickListener {
            val stmdetTime = bruxweeperBleDevice?.getStmdetTimeMillis()
            stmdetTimeEdit.setText(stmdetTime.toString())
        }
        setStmdetTimeBtn.setOnClickListener {
            val stmdetTime = stmdetTimeEdit.text.toString().toFloat()
            bruxweeperBleDevice?.setStmdetTimeMillis(stmdetTime)
                    ?.subscribe({
                        Log.e("!!!", "setStmdetTimeMillis Success")
                    }, {
                        Log.e(TAG, "", it)
                    })
        }
        getEmgDelayTimeBtn.setOnClickListener {
            val emgDelayTime = bruxweeperBleDevice?.getEmgDelayTimeMillis()
            emgDelayTimeEdit.setText(emgDelayTime.toString())
        }
        setEmgDelayTimeBtn.setOnClickListener {
            val emgDelayTime = emgDelayTimeEdit.text.toString().toFloat()
            bruxweeperBleDevice?.setEmgDelayTimeMillis(emgDelayTime)
                    ?.subscribe({
                        Log.e("!!!", "setEmgDelayTimeMillis Success")
                    }, {
                        Log.e(TAG, "", it)
                    })
        }
        getStmSourceTypeBtn.setOnClickListener {
            val stmSourceType = bruxweeperBleDevice?.getStmSourceType()
            when (stmSourceType) {
                BruxweeperBleDevice.StimulationSourceType.NONE -> {
                    noneRadioType.isChecked = true
                }
                BruxweeperBleDevice.StimulationSourceType.CURRENT -> {
                    currentRadioType.isChecked = true
                }
                BruxweeperBleDevice.StimulationSourceType.MOTOR -> {
                    motorRadioType.isChecked = true
                }
            }
        }
        setStmSourceTypeBtn.setOnClickListener {
            val stmSourceType = when (stmSourceTypeRadioGroup.checkedRadioButtonId) {
                noneRadioType.id -> {
                    BruxweeperBleDevice.StimulationSourceType.NONE
                }
                currentRadioType.id -> {
                    BruxweeperBleDevice.StimulationSourceType.CURRENT
                }
                motorRadioType.id -> {
                    BruxweeperBleDevice.StimulationSourceType.MOTOR
                }
                else -> {
                    BruxweeperBleDevice.StimulationSourceType.CURRENT
                }
            }
            bruxweeperBleDevice?.setStmSourceType(stmSourceType)
                    ?.subscribe({
                        Log.e("!!!", "setStmSourceType Success")
                    }, {
                        Log.e(TAG, "", it)
                    })
        }
        getStmWaveformBtn.setOnClickListener {
            val stmWaveform = bruxweeperBleDevice?.getStmWaveform()
            when (stmWaveform) {
                BruxweeperBleDevice.StimulationWaveform.RECTANGULAR -> {
                    rectecgularRadioType.isChecked = true
                }
                BruxweeperBleDevice.StimulationWaveform.SAW -> {
                    sawRadioType.isChecked = true
                }
            }
        }
        setStmWaveformBtn.setOnClickListener {
            val stmWaveform = when (stmWaveformRadioGroup.checkedRadioButtonId) {
                rectecgularRadioType.id -> {
                    BruxweeperBleDevice.StimulationWaveform.RECTANGULAR
                }
                sawRadioType.id -> {
                    BruxweeperBleDevice.StimulationWaveform.SAW
                }
                else -> {
                    BruxweeperBleDevice.StimulationWaveform.SAW
                }
            }
            bruxweeperBleDevice?.setStmWaveform(stmWaveform)
                    ?.subscribe({
                        Log.e("!!!", "setStmWaveform Success")
                    }, {
                        Log.e(TAG, "", it)
                    })
        }
        getControlParamsBtn.setOnClickListener {
            val controlParams = bruxweeperBleDevice?.getControlParams()
            stmActivationCheck.isChecked = controlParams?.stmActivation ?: false
            drvApplyCheck.isChecked = controlParams?.drvApply ?: false
            grindingCountCheck.isChecked = controlParams?.grindingCount ?: false
            motionCountCheck.isChecked = controlParams?.motionCount ?: false
        }
        setControlParamsBtn.setOnClickListener {
            val stmActivation = stmActivationCheck.isChecked
            val drvApply = drvApplyCheck.isChecked
            val grindingCount = grindingCountCheck.isChecked
            val motionCount = motionCountCheck.isChecked
            bruxweeperBleDevice?.setControlParams(stmActivation, drvApply, grindingCount, motionCount)
                    ?.subscribe({
                        Log.e("!!!", "setControlParams Success")
                    }, {
                        Log.e(TAG, "", it)
                    })
        }
        getDeviceOperationModeBtn.setOnClickListener {
            bruxweeperBleDevice?.getDeviceOperationMode()
                ?.observeOn(AndroidSchedulers.mainThread())
                ?.subscribe({
                    when (it) {
                        BruxweeperBleDevice.DeviceOperationMode.EMG_1CH -> {
                            emg1chModeRadio.isChecked = true
                        }
                        BruxweeperBleDevice.DeviceOperationMode.NORMAL_MODE -> {
                            normalModeRadio.isChecked = true
                        }
                    }
                }, {
                    Log.e(TAG, "", it)
                })
        }
        setDeviceOperationModeBtn.setOnClickListener {
            val mode = when (deviceOperationModeRadioGroup.checkedRadioButtonId) {
                emg1chModeRadio.id -> {
                    BruxweeperBleDevice.DeviceOperationMode.EMG_1CH
                }
                normalModeRadio.id -> {
                    BruxweeperBleDevice.DeviceOperationMode.NORMAL_MODE
                }
                else -> {
                    BruxweeperBleDevice.DeviceOperationMode.NORMAL_MODE
                }
            }
            bruxweeperBleDevice?.setDeviceOperationMode(mode)
                    ?.subscribe({
                        Log.e("!!!", "setDeviceOperationMode Success")
                    }, {
                        Log.e(TAG, "", it)
                    })
        }
        getEmgActivityDectectionHysteresisBtn.setOnClickListener {
            val hysteresis = bruxweeperBleDevice?.getEmgActivityDetectionHysteresis()
            emgActivityDetectionHysteresisEdit.setText(hysteresis.toString())
        }
        setEmgActivityDectectionHysteresisBtn.setOnClickListener {
            val hysteresis = emgActivityDetectionHysteresisEdit.text.toString().toInt()
            bruxweeperBleDevice?.setEmgActivityDetectionHysteresis(hysteresis)
                    ?.subscribe({
                        Log.e("!!!", "setEmgActivityDetectionHysteresis Success")
                    }, {
                        Log.e(TAG, "", it)
                    })
        }
        getEmgActivityDetectionHysteresisWidthBtn.setOnClickListener {
            val width = bruxweeperBleDevice?.getEmgActivityDetectionHysteresisWidth()
            emgActivityDetectionHysteresisWidthEdit.setText(width.toString())
        }
        setEmgActivityDetectionHysteresisWidthBtn.setOnClickListener {
            val width = emgActivityDetectionHysteresisWidthEdit.text.toString().toInt()
            bruxweeperBleDevice?.setEmgActivityDetectionHysteresisWidth(width)
                    ?.subscribe({
                        Log.e("!!!", "setEmgActivityDetectionHysteresisWidth Success")
                    }, {
                        Log.e(TAG, "", it)
                    })
        }
        getMotionActivityDetectionHysteresisBtn.setOnClickListener {
            bruxweeperBleDevice?.getMotionActivityDetectionHysteresis()
                    ?.observeOn(AndroidSchedulers.mainThread())
                    ?.subscribe({
                        motionActivityDetectionHysteresisEdit.setText(it.toString())
                    }, {
                        Log.e("!!!", "", it)
                    })
        }
        setMotionActivityDetectionHysteresisBtn.setOnClickListener {
            val hysteresis = motionActivityDetectionHysteresisEdit.text.toString().toInt()
            bruxweeperBleDevice?.setMotionActivityDetectionHysteresis(hysteresis)
                    ?.subscribe({
                        Log.e("!!!", "setMotionActivityDetectionHysteresis Success")
                    }, {
                        Log.e(TAG, "", it)
                    })
        }
        getMotionActivityDetectionHysteresisWidthBtn.setOnClickListener {
            bruxweeperBleDevice?.getMotionActivityDetectionHysteresisWidth()
                    ?.observeOn(AndroidSchedulers.mainThread())
                    ?.subscribe({
                        motionActivityDetectionHysteresisWidthEdit.setText(it.toString())
                    }, {
                        Log.e("!!!", "", it)
                    })
        }
        setMotionActivityDetectionHysteresisWidthBtn.setOnClickListener {
            val width = motionActivityDetectionHysteresisWidthEdit.text.toString().toInt()
            bruxweeperBleDevice?.setMotionActivityDetectionHysteresisWidth(width)
                    ?.subscribe({
                        Log.e("!!!", "setMotionActivityDetectionHysteresisWidth Success")
                    }, {
                        Log.e(TAG, "", it)
                    })
        }
        getBatteryBtn.setOnClickListener {
            val battery = bruxweeperBleDevice?.getBatteryPercent()
            Log.e("!!!", "getBatteryPercent:$battery")
        }
    }

    companion object {
        private val TAG = BruxweeperActivity::class.java.simpleName

        const val NAME_MAC_ADDRESS = "NAME_MAC_ADDRESS"
    }
}
