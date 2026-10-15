package com.algorigo.algorigoblelibrary

import android.Manifest
import android.bluetooth.BluetoothManager
import android.os.Build
import android.os.SystemClock
import android.widget.Button
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.closeSoftKeyboard
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Connect Device 연결이 실패하면 백그라운드 스레드에서 Toast 를 띄워 앱이 죽던 문제(P1) 테스트.
 */
@RunWith(AndroidJUnit4::class)
class ConnectDeviceFailureTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Before
    fun setUp() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN).forEach {
                instrumentation.uiAutomation.grantRuntimePermission(context.packageName, it)
            }
        }
        val adapter = (context.getSystemService(BluetoothManager::class.java)).adapter
        assumeTrue("bluetooth must be enabled", adapter?.isEnabled == true)
    }

    @Test
    fun connectTimeout_showsErrorWithoutCrash_andReenablesButton() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            onView(withId(R.id.mac_address_edittext)).perform(replaceText(UNREACHABLE_MAC), closeSoftKeyboard())
            onView(withId(R.id.mac_address_btn)).perform(click())

            // 연결 타임아웃(10초)으로 onError 가 호출될 때까지 기다린다. 수정 전에는 이때 프로세스가 죽는다
            var disabledWhileConnecting = false
            var enabled = false
            val deadline = SystemClock.uptimeMillis() + WAIT_MILLIS
            while (SystemClock.uptimeMillis() < deadline) {
                scenario.onActivity { enabled = it.findViewById<Button>(R.id.mac_address_btn).isEnabled }
                if (!enabled) disabledWhileConnecting = true
                if (disabledWhileConnecting && enabled) break
                SystemClock.sleep(200)
            }

            assertEquals(Lifecycle.State.RESUMED, scenario.state)
            assertTrue("button must be disabled while connecting", disabledWhileConnecting)
            assertTrue("button must be enabled again after the failure", enabled)
        }
    }

    companion object {
        // RFC 7042 문서용 MAC 주소 범위라서 실제 기기가 응답할 일이 없다
        private const val UNREACHABLE_MAC = "00:00:5E:00:53:01"
        private const val WAIT_MILLIS = 30_000L
    }
}
