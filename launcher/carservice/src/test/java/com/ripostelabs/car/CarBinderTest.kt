package com.ripostelabs.car

import android.os.RemoteException
import com.ripostelabs.carlauncher.carlib.McuOwner
import com.ripostelabs.carlauncher.carlib.McuSerial
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CarBinderTest {

    private val systemUid = 1000

    private class FakeListeners : ListenerSet {
        val held = mutableListOf<ICarListener>()
        override fun add(listener: ICarListener) { held += listener }
        override fun remove(listener: ICarListener) { held -= listener }
        override fun each(action: (ICarListener) -> Unit) = held.toList().forEach(action)
    }

    private class FakeListener(private val dead: Boolean = false) : ICarListener.Stub() {
        val seen = mutableListOf<CarStatus>()
        val events = mutableListOf<McuEvent>()
        val reverse = mutableListOf<ReverseState>()
        override fun onReverse(state: ReverseState) { reverse += state }
        var navTouches = 0
        override fun onNavInteract() { navTouches++ }
        override fun onStatus(status: CarStatus) { seen += status }
        override fun onMcuEvent(event: McuEvent) {
            if (dead) {
                throw RemoteException("gone")
            }
            events += event
        }
    }

    /** Records what reached the owner, so a refused call can be shown to have done nothing. */
    private class FakeLink : Link {
        var status: McuOwner.Status = McuOwner.Status.Idle
        val calls = mutableListOf<String>()
        override fun status() = status
        override fun open() { calls += "open" }
        override fun close() { calls += "close" }
        override fun setStartup(packed: ByteArray) { calls += "startup ${packed.size}" }
        override fun setSource(mode: Int): Boolean { calls += "source $mode"; return true }
        override fun currentSource() = 7
        override fun lastSource() = 11
        override fun selectCar(id: String) { calls += "car $id" }
        override fun send(frame: ByteArray) { calls += "send ${frame.size}" }
        override fun setPowerKey(mode: Int) { calls += "power key $mode" }
    }

    /** Counts what reached the power side, so a refused call can be shown to have done nothing. */
    private class FakePower : Power {
        var reboots = 0
        var wipes = 0
        override fun reboot() { reboots++ }
        override fun wipeData() { wipes++ }
    }

    /** The PR2000 as the binder drives it: records every write. */
    private class FakeDecoder : Decoder {
        var row = 0
        var status = 0
        val calls = mutableListOf<String>()
        override fun mode() = row
        override fun setMode(mode: Int) { calls += "mode $mode"; row = mode }
        override fun signal() = status
        override fun forceStreamable() { calls += "force" }
        override fun redetect() { calls += "redetect" }
    }

    /** The nav bar window: records what the launcher asked it to show. */
    private class FakeNav : NavPanel {
        val shown = mutableListOf<String>()
        override fun show(state: Int, colors: IntArray) { shown += "$state ${colors.size}" }
    }

    /** The system night mode: records what the launcher asked for. */
    private class FakeUiMode : UiMode {
        val modes = mutableListOf<Int>()
        override fun setNightMode(mode: Int) { modes += mode }
    }

    private val nav = FakeNav()
    private val uiMode = FakeUiMode()
    private val power = FakePower()
    private val decoder = FakeDecoder()
    private val listeners = FakeListeners()
    private val link = FakeLink()

    private fun binder(vararg held: String) =
        CarBinder(Gate { it in held }, listeners, power, systemUid, link, decoder, nav, uiMode)

    @Test
    fun apiVersionIsEight() {
        assertEquals(8, binder().apiVersion())
    }

    @Test
    fun statusCarriesOwnUidAndOwnerState() {
        link.status = McuOwner.Status.Running(acked = true, frames = 5, badChecksum = 1, skipped = 0)
        val s = binder(READ_PERMISSION).status()

        assertEquals(systemUid, s.uid)
        assertTrue(s.mcuLinkUp)
        assertEquals(link.status, s.ownerStatus())
    }

    @Test
    fun statusNeedsRead() {
        assertThrows(SecurityException::class.java) { binder().status() }
    }

    @Test
    fun registerAddsAndPushesStatusOnce() {
        val l = FakeListener()
        binder(READ_PERMISSION).registerListener(l)

        assertEquals(listOf<ICarListener>(l), listeners.held)
        assertEquals(listOf(CarStatus(uid = systemUid)), l.seen)
    }

    @Test
    fun unregisterRemoves() {
        val b = binder(READ_PERMISSION)
        val l = FakeListener()
        b.registerListener(l)
        b.unregisterListener(l)

        assertTrue(listeners.held.isEmpty())
    }

    @Test
    fun registerNeedsRead() {
        assertThrows(SecurityException::class.java) { binder().registerListener(FakeListener()) }
        assertTrue(listeners.held.isEmpty())
    }

    @Test
    fun rebootWithControlReboots() {
        binder(CONTROL_PERMISSION).reboot()
        assertEquals(1, power.reboots)
    }

    @Test
    fun rebootWithReadOnlyIsRefusedBeforePower() {
        assertThrows(SecurityException::class.java) { binder(READ_PERMISSION).reboot() }
        assertEquals(0, power.reboots)
    }

    @Test
    fun dataWipeWithControlWipes() {
        binder(CONTROL_PERMISSION).factoryReset(ICarService.RESET_DATA_WIPE)
        assertEquals(1, power.wipes)
    }

    @Test
    fun factoryResetWithReadOnlyIsRefusedBeforePower() {
        assertThrows(SecurityException::class.java) {
            binder(READ_PERMISSION).factoryReset(ICarService.RESET_DATA_WIPE)
        }
        assertEquals(0, power.wipes)
    }

    // The owner has decided one scope so far; any other number wipes nothing.
    @Test
    fun unknownResetScopeIsRefused() {
        assertThrows(IllegalArgumentException::class.java) { binder(CONTROL_PERMISSION).factoryReset(99) }
        assertEquals(0, power.wipes)
    }

    @Test
    fun linkCallsWithControlReachTheOwner() {
        val b = binder(CONTROL_PERMISSION)
        b.openLink()
        b.setStartup(byteArrayOf(0, 1, 9))
        b.selectCar("hiworld_toyota:1:2")
        assertTrue(b.setSource(3))
        b.sendMcuFrame(byteArrayOf(1, 2))
        b.closeLink()

        assertEquals(listOf("open", "startup 3", "car hiworld_toyota:1:2", "source 3", "send 2", "close"), link.calls)
    }

    // Everything that moves the car needs CONTROL: READ alone is refused before the owner hears it.
    @Test
    fun linkCallsWithReadOnlyAreRefused() {
        val b = binder(READ_PERMISSION)
        val calls = listOf<() -> Unit>(
            { b.openLink() },
            { b.closeLink() },
            { b.setStartup(byteArrayOf()) },
            { b.setSource(1) },
            { b.selectCar("x") },
            { b.sendMcuFrame(byteArrayOf(1)) },
        )
        for (call in calls) {
            assertThrows(SecurityException::class.java) { call() }
        }

        assertTrue(link.calls.isEmpty())
    }

    @Test
    fun currentSourceNeedsOnlyRead() {
        assertEquals(7, binder(READ_PERMISSION).currentSource())
        assertThrows(SecurityException::class.java) { binder().currentSource() }
    }

    // RAV4-169: the system night mode is a change, so CONTROL; only the two modes pass.
    @Test
    fun nightModeNeedsControlAndAKnownMode() {
        binder(CONTROL_PERMISSION).setNightMode(ICarService.NIGHT_MODE_NIGHT)
        binder(CONTROL_PERMISSION).setNightMode(ICarService.NIGHT_MODE_DAY)

        assertEquals(listOf(ICarService.NIGHT_MODE_NIGHT, ICarService.NIGHT_MODE_DAY), uiMode.modes)
        assertThrows(SecurityException::class.java) { binder(READ_PERMISSION).setNightMode(ICarService.NIGHT_MODE_DAY) }
        assertThrows(IllegalArgumentException::class.java) { binder(CONTROL_PERMISSION).setNightMode(0) }
        assertEquals(2, uiMode.modes.size)
    }

    // RAV4-170: the source kept across boots is a read, like the live one.
    @Test
    fun lastSourceNeedsOnlyRead() {
        assertEquals(11, binder(READ_PERMISSION).lastSource())
        assertThrows(SecurityException::class.java) { binder().lastSource() }
    }

    @Test
    fun publishReachesEveryListenerAndDropsTheDead() {
        val b = binder(READ_PERMISSION)
        val live = FakeListener()
        val dead = FakeListener(dead = true)
        b.registerListener(live)
        b.registerListener(dead)

        val event = McuEvent.of(McuSerial.Command(0x71, byteArrayOf(1, 2)))
        b.publish(event)

        assertEquals(listOf(event), live.events)
        assertEquals(listOf<ICarListener>(live), listeners.held)
    }

    // ---- reverse and decoder (API 4) ----

    private fun sysEvent(reverse: Boolean) =
        McuEvent.of(McuSerial.Command(0x71, byteArrayOf(if (reverse) 0x02 else 0x00, 0)))

    private val running = McuOwner.Status.Running(acked = true, frames = 1, badChecksum = 0, skipped = 0)

    @Test
    fun reverseStateCarriesLineModeAndSignal() {
        decoder.row = 7
        decoder.status = 7
        val s = binder(READ_PERMISSION).reverseState()

        assertEquals(ReverseState(trigger = false, decoderMode = 7, signal = 7), s)
        assertTrue(s.locked)
    }

    @Test
    fun reverseStateNeedsRead() {
        assertThrows(SecurityException::class.java) { binder().reverseState() }
    }

    // The line is the 71 bit while the link runs, the vendor's "awake"; each edge goes out once.
    @Test
    fun reverseEdgesReachListenersOnce() {
        link.status = running
        val b = binder(READ_PERMISSION)
        val l = FakeListener()
        b.registerListener(l)

        b.publish(sysEvent(reverse = true))
        b.publish(sysEvent(reverse = true))
        b.publish(sysEvent(reverse = false))

        assertEquals(listOf(true, false), l.reverse.map { it.trigger })
        assertFalse(b.reverseState().trigger)
    }

    @Test
    fun aLinkThatStopsDropsTheLine() {
        link.status = running
        val b = binder(READ_PERMISSION)
        val l = FakeListener()
        b.registerListener(l)
        b.publish(sysEvent(reverse = true))

        link.status = McuOwner.Status.Failed("gone")
        b.publishStatus()

        assertEquals(listOf(true, false), l.reverse.map { it.trigger })
    }

    @Test
    fun aReverseBitWithoutARunningLinkIsNoLine() {
        val b = binder(READ_PERMISSION)
        b.publish(sysEvent(reverse = true))

        assertFalse(b.reverseState().trigger)
    }

    @Test
    fun decoderCallsWithControlReachTheDecoder() {
        val b = binder(CONTROL_PERMISSION)
        b.setDecoderMode(3)
        b.decoderSignal(ICarService.DECODER_FORCE_STREAMABLE)
        b.decoderSignal(ICarService.DECODER_REDETECT)

        assertEquals(listOf("mode 3", "force", "redetect"), decoder.calls)
    }

    @Test
    fun decoderCallsWithReadOnlyAreRefused() {
        val b = binder(READ_PERMISSION)
        assertThrows(SecurityException::class.java) { b.setDecoderMode(3) }
        assertThrows(SecurityException::class.java) { b.decoderSignal(ICarService.DECODER_REDETECT) }

        assertTrue(decoder.calls.isEmpty())
    }

    // 0 auto .. 8 PAL 60, the vendor picker's nine rows; anything else never reaches the node.
    @Test
    fun outOfRangeModeOrActionIsRefused() {
        val b = binder(CONTROL_PERMISSION)
        assertThrows(IllegalArgumentException::class.java) { b.setDecoderMode(9) }
        assertThrows(IllegalArgumentException::class.java) { b.setDecoderMode(-1) }
        assertThrows(IllegalArgumentException::class.java) { b.decoderSignal(0) }

        assertTrue(decoder.calls.isEmpty())
    }

    // ---- nav bar (API 5) ----

    private val colors = intArrayOf(0x111111, 0xEEEEEE, 0x3366FF)

    @Test
    fun navBarWithControlReachesTheWindow() {
        val b = binder(CONTROL_PERMISSION)
        b.setNavBar(ICarService.NAV_EXPANDED, colors)
        b.setNavBar(ICarService.NAV_HIDDEN, colors)

        assertEquals(listOf("${ICarService.NAV_EXPANDED} 3", "${ICarService.NAV_HIDDEN} 3"), nav.shown)
    }

    @Test
    fun navBarWithReadOnlyIsRefused() {
        assertThrows(SecurityException::class.java) { binder(READ_PERMISSION).setNavBar(ICarService.NAV_HANDLE, colors) }
        assertTrue(nav.shown.isEmpty())
    }

    // RAV4-156: the POWER key choice reaches the owner, which then skips its power-off burst.
    @Test
    fun powerKeyWithControlReachesTheLink() {
        binder(CONTROL_PERMISSION).setPowerKey(ICarService.POWER_KEY_SCREEN_OFF)

        assertEquals(listOf("power key ${ICarService.POWER_KEY_SCREEN_OFF}"), link.calls)
    }

    @Test
    fun powerKeyWithReadOnlyOrAnUnknownModeIsRefused() {
        assertThrows(SecurityException::class.java) { binder(READ_PERMISSION).setPowerKey(ICarService.POWER_KEY_STANDBY) }
        assertThrows(IllegalArgumentException::class.java) { binder(CONTROL_PERMISSION).setPowerKey(9) }

        assertTrue(link.calls.isEmpty())
    }

    // Three colours (surface, on-surface, accent) and a known state, or nothing is drawn.
    @Test
    fun navBarWithABadStateOrColoursIsRefused() {
        val b = binder(CONTROL_PERMISSION)
        assertThrows(IllegalArgumentException::class.java) { b.setNavBar(7, colors) }
        assertThrows(IllegalArgumentException::class.java) { b.setNavBar(ICarService.NAV_EXPANDED, intArrayOf(1)) }
        assertThrows(IllegalArgumentException::class.java) { b.setNavBar(ICarService.NAV_EXPANDED, null) }

        assertTrue(nav.shown.isEmpty())
    }

    // A touch on the bar goes to the launcher, whose NavBarPolicy decides what comes next.
    @Test
    fun navTouchReachesEveryListener() {
        val b = binder(READ_PERMISSION)
        val l = FakeListener()
        b.registerListener(l)

        b.navTouched()

        assertEquals(1, l.navTouches)
    }
}
