package com.algorigo.algorigoble2.impl

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import com.algorigo.algorigoble2.BleManager
import com.algorigo.algorigoble2.testutil.RxTestSchedulerRule
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.TimeUnit

/** 본딩 상태를 1초 polling 하던 것을 ACTION_BOND_STATE_CHANGED broadcast 와 timeout 으로 바꾼 것을 확인한다. */
class BondTest {

    @get:Rule
    val rxRule = RxTestSchedulerRule()

    private val fixture = GattTestFixture()
    private val device = fixture.bluetoothDevice
    private val receiver = slot<BroadcastReceiver>()

    @Before
    fun setUp() {
        every { fixture.context.registerReceiver(capture(receiver), any<IntentFilter>()) } returns null
        every { device.bondState } returns BluetoothDevice.BOND_NONE
        every { device.createBond() } returns true
    }

    private fun bondStateChanged(previous: Int, state: Int, reason: Int? = null, target: BluetoothDevice = device) {
        val intent = mockk<Intent>(relaxed = true)
        every { intent.action } returns BluetoothDevice.ACTION_BOND_STATE_CHANGED
        every { intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) } returns target
        every { intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, any()) } returns previous
        every { intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, any()) } returns state
        every { intent.hasExtra("android.bluetooth.device.extra.REASON") } returns (reason != null)
        every { intent.getIntExtra("android.bluetooth.device.extra.REASON", any()) } returns (reason ?: 0)
        receiver.captured.onReceive(fixture.context, intent)
    }

    @Test
    fun alreadyBonded_completesWithoutRequest() {
        every { device.bondState } returns BluetoothDevice.BOND_BONDED

        fixture.bleDevice.bondCompletable().test().assertComplete()

        verify(exactly = 0) { device.createBond() }
    }

    @Test
    fun bond_completesWhenBondedBroadcastArrives_withoutPolling() {
        val observer = fixture.bleDevice.bondCompletable().test()
        verify(exactly = 1) { device.createBond() }

        every { device.bondState } returns BluetoothDevice.BOND_BONDING
        bondStateChanged(BluetoothDevice.BOND_NONE, BluetoothDevice.BOND_BONDING)
        rxRule.testScheduler.advanceTimeBy(5, TimeUnit.SECONDS)
        observer.assertNotComplete()

        bondStateChanged(BluetoothDevice.BOND_BONDING, BluetoothDevice.BOND_BONDED)

        observer.assertComplete()
        verify { fixture.context.unregisterReceiver(receiver.captured) }
    }

    @Test
    fun bondingEndingInNone_failsWithReason() {
        val observer = fixture.bleDevice.bondCompletable().test()

        bondStateChanged(BluetoothDevice.BOND_NONE, BluetoothDevice.BOND_BONDING)
        bondStateChanged(BluetoothDevice.BOND_BONDING, BluetoothDevice.BOND_NONE, reason = 2)

        observer.assertError { it is BleManager.BondFailedException && it.reason == 2 }
    }

    @Test
    fun broadcastOfAnotherDevice_isIgnored() {
        val other = mockk<BluetoothDevice>(relaxed = true).also { every { it.address } returns "AA:BB:CC:DD:EE:FF" }
        every { device.address } returns GattTestFixture.ADDRESS
        val observer = fixture.bleDevice.bondCompletable().test()

        bondStateChanged(BluetoothDevice.BOND_BONDING, BluetoothDevice.BOND_NONE, target = other)
        bondStateChanged(BluetoothDevice.BOND_BONDING, BluetoothDevice.BOND_BONDED, target = other)

        observer.assertNotComplete().assertNoErrors()
    }

    @Test
    fun bond_timesOut_andUnregistersReceiver() {
        val observer = fixture.bleDevice.bondCompletable(10_000).test()
        // 본딩은 시작됐지만(사용자 확인을 기다리는 등) 끝나지 않는다
        every { device.bondState } returns BluetoothDevice.BOND_BONDING
        bondStateChanged(BluetoothDevice.BOND_NONE, BluetoothDevice.BOND_BONDING)

        rxRule.testScheduler.advanceTimeBy(10_000, TimeUnit.MILLISECONDS)

        observer.assertError { it is BleManager.OperationTimeoutException && it.operation == "BOND" && it.timeoutMillis == 10_000L }
        verify { fixture.context.unregisterReceiver(receiver.captured) }
    }

    // 다른 기기와 본딩 중이면 createBond 가 true 를 돌려줘도 시작되지 않고 브로드캐스트가 오지 않는다 (실기기에서 확인)
    @Test
    fun bondingThatDidNotStart_failsAfterStartCheck() {
        val observer = fixture.bleDevice.bondCompletable().test()

        rxRule.testScheduler.advanceTimeBy(4_999, TimeUnit.MILLISECONDS)
        observer.assertNotComplete().assertNoErrors()
        rxRule.testScheduler.advanceTimeBy(1, TimeUnit.MILLISECONDS)

        observer.assertError { it is BleManager.BondFailedException && it.message!!.contains("did not start") }
    }

    @Test
    fun rejectedCreateBond_failsImmediately() {
        every { device.createBond() } returns false

        fixture.bleDevice.bondCompletable().test()
            .assertError(BleManager.BondFailedException::class.java)
    }

    // 다른 곳에서 시작한 본딩이 진행 중이면 새로 요청하지 않고 결과를 기다린다
    @Test
    fun bondingInProgress_waitsWithoutNewRequest() {
        every { device.bondState } returns BluetoothDevice.BOND_BONDING
        val observer = fixture.bleDevice.bondCompletable().test()

        bondStateChanged(BluetoothDevice.BOND_BONDING, BluetoothDevice.BOND_BONDED)

        observer.assertComplete()
        verify(exactly = 0) { device.createBond() }
    }

    @Test
    fun unbond_whenNotBonded_completesImmediately() {
        fixture.bleDevice.unbondCompletable().test().assertComplete()
    }

    // JVM 테스트의 android.jar 에는 숨겨진 removeBond() 가 없다. 리플렉션이 막힌 기기처럼 크래시 대신 BondFailedException 으로 끝난다
    @Test
    fun unbond_whenRemoveBondIsUnavailable_failsWithBondFailed() {
        every { device.bondState } returns BluetoothDevice.BOND_BONDED

        fixture.bleDevice.unbondCompletable().test()
            .assertError { it is BleManager.BondFailedException && it.message!!.contains("removeBond is not available") }
    }
}
