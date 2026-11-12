package com.algorigo.algorigoble2.impl

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanRecord
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import com.algorigo.algorigoble2.BleDevice
import com.algorigo.algorigoble2.BleManager
import com.algorigo.algorigoble2.testutil.RxTestSchedulerRule
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.lang.ref.WeakReference

/**
 * BroadcastReceiver 를 finalize() 에서 해제해서, 등록된 receiver 가 엔진을 붙잡아 해제되지 않던 문제(P2) 테스트.
 */
class BleManagerEngineImplCloseTest {

    @get:Rule
    val rxRule = RxTestSchedulerRule()

    private val context = mockk<Context>(relaxed = true)
    private val bluetoothManager = mockk<BluetoothManager>(relaxed = true)
    private val adapter = mockk<BluetoothAdapter>(relaxed = true)
    private val registered = mutableListOf<BroadcastReceiver>()

    private val delegate = object : BleManager.BleDeviceDelegate() {
        override fun createBleDevice(bluetoothDevice: BluetoothDevice, scanRecord: ScanRecord?) = BleDevice()
    }

    init {
        every { context.getSystemService(Context.BLUETOOTH_SERVICE) } returns bluetoothManager
        every { context.applicationContext } returns context
        every { bluetoothManager.adapter } returns adapter
        every { context.registerReceiver(capture(registered), any<IntentFilter>()) } returns null
    }

    @Test
    fun close_unregistersReceiver_once() {
        val engine = BleManagerEngineImpl(context, delegate)

        engine.close()
        engine.close()

        verify(exactly = 1) { context.unregisterReceiver(registered.single()) }
    }

    @Test
    fun close_disconnectsConnectedDevices() {
        val engine = BleManagerEngineImpl(context, delegate)
        val bluetoothDevice = mockk<BluetoothDevice>(relaxed = true)
        every { bluetoothDevice.address } returns ADDRESS
        every { adapter.getRemoteDevice(ADDRESS) } returns bluetoothDevice
        val callback = slot<BluetoothGattCallback>()
        val gatt = mockk<BluetoothGatt>(relaxed = true)
        every { gatt.discoverServices() } returns true
        every { bluetoothDevice.connectGatt(any(), any(), capture(callback), any()) } returns gatt
        val device = engine.getDevice<BleDevice>(ADDRESS)!!
        device.connectCompletable().test()
        callback.captured.onConnectionStateChange(gatt, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED)
        callback.captured.onServicesDiscovered(gatt, BluetoothGatt.GATT_SUCCESS)

        engine.close()

        verify(exactly = 1) { gatt.disconnect() }
    }

    // 별도 함수로 만들어 테스트 코드에 엔진 참조가 남지 않게 한다
    private fun createEngineWithoutKeepingReference(): WeakReference<BleManagerEngineImpl> =
        WeakReference(BleManagerEngineImpl(context, delegate))

    @Test
    fun engineNotClosed_canBeCollected_andReceiverUnregistersItselfOnNextBroadcast() {
        val engineReference = createEngineWithoutKeepingReference()

        // 등록된 receiver 는 계속 참조되지만(앱에서는 Context 가, 여기서는 mock 이 참조) 엔진은 GC 되어야 한다
        var collected = false
        repeat(50) {
            System.gc()
            if (engineReference.get() == null) {
                collected = true
                return@repeat
            }
            Thread.sleep(20)
        }
        assertTrue("engine must be collectable while its receiver is registered", collected)

        val intent = mockk<Intent>(relaxed = true)
        every { intent.action } returns BluetoothAdapter.ACTION_STATE_CHANGED
        registered.single().onReceive(context, intent)

        verify(exactly = 1) { context.unregisterReceiver(registered.single()) }
    }

    companion object {
        private const val ADDRESS = "00:11:22:33:44:55"
    }
}
