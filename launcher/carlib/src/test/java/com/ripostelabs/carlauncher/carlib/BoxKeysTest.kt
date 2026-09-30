package com.ripostelabs.carlauncher.carlib

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The CAN box's own keys, frame bytes as `HiworldCanParseToyota` reads them: `bArr[2]` is our
 * p[0], `bArr[3]` p[1].
 *
 *  - 0x21 panel button (`:726-815`): `id 01` press, `id 00` release; stock acts on the release.
 *  - 0x22 knob (`:676-724`): `which position`; a step under 10 is that many key presses.
 *  - 0xC5 voice (`:376-388`): 1 talk, 2 or 5 hang up.
 *  - 0xE0 mode (`:390-408`): 0x20/0x21 radio, 0x22 music, 0x23 BT music, 0x27 AUX.
 */
class BoxKeysTest {

    private fun frame(opcode: Int, vararg p: Int): CanSignal =
        HiworldCanDecoder.decodePayload(opcode, p.map { it.toByte() }.toByteArray())

    private fun feed(keys: BoxKeys, vararg frames: CanSignal): List<KeyAction> =
        frames.flatMap { keys.onSignal(it) }

    @Test
    fun `the four opcodes decode to box keys`() {
        assertEquals(CanSignal.BoxKey(CanSignal.BoxKey.Kind.PANEL, 0x2B, 1), frame(0x21, 0x2B, 0x01))
        assertEquals(CanSignal.BoxKey(CanSignal.BoxKey.Kind.KNOB, 1, 0x32), frame(0x22, 0x01, 0x32))
        assertEquals(CanSignal.BoxKey(CanSignal.BoxKey.Kind.VOICE, 1, 0), frame(0xC5, 0x01))
        assertEquals(CanSignal.BoxKey(CanSignal.BoxKey.Kind.MODE, 0x22, 0), frame(0xE0, 0x22))
    }

    /** Panel 43 (0x2B) is `g_panel_byKeyVal = 76`, MCU_KEY_SYS_HOME, fired on the release. */
    @Test
    fun `a panel button acts on its release`() {
        val keys = BoxKeys()

        assertEquals(emptyList<KeyAction>(), feed(keys, frame(0x21, 0x2B, 0x01)))
        assertEquals(listOf(KeyAction.HOME), feed(keys, frame(0x21, 0x2B, 0x00)))
    }

    /** The box repeats the press while held; it is still one action. */
    @Test
    fun `a held panel button is one action`() {
        val keys = BoxKeys()
        val press = frame(0x21, 0x09, 0x01)

        assertEquals(listOf(KeyAction.MUTE), feed(keys, press, press, press, frame(0x21, 0x09, 0x00)))
    }

    @Test
    fun `a release with no press does nothing`() {
        assertEquals(emptyList<KeyAction>(), feed(BoxKeys(), frame(0x21, 0x00, 0x00)))
    }

    @Test
    fun `the panel table follows stock`() {
        val stock = mapOf(
            0x02 to KeyAction.PREV, 0x03 to KeyAction.NEXT, 0x06 to KeyAction.BACK,
            0x09 to KeyAction.MUTE, 0x10 to KeyAction.PLAY_PAUSE, 0x20 to KeyAction.NAV,
            0x21 to KeyAction.NAV, 0x24 to KeyAction.MODE, 0x2A to KeyAction.PLAY_PAUSE,
            0x2B to KeyAction.HOME, 0x2C to KeyAction.MODE, 0x33 to KeyAction.RADIO,
            0x34 to KeyAction.TALK, 0x35 to KeyAction.HANGUP, 0x3B to KeyAction.MODE,
            0x45 to KeyAction.VOLUME_UP, 0x46 to KeyAction.VOLUME_DOWN, 0x4B to KeyAction.RADIO,
        )

        stock.forEach { (id, action) ->
            val keys = BoxKeys()
            assertEquals("id 0x%02X".format(id), listOf(action), feed(keys, frame(0x21, id, 1), frame(0x21, id, 0)))
        }
    }

    /** Power, AUX, the canbus console and the BT phone have no launcher twin. */
    @Test
    fun `panel keys without a launcher twin do nothing`() {
        listOf(0x01, 0x08, 0x12, 0x28, 0x2F, 0x30, 0x39, 0x61).forEach { id ->
            val keys = BoxKeys()
            assertEquals("id 0x%02X".format(id), emptyList<KeyAction>(), feed(keys, frame(0x21, id, 1), frame(0x21, id, 0)))
        }
    }

    /** Volume knob: 0x32 then 0x35 is three steps up, then 0x33 two down. */
    @Test
    fun `the volume knob steps by the position change`() {
        val keys = BoxKeys()
        feed(keys, frame(0x22, 0x01, 0x32))

        assertEquals(List(3) { KeyAction.VOLUME_UP }, feed(keys, frame(0x22, 0x01, 0x35)))
        assertEquals(List(2) { KeyAction.VOLUME_DOWN }, feed(keys, frame(0x22, 0x01, 0x33)))
    }

    /** Tune knob, `sendMCUKey(2)` NEXT up and `(3)` PREV down, with its own position. */
    @Test
    fun `the tune knob keeps its own position`() {
        val keys = BoxKeys()
        feed(keys, frame(0x22, 0x02, 0x10), frame(0x22, 0x01, 0x80))

        assertEquals(listOf(KeyAction.NEXT), feed(keys, frame(0x22, 0x02, 0x11)))
        assertEquals(listOf(KeyAction.PREV), feed(keys, frame(0x22, 0x02, 0x10)))
    }

    /** The first report after power-on jumps from 0; stock drops any jump of 10 or more. */
    @Test
    fun `a knob jump of 10 or more is not a turn`() {
        val keys = BoxKeys()

        assertEquals(emptyList<KeyAction>(), feed(keys, frame(0x22, 0x01, 0x32)))
        assertEquals(emptyList<KeyAction>(), feed(keys, frame(0x22, 0x01, 0x3C)))
    }

    @Test
    fun `voice status talks and hangs up`() {
        val keys = BoxKeys()

        assertEquals(listOf(KeyAction.TALK), feed(keys, frame(0xC5, 0x01)))
        assertEquals(listOf(KeyAction.HANGUP), feed(keys, frame(0xC5, 0x02)))
        assertEquals(listOf(KeyAction.HANGUP), feed(keys, frame(0xC5, 0x05)))
        assertEquals(emptyList<KeyAction>(), feed(keys, frame(0xC5, 0x00)))
    }

    @Test
    fun `mode change opens the source`() {
        val keys = BoxKeys()

        assertEquals(listOf(KeyAction.RADIO), feed(keys, frame(0xE0, 0x20)))
        assertEquals(listOf(KeyAction.RADIO), feed(keys, frame(0xE0, 0x21)))
        assertEquals(listOf(KeyAction.OPEN_MEDIA), feed(keys, frame(0xE0, 0x22)))
        assertEquals(listOf(KeyAction.OPEN_MEDIA), feed(keys, frame(0xE0, 0x23)))
        assertEquals(emptyList<KeyAction>(), feed(keys, frame(0xE0, 0x27)))
    }

    /** KeyRouter hands box keys on; the 0x11 wheel path stays its own. */
    @Test
    fun `the key router emits box keys`() {
        val out = mutableListOf<KeyAction>()
        val router = KeyRouter(map = { WheelKeyMap.EMPTY }, emit = { out += it })

        router.onCanSignal(frame(0xC5, 0x01), atMs = 0L)

        assertEquals(listOf(KeyAction.TALK), out)
    }
}
