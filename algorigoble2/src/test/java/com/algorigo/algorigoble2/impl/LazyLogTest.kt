package com.algorigo.algorigoble2.impl

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import com.algorigo.algorigoble2.BleDevice
import com.algorigo.algorigoble2.BleManager
import com.algorigo.algorigoble2.testutil.RxTestSchedulerRule
import io.mockk.every
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.UUID
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord

/**
 * notification 마다 로그 문자열(hex 변환, 기기 이름 조회 IPC)을 로그가 꺼져 있어도 만들던 문제 테스트.
 * 로그 레벨을 먼저 확인하므로 꺼져 있으면 기기 이름을 조회하지 않는다.
 */
class LazyLogTest {

    @get:Rule
    val rxRule = RxTestSchedulerRule()

    private val fixture = GattTestFixture()
    private val characteristic = fixture.addCharacteristic(NOTIFY_UUID, BluetoothGattCharacteristic.PROPERTY_NOTIFY)
    private val logger = BleManager.getLogger()
    private val previousLevel = logger.level
    private val records = mutableListOf<LogRecord>()
    private val handler = object : Handler() {
        override fun publish(record: LogRecord) { records.add(record) }
        override fun flush() {}
        override fun close() {}
    }
    private var nameCalls = 0

    @Before
    fun setUp() {
        logger.addHandler(handler)
        every { fixture.bluetoothDevice.name } answers { nameCalls++; GattTestFixture.NAME }
        fixture.connect()
        fixture.bleDevice.setupNotification(BleDevice.NotificationType.NOTIFICATION, NOTIFY_UUID).flatMap { it }.test()
        fixture.callback.onDescriptorWrite(fixture.gatt, characteristic.getDescriptor(GattTestFixture.CCCD_UUID), BluetoothGatt.GATT_SUCCESS)
    }

    @After
    fun tearDown() {
        logger.removeHandler(handler)
        logger.level = previousLevel
    }

    private fun notify(count: Int) {
        nameCalls = 0
        records.clear()
        repeat(count) {
            every { characteristic.value } returns byteArrayOf(it.toByte())
            fixture.callback.onCharacteristicChanged(fixture.gatt, characteristic)
        }
    }

    @Test
    fun notifications_doNotBuildLogMessages_whenLoggingIsOff() {
        logger.level = Level.INFO

        notify(100)

        assertEquals(0, nameCalls)
        assertEquals(0, records.size)
    }

    // notification 로그는 VERBOSE 로 분리했다. DEBUG 에서는 남지 않는다
    @Test
    fun notifications_areLoggedOnlyAtVerbose() {
        logger.level = Level.FINE
        notify(10)
        assertEquals(0, nameCalls)

        logger.level = Level.FINEST
        notify(10)
        assertEquals(10, records.count { it.message.contains("onCharacteristicChanged") })
        assertTrue(records.any { it.message.contains(":09") })
        assertEquals(10, nameCalls)
    }

    companion object {
        private val NOTIFY_UUID = UUID.fromString("0000aaa1-0000-1000-8000-aabbccddeeff")
    }
}
