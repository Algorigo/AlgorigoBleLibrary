package com.algorigo.algorigoble2.impl

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import com.algorigo.algorigoble2.BleManager
import com.algorigo.algorigoble2.testutil.RxTestSchedulerRule
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** bondCompletable(pin = ...) 이 PIN 입력 요청(ACTION_PAIRING_REQUEST)에 PIN 을 넣는 것을 확인한다. */
class BondPinTest {

    @get:Rule
    val rxRule = RxTestSchedulerRule()

    private val fixture = GattTestFixture()
    private val device = fixture.bluetoothDevice
    private val receivers = mutableListOf<BroadcastReceiver>()
    private var receiversAtCreateBond = -1

    // bondCompletable 은 본딩 상태 receiver, PIN 입력 요청 receiver 순서로 등록한다.
    // (JVM 테스트의 IntentFilter 는 stub 이라 action 으로 구분할 수 없다)
    private val bondReceiver get() = receivers[0]
    private val pairingReceiver get() = receivers[1]

    @Before
    fun setUp() {
        every { fixture.context.registerReceiver(capture(receivers), any<IntentFilter>()) } returns null
        every { device.bondState } returns BluetoothDevice.BOND_NONE
        every { device.createBond() } answers {
            receiversAtCreateBond = receivers.size
            true
        }
        every { device.setPin(any()) } returns true
    }

