package com.algorigo.algorigoble2

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.algorigo.algorigoble2.logging.Ble
import com.algorigo.logger.L
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * algorigo-logger 의 Tag 는 부모 태그를 Kotlin reflection 으로 찾으므로 런타임에 kotlin-reflect 가 필요하다.
 * JVM 단위 테스트는 MockK 가 kotlin-reflect 를 classpath 에 넣어 이 문제를 잡지 못하므로,
 * 라이브러리의 실제 runtime classpath 로 실행되는 instrumented test 로 확인한다.
 */
@RunWith(AndroidJUnit4::class)
class LoggingTagInstrumentedTest {

    @Test
    fun nestedTags_canBeInitializedAndUsedForLogging() {
        val tags = listOf(
            Ble,
            Ble.Engine, Ble.Engine.Rx, Ble.Engine.Default,
            Ble.Device, Ble.Device.Rx, Ble.Device.Default, Ble.Device.Provisioning,
        )

        tags.forEach { L.debug(it, "tag initialization check") }

        assertEquals("Ble.Engine.Default", Ble.Engine.Default.name)
        assertEquals("Ble.Device.Default", Ble.Device.Default.name)
    }

    @Test
    fun getDevice_returnsVirtualDevice_withoutReflectionError() {
        // getDevice 는 실제 엔진에서 Ble.Engine.Default 태그로 로그를 남긴 뒤 가상 기기를 찾는다
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val bleManager = BleManager(context, virtualDevices = arrayOf(TestVirtualDevice() to BleDevice()))

        val device = bleManager.getDevice(TestVirtualDevice.ADDRESS)

        assertNotNull(device)
        assertEquals(TestVirtualDevice.ADDRESS, device!!.deviceId)
    }
}
