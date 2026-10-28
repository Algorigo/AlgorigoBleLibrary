package com.algorigo.algorigoble2

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** BleManager 의 권한/블루투스 설정 helper 를 실제 Android 에서 확인한다. (Intent 는 JVM 단위 테스트에서 stub 이다) */
@RunWith(AndroidJUnit4::class)
class BleManagerHelperInstrumentedTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun intents_openSystemScreens() {
        assertEquals(BluetoothAdapter.ACTION_REQUEST_ENABLE, BleManager.createEnableBluetoothIntent().action)
        assertEquals(Settings.ACTION_LOCATION_SOURCE_SETTINGS, BleManager.createLocationSettingsIntent().action)
        // 기기에 처리할 화면이 있어야 한다
        assertNotNull(BleManager.createLocationSettingsIntent().resolveActivity(context.packageManager))
    }

    @Test
    fun requiredPermissions_matchThisDevice() {
        val expected = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> listOf(Manifest.permission.ACCESS_FINE_LOCATION)
            else -> listOf(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        assertEquals(expected, BleManager.getRequiredPermissions())
    }

    @Test
    fun missingPermissions_areRequiredAndNotGranted() {
        val missing = BleManager.getMissingPermissions(context)

        assertTrue(BleManager.getRequiredPermissions().containsAll(missing))
        missing.forEach {
            assertEquals(PackageManager.PERMISSION_DENIED, ContextCompat.checkSelfPermission(context, it))
        }
    }
}
