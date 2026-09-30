package com.ripostelabs.car

import android.os.RemoteException
import com.ripostelabs.carlauncher.carlib.McuOwner
import com.ripostelabs.carlauncher.carlib.McuOwnerProtocol

/** The listeners a client registered; RemoteCallbackList on the unit, a list in tests. */
interface ListenerSet {
    fun add(listener: ICarListener)
    fun remove(listener: ICarListener)

    /** [action] on every listener; one that throws is the caller's to drop. */
    fun each(action: (ICarListener) -> Unit)
}

/** The power actions the service performs; PowerManager and RecoverySystem on the unit. */
interface Power {
    fun reboot()

    /** Android's standard factory reset: wipe /data in recovery, then boot to setup. */
    fun wipeData()
}

/** The reverse camera's PR2000 decoder: sysfs and properties on the unit ([SysfsDecoder]), a fake in tests. */
interface Decoder {
    /** The persisted row, 0 auto .. 8. */
    fun mode(): Int
    fun setMode(mode: Int)

    /** Raw camera_status, or [ReverseState.NO_SIGNAL] when unreadable. */
    fun signal(): Int
    fun forceStreamable()
    fun redetect()
}

/** The nav bar window: [NavWindow] on the unit, a fake in tests. */
interface NavPanel {
    /** ICarService.NAV_* in [colors] (surface, onSurface, primary). */
    fun show(state: Int, colors: IntArray)

    companion object {
        private const val EXPANDED_DP = 64

        /**
         * The folded strip: 36 px on the 240 dpi panel, visible at a glance and easy to hit. It is
         * also the inset apps give up while folded, so it stays well short of the 64 dp strip.
         */
        private const val HANDLE_STRIP_DP = 24

        /** The pill on the folded strip: 240 x 12 px, the old 6 dp line was 9 px of faint accent. */
        const val PILL_WIDTH_DP = 160
        const val PILL_HEIGHT_DP = 8

        /** The window height, which is also the inset the apps above it give up. */
        fun heightDp(state: Int): Int = when (state) {
            ICarService.NAV_EXPANDED -> EXPANDED_DP
            ICarService.NAV_HANDLE -> HANDLE_STRIP_DP
            else -> 0
        }
    }
}

/** The MCU link as the binder drives it: [OwnerHost] around McuOwner on the unit, a fake in tests. */
interface Link {
    fun status(): McuOwner.Status
    fun open()
    fun close()
    fun setStartup(packed: ByteArray)
    fun setSource(mode: Int): Boolean

    /** McuOwnerProtocol.Mode code of the last source set, or [NO_SOURCE]. */
    fun currentSource(): Int
    fun selectCar(id: String)
    fun send(frame: ByteArray)

    /** ICarService.POWER_KEY_* */
    fun setPowerKey(mode: Int)

    /** The last playable source kept across boots (RAV4-170), or [NO_SOURCE]. */
    fun lastSource(): Int

    companion object {
        const val NO_SOURCE = -1
    }
}

/**
 * ICarService. Every call checks its caller through [gate] before it acts, so a refused call
 * has no side effect. [uid] is the service's own, reported in every status.
 */
