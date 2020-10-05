package com.algorigo.algorigoblelibrary.bruxweeper

import android.bluetooth.BluetoothDevice
import android.util.Log
import com.algorigo.algorigoble.BleScanFilter
import com.algorigo.algorigoble.InitializableBleDevice
import com.algorigo.algorigoblelibrary.ByteUtil
import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.disposables.Disposable
import io.reactivex.rxjava3.subjects.BehaviorSubject
import io.reactivex.rxjava3.subjects.PublishSubject
import io.reactivex.rxjava3.subjects.Subject
import java.util.UUID
import java.util.concurrent.TimeUnit

class BruxweeperBleDevice: InitializableBleDevice() {

    interface StreamDataPacket {
        val crd: Boolean
        val pcd: Pair<PacketCyclicData, Byte>?
    }

    class Emg1ChStreamDataPacket(byteArray: ByteArray) :
        StreamDataPacket {
        override val crd: Boolean
        override val pcd: Pair<PacketCyclicData, Byte>?
        val emgData = FloatArray(10)

        init {
            crd = byteArray[1].toInt() and 0b10000000 > 0
            pcd = when(byteArray[2].toInt()) {
                0 -> Pair(PacketCyclicData.DeviceIdCyclicData, byteArray[3])
                1 -> Pair(PacketCyclicData.FirmDCyclicData, byteArray[3])
                2 -> Pair(PacketCyclicData.FirmFCyclicData, byteArray[3])
                3 -> Pair(PacketCyclicData.FirmRCyclicData, byteArray[3])
                4 -> Pair(PacketCyclicData.SeralNoCyclic1Dta, byteArray[3])
                5 -> Pair(PacketCyclicData.SeralNoCyclic2Dta, byteArray[3])
                6 -> Pair(PacketCyclicData.SeralNoCyclic3Dta, byteArray[3])
                7 -> Pair(PacketCyclicData.SeralNoCyclic4Dta, byteArray[3])
                10 -> Pair(PacketCyclicData.BatteryCyclicData, byteArray[3])
                else -> null
            }
            emgData[0] = (ByteUtil.getIntOfBytes(
                byteArray[4],
                byteArray[5]
            ) - 32768) * 0.01f
            emgData[1] = (ByteUtil.getIntOfBytes(
                byteArray[6],
                byteArray[7]
            ) - 32768) * 0.01f
            emgData[2] = (ByteUtil.getIntOfBytes(
                byteArray[8],
                byteArray[9]
            ) - 32768) * 0.01f
            emgData[3] = (ByteUtil.getIntOfBytes(
                byteArray[10],
                byteArray[11]
            ) - 32768) * 0.01f
            emgData[4] = (ByteUtil.getIntOfBytes(
                byteArray[12],
                byteArray[13]
            ) - 32768) * 0.01f
            emgData[5] = (ByteUtil.getIntOfBytes(
                byteArray[14],
                byteArray[15]
            ) - 32768) * 0.01f
            emgData[6] = (ByteUtil.getIntOfBytes(
                byteArray[16],
                byteArray[17]
            ) - 32768) * 0.01f
            emgData[7] = (ByteUtil.getIntOfBytes(
                byteArray[18],
                byteArray[19]
            ) - 32768) * 0.01f
            emgData[8] = (ByteUtil.getIntOfBytes(
                byteArray[20],
                byteArray[21]
            ) - 32768) * 0.01f
            emgData[9] = (ByteUtil.getIntOfBytes(
                byteArray[22],
                byteArray[23]
            ) - 32768) * 0.01f
        }
    }

