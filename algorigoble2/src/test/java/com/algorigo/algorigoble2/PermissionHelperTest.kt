package com.algorigo.algorigoble2

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Test

/** 앱이 요청할 런타임 권한 목록과 아직 허용되지 않은 권한 목록 helper 테스트 */
class PermissionHelperTest {

    private val context = mockk<Context>(relaxed = true)

    private fun deny(vararg permissions: String) {
        permissions.forEach { every { context.checkPermission(it, any(), any()) } returns PackageManager.PERMISSION_DENIED }
    }

    @Test
    fun requiredPermissions_dependOnApiLevel() {
        assertEquals(listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT), BleManager.requiredPermissions(31))
        assertEquals(listOf(Manifest.permission.ACCESS_FINE_LOCATION), BleManager.requiredPermissions(30))
        assertEquals(listOf(Manifest.permission.ACCESS_FINE_LOCATION), BleManager.requiredPermissions(29))
        assertEquals(listOf(Manifest.permission.ACCESS_COARSE_LOCATION), BleManager.requiredPermissions(28))
    }

    @Test
    fun missingPermissions_areOnlyDeniedOnes() {
        assertEquals(listOf<String>(), BleManager.missingPermissions(context, 31))

        deny(Manifest.permission.BLUETOOTH_CONNECT)
        assertEquals(listOf(Manifest.permission.BLUETOOTH_CONNECT), BleManager.missingPermissions(context, 31))

        deny(Manifest.permission.BLUETOOTH_SCAN)
        assertEquals(listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT), BleManager.missingPermissions(context, 31))
        // API 30 이하에는 BLUETOOTH_SCAN/CONNECT 권한이 없다
        assertEquals(listOf<String>(), BleManager.missingPermissions(context, 30))
    }

    // API 28 이하에서는 COARSE 대신 FINE 이 있어도 스캔할 수 있다
    @Test
    fun fineLocation_satisfiesScanPermission_onApi28AndBelow() {
        deny(Manifest.permission.ACCESS_COARSE_LOCATION)
        assertEquals(listOf<String>(), BleManager.missingPermissions(context, 28))

        deny(Manifest.permission.ACCESS_FINE_LOCATION)
        assertEquals(listOf(Manifest.permission.ACCESS_COARSE_LOCATION), BleManager.missingPermissions(context, 28))
        assertEquals(listOf(Manifest.permission.ACCESS_FINE_LOCATION), BleManager.missingPermissions(context, 29))
    }
}