class CarBinder(
    private val gate: Gate,
    private val listeners: ListenerSet,
    private val power: Power,
    private val uid: Int,
    private val link: Link,
    private val decoder: Decoder,
    private val nav: NavPanel,
) : ICarService.Stub() {

    /** The `71` reverse bit while the link runs; only [publish] and [publishStatus] move it. */
    @Volatile
    private var line = false

    override fun apiVersion() = API_VERSION

    override fun status(): CarStatus {
        gate.enforce(Access.READ)
        return current()
    }

    // A new listener gets the current status at once, so it never waits for the next change.
    override fun registerListener(listener: ICarListener?) {
        gate.enforce(Access.READ)
        if (listener == null) {
            return
        }

        listeners.add(listener)
        try {
            listener.onStatus(current())
        } catch (e: RemoteException) {
            listeners.remove(listener)
        }
    }

    override fun unregisterListener(listener: ICarListener?) {
        gate.enforce(Access.READ)
        if (listener == null) {
            return
        }
        listeners.remove(listener)
    }

    override fun reboot() {
        gate.enforce(Access.CONTROL)
        power.reboot()
    }

    // Only DATA_WIPE exists yet; any other scope is refused before power hears it.
    override fun factoryReset(scope: Int) {
        gate.enforce(Access.CONTROL)
        require(scope == ICarService.RESET_DATA_WIPE) { "unknown reset scope $scope" }
        power.wipeData()
    }

    override fun reverseState(): ReverseState {
        gate.enforce(Access.READ)
        return reverse()
    }

    // The vendor picker's nine rows; the node would take any digit, so the range is checked here.
    override fun setDecoderMode(mode: Int) {
        gate.enforce(Access.CONTROL)
        require(mode in 0..DECODER_MODE_MAX) { "decoder mode $mode outside 0..$DECODER_MODE_MAX" }
        decoder.setMode(mode)
    }

    override fun decoderSignal(action: Int) {
        gate.enforce(Access.CONTROL)
        when (action) {
            DECODER_FORCE_STREAMABLE -> decoder.forceStreamable()
            DECODER_REDETECT -> decoder.redetect()
            else -> throw IllegalArgumentException("unknown decoder action $action")
        }
    }

    override fun setNavBar(state: Int, colors: IntArray?) {
        gate.enforce(Access.CONTROL)
        require(state in ICarService.NAV_HIDDEN..ICarService.NAV_EXPANDED) { "unknown nav bar state $state" }
        require(colors != null && colors.size == NAV_COLORS) { "nav bar needs $NAV_COLORS colours" }
        nav.show(state, colors)
    }

    /** A touch on the bar, to every client: the launcher's NavBarPolicy answers with a state. */
    fun navTouched() = broadcast { it.onNavInteract() }

    override fun openLink() {
        gate.enforce(Access.CONTROL)
        link.open()
    }

    override fun closeLink() {
        gate.enforce(Access.CONTROL)
        link.close()
    }

    override fun setStartup(frames: ByteArray?) {
        gate.enforce(Access.CONTROL)
        frames?.let(link::setStartup)
    }

    override fun setSource(mode: Int): Boolean {
        gate.enforce(Access.CONTROL)
        return link.setSource(mode)
    }

    override fun currentSource(): Int {
        gate.enforce(Access.READ)
        return link.currentSource()
    }

    override fun lastSource(): Int {
        gate.enforce(Access.READ)
        return link.lastSource()
    }

    override fun selectCar(carId: String?) {
        gate.enforce(Access.CONTROL)
        carId?.let(link::selectCar)
    }

    override fun sendMcuFrame(frame: ByteArray?) {
        gate.enforce(Access.CONTROL)
        frame?.let(link::send)
    }

    override fun setPowerKey(mode: Int) {
        gate.enforce(Access.CONTROL)
        require(mode == ICarService.POWER_KEY_SCREEN_OFF || mode == ICarService.POWER_KEY_STANDBY) { "unknown power key mode $mode" }
        link.setPowerKey(mode)
    }

    /** One MCU event to every client, in the order the owner saw them; a `71` also moves the line. */
    fun publish(event: McuEvent) {
        broadcast { it.onMcuEvent(event) }
        val sys = event.command()?.let(McuOwnerProtocol::sysEvent) ?: return
        moveLine(sys.reverse && link.status() is McuOwner.Status.Running)
    }

    /** The link's status to every client; a link that stops drops the line (no more `71`s). */
    fun publishStatus() {
        val status = current()
        broadcast { it.onStatus(status) }
        if (!status.mcuLinkUp) {
            moveLine(false)
        }
    }

    // Edges only: the MCU repeats its 71 on every bit change, most of them not the reverse bit.
    private fun moveLine(next: Boolean) {
        if (next == line) {
            return
        }
        line = next
        val state = reverse()
        broadcast { it.onReverse(state) }
    }

    private fun reverse() = ReverseState(line, decoder.mode(), decoder.signal())

    /** The reverse line for the vendor subset ([EventCalls]), which answers callers without READ. */
    internal fun reversing() = line

    // A dead client is dropped here; RemoteCallbackList also drops it on its binder death.
    private fun broadcast(action: (ICarListener) -> Unit) {
        val dead = mutableListOf<ICarListener>()
        listeners.each { l ->
            try {
                action(l)
            } catch (e: RemoteException) {
                dead += l
            }
        }
        dead.forEach(listeners::remove)
    }

    private fun current() = CarStatus.of(uid, link.status())

    private companion object {
        // 1 was the skeleton; 2 adds the MCU link calls; 3 power (factoryReset); 4 reverse and
        // decoder; 5 the nav bar; 6 the POWER key choice. Additions to ICarService bump it. The manifest's com.ripostelabs.car.API
        // meta-data must say the same.
        const val API_VERSION = 7

        /** surface, onSurface, primary. */
        const val NAV_COLORS = 3

        /** CVBS PAL 60, the last of the vendor picker's rows (BackcarSignalTypeSet.java:99-100). */
        const val DECODER_MODE_MAX = 8
    }
}