    class NormalStreamDataPacket(byteArray: ByteArray) :
        StreamDataPacket {
        override val crd: Boolean
        val emgStatus: Boolean
        val feedbackStatus: Boolean
        val emgActive: Boolean
        val motionStatus: Boolean
        val motionActive: Boolean
        override val pcd: Pair<PacketCyclicData, Byte>?
        val emgEnv: Int
        val motion: Int
        val acceleration = FloatArray(3)

        init {
            crd = byteArray[1].toInt() and 0b10000000 > 0
            emgStatus = byteArray[1].toInt() and 0b1000000 > 0
            feedbackStatus = byteArray[1].toInt() and 0b00000 > 0
            emgActive = byteArray[1].toInt() and 0b10000 > 0
            motionStatus = byteArray[1].toInt() and 0b1000 > 0
            motionActive = byteArray[1].toInt() and 0b100 > 0
            pcd = when(byteArray[2].toInt()) {
                0 -> Pair(PacketCyclicData.DeviceIdCyclicData, byteArray[3])
                1 -> Pair(PacketCyclicData.FirmDCyclicData, byteArray[3])
                2 -> Pair(PacketCyclicData.FirmFCyclicData, byteArray[3])
                3 -> Pair(PacketCyclicData.FirmRCyclicData, byteArray[3])
                4 -> Pair(PacketCyclicData.SeralNoCyclic1Dta, byteArray[3])
                5 -> Pair(PacketCyclicData.SeralNoCyclic2Dta, byteArray[3])
                6 -> Pair(PacketCyclicData.SeralNoCyclic3Dta, byteArray[3])
                7 -> Pair(PacketCyclicData.SeralNoCyclic4Dta, byteArray[3])
                10 -> Pair(PacketCyclicData.BatteryCyclicData, byteArray[3])
                12 -> Pair(PacketCyclicData.EmgActiveCountHData, byteArray[3])
                13 -> Pair(PacketCyclicData.EmgActiveCountLData, byteArray[3])
                14 -> Pair(PacketCyclicData.MotionActiveCountHData, byteArray[3])
                15 -> Pair(PacketCyclicData.MotionActiveCountLData, byteArray[3])
                else -> null
            }
            emgEnv =
                ByteUtil.getIntOfBytes(byteArray[4], byteArray[5])
            motion =
                ByteUtil.getIntOfBytes(byteArray[6], byteArray[7])
            acceleration[0] = (ByteUtil.getIntOfBytes(
                byteArray[8],
                byteArray[9]
            ) - 32768) * 0.01f
            acceleration[1] = (ByteUtil.getIntOfBytes(
                byteArray[10],
                byteArray[11]
            ) - 32768) * 0.01f
            acceleration[2] = (ByteUtil.getIntOfBytes(
                byteArray[12],
                byteArray[13]
            ) - 32768) * 0.01f
        }
    }

    enum class PacketCyclicData {
        DeviceIdCyclicData,
        FirmDCyclicData,
        FirmFCyclicData,
        FirmRCyclicData,
        SeralNoCyclic1Dta,
        SeralNoCyclic2Dta,
        SeralNoCyclic3Dta,
        SeralNoCyclic4Dta,
        BatteryCyclicData,
        EmgActiveCountHData,
        EmgActiveCountLData,
        MotionActiveCountHData,
        MotionActiveCountLData
    }

    data class CurrentShape(val loadCurrent: Int, val pulseDuration: Int)

    enum class RampUpType(val value: Byte) {
        TYPE_0(0),
        TYPE_1(1),
        TYPE_2(2),
        TYPE_3(3),
        TYPE_4(4),
        TYPE_5(5),
        TYPE_6(6),
        TYPE_7(7),
        TYPE_8(8),
        TYPE_9(9),
        TYPE_10(10),
        TYPE_11(11),
    }

    enum class StimulationSourceType(val value: Byte) {
        NONE(0),
        CURRENT(1),
        MOTOR(2),
    }

    enum class StimulationWaveform(val value: Byte) {
        RECTANGULAR(0),
        SAW(1),
    }

    data class ControlParams(val stmActivation: Boolean, val drvApply: Boolean, val grindingCount: Boolean, val motionCount: Boolean)

    enum class DeviceOperationMode(val value: Byte) {
        EMG_1CH(3),
        NORMAL_MODE(4),
    }

    data class Data(val pda: Byte, val subject: Subject<ByteArray>)

    private lateinit var initializeSubject: Subject<ByteArray>
    private var nonStreamDisposable: Disposable? = null
    private val dataMap = mutableMapOf<Byte, Data>()

    private var timerSetSec = 0f
    private lateinit var currentShape: CurrentShape
    private lateinit var rampUpType: RampUpType
    private var stmdetTime = 0f
    private var emgDelayTime = 0f
    private lateinit var stimulationSourceType: StimulationSourceType
    private lateinit var stimulationWaveform: StimulationWaveform
    private lateinit var controlParams: ControlParams
    private var emgActivityDetectionHysteresis = 0
    private var emgActivityDetectionHysteresisWidth = 0
    private var batteryPercent = 0
    private lateinit var firmwareVersion: String

