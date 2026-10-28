package com.algorigo.algorigoble2.impl

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import com.algorigo.algorigoble2.BleDevice
import com.algorigo.algorigoble2.BleManager
import com.algorigo.algorigoble2.BleSppSocket
import com.algorigo.algorigoble2.logging.Ble
import com.algorigo.logger.L
import com.jakewharton.rxrelay3.BehaviorRelay
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.schedulers.Schedulers
import java.io.IOException
import java.util.UUID

/**
 * 기기 하나의 Classic(RFCOMM/SPP) 연결. BLE GATT 와 전송이 다르므로 GATT 상태 머신과 분리해 자체 상태를 가진다.
 *
 * 기기의 공개 연결 상태는 하나라서(SPP_CONNECTING/SPP_CONNECTED 도 그 안의 값) 같은 기기의 GATT 연결과는 동시에 쓰지 않는다.
 * 그 확인은 [BleDeviceEngineImpl]이 하고, 상태 전환은 엔진과 같은 [lock] 안에서 해서 두 상태가 같은 순서로 보이게 한다.
 */
@SuppressLint("MissingPermission")
internal class SppConnection(
    private val context: Context,
    private val bluetoothDevice: BluetoothDevice,
    private val lock: Any,
    private val logLabel: () -> String,
) {

    sealed class State(val connectionState: BleDevice.ConnectionState) {
        object DISCONNECTED : State(BleDevice.ConnectionState.DISCONNECTED)
        // 연결 중에 disconnect() 가 불리면 소켓을 닫아 connect() 를 중단할 수 있도록 소켓을 담는다
        class CONNECTING(val socket: BluetoothSocket) : State(BleDevice.ConnectionState.SPP_CONNECTING)
        class CONNECTED(val socket: BleSppSocket) : State(BleDevice.ConnectionState.SPP_CONNECTED)
    }

    private val stateRelay = BehaviorRelay.createDefault<State>(State.DISCONNECTED)

    val stateObservable: Observable<State>
        get() = stateRelay

    val state: State
        get() = stateRelay.value!!

    private fun setState(state: State) = synchronized(lock) {
        stateRelay.accept(state)
    }

    /**
     * RFCOMM 소켓으로 연결하고 연결된 소켓을 내보낸다. 구독을 해제하거나 [disconnect]로 끊으면 소켓을 닫는다.
     * [checkCanStart]는 잠금 안에서 불리며, 연결을 시작할 수 없으면 예외를 던진다. (예: GATT 가 연결 중)
     */
    fun connect(uuid: UUID?, checkCanStart: () -> Unit): Observable<BleSppSocket> = Observable.defer {
        var connecting: State.CONNECTING? = null
        var socket: BleSppSocket? = null
        Observable.create<BleSppSocket> { emitter ->
            val aUuid = uuid ?: defaultSppUuid()
            // 확인과 전환을 한 번에 해서, 동시에 연결해도 소켓이 하나만 만들어지게 한다
            val state = synchronized(lock) {
                checkCanStart()
                val current = state
                if (current !is State.DISCONNECTED) {
                    throw BleManager.InvalidConnectionStateException(current.connectionState, BleDevice.ConnectionState.DISCONNECTED)
                }
                State.CONNECTING(bluetoothDevice.createRfcommSocketToServiceRecord(aUuid)).also { setState(it) }
            }
            connecting = state
            BleSppSocket.unconnected(state.socket).also { theSocket ->
                // 연결에 실패해도 doFinally 에서 소켓을 닫을 수 있도록 먼저 담는다
                socket = theSocket
                // 기기 검색이 진행 중이면 연결이 느려지거나 실패하므로 먼저 멈춘다
                cancelDiscovery()
                theSocket.connect()
                setState(State.CONNECTED(theSocket))
                // disconnect() 로 SPP 연결이 끊기면 스트림을 끝낸다
                emitter.setDisposable(stateRelay.filter { it !is State.CONNECTED }.firstElement()
                    .subscribe { emitter.tryOnError(BleManager.DisconnectedException()) })
                emitter.onNext(theSocket)
            }
        }
            .doFinally {
                // 이 구독이 시작한 연결만 정리한다. (연결을 시작하지 못했다면 다른 연결의 상태를 건드리지 않는다)
                synchronized(lock) {
                    val current = state
                    if ((current is State.CONNECTING && current === connecting) ||
                        (current is State.CONNECTED && current.socket === socket)) {
                        setState(State.DISCONNECTED)
                    }
                }
                socket?.close()
            }
            .subscribeOn(Schedulers.io())
    }

    /** 연결 중이면 소켓을 닫아 중단하고, 연결돼 있으면 닫고 DISCONNECTED 로 바꾼다. 아무 연결도 없으면 false. */
    fun disconnect(): Boolean = synchronized(lock) {
        when (val current = state) {
            is State.CONNECTING -> {
                // 소켓을 닫으면 진행 중인 connect() 가 IOException 으로 끝나고, connect() 의 정리 과정이 상태를 바꾼다
                try {
                    current.socket.close()
                } catch (exception: IOException) {
                    L.error(Ble, "spp socket close error", exception)
                }
                true
            }
            is State.CONNECTED -> {
                current.socket.close()
                setState(State.DISCONNECTED)
                true
            }
            State.DISCONNECTED -> false
        }
    }

    /**
     * uuid 를 지정하지 않았을 때 쓸 RFCOMM 서비스 UUID. 기기가 알려 준 UUID 에 SPP 가 있으면 SPP, 없으면 첫 UUID,
     * 기기의 UUID 를 모르면(SDP 를 한 적이 없으면 null) 표준 SPP UUID 를 쓴다.
     */
    private fun defaultSppUuid(): UUID {
        val uuids = bluetoothDevice.uuids?.map { it.uuid }.orEmpty()
        return when {
            SPP_UUID in uuids -> SPP_UUID
            uuids.isNotEmpty() -> uuids.first()
            else -> SPP_UUID
        }
    }

    private fun cancelDiscovery() {
        try {
            (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter?.cancelDiscovery()
        } catch (exception: SecurityException) {
            // API 31+ 에서 BLUETOOTH_SCAN 권한이 없으면 검색을 멈출 수 없으므로 그대로 연결한다
            L.warning(Ble.Device.Default, "${logLabel()} : cancelDiscovery is not permitted", exception)
        }
    }

    companion object {
        // Serial Port Profile
        private val SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805f9b34fb")
    }
}
