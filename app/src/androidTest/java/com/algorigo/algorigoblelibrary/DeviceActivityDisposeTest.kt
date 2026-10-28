package com.algorigo.algorigoblelibrary

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * DeviceActivity 가 종료돼도 notification 구독을 해제하지 않던 문제(P2) 테스트.
 * 샘플 앱의 가상 기기는 구독이 하나라도 있으면 1초마다 값을 보내므로, 화면을 닫은 뒤 보내기를 멈추는지 확인한다.
 */
@RunWith(AndroidJUnit4::class)
class DeviceActivityDisposeTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private var connection: ServiceConnection? = null

    private fun bindService(): BluetoothService {
        val latch = CountDownLatch(1)
        var service: BluetoothService? = null
        connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                service = (binder as BluetoothService.BluetoothBinder).getService()
                latch.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName?) {}
        }
        context.bindService(Intent(context, BluetoothService::class.java), connection!!, Context.BIND_AUTO_CREATE)
        assertTrue("service must be bound", latch.await(5, TimeUnit.SECONDS))
        return service!!
    }

    @After
    fun tearDown() {
        connection?.let { context.unbindService(it) }
    }

    private fun waitUntil(condition: () -> Boolean): Boolean {
        val deadline = SystemClock.uptimeMillis() + 5_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return true
            SystemClock.sleep(100)
        }
        return condition()
    }

    @Test
    fun closingActivity_stopsNotification() {
        val service = bindService()
        val device = service.bleManager.getDevice(VIRTUAL_MAC)!!
        device.connectCompletable().blockingAwait()
        val uuid = device.getCharacteristicsSingle().blockingGet().first().uuid

        val intent = Intent(context, DeviceActivity::class.java).putExtra(DeviceActivity.DEVICE_MAC_ADDRESS, VIRTUAL_MAC)
        ActivityScenario.launch<DeviceActivity>(intent).use { scenario ->
            scenario.onActivity { it.onNotifyBtn(uuid) }
            assertTrue("notification must start", waitUntil { service.virtualBleDevice.notifying })
        }

        // 수정 전에는 화면이 닫혀도 구독이 남아 계속 보낸다
        assertTrue("notification must stop after the activity is destroyed", waitUntil { !service.virtualBleDevice.notifying })
        device.disconnect()
        assertFalse(service.virtualBleDevice.notifying)
    }

    companion object {
        private const val VIRTUAL_MAC = "12:34:56:78:90:ab"
    }
}
