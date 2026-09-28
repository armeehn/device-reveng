package com.ripostelabs.carlauncher.carlib

import com.ripostelabs.car.CarStatus
import com.ripostelabs.car.ICarListener
import com.ripostelabs.car.ICarService
import com.ripostelabs.car.McuEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteMcuOwnerTest {

    /** The service side: records calls, holds the registered listener like the real one. */
    private class FakeService(private val api: Int = RemoteMcuOwner.MIN_API) : ICarService.Stub() {
        val calls = mutableListOf<String>()
        var listener: ICarListener? = null
        var source = -1

        override fun apiVersion() = api
        override fun status() = CarStatus(uid = 1000)
        override fun registerListener(l: ICarListener?) { calls += "register"; listener = l }
        override fun unregisterListener(l: ICarListener?) { calls += "unregister"; listener = null }
        override fun reboot() { calls += "reboot" }
        override fun openLink() { calls += "open" }
        override fun closeLink() { calls += "close" }
        override fun setStartup(frames: ByteArray?) { calls += "startup ${FramePack.unpack(frames!!).size}" }
        override fun setSource(mode: Int): Boolean { calls += "source $mode"; source = mode; return true }
        override fun currentSource() = source
        override fun selectCar(carId: String?) { calls += "car $carId" }
        override fun sendMcuFrame(frame: ByteArray?) { calls += "send ${frame!!.size}" }
        override fun factoryReset(scope: Int) { calls += "reset $scope" }
    }

    /** bindService stand-in: the test decides when the service comes up or dies. */
    private class FakeBinding : CarBinding {
        var binds = 0
        var unbinds = 0
        private var up: ((ICarService) -> Unit)? = null
        private var down: (() -> Unit)? = null

        override fun bind(onUp: (ICarService) -> Unit, onDown: () -> Unit) {
            binds++
            up = onUp
            down = onDown
        }

        override fun unbind() { unbinds++ }

        fun connect(svc: ICarService) = up!!(svc)
        fun die() = down!!()
    }

    /** Every listener call as text; CAN signal times differ by process, so they are left out. */
    private class Recorder : McuOwner.Listener {
        val seen = mutableListOf<String>()
        override fun onSysEvent(event: McuOwnerProtocol.SysEvent) { seen += "sys $event" }
        override fun onMainVolume(volume: McuOwnerProtocol.MainVolume) { seen += "vol $volume" }
        override fun onKey(key: Int) { seen += "key $key" }
        override fun onPanelKey(key: McuOwnerProtocol.PanelKey) { seen += "panel $key" }
        override fun onCanSignal(signal: CanSignal, atMs: Long) { seen += "can $signal" }
        override fun onCanRelay(body: ByteArray, cut: List<McuFrame.Decoded>) { seen += "relay ${body.size} ${cut.size}" }
        override fun onOther(command: McuSerial.Command) { seen += "other ${command.opcode}" }
        override fun onCanBoxCar(car: CarProfile) { seen += "car ${car.id}" }
    }

    private val startup = listOf(byteArrayOf(1, 2), byteArrayOf(3))
    private val binding = FakeBinding()
    private val recorder = Recorder()
    private val remote = RemoteMcuOwner(binding, recorder, startup)

    @Test
    fun serviceWithTheLinkApiIsChosen() {
        assertEquals(RemoteMcuOwner.Owner.SERVICE, RemoteMcuOwner.choose(2))
        assertEquals(RemoteMcuOwner.Owner.SERVICE, RemoteMcuOwner.choose(3))
    }

    // No service, the skeleton (1) or a missing meta-data (0): this process owns the port.
    @Test
    fun noServiceOrAnOldOneFallsBackToLocal() {
        assertEquals(RemoteMcuOwner.Owner.LOCAL, RemoteMcuOwner.choose(null))
        assertEquals(RemoteMcuOwner.Owner.LOCAL, RemoteMcuOwner.choose(1))
        assertEquals(RemoteMcuOwner.Owner.LOCAL, RemoteMcuOwner.choose(0))
    }

    @Test
    fun startBindsOnceAndConnectReplaysThenListens() {
        remote.start()
        remote.start()
        remote.selectCar(CarProfiles.DEFAULT)
        val svc = FakeService()
        binding.connect(svc)

        assertEquals(1, binding.binds)
        assertEquals(listOf("startup 2", "car ${CarProfiles.DEFAULT.id}", "open", "register"), svc.calls)
    }

    // The service died and came back (binderDied, then the system reconnects): everything replays.
    @Test
    fun deathReportsFailedAndReconnectReplays() {
        remote.start()
        binding.connect(FakeService())
        binding.die()

        assertEquals(McuOwner.Status.Failed(RemoteMcuOwner.SERVICE_GONE), remote.status.value)
        remote.send(byteArrayOf(1))

        val again = FakeService()
        binding.connect(again)
        assertEquals(listOf("startup 2", "open", "register"), again.calls)
        remote.send(byteArrayOf(1, 2, 3))
        assertEquals("send 3", again.calls.last())
    }

    // Closed for ACC sleep when the service restarted: it hears "closed", not "open".
    @Test
    fun reconnectWhileStoppedReplaysClose() {
        remote.start()
        remote.stop()
        binding.connect(FakeService())
        binding.die()
        val again = FakeService()
        binding.connect(again)

        assertEquals(listOf("startup 2", "close", "register"), again.calls)
    }

    @Test
    fun oldServiceIsBlockedAndGetsNoCalls() {
        remote.start()
        val old = FakeService(api = 1)
        binding.connect(old)

        assertTrue(remote.status.value is McuOwner.Status.Blocked)
        assertFalse(remote.setMode(McuOwnerProtocol.Mode.RADIO))
        assertTrue(old.calls.isEmpty())
    }

    @Test
    fun powerGoesToAServiceWithThePowerApi() {
        remote.start()
        val svc = FakeService(api = RemoteMcuOwner.POWER_API)
        binding.connect(svc)

        assertTrue(remote.reboot())
        assertTrue(remote.factoryReset())
        assertEquals(listOf("reboot", "reset ${ICarService.RESET_DATA_WIPE}"), svc.calls.takeLast(2))
    }

    // The link-only service (2) rebooted through its skeleton call, untested on the unit, and has
    // no factoryReset: the launcher keeps its own fallbacks.
    @Test
    fun powerRefusesALinkOnlyServiceOrNone() {
        assertFalse(remote.reboot())

        remote.start()
        val svc = FakeService(api = RemoteMcuOwner.MIN_API)
        binding.connect(svc)

        assertFalse(remote.reboot())
        assertFalse(remote.factoryReset())
        assertFalse("reboot" in svc.calls)
    }

    @Test
    fun setModeAndLastModeGoThroughTheService() {
        remote.start()
        binding.connect(FakeService())

        assertNull(remote.lastMode)
        assertTrue(remote.setMode(McuOwnerProtocol.Mode.RADIO))
        assertEquals(McuOwnerProtocol.Mode.RADIO, remote.lastMode)
    }

    @Test
    fun releaseUnregistersAndUnbindsWithoutClosing() {
        remote.start()
        val svc = FakeService()
        binding.connect(svc)
        remote.release()

        assertEquals("unregister", svc.calls.last())
        assertFalse("close" in svc.calls)
        assertEquals(1, binding.unbinds)
        assertEquals(McuOwner.Status.Idle, remote.status.value)
    }

    @Test
    fun statusFollowsTheService() {
        remote.start()
        val svc = FakeService()
        binding.connect(svc)
        val running = McuOwner.Status.Running(acked = true, frames = 9, badChecksum = 0, skipped = 1)
        svc.listener!!.onStatus(CarStatus.of(1000, running))

        assertEquals(running, remote.status.value)
    }

    // The round trip: commands the owner decoded in-process, forwarded as McuEvents and decoded
    // on the client, reach the launcher's listener as the same calls in the same order.
    @Test
    fun forwardedEventsDecodeLikeTheOwner() {
        val commands = listOf(
            McuSerial.Command(McuOpcode.SYS_EVENT.code, byteArrayOf(0x10, 0x00)),
            McuSerial.Command(McuOpcode.MAIN_VOLUME.code, byteArrayOf(0x85.toByte())),
            McuSerial.Command(McuOpcode.KEY_EVENT.code, byteArrayOf(0x02, 0x01)),
            McuSerial.Command(McuSerial.OP_CAN, byteArrayOf(0x2E, 0x11, 0x02, 0x00, 0x00, 0xEC.toByte())),
            McuSerial.Command(0x8E, byteArrayOf(1, 2, 3)),
        )
        val local = Recorder()
        val owner = McuDecoder(local)
        commands.forEach(owner::decode)

        remote.start()
        val svc = FakeService()
        binding.connect(svc)
        commands.forEach { svc.listener!!.onMcuEvent(McuEvent.of(it)) }
        svc.listener!!.onMcuEvent(McuEvent.of(CarProfiles.DEFAULT))

        assertTrue(local.seen.size >= commands.size)
        assertEquals(local.seen + "car ${CarProfiles.DEFAULT.id}", recorder.seen)
    }

    @Test
    fun unknownEventKindIsIgnored() {
        remote.start()
        val svc = FakeService()
        binding.connect(svc)
        svc.listener!!.onMcuEvent(McuEvent(McuEvent.Kind.UNKNOWN, 0x71, byteArrayOf(1, 2)))

        assertTrue(recorder.seen.isEmpty())
    }
}
