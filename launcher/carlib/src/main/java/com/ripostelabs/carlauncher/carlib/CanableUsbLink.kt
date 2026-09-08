package com.ripostelabs.carlauncher.carlib

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager

/**
 * CanableUsbLink — the CANable 2.0 Pro as a stream of CAN frames, over Android's USB host API.
 *
 * This is the driver layer. It owns every raw USB detail (claiming interfaces, control transfers,
 * bulk reads) and hands callers [SlcanEvent]s. Nothing above it should import `android.hardware.usb`.
 *
 * ── Why not /dev/ttyACM0 ────────────────────────────────────────────────────────────────────────
 * The head unit's kernel has no CDC-ACM driver: no `/dev/ttyACM*`, and `/sys/bus/usb-serial` is
 * absent entirely. Checked on both USB ports 2026-09-08, adapter enumerating correctly each time.
 * CDC-ACM is only a bulk pair plus two control requests, so the app does it directly. No kernel
 * module, no root, no vendor cooperation.
 */
class CanableUsbLink(private val manager: UsbManager) {

    /** Find an attached CANable. Returns null when none is plugged in, or none we recognise. */
    fun find(): UsbDevice? = manager.deviceList.values.firstOrNull {
        it.vendorId == VENDOR_ID && it.productId == PRODUCT_ID
    }

    /**
     * Claim [device] and open the CAN channel at [bitrate].
     *
     * Returns null when the app has no USB permission for the device, when its descriptors carry
     * no usable bulk pair, or when the interface is already claimed by something else.
     */
    fun open(device: UsbDevice, bitrate: SlcanBitrate): Session? {
        if (!manager.hasPermission(device)) {
            return null
        }

        val pipes = CdcAcm.findPipes(describe(device)) ?: return null
        val connection = manager.openDevice(device) ?: return null

        val session = Session(device, connection, pipes)
        if (!session.start(bitrate)) {
            session.close()
            return null
        }

        return session
    }

    /** Flatten [device]'s descriptors into the plain model [CdcAcm.findPipes] reasons about. */
    private fun describe(device: UsbDevice): List<InterfaceDesc> =
        (0 until device.interfaceCount).map { i ->
            val iface = device.getInterface(i)
            val endpoints = (0 until iface.endpointCount).map { e ->
                val ep = iface.getEndpoint(e)
                EndpointDesc(ep.address, endpointType(ep.type), endpointDirection(ep.direction))
            }

            InterfaceDesc(iface.id, iface.interfaceClass, endpoints)
        }

    private fun endpointType(type: Int): EndpointType = when (type) {
        UsbConstants.USB_ENDPOINT_XFER_BULK -> EndpointType.BULK
        UsbConstants.USB_ENDPOINT_XFER_INT -> EndpointType.INTERRUPT
        else -> EndpointType.OTHER
    }

    private fun endpointDirection(direction: Int): UsbDirection =
        if (direction == UsbConstants.USB_DIR_IN) UsbDirection.IN else UsbDirection.OUT

    /**
     * An open channel. Not thread-safe: read from one thread, and do not share it.
     */
    class Session internal constructor(
        private val device: UsbDevice,
        private val connection: UsbDeviceConnection,
        private val pipes: CdcAcmPipes,
    ) {

        private val reader = SlcanReader()
        private val buffer = ByteArray(READ_BUFFER_BYTES)
        private val claimed = ArrayList<UsbInterface>()

        /** Claim the interfaces, raise DTR, then close/set-bitrate/open the CAN channel. */
        internal fun start(bitrate: SlcanBitrate): Boolean {
            if (!claim(pipes.dataInterface)) {
                return false
            }

            // The comm interface is what SET_CONTROL_LINE_STATE is addressed to. A native-USB CDC
            // device will not transmit until DTR is asserted, so a missing claim here reads as an
            // adapter that opened and then said nothing.
            pipes.controlInterface?.let {
                claim(it)
                setControlLineState(it)
            }

            for (command in SlcanCodec.startup(bitrate)) {
                if (!write(command)) {
                    return false
                }

                Thread.sleep(SETTLE_MS)
            }

            return true
        }

        /**
         * Read whatever has arrived, up to [timeoutMs]. Returns the events completed by this read;
         * an empty list means the bus was quiet, not that the link failed.
         */
        fun poll(timeoutMs: Int = READ_TIMEOUT_MS): List<SlcanEvent> {
            val endpoint = endpoint(pipes.bulkIn) ?: return emptyList()
            val read = connection.bulkTransfer(endpoint, buffer, buffer.size, timeoutMs)
            if (read <= 0) {
                return emptyList()
            }

            return reader.feed(buffer, read)
        }

        /** Transmit one frame, e.g. the OBD speed request `7DF#02 01 0D …`. */
        fun send(frame: SlcanFrame): Boolean = write(SlcanCodec.transmit(frame))

        /** Close the CAN channel and release the device. Safe to call more than once. */
        fun close() {
            runCatching { write(SlcanCodec.shutdown()) }

            for (iface in claimed) {
                connection.releaseInterface(iface)
            }
            claimed.clear()

            connection.close()
        }

        private fun claim(index: Int): Boolean {
            val iface = (0 until device.interfaceCount)
                .map { device.getInterface(it) }
                .firstOrNull { it.id == index }
                ?: return false

            if (!connection.claimInterface(iface, true)) {
                return false
            }

            claimed.add(iface)
            return true
        }

        /** USB CDC 1.2 §6.2.14: SET_CONTROL_LINE_STATE, DTR and RTS both asserted. */
        private fun setControlLineState(commInterface: Int) {
            connection.controlTransfer(
                REQUEST_TYPE_CLASS_INTERFACE_OUT,
                REQUEST_SET_CONTROL_LINE_STATE,
                CONTROL_LINE_DTR_AND_RTS,
                commInterface,
                null,
                0,
                CONTROL_TIMEOUT_MS,
            )
        }

        private fun write(bytes: ByteArray): Boolean {
            val endpoint = endpoint(pipes.bulkOut) ?: return false
            return connection.bulkTransfer(endpoint, bytes, bytes.size, WRITE_TIMEOUT_MS) >= 0
        }

        private fun endpoint(address: Int) = claimed
            .flatMap { iface -> (0 until iface.endpointCount).map { iface.getEndpoint(it) } }
            .firstOrNull { it.address == address }
    }

    companion object {
        /** CANable 2.0 Pro running the slcan firmware. */
        const val VENDOR_ID = 0x16D0
        const val PRODUCT_ID = 0x117E

        /** USB CDC 1.2 constants. */
        private const val REQUEST_TYPE_CLASS_INTERFACE_OUT = 0x21
        private const val REQUEST_SET_CONTROL_LINE_STATE = 0x22
        private const val CONTROL_LINE_DTR_AND_RTS = 0x03

        /** One read must hold a burst comfortably: the tapped bus runs ~1215 frames/s. */
        private const val READ_BUFFER_BYTES = 4096
        private const val READ_TIMEOUT_MS = 200
        private const val WRITE_TIMEOUT_MS = 500
        private const val CONTROL_TIMEOUT_MS = 500

        /** The firmware needs a moment between close, bitrate and open. Matches the shell probe. */
        private const val SETTLE_MS = 200L
    }
}
