package com.ripostelabs.carlauncher.carlib

import android.os.RemoteException
import android.util.Log
import com.ripostelabs.car.CarStatus
import com.ripostelabs.car.ICarListener
import com.ripostelabs.car.ICarService
import com.ripostelabs.car.McuEvent
import com.ripostelabs.car.ReverseState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** How a client reaches ICarService: bindService on the unit ([ServiceCarBinding]), a fake in tests. */
interface CarBinding {
    /** Bind and stay bound: [onUp] after every (re)connect, [onDown] after every loss. */
    fun bind(onUp: (ICarService) -> Unit, onDown: () -> Unit)

    fun unbind()
}

/**
 * The launcher's [McuPort] when the car service owns the MCU link (os/CARHAL.md "One owner").
 *
 *     launcher ── start/stop/send/setMode/selectCar ──▶ ICarService ──▶ McuOwner ──▶ MCU
 *     launcher ◀── [listener] ◀── McuDecoder ◀── onMcuEvent(COMMAND) ◀── McuOwner's tap
 *
 * The service keeps the port through a launcher crash; every (re)connect replays what this
 * side asked for (startup frames, car, open or closed), since a restarted service knows none of it.
 */
class RemoteMcuOwner(
    private val binding: CarBinding,
    private val listener: McuOwner.Listener,
    /** The handshake frames the launcher's stores built (McuOwnerProtocol.startup). */
    private val startup: List<ByteArray>,
) : McuPort, CarDecoder, CarNav {

    /** Which process owns the MCU link on this image. */
    enum class Owner { LOCAL, SERVICE }

    /** Whether this app holds the service's CONTROL permission, which binding needs. */
    enum class BindAccess { GRANTED, REFUSED }

    private val _status = MutableStateFlow<McuOwner.Status>(McuOwner.Status.Idle)
    override val status: StateFlow<McuOwner.Status> = _status.asStateFlow()

    @Volatile
    private var service: ICarService? = null

    @Volatile
    private var bound = false

    /** What [start]/[stop] last asked for; replayed on every connect. */
    @Volatile
    private var open = false

    @Volatile
    private var car: CarProfile? = null

    /** The bound service's apiVersion; 0 while unbound. */
    @Volatile
    private var api = 0

    private val decoder = McuDecoder(listener)

    private val callback = object : ICarListener.Stub() {
        override fun onStatus(status: CarStatus?) {
            status?.let { _status.value = it.ownerStatus() }
        }

        override fun onMcuEvent(event: McuEvent?) {
            event?.let(::deliver)
        }

        // The same 71 arrives through onMcuEvent, and the launcher's ReverseTrigger adds the
        // speed gate on it; the service's line is for clients without a decoder of their own.
        override fun onReverse(state: ReverseState?) = Unit

        override fun onNavInteract() {
            navTouch?.invoke()
        }
    }

    override val lastMode: McuOwnerProtocol.Mode?
        get() {
            val code = call { it.currentSource() } ?: return null
            return McuOwnerProtocol.Mode.entries.firstOrNull { it.code == code }
        }

    override fun start() {
        open = true
        if (!bound) {
            bound = true
            bindOrBlock()
        }
        call { it.openLink() }
    }

    override fun stop() {
        open = false
        call { it.closeLink() }
    }

    // The service keeps the link: letting go is unbinding, never closing the port.
    override fun release() {
        call { it.unregisterListener(callback) }
        service = null
        api = 0
        if (bound) {
            bound = false
            binding.unbind()
        }
        _status.value = McuOwner.Status.Idle
    }

    override fun send(frame: ByteArray) {
        call { it.sendMcuFrame(frame) }
    }

    override fun setMode(mode: McuOwnerProtocol.Mode): Boolean = call { it.setSource(mode.code) } ?: false

    override fun selectCar(profile: CarProfile) {
        car = profile
        call { it.selectCar(profile.id) }
    }

    /** ICarService.reboot; false when the service is down or older than [POWER_API]. */
    fun reboot(): Boolean = power { it.reboot() }

    /** Android's factory reset through the service; false as for [reboot]. */
    fun factoryReset(): Boolean = power { it.factoryReset(ICarService.RESET_DATA_WIPE) }

    private inline fun power(block: (ICarService) -> Unit): Boolean = at(POWER_API, block)

    override fun setDecoderMode(mode: Int): Boolean = at(REVERSE_API) { it.setDecoderMode(mode) }

    override fun decoderLocked(): Boolean? {
        if (api < REVERSE_API) {
            return null
        }
        return call { it.reverseState().locked }
    }

    override fun decoderSignal(action: Int): Boolean = at(REVERSE_API) { it.decoderSignal(action) }

    @Volatile
    private var navTouch: (() -> Unit)? = null

    override fun showNav(state: Int, colors: IntArray): Boolean = at(NAV_API) { it.setNavBar(state, colors) }

    override fun onNavTouch(action: () -> Unit) {
        navTouch = action
    }

    /** [block] on a service at [level] or newer; false when older, down or dead mid-call. */
    private inline fun at(level: Int, block: (ICarService) -> Unit): Boolean {
        if (api < level) {
            return false
        }
        return call { block(it); true } ?: false
    }

    // bindService throws SecurityException when CONTROL is not held; a crash here is a HOME
    // crash loop, so the owner reports Blocked instead.
    private fun bindOrBlock() {
        try {
            binding.bind(::onUp, ::onDown)
        } catch (e: RuntimeException) {
            Log.e(TAG, "cannot bind the car service", e)
            _status.value = McuOwner.Status.Blocked("$BIND_REFUSED: ${e.message}")
        }
    }

    /** One forwarded event into the launcher's listener, decoded as the owner decoded it. */
    private fun deliver(event: McuEvent) {
        when (event.kind) {
            McuEvent.Kind.COMMAND -> event.command()?.let(decoder::decode)
            McuEvent.Kind.CAN_BOX_CAR -> listener.onCanBoxCar(CarProfiles.byId(event.text))
            McuEvent.Kind.UNKNOWN -> Unit
        }
    }

    private fun onUp(svc: ICarService) {
        val ok = try {
            replay(svc)
        } catch (e: RemoteException) {
            // Died between connect and replay; onDown and the next onUp follow.
            Log.w(TAG, "car service lost during connect", e)
            false
        }
        if (ok) {
            service = svc
        }
    }

    /** A new binding knows nothing of this client: tell it everything, then listen. */
    private fun replay(svc: ICarService): Boolean {
        val api = svc.apiVersion()
        if (api < MIN_API) {
            _status.value = McuOwner.Status.Blocked("car service API $api, need $MIN_API")
            return false
        }

        decoder.reset()
        svc.setStartup(FramePack.pack(startup))
        car?.let { svc.selectCar(it.id) }
        if (open) {
            svc.openLink()
        } else {
            svc.closeLink()
        }
        // Last: registering pushes the current status at once.
        svc.registerListener(callback)
        this.api = api
        return true
    }

    private fun onDown() {
        service = null
        api = 0
        _status.value = McuOwner.Status.Failed(SERVICE_GONE)
    }

    /** A call on the live binding; null while unbound or when the service just died. */
    private inline fun <T> call(block: (ICarService) -> T): T? {
        val svc = service ?: return null
        return try {
            block(svc)
        } catch (e: RemoteException) {
            Log.w(TAG, "car service call failed", e)
            null
        }
    }

    companion object {
        private const val TAG = "RemoteMcuOwner"

        /** ICarService.apiVersion that carries the MCU link calls. */
        const val MIN_API = 2

        /** ICarService.apiVersion that carries power: reboot and factoryReset. */
        const val POWER_API = 3

        /** ICarService.apiVersion that carries the reverse line and the decoder. */
        const val REVERSE_API = 4

        /** ICarService.apiVersion that draws the nav bar as a system window. */
        const val NAV_API = 5

        /** [McuOwner.Status.Failed] reason while the service is down (it restarts, we rebind). */
        const val SERVICE_GONE = "car service gone"

        /** [McuOwner.Status.Blocked] reason when bindService refused us. */
        const val BIND_REFUSED = "car service bind refused"

        /**
         * [installedApi] is the car service's, or null when the image has none. [access] REFUSED
         * (a service installed after the launcher, so CONTROL was never granted) keeps the port
         * here, as when there is no service.
         */
        fun choose(installedApi: Int?, access: BindAccess = BindAccess.GRANTED): Owner {
            if (installedApi == null || installedApi < MIN_API || access == BindAccess.REFUSED) {
                return Owner.LOCAL
            }
            return Owner.SERVICE
        }
    }
}
