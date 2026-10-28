package com.algorigo.algorigoble2

/**
 * 스캔에 필요한 시스템 서비스 상태.
 * [bluetoothEnabled]는 블루투스가 켜져 있는지(켜지는 중/꺼지는 중은 false), [locationEnabled]는 위치 서비스가 켜져 있는지다.
 * 위치 서비스는 API 30 이하에서만 스캔에 필요하므로 API 31 이상에서는 항상 true 다.
 * 런타임 권한은 바뀌어도 알림이 없어서 여기에 넣지 않는다. [BleManager.getMissingPermissions]로 확인한다.
 */
data class ScanAvailability(
    val bluetoothEnabled: Boolean,
    val locationEnabled: Boolean,
) {
    /** 스캔할 수 있는 상태인지 */
    val available: Boolean
        get() = bluetoothEnabled && locationEnabled
}