    override fun initializeCompletable(): Completable {
        initializeSubject = PublishSubject.create<ByteArray>().toSerialized()
        return initializeSubject
            .doOnSubscribe {
                nonStreamDisposable = nonStreamNotification()?.subscribe({
                    initializeSubject.onComplete()
                    it.doFinally {
                        Completable.timer(100, TimeUnit.MILLISECONDS)
                            .andThen(initializeCompletable())
                            .subscribe({
                                Log.d(TAG, "initializeCompletable recomplete")
                            }, {
                                Log.e(TAG, "", it)
                            })
                    }.subscribe({
                        onCallback(it)
                    }, {
                        Log.e(TAG, "", it)
                    })
                }, {
                    Log.e(TAG, "", it)
                    if (!initializeSubject.hasComplete() && !initializeSubject.hasThrowable() && initializeSubject.hasObservers()) {
                        initializeSubject.onError(it)
                    }
                })
            }
            .ignoreElements()
            .andThen(getTimerSetSecSingle())
            .flatMap {
                timerSetSec = it
                getCurrentShapeSingle()
            }
            .flatMap {
                currentShape = it
                getRampUpTypeSingle()
            }
            .flatMap {
                rampUpType = it
                getStmdetTimeMillisSingle()
            }
            .flatMap {
                stmdetTime = it
                getEmgDelayTimeMillisSingle()
            }
            .flatMap {
                emgDelayTime = it
                getStmSourceTypeSingle()
            }
            .flatMap {
                stimulationSourceType = it
                getStmWaveformSingle()
            }
            .flatMap {
                stimulationWaveform = it
                getControlParamsSingle()
            }
            .flatMap {
                controlParams = it
                getEmgActivityDetectionHysteresisSingle()
            }
            .flatMap {
                emgActivityDetectionHysteresis = it
                getEmgActivityDetectionHysteresisWidthSingle()
            }
            .flatMap {
                emgActivityDetectionHysteresisWidth = it
                getBatteryPercentSingle()
            }
            .flatMap {
                batteryPercent = it
                getFirmwareVersion()
            }
            .map {
                firmwareVersion = it
            }
            .ignoreElement()
    }

    override fun onDisconnected() {
        super.onDisconnected()
        nonStreamDisposable?.dispose()
        nonStreamDisposable = null
    }

    fun startStream(): Observable<StreamDataPacket>? {
        var emgActiveCountHData: Byte = 0x00
        var motionActiveCountHData: Byte = 0x00
        return setupNotification(UUID.fromString(UUID_TX))
            ?.flatMap { it }
            ?.map {
                if (it[0].toInt() == 16) {
                    Emg1ChStreamDataPacket(
                        it
                    )
                } else {
                    NormalStreamDataPacket(
                        it
                    )
                }
            }
            ?.doOnNext {
                when (it.pcd?.first) {
                    PacketCyclicData.BatteryCyclicData -> {
                        batteryPercent = it.pcd!!.second.toInt()
                    }
                    PacketCyclicData.EmgActiveCountHData -> {
                        emgActiveCountHData = it.pcd!!.second
                    }
                    PacketCyclicData.EmgActiveCountLData -> {
                        ByteUtil.getIntOfBytes(
                            emgActiveCountHData,
                            it.pcd!!.second
                        )
                    }
                    PacketCyclicData.MotionActiveCountHData -> {
                        motionActiveCountHData = it.pcd!!.second
                    }
                    PacketCyclicData.MotionActiveCountLData -> {
                        ByteUtil.getIntOfBytes(
                            motionActiveCountHData,
                            it.pcd!!.second
                        )
                    }
                }
            }
    }

    fun motorOn(): Completable? {
        return command(0)
    }

    fun pulse(): Completable? {
        return command(1)
    }

    fun setTimerSetSec(timerSetSec: Float = 0.25f): Completable? {
        return setTimerSet(Math.round(timerSetSec/0.25f))
    }

    private fun setTimerSet(timerSet: Int = 1): Completable? {
        Log.d(TAG, "stmdet time = ${timerSet*0.25f}Sec")
        return write(0, 1, byteArrayOf(timerSet.toByte()))
            ?.doOnComplete {
                timerSetSec = timerSet * 0.25f
            }
    }

    fun getTimerSetSec(): Float {
        return timerSetSec
    }

    private fun getTimerSetSecSingle(): Single<Float>? {
        return read(0)
            ?.map {
                it[4].toInt()*0.25f
            }
    }