    private fun bondStateChanged(previous: Int, state: Int, reason: Int? = null) {
        val intent = mockk<Intent>(relaxed = true)
        every { intent.action } returns BluetoothDevice.ACTION_BOND_STATE_CHANGED
        every { intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) } returns device
        every { intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, any()) } returns previous
        every { intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, any()) } returns state
        every { intent.hasExtra("android.bluetooth.device.extra.REASON") } returns (reason != null)
        every { intent.getIntExtra("android.bluetooth.device.extra.REASON", any()) } returns (reason ?: 0)
        bondReceiver.onReceive(fixture.context, intent)
    }

    private fun pairingRequest(variant: Int, target: BluetoothDevice = device): Intent {
        val intent = mockk<Intent>(relaxed = true)
        every { intent.action } returns BluetoothDevice.ACTION_PAIRING_REQUEST
        every { intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) } returns target
        every { intent.getIntExtra(BluetoothDevice.EXTRA_PAIRING_VARIANT, any()) } returns variant
        return intent
    }

    @Test
    fun withPin_setsPinOnPinRequest_andCompletesWhenBonded() {
        val observer = fixture.bleDevice.bondCompletable(pin = "123456").test()
        // PIN 입력 요청 receiver 도 createBond 전에 등록돼 있다
        assertEquals(2, receiversAtCreateBond)

        bondStateChanged(BluetoothDevice.BOND_NONE, BluetoothDevice.BOND_BONDING)
        pairingReceiver.onReceive(fixture.context, pairingRequest(BluetoothDevice.PAIRING_VARIANT_PIN))
        verify(exactly = 1) { device.setPin(match { it.contentEquals("123456".toByteArray()) }) }
        bondStateChanged(BluetoothDevice.BOND_BONDING, BluetoothDevice.BOND_BONDED)

        observer.assertComplete()
        verify { fixture.context.unregisterReceiver(receivers[0]) }
        verify { fixture.context.unregisterReceiver(receivers[1]) }
    }

    @Test
    fun withPin_abortsOrderedBroadcast_soTheSystemDialogIsNotShown() {
        fixture.bleDevice.bondCompletable(pin = "123456").test()
        val receiver = spyk(pairingReceiver)
        every { receiver.isOrderedBroadcast } returns true
        every { receiver.abortBroadcast() } just Runs

        receiver.onReceive(fixture.context, pairingRequest(BluetoothDevice.PAIRING_VARIANT_PIN))

        verify(exactly = 1) { receiver.abortBroadcast() }
    }

    // 넣지 못했으면 사용자가 시스템 입력 화면에서 넣을 수 있게 broadcast 를 끊지 않고 결과를 계속 기다린다
    @Test
    fun rejectedSetPin_leavesBroadcastToSystem_andKeepsWaiting() {
        every { device.setPin(any()) } returns false
        val observer = fixture.bleDevice.bondCompletable(pin = "123456").test()
        val receiver = spyk(pairingReceiver)
        every { receiver.isOrderedBroadcast } returns true
        every { receiver.abortBroadcast() } just Runs

        receiver.onReceive(fixture.context, pairingRequest(BluetoothDevice.PAIRING_VARIANT_PIN))

        verify(exactly = 0) { receiver.abortBroadcast() }
        observer.assertNotComplete().assertNoErrors()
    }

    // 숫자 비교(양쪽 화면의 숫자 확인)는 일반 앱이 승인할 수 없으므로 시스템 화면에 맡긴다
    @Test
    fun passkeyConfirmationRequest_isLeftToSystem() {
        val observer = fixture.bleDevice.bondCompletable(pin = "123456").test()
        val receiver = spyk(pairingReceiver)
        every { receiver.isOrderedBroadcast } returns true
        every { receiver.abortBroadcast() } just Runs

        receiver.onReceive(fixture.context, pairingRequest(BluetoothDevice.PAIRING_VARIANT_PASSKEY_CONFIRMATION))

        verify(exactly = 0) { device.setPin(any()) }
        verify(exactly = 0) { receiver.abortBroadcast() }
        observer.assertNotComplete().assertNoErrors()
    }

    @Test
    fun pinRequestOfAnotherDevice_isIgnored() {
        val other = mockk<BluetoothDevice>(relaxed = true).also { every { it.address } returns "AA:BB:CC:DD:EE:FF" }
        fixture.bleDevice.bondCompletable(pin = "123456").test()

        pairingReceiver.onReceive(fixture.context, pairingRequest(BluetoothDevice.PAIRING_VARIANT_PIN, target = other))

        verify(exactly = 0) { device.setPin(any()) }
        verify(exactly = 0) { other.setPin(any()) }
    }

    @Test
    fun wrongPin_failsWithBondFailed() {
        val observer = fixture.bleDevice.bondCompletable(pin = "000000").test()

        bondStateChanged(BluetoothDevice.BOND_NONE, BluetoothDevice.BOND_BONDING)
        pairingReceiver.onReceive(fixture.context, pairingRequest(BluetoothDevice.PAIRING_VARIANT_PIN))
        // 기기가 PIN 을 받지 않으면 인증 실패(reason 1)로 끝난다
        bondStateChanged(BluetoothDevice.BOND_BONDING, BluetoothDevice.BOND_NONE, reason = 1)

        observer.assertError { it is BleManager.BondFailedException && it.reason == 1 }
        verify { fixture.context.unregisterReceiver(receivers[1]) }
    }

    @Test
    fun withoutPin_doesNotHandlePinRequests() {
        fixture.bleDevice.bondCompletable().test()

        assertEquals(1, receivers.size)
        verify(exactly = 0) { device.setPin(any()) }
    }

    @Test
    fun alreadyBonded_withPin_completesWithoutReceivers() {
        every { device.bondState } returns BluetoothDevice.BOND_BONDED

        fixture.bleDevice.bondCompletable(pin = "123456").test().assertComplete()

        assertEquals(0, receivers.size)
        verify(exactly = 0) { device.createBond() }
    }

    @Test
    fun pinOutOfRange_failsWithIllegalArgument_withoutRequest() {
        fixture.bleDevice.bondCompletable(pin = "").test().assertError(IllegalArgumentException::class.java)
        fixture.bleDevice.bondCompletable(pin = "12345678901234567").test().assertError(IllegalArgumentException::class.java)

        assertEquals(0, receivers.size)
        verify(exactly = 0) { device.createBond() }
    }

    @Test
    fun pinOf16Bytes_isAccepted() {
        fixture.bleDevice.bondCompletable(pin = "1234567890123456").test().assertNoErrors()

        verify(exactly = 1) { device.createBond() }
    }
}
