package com.ripostelabs.carlauncher.carlib

/**
 * CdcAcm — which USB interface and endpoints to talk to, decided without touching Android.
 *
 * A CDC-ACM device is a composite: one *communications* interface (class 0x02) carrying an
 * interrupt endpoint for modem status, and one *data* interface (class 0x0A) carrying the bulk
 * IN/OUT pair that actually moves bytes. Picking the wrong one is the classic way a USB-serial
 * driver comes up "connected" and then reads nothing forever, which is exactly the failure we
 * already burned an hour on with slcan listen-only. So the choice is a pure function with tests
 * rather than a few lines buried in a service.
 *
 *     device
 *       ├── interface 0  class 0x02 (comm)  ── endpoint 0x82 interrupt IN   ← control only
 *       └── interface 1  class 0x0A (data)  ── endpoint 0x81 bulk IN        ← frames arrive here
 *                                           └─ endpoint 0x01 bulk OUT       ← commands go here
 */

/** Transfer direction of an endpoint, as reported by its descriptor. */
enum class UsbDirection {
    IN,
    OUT,
}

/** Transfer type of an endpoint. Only BULK moves slcan traffic. */
enum class EndpointType {
    BULK,
    INTERRUPT,
    OTHER,
}

/** The part of a USB endpoint descriptor this driver needs. */
data class EndpointDesc(
    val address: Int,
    val type: EndpointType,
    val direction: UsbDirection,
)

/** The part of a USB interface descriptor this driver needs. */
data class InterfaceDesc(
    val index: Int,
    val usbClass: Int,
    val endpoints: List<EndpointDesc>,
)

/** The interfaces and endpoints to claim, once [CdcAcm.findPipes] has chosen them. */
data class CdcAcmPipes(
    val dataInterface: Int,
    val bulkIn: Int,
    val bulkOut: Int,

    /**
     * The communications interface, when the device exposes one. Null for a vendor-class adapter
     * that puts its bulk pair on a single interface — then there is nothing to send DTR to.
     */
    val controlInterface: Int?,
)

object CdcAcm {

    /** USB class codes, from the USB CDC 1.2 specification. */
    const val CLASS_COMM = 0x02
    const val CLASS_DATA = 0x0A

    /**
     * Choose the interface carrying the bulk pair.
     *
     * Prefers a real CDC data interface; falls back to any interface that owns both a bulk IN and
     * a bulk OUT, which covers adapters flashed with vendor-class firmware. Returns null when no
     * interface can carry traffic in both directions — a half-duplex match is not usable, and
     * pretending otherwise produces a link that opens and then stalls.
     */
    fun findPipes(interfaces: List<InterfaceDesc>): CdcAcmPipes? {
        val control = interfaces.firstOrNull { it.usbClass == CLASS_COMM }?.index

        val preferred = interfaces.filter { it.usbClass == CLASS_DATA }
        val candidates = preferred + interfaces.filter { it.usbClass != CLASS_DATA }

        for (iface in candidates) {
            val bulkIn = iface.bulk(UsbDirection.IN) ?: continue
            val bulkOut = iface.bulk(UsbDirection.OUT) ?: continue

            return CdcAcmPipes(iface.index, bulkIn.address, bulkOut.address, control)
        }

        return null
    }

    private fun InterfaceDesc.bulk(direction: UsbDirection): EndpointDesc? =
        endpoints.firstOrNull { it.type == EndpointType.BULK && it.direction == direction }
}