    fun setCurrentShape(loadCurrent: Int = 2, pulseDuration: Int = 450): Completable? {
        Log.d(TAG, "current: ${loadCurrent*0.1}mA, pulse duration: ${pulseDuration}ms")
        return write(2, 2, byteArrayOf(loadCurrent.toByte(), (pulseDuration shr 8).toByte(), pulseDuration.toByte()))
            ?.doOnComplete {
                currentShape =
                    CurrentShape(
                        loadCurrent,
                        pulseDuration
                    )
            }
    }

    fun getCurrentShape(): CurrentShape {
        return currentShape
    }

    private fun getCurrentShapeSingle(): Single<CurrentShape>? {
        return read(2)
            ?.map {
                val aUpper = it[5].toInt() and 0xff
                val aLower = it[6].toInt() and 0xff
                CurrentShape(
                    it[4].toInt(),
                    (aUpper shl 8) + aLower
                )
            }
    }

    fun setRampUpType(rampUpType: RampUpType = RampUpType.TYPE_0): Completable? {
        return write(3, 1, byteArrayOf(rampUpType.value))
            ?.doOnComplete {
                this.rampUpType = rampUpType
            }
    }

    fun getRampUpType(): RampUpType {
        return rampUpType
    }

    private fun getRampUpTypeSingle(): Single<RampUpType>? {
        return read(3)
            ?.map {
                when (it[4]) {
                    RampUpType.TYPE_0.value -> RampUpType.TYPE_0
                    RampUpType.TYPE_1.value -> RampUpType.TYPE_1
                    RampUpType.TYPE_2.value -> RampUpType.TYPE_2
                    RampUpType.TYPE_3.value -> RampUpType.TYPE_3
                    RampUpType.TYPE_4.value -> RampUpType.TYPE_4
                    RampUpType.TYPE_5.value -> RampUpType.TYPE_5
                    RampUpType.TYPE_6.value -> RampUpType.TYPE_6
                    RampUpType.TYPE_7.value -> RampUpType.TYPE_7
                    RampUpType.TYPE_8.value -> RampUpType.TYPE_8
                    RampUpType.TYPE_9.value -> RampUpType.TYPE_9
                    RampUpType.TYPE_10.value -> RampUpType.TYPE_10
                    RampUpType.TYPE_11.value -> RampUpType.TYPE_11
                    else -> throw IllegalStateException("ramp up type is wrong : ${it[4]}")
                }
            }
    }

    fun setStmdetTimeMillis(millisec: Float = 500f): Completable? {
        return setStmdetTime(Math.round(millisec/62.5f))
    }

    private fun setStmdetTime(stmdetTime: Int = 8): Completable? {
        Log.d(TAG, "stmdet time = ${stmdetTime*62.5}millisec")
        return write(4, 1, byteArrayOf(stmdetTime.toByte()))
            ?.doOnComplete {
                this.stmdetTime = stmdetTime * 62.5f
            }
    }

    fun getStmdetTimeMillis(): Float {
        return stmdetTime
    }

    private fun getStmdetTimeMillisSingle(): Single<Float>? {
        return read(4)
            ?.map {
                it[4].toInt() * 62.5f
            }
    }

    fun setEmgDelayTimeMillis(millisec: Float = 1500f): Completable? {
        return setEmgDelayTime(Math.round(millisec/62.5f))
    }

    private fun setEmgDelayTime(emgDelayTime: Int = 24): Completable? {
        Log.d(TAG, "emg delay time = ${emgDelayTime*62.5}millisec")
        return write(5, 1, byteArrayOf(emgDelayTime.toByte()))
            ?.doOnComplete {
                this.emgDelayTime = emgDelayTime * 62.5f
            }
    }

    fun getEmgDelayTimeMillis(): Float {
        return emgDelayTime
    }

    private fun getEmgDelayTimeMillisSingle(): Single<Float>? {
        return read(5)
            ?.map {
                it[4].toInt() * 62.5f
            }
    }

    fun setStmSourceType(stmSourcetype: StimulationSourceType = StimulationSourceType.CURRENT): Completable? {
        return write(6, 1, byteArrayOf(stmSourcetype.value))
            ?.doOnComplete {
                stimulationSourceType = stmSourcetype
            }
    }

    fun getStmSourceType(): StimulationSourceType {
        return stimulationSourceType
    }

    private fun getStmSourceTypeSingle(): Single<StimulationSourceType>? {
        return read(6)
            ?.map {
                when (it[4]) {
                    StimulationSourceType.NONE.value -> StimulationSourceType.NONE
                    StimulationSourceType.CURRENT.value -> StimulationSourceType.CURRENT
                    StimulationSourceType.MOTOR.value -> StimulationSourceType.MOTOR
                    else -> throw IllegalStateException("stimulation source type is wrong : ${it[4]}")
                }
            }
    }

