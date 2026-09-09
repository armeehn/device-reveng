package com.ripostelabs.carlauncher.carlib

/**
 * McuOpcode — what the MCU can say to Android, one byte at a time.
 *
 * The first byte of every [McuSerial.Command] body selects a handler in the vendor's port owner
 * (`EventService.processCmd` in com.szchoiceway.eventcenter). This is that dispatch, transcribed
 * from the decompile on 2026-09-08 with the vendor's own handler names kept as evidence. A native
 * client needs the same table to route bodies; nothing here interprets a payload.
 *
 *     McuSerial.Reader ──▶ Command(opcode, payload) ──▶ McuOpcode.of(opcode) ──▶ ?: unknown
 *
 * Two namespaces look alike and are not: these are *outer* opcodes on the wire. The `cmd` inside
 * a [CAN] body is the CAN box's own command byte ([McuFrame]), listed in HIWORLD_MCU_PROTOCOL.md.
 *
 * An opcode absent here is not "unknown to the MCU", only unseen in this dispatch; a body that
 * arrives with one is worth logging, not dropping.
 */
enum class McuOpcode(val code: Int, val handler: String) {
    MODE_ACK(0x70, "onCmdModeAck"),
    SYS_EVENT(0x71, "onCmdSysEvent"),
    KEY_EVENT(0x72, "onCmdKeyEvent"),
    RADIO_EVENT(0x73, "onCmdRadioEvent"),
    WHEEL_EVENT(0x74, "onCmdWheelEvent"),
    TV_EVENT(0x75, "onCmdTVEvent"),
    BMT_VOLUME(0x76, "onCmdBMTVolEvent"),
    EQ(0x77, "onCmdEQEvent"),
    MUTE(0x78, "onCmdMuteEvent"),
    MAIN_VOLUME(0x79, "onCmdMainVolEvent"),
    BALANCE(0x7A, "onCmdBalanceEvent"),
    LOUDNESS(0x7B, "onCmdLoudnessEvent"),
    MCU_INIT(0x7C, "onCmdMcuInitEvent"),
    PRESS_KEY(0x7E, "onCmdPressKeyEvent"),
    UPGRADE_ACK(0x7F, "onCmdUpgradeAck"),
    FREQ_SELECT(0x80, "onCmdFreqSelectEvt"),
    MODE_POWER_ON(0x81, "onCmdModePowerOnEvt"),
    DISC_AUTO_IN(0x82, "onCmdDiscAutoInEvt"),
    SYS_RTC_TIME(0x83, "onCmdSysRTCTimeEvt"),
    CAMERA_SINGLE(0x84, "onCmdMcuCameraSingle"),
    VALUE_8825(0x86, "onCmd8825ValEvent"),
    WHEEL_STATE(0x88, "OnCmdWheelState"),
    RADAR_PLUG_IN(0x6D, "onCmdMcuPlugInRadarData"),
    RADAR_IR(0x8D, "onCmdMcuIRRadarData"),
    RADAR_3DH(0x8E, "onCmdMcu3DHData"),
    VALUE_8836(0x94, "ACTION_MCU_8836_VALUE_EVENT"),
    HDMI_RESOLUTION(0x95, "onCmdMcuHdmiResolutionData"),
    SLEEP_STATE(0x96, "onCmdMcuSleepState"),
    TEST_STATE(0x97, "onCmdTestStateData"),
    ENCODE_STATE(0x98, "onCmdMcuEncodeState"),
    SEND_99(0x99, "onCmdMcuSend99"),
    CAR_AIR(0xA1, "onCmdCarAirEvent"),

    /** The CAN box's frame, relayed whole; see [McuSerial.Command.innerFrame]. */
    CAN(McuSerial.OP_CAN, "onCmdCanEvent"),
    ATA(0xA6, "onCmdMcuATAData"),
    ;

    companion object {
        private val byCode = entries.associateBy { it.code }

        /** The opcode for a body's first byte, or null when the dispatch has no handler for it. */
        fun of(code: Int): McuOpcode? = byCode[code]
    }
}
