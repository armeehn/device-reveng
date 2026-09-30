package com.ripostelabs.carlauncher.carlib

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The door popup follows stock `DoorInfoWindow` (`CB/ui/door/DoorInfoWindow.java:50-85`): a new
 * door mask with anything open shows it, all shut hides it, a repeat of the same mask does
 * nothing, and it hides itself after 10 s (`:177-178`). Stock has no speed gate on this path
 * (`CanDataParseBase.java:452-460` hands every 0x11 door byte straight to the window), so the
 * decision takes no speed either.
 *
 * Frames are 0x11 payloads as the box sends them; p[4] is the door byte, driver on 0x40 and
 * tailgate on 0x08 (see [HiworldDoorBitsTest]).
 */
class DoorPopupTest {

    /** Decode a 0x11 payload with only the door byte set, the way the owner does on 0.2. */
    private fun doors(doorByte: Int): DoorState {
        val p = ByteArray(8)
        p[4] = doorByte.toByte()
        val status = HiworldCanDecoder.decodePayload(0x11, p) as CanSignal.BasicStatus
        return DoorState.from(status, atMs = 0L)
    }

    @Test
    fun `a door opening shows the popup`() {
        val popup = DoorPopup()

        assertEquals(DoorPopup.Action.SHOW, popup.onDoors(doors(0x40)))
    }

    @Test
    fun `the box repeating the same mask does nothing`() {
        val popup = DoorPopup()
        popup.onDoors(doors(0x40))

        assertEquals(DoorPopup.Action.NONE, popup.onDoors(doors(0x40)))
    }

    /** 0x28, seen on the bus: tailgate and rear right. A second opening shows it again. */
    @Test
    fun `another opening shows it again`() {
        val popup = DoorPopup()
        popup.onDoors(doors(0x08))

        assertEquals(DoorPopup.Action.SHOW, popup.onDoors(doors(0x28)))
    }

    @Test
    fun `all shut hides it`() {
        val popup = DoorPopup()
        popup.onDoors(doors(0x08))

        assertEquals(DoorPopup.Action.HIDE, popup.onDoors(doors(0x00)))
    }

    /** At power-on the box reports all shut; nothing was shown, so nothing changes. */
    @Test
    fun `all shut at start does nothing`() {
        assertEquals(DoorPopup.Action.NONE, DoorPopup().onDoors(doors(0x00)))
    }

    /** Bits 1 and 0 are not doors; stock masks them off with `& 252`. */
    @Test
    fun `the two low bits are ignored`() {
        val popup = DoorPopup()

        assertEquals(DoorPopup.Action.NONE, popup.onDoors(doors(0x03)))
    }

    @Test
    fun `it hides after the stock 10 s`() {
        assertEquals(10_000L, DoorPopup.AUTO_HIDE_MS)
    }
}