    fun setStmWaveform(stmWaveform: StimulationWaveform = StimulationWaveform.SAW): Completable? {
        return write(7, 1, byteArrayOf(stmWaveform.value))
            ?.doOnComplete {
                stimulationWaveform = stmWaveform
            }
    }

    fun getStmWaveform(): StimulationWaveform {
        return stimulationWaveform
    }

    private fun getStmWaveformSingle(): Single<StimulationWaveform>? {
        return read(7)
            ?.map {
                when (it[4]) {
                    StimulationWaveform.RECTANGULAR.value -> StimulationWaveform.RECTANGULAR
                    StimulationWaveform.SAW.value -> StimulationWaveform.SAW
                    else -> throw IllegalStateException("stimulation waveform is wrong : ${it[4]}")
                }
            }
    }

    fun setControlParams(stmActivation: Boolean, drvApply: Boolean, grindingCount: Boolean, motionCount: Boolean): Completable? {
        val param = (if (stmActivation) 0b10000000 else 0) or
                (if (drvApply) 0b1000000 else 0) or
                (if (grindingCount) 0b100000 else 0) or
                (if (motionCount) 0b10000 else 0)
        return write(8, 1, byteArrayOf(param.toByte()))
            ?.doOnComplete {
                controlParams =
                    ControlParams(
                        stmActivation,
                        drvApply,
                        grindingCount,
                        motionCount
                    )
            }
    }

    fun getControlParams(): ControlParams {
        return controlParams
    }

    private fun getControlParamsSingle(): Single<ControlParams>? {
        return read(8)
            ?.map {
                ControlParams(
                    (it[4].toInt() and 0b10000000) != 0,
                    (it[4].toInt() and 0b1000000) != 0,
                    (it[4].toInt() and 0b100000) != 0,
                    (it[4].toInt() and 0b10000) != 0
                )
            }
    }

    fun setDeviceOperationMode(streamMode: DeviceOperationMode = DeviceOperationMode.NORMAL_MODE): Completable? {
        return write(10, 1, byteArrayOf(streamMode.value))
    }

    fun getDeviceOperationMode(): Single<DeviceOperationMode>? {
        return read(10)
            ?.map {
                when (it[4]) {
                    DeviceOperationMode.EMG_1CH.value -> DeviceOperationMode.EMG_1CH
                    DeviceOperationMode.NORMAL_MODE.value -> DeviceOperationMode.NORMAL_MODE
                    else -> throw IllegalStateException("device operation mode is wrong : ${it[4]}")
                }
            }
    }

    fun setEmgActivityDetectionHysteresis(envHystTh: Int = 5000): Completable? {
        return write(12, 2, byteArrayOf((envHystTh shr 8).toByte(), envHystTh.toByte()))
            ?.doOnComplete {
                emgActivityDetectionHysteresis = envHystTh
            }
    }

    fun getEmgActivityDetectionHysteresis(): Int {
        return emgActivityDetectionHysteresis
    }

    private fun getEmgActivityDetectionHysteresisSingle(): Single<Int>? {
        return read(12)
            ?.map {
                val aUpper = it[4].toInt() and 0xff
                val aLower = it[5].toInt() and 0xff
                (aUpper shl 8) + aLower
            }
    }

    fun setEmgActivityDetectionHysteresisWidth(envHystWid: Int = 1000): Completable? {
        return write(13, 2, byteArrayOf((envHystWid shr 8).toByte(), envHystWid.toByte()))
            ?.doOnComplete {
                emgActivityDetectionHysteresisWidth = envHystWid
            }
    }

    fun getEmgActivityDetectionHysteresisWidth(): Int {
        return emgActivityDetectionHysteresisWidth
    }

    private fun getEmgActivityDetectionHysteresisWidthSingle(): Single<Int>? {
        return read(13)
            ?.map {
                val aUpper = it[4].toInt() and 0xff
                val aLower = it[5].toInt() and 0xff
                (aUpper shl 8) + aLower
            }
    }

    fun setMotionActivityDetectionHysteresis(motionHystTh: Int = 500): Completable? {
        return write(14, 2, byteArrayOf((motionHystTh shr 8).toByte(), motionHystTh.toByte()))
    }

    fun getMotionActivityDetectionHysteresis(): Single<Int>? {
        return read(14)
            ?.map {
                val aUpper = it[4].toInt() and 0xff
                val aLower = it[5].toInt() and 0xff
                (aUpper shl 8) + aLower
            }
    }

