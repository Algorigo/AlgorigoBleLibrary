package com.algorigo.algorigoble2.impl

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.algorigo.algorigoble2.BleManager

/**
 * BLE 작업에 필요한 런타임 권한을 확인한다. 없으면 플랫폼의 SecurityException 대신 [BleManager.PermissionDeniedException]을 던진다.
 * (BLUETOOTH, BLUETOOTH_ADMIN 은 API 30 이하에서 설치 시 부여되는 권한이라 확인하지 않는다)
 */
@SuppressLint("InlinedApi")
internal object BlePermissions {

    /** 스캔: API 31+ BLUETOOTH_SCAN, API 29~30 ACCESS_FINE_LOCATION, 그 이하 ACCESS_COARSE_LOCATION 또는 FINE */
    fun missingScanPermissions(context: Context, sdkInt: Int = Build.VERSION.SDK_INT): List<String> = when {
        sdkInt >= Build.VERSION_CODES.S -> missing(context, Manifest.permission.BLUETOOTH_SCAN)
        sdkInt >= Build.VERSION_CODES.Q -> missing(context, Manifest.permission.ACCESS_FINE_LOCATION)
        granted(context, Manifest.permission.ACCESS_FINE_LOCATION) -> listOf()
        else -> missing(context, Manifest.permission.ACCESS_COARSE_LOCATION)
    }

    /** 연결/본딩/기기 이름/본딩 상태: API 31+ BLUETOOTH_CONNECT */
    fun missingConnectPermissions(context: Context, sdkInt: Int = Build.VERSION.SDK_INT): List<String> = when {
        sdkInt >= Build.VERSION_CODES.S -> missing(context, Manifest.permission.BLUETOOTH_CONNECT)
        else -> listOf()
    }

    fun checkScan(context: Context, sdkInt: Int = Build.VERSION.SDK_INT) = throwIfMissing(missingScanPermissions(context, sdkInt))

    fun checkConnect(context: Context, sdkInt: Int = Build.VERSION.SDK_INT) = throwIfMissing(missingConnectPermissions(context, sdkInt))

    private fun throwIfMissing(missing: List<String>) {
        if (missing.isNotEmpty()) {
            throw BleManager.PermissionDeniedException(missing)
        }
    }

    private fun granted(context: Context, permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private fun missing(context: Context, permission: String) = if (granted(context, permission)) listOf() else listOf(permission)
}