    fun setMotionActivityDetectionHysteresisWidth(motionHystWidth: Int = 100): Completable? {
        return write(15, 2, byteArrayOf((motionHystWidth shr 8).toByte(), motionHystWidth.toByte()))
    }

    fun getMotionActivityDetectionHysteresisWidth(): Single<Int>? {
        return read(15)
            ?.map {
                val aUpper = it[4].toInt() and 0xff
                val aLower = it[5].toInt() and 0xff
                (aUpper shl 8) + aLower
            }
    }

    fun setMotionDetectionTimeMillis(millisec: Float = 500f): Completable? {
        return setMotionDetectionTime(Math.round(millisec/62.5f))
    }

    private fun setMotionDetectionTime(motionDetTime: Int = 8): Completable? {
        Log.d(TAG, "motion detection time = ${motionDetTime*62.5}millisec")
        return write(16, 2, byteArrayOf(motionDetTime.toByte()))
    }

    fun getMotionDetectionTimeMillis(): Single<Float>? {
        return read(16)
            ?.map {
                it[4].toInt() * 62.5f
            }
    }

    fun getBatteryPercent(): Int {
        return batteryPercent
    }

    private fun getBatteryPercentSingle(): Single<Int>? {
        return read(29)
            ?.map {
                it[4].toInt()
            }
    }

    private fun getFirmwareVersion(): Single<String>? {
        return read(30)
            ?.map {
                "${it[4].toInt()}.${it[5].toInt()}.${it[6].toInt()}"
            }
    }

    private fun command(pda: Byte): Completable? {
        val data = ByteArray(16).apply { fill(0) }
        data[0] = 54
        data[1] = 1
        data[2] = pda
        return sendData(data)
            ?.ignoreElement()
    }

    private fun write(pda: Byte, pds: Byte, pd_data: ByteArray): Completable? {
        val data = ByteArray(16).apply { fill(0) }
        data[0] = 54
        data[1] = 2
        data[2] = pda
        data[3] = pds
        pd_data.copyInto(data, 4, 0, pd_data.size)
        return sendData(data)
            ?.ignoreElement()
    }

    private fun read(pda: Byte): Single<ByteArray>? {
        val data = ByteArray(16).apply { fill(0) }
        data[0] = 54
        data[1] = 3
        data[2] = pda

        val subject = BehaviorSubject.create<ByteArray>()
        return subject
            .doOnSubscribe {
                checkPdaAvailable(pda)
                    .andThen(sendData(data)?.doOnSubscribe {
                        dataMap.put(pda,
                            Data(
                                pda,
                                subject
                            )
                        )
                    })
                    .subscribe({
                    }, {
                        Log.e(TAG, "", it)
                        dataMap.remove(pda)
                    })
            }
            .firstOrError()
    }

    private fun checkPdaAvailable(pda: Byte): Completable {
        return Completable.fromCallable {
            if (dataMap.containsKey(pda)) {
                throw IllegalStateException("pda $pda is in progress")
            }
        }
            .retry(5)
    }

    private fun sendData(data: ByteArray): Single<ByteArray>? {
        return writeCharacteristic(UUID.fromString(UUID_RX), data)
    }

    private fun nonStreamNotification(): Observable<Observable<ByteArray>>? {
        return setupNotification(UUID.fromString(UUID_NON_STREAM))
    }

    private fun onCallback(byteArray: ByteArray) {
        val pda = byteArray[2]
        dataMap.get(pda)?.let {
            dataMap.remove(pda)
            it.subject.onNext(byteArray)
            it.subject.onComplete()
        }
    }

    companion object {
        private val TAG = BruxweeperBleDevice::class.java.simpleName

        private const val BLE_NAME = "bruxweeper"

        fun isMatch(bluetoothDevice: BluetoothDevice): Boolean {
            return bluetoothDevice.name?.equals(BLE_NAME) ?: false
        }

        fun getScanFilter(): BleScanFilter {
            return BleScanFilter.Builder()
                .setDeviceName(BLE_NAME)
                .build()
        }

        private const val UUID_TX = "581f3b86-6e63-48cc-a618-288167d2c4a3"
        private const val UUID_RX = "581f3b86-6e63-48cc-a618-288167d2c4a4"
        private const val UUID_NON_STREAM = "581f3b86-6e63-48cc-a618-288167d2c4a5"
    }
}