#!/usr/bin/env python3
"""carsim — the vehicle side of the RAV4 head unit, on the wire.

The vendor's Android cannot be emulated (Qualcomm SoC, arm64 vendor HALs,
platform-signed apps), so the desk rig emulates the CAR instead: everything
vehicle-side reaches Android through one UART, and this program is what sits
on the far end of it. It plays the MCU (the head unit's own microcontroller)
and the HiWorld CAN box behind it, plus the raw body bus a CANable would tap.

    scenario ──▶ Timeline ──▶ Vehicle ──▶ McuSide  (0D 0A framing) ──TCP──▶ QEMU vport "carsim.mcu" ──▶ McuOwner
                                     └──▶ CanSide  (slcan text)    ──TCP──▶ QEMU vport "carsim.can" ──▶ SlcanLinkSource

Every byte layout is transcribed from the launcher's own decoders, which were
themselves transcribed from the vendor decompile; the file and symbol are
named next to each constant so a change on either side is a one-line diff.

Wire formats (launcher/carlib):
  McuSerial.kt   outer frame   0D 0A | LEN | OPCODE | payload | CK | 00
                               LEN = 1 + len(payload) + 1, CK = ~(LEN + OPCODE + Σpayload) & 0xFF
  McuFrame.kt    inner frame   5A A5 | len | cmd | payload | ck
                               len = len(payload), ck = Σ(all preceding bytes) & 0xFF
                               relayed by the MCU under outer opcode 0xA5 (McuSerial.OP_CAN)
  SlcanCodec.kt  raw bus       tIIIL<hex bytes>\\r   (standard id, DLC L)

Stdlib only. Run with --help for the scenario grammar.
"""

from __future__ import annotations

import argparse
import re
import socket
import sys
import threading
import time
from dataclasses import dataclass, field
from pathlib import Path

# ── Outer framing: McuSerial.kt ───────────────────────────────────────────────────────────────
OUTER_HEADER = b"\x0d\x0a"
OUTER_PAD = b"\x00"
OUTER_LEN_MIN = 2  # opcode + CK

# Opcodes Android sends (McuOwnerProtocol.kt OP_*), named for the log.
TX_OPCODES = {
    0x01: "MODE", 0x02: "RADIO_KEY", 0x05: "SETUP", 0x08: "SYSTEM_KEY",
    0x0A: "MUTE", 0x0C: "USER_FREQ", 0x13: "RTC", 0x2E: "BACKLIGHT",
}
OP_MODE = 0x01
OP_RADIO_KEY = 0x02
OP_SYSTEM_KEY = 0x08
OP_USER_FREQ = 0x0C
SYSTEM_KEY_VOLUME_UP = 0     # McuOwnerProtocol.SystemKey
SYSTEM_KEY_VOLUME_DOWN = 1
SYSTEM_KEY_MUTE = 12
SRC_RADIO = 1                # McuOwnerProtocol.Mode.RADIO; `01 01` selects the tuner
SRC_NULL = 99                # Mode.NULL, what exitCurMode sends when the tuner is left

# `02 <key>` tuner keys (CarService.RADIO_KEY_*, EventService.sendRadioKey).
RADIO_KEY_SCAN = 13
RADIO_KEY_STEP_DOWN, RADIO_KEY_STEP_UP = 14, 15
RADIO_KEY_SEEK_DOWN, RADIO_KEY_SEEK_UP = 16, 17
RADIO_KEY_BAND_CYCLE = 24
RADIO_KEY_BAND_FM, RADIO_KEY_BAND_AM = 30, 31

# Opcodes the MCU sends (McuOpcode.kt).
RX_MODE_ACK = 0x70
RX_SYS_EVENT = 0x71
RX_KEY_EVENT = 0x72
RX_RADIO_EVENT = 0x73
RX_MUTE = 0x78
RX_MAIN_VOLUME = 0x79
RX_CAN = 0xA5

# SYS_EVENT bits, byte 1 then byte 2 (McuOwnerProtocol.sysEvent).
SYS1_DISC, SYS1_USB, SYS1_RIGHT_TURN, SYS1_ILLUMINATION = 0x80, 0x40, 0x10, 0x08
SYS1_BRAKE, SYS1_REVERSE, SYS1_ACC = 0x04, 0x02, 0x01
SYS2_MCAN, SYS2_START_STOP, SYS2_HDMI, SYS2_LEFT_TURN = 0x80, 0x40, 0x08, 0x01

# `79`/`78`: bit 7 = silent (no volume window), low bits = level / muted.
SILENT_BIT = 0x80
VOLUME_MAX = 40

# `73` RADIO_EVENT sub-commands, first payload byte (McuOwnerProtocol.radioEvent).
RADIO_STATE = 0       # [0, icons, flags]  icons: bit0 stereo, bit1 TP; flags: bit0 RDS, bit3 TA
RADIO_BAND = 1        # [1, band, preset]  band 0-2 FM, 3+ AM (CarService.isAmBand)
RADIO_PRESET = 2      # [2, preset]
RADIO_FREQ = 3        # [3, hi, lo]        FM in 10 kHz, AM in kHz (RadioStateHolder)
RADIO_PTY = 5         # [5, pty]
RADIO_PS_NAME = 6     # [6, text...]       trailing spaces/NULs trimmed by the launcher
RADIO_ICON_STEREO, RADIO_ICON_TP = 0x01, 0x02
RADIO_FLAG_RDS = 0x01
RADIO_PS_LEN = 8      # the RDS PS field is eight characters, space padded
BAND_FM1, BAND_AM = 0, 3
# North American dial (RadioTuning.kt): FM 87.5-108.0 MHz by 0.2, AM 530-1710 kHz by 10.
FM_MIN, FM_MAX, FM_STEP = 8750, 10790, 20
AM_MIN, AM_MAX, AM_STEP = 530, 1710, 10

# Panel key codes (McuOwnerProtocol.Key).
PANEL_KEYS = {
    "POWER": 0x01, "NEXT": 0x02, "PREV": 0x03, "PLAY": 0x04, "STOP": 0x05,
    "PLAY_PAUSE": 0x06, "MENU": 0x09, "MODE": 0x10, "MUTE": 0x11,
    "VOL_UP": 0x12, "VOL_DOWN": 0x13, "SETUP": 0x14, "HANGUP": 0x16,
    "TALK": 0x17, "EQ": 0x33, "RADIO": 0x36, "RETURN": 0x55,
    "TASK_LIST": 0x71, "VOICE": 0x74,
}

# CAN steering-wheel button ids (HiworldCanDecoder.swcAction, car-verified 2026-09-07).
SWC_BUTTONS = {
    "VOL_UP": 1, "VOL_DOWN": 2, "MUTE": 3, "VOICE": 4, "CALL": 5, "HANGUP": 6,
    "NEXT": 8, "PREV": 9, "MODE": 12, "PLAY_PAUSE": 15, "BACK": 16,
}
SWC_PRESS_MS = 120
# Hold gestures (WheelGestures.kt): the box repeats 0x11 every FRAME_PERIOD_MS while a key is
# held; LONG_PRESS_MS = 600 fires the long press, DOUBLE_PRESS_MS = 400 pairs two presses.
SWC_FRAME_MS = 100
SWC_HOLD_MS = 900
SWC_DOUBLE_GAP_MS = 200

# ── Inner framing: McuFrame.kt, cmd set: HiworldCanDecoder.kt ────────────────────────────────
INNER_HEADER = b"\x5a\xa5"
CMD_BASIC_STATUS = 0x11    # SWC key p[2:3], doors p[4], steering p[6:7]
CMD_TRIP_INFO = 0x13       # fuel p[0:1], range p[2:3], best fuel p[4:5], elapsed min p[6:7],
                           # avg km/h p[8:9], fuel unit p[10], distance unit p[11] (1 = mile)
CMD_SPEED = 0x17           # p[0:1] BE × 0.1 km/h
CMD_RPM_GEAR = 0x1A        # gear code p[5], rpm mirror p[9:10]
CMD_HYBRID = 0x1F
CMD_CLIMATE = 0x31         # OnHandleCanAirCmdVertical layout
CMD_VEHICLE_INFO = 0x32    # rpm p[2:3], speed p[4:5] (canbus2's iCarSpeed, untrusted), coolant p[9] (−40)
CMD_RADAR = 0x41           # rear p[0..3], front p[4..7], steps of 30 cm
CMD_TPMS = 0x48            # FL/FR/RL/RR/spare = p[i] + p[i+5], i = 2..6
CMD_SYS_EVENT = 0x71       # the box's own reverse flag, p[0] & 0x02

SPEED_017_SCALE = 10       # raw = km/h × 10 (SPEED_017_SCALE_KMH = 0.1)
GEAR_CODES_1A = {"D": 0, "P": 1, "N": 2, "R": 3}   # gearFromCode, 2026-08-29 drive
COOLANT_OFFSET_C = 40
TEMP_SCALE_HALF_C = 2      # climate setpoint byte = °C × 2 (TEMP_SCALE_C = 0.5)
RADAR_STEP_CM = 30
TPMS_NONE = 0xFE
TRIP_NONE = 0xFFFF         # 0x13 word sentinel: the page has no such reading
FUEL_TENTHS = 10           # 0x13 fuel words carry tenths of the p[10] unit
FUEL_UNIT_CODES = {"km/L": 1, "L/100km": 2, "MPG(UK)": 3, "MPG(US)": 0}
TRIP_FLOAT_KEYS = ("fuel", "best")   # human units on the command line, tenths on the wire

# Door bits, shared by inner 0x11 p[4] and raw 0x4A5 byte 3 (RawCanDecoder DOOR_*).
DOOR_BITS = {"FL": 0x80, "FR": 0x40, "RR": 0x20, "RL": 0x10, "TAIL": 0x08, "HOOD": 0x04}
# HiworldCanDecoder.decodeBasicStatus names FL=0x40 and FR=0x80; RawCanDecoder, verified by
# opening the doors, has driver=0x80. The raw decoder is the car-checked one, so it wins here.

# ── Raw body bus: RawCanDecoder.kt ───────────────────────────────────────────────────────────
ID_SPEED_FAST = 0x361      # byte 6 = km/h
ID_GEAR = 0x3BC            # byte 1: 0x20 P, 0x10 R; byte 5: 0x80 D; else N
ID_DOOR_STATUS = 0x4A5     # byte 3 = door bits
RAW_DLC = 8                # RawCanDecoder.DLC_MIN: shorter frames are ignored
GEAR_P_BIT, GEAR_R_BIT, GEAR_D_BIT = 0x20, 0x10, 0x80
# Standard OBD, SAE J1979 service 01: the launcher asks on 0x7DF, the engine ECU answers on 0x7E8.
OBD_REQUEST_ID = 0x7DF     # Obd.REQUEST_ID, functional: every emissions ECU listens
OBD_ECU_ID = 0x7E8         # the engine ECU's reply id
OBD_SERVICE_CURRENT = 0x01
OBD_POSITIVE_OFFSET = 0x40 # 0x41 = "service 01 answered"
OBD_PCI_SINGLE = 0x03      # ISO-TP single frame: service + PID + one data byte
OBD_AT_SERVICE = 1
OBD_AT_PID = 2
OBD_AT_VALUE = 3
OBD_PIDS = {"load": 0x04, "coolant": 0x05, "throttle": 0x11}   # ObdPid; the key is the `obd` verb's
OBD_PID_SPEED = 0x0D       # answered from speed_kmh, the launcher's own cross-check
OBD_BYTE_FULL = 255        # J1979: a full byte is 100 %
OBD_PERCENT_FULL = 100
SLCAN_FRAME = re.compile(rb"t(?P<id>[0-9A-Fa-f]{3})(?P<dlc>[0-9A-Fa-f])(?P<data>(?:[0-9A-Fa-f]{2})*)")

BUS_TICK_S = 0.1           # CarEvents.BUS_SPEED_STALE_MS is 2 s; 10 Hz keeps the gate fed
RELAY_TICK_S = 1.0         # HiWorld status relay cadence (the real box is slower)

CANDUMP_LINE = re.compile(r"^\s*\((?P<ts>[\d.]+)\)\s+\S+\s+(?P<id>[0-9A-Fa-f]+)\s+\[(?P<dlc>\d+)\]\s*(?P<data>(?:[0-9A-Fa-f]{2}\s*)*)$")

SOCKET_TIMEOUT_S = 0.2
CONNECT_RETRY_S = 0.5
CONNECT_ATTEMPTS = 20


def outer_encode(opcode: int, payload: bytes = b"") -> bytes:
    body = bytes([len(payload) + OUTER_LEN_MIN, opcode]) + payload
    ck = (~sum(body)) & 0xFF
    return OUTER_HEADER + body + bytes([ck]) + OUTER_PAD


def outer_decode(buf: bytearray) -> list[tuple[int, bytes]]:
    """Pull every complete outer frame off the front of `buf`; a bad CK is logged and skipped."""
    frames = []
    while True:
        start = buf.find(OUTER_HEADER)
        if start < 0:
            buf.clear()
            return frames
        if start:
            del buf[:start]
        if len(buf) < len(OUTER_HEADER) + 1:
            return frames
        length = buf[2]
        end = 3 + length
        if len(buf) < end:
            return frames
        body = bytes(buf[2:end])
        del buf[:end]
        expected = (~sum(body[:-1])) & 0xFF
        if body[-1] != expected:
            log(f"mcu rx bad CK {body.hex()} (want {expected:02x})")
            continue
        frames.append((body[1], body[2:-1]))


def inner_encode(cmd: int, payload: bytes) -> bytes:
    head = INNER_HEADER + bytes([len(payload), cmd]) + payload
    return head + bytes([sum(head) & 0xFF])


def slcan_frame(can_id: int, data: bytes) -> bytes:
    return f"t{can_id:03X}{len(data):d}{data.hex().upper()}\r".encode("ascii")


def u16(value: int) -> bytes:
    return bytes([(value >> 8) & 0xFF, value & 0xFF])


def obd_value(key: str, text: str) -> int:
    """One `obd k=v` pair as the ECU's data byte A carries it: J1979's formulas, inverted."""
    if key not in OBD_PIDS:
        raise KeyError(f"unknown obd key {key}")
    if key == "coolant":
        return int(text) + COOLANT_OFFSET_C
    return round(float(text) * OBD_BYTE_FULL / OBD_PERCENT_FULL)


def trip_value(key: str, text: str) -> int:
    """One `trip k=v` pair as the 0x13 page carries it: fuel words in tenths, unit as its code."""
    if key == "unit":
        return FUEL_UNIT_CODES[text]
    if key in TRIP_FLOAT_KEYS:
        return round(float(text) * FUEL_TENTHS)
    return int(text)


_log_lock = threading.Lock()
_log_file = sys.stderr
_t0 = time.monotonic()


def log(msg: str) -> None:
    with _log_lock:
        _log_file.write(f"[{time.monotonic() - _t0:7.2f}] {msg}\n")
        _log_file.flush()


# ── The vehicle: state and the frames that describe it ───────────────────────────────────────
@dataclass
class Climate:
    on: bool = True
    ac: bool = True
    auto: bool = True
    recirc: bool = False
    fan: int = 2
    temp_c: float = 21.0


@dataclass
class Tuner:
    """The MCU's tuner as the `73` events describe it: one field per sub-command."""
    band: int = BAND_FM1
    freq: int = 9630          # 96.3 MHz
    preset: int = 0
    ps_name: str = "CBC R1"
    pty: int = 0
    stereo: bool = True
    rds: bool = True
    selected: bool = False    # `01 01` seen; events flow only while the source is ours

    @property
    def fm(self) -> bool:
        return self.band < BAND_AM

    def step(self, direction: int) -> None:
        lo, hi, step = (FM_MIN, FM_MAX, FM_STEP) if self.fm else (AM_MIN, AM_MAX, AM_STEP)
        nxt = self.freq + direction * step
        self.freq = lo if nxt > hi else hi if nxt < lo else nxt

    def set_band(self, band: int) -> None:
        if (band < BAND_AM) == self.fm:
            self.band = band
            return
        self.band = band
        self.freq = FM_MIN if self.fm else AM_MIN

    def frames(self) -> list[tuple[bytes, str]]:
        icons = (RADIO_ICON_STEREO if self.stereo else 0) | RADIO_ICON_TP
        flags = RADIO_FLAG_RDS if self.rds else 0
        ps = self.ps_name.encode("ascii", "replace").ljust(RADIO_PS_LEN)[:RADIO_PS_LEN]
        return [
            (outer_encode(RX_RADIO_EVENT, bytes([RADIO_STATE, icons, flags])), f"RADIO state icons={icons:02x} flags={flags:02x}"),
            (outer_encode(RX_RADIO_EVENT, bytes([RADIO_BAND, self.band, self.preset])), f"RADIO band={self.band} preset={self.preset}"),
            (outer_encode(RX_RADIO_EVENT, bytes([RADIO_FREQ]) + u16(self.freq)), f"RADIO freq={self.freq}"),
            (outer_encode(RX_RADIO_EVENT, bytes([RADIO_PTY, self.pty])), f"RADIO pty={self.pty}"),
            (outer_encode(RX_RADIO_EVENT, bytes([RADIO_PS_NAME]) + ps), f"RADIO ps={ps.decode().rstrip()!r}"),
        ]


@dataclass
class Vehicle:
    acc: bool = True
    reverse: bool = False
    lamp: bool = False
    brake: bool = False
    left_turn: bool = False
    right_turn: bool = False
    volume: int = 12
    muted: bool = False
    speed_kmh: float = 0.0
    gear: str = "P"
    doors: int = 0
    rpm: int = 0
    trip: dict[str, int] | None = None   # range/elapsed/avg/fuel/best/unit once a `trip` event set them
    obd: dict[str, int] | None = None    # load/coolant/throttle data bytes once an `obd` event set them
    climate: Climate = field(default_factory=Climate)
    tuner: Tuner = field(default_factory=Tuner)

    # ── MCU-native frames (McuOwnerProtocol) ──
    def sys_event(self) -> bytes:
        b1 = (SYS1_ACC if self.acc else 0) | (SYS1_REVERSE if self.reverse else 0) \
            | (SYS1_ILLUMINATION if self.lamp else 0) | (SYS1_BRAKE if self.brake else 0) \
            | (SYS1_RIGHT_TURN if self.right_turn else 0)
        b2 = SYS2_MCAN | (SYS2_LEFT_TURN if self.left_turn else 0)
        return outer_encode(RX_SYS_EVENT, bytes([b1, b2]))

    def main_volume(self, silent: bool = False) -> bytes:
        return outer_encode(RX_MAIN_VOLUME, bytes([(self.volume & ~SILENT_BIT) | (SILENT_BIT if silent else 0)]))

    def mute(self, silent: bool = False) -> bytes:
        return outer_encode(RX_MUTE, bytes([(1 if self.muted else 0) | (SILENT_BIT if silent else 0)]))

    @staticmethod
    def panel_key(code: int) -> bytes:
        return outer_encode(RX_KEY_EVENT, bytes([code, 0]))

    # ── CAN box frames, relayed under 0xA5 ──
    @staticmethod
    def relay(cmd: int, payload: bytes) -> bytes:
        return outer_encode(RX_CAN, inner_encode(cmd, payload))

    def basic_status(self, swc_id: int = 0, pressed: bool = False, steer_deg: float = 0.0) -> bytes:
        raw = int(steer_deg * 14) & 0xFFFF
        return self.relay(CMD_BASIC_STATUS, bytes([0, 0, swc_id, 1 if pressed else 0, self.doors, 0]) + u16(raw))

    def speed_relay(self) -> bytes:
        return self.relay(CMD_SPEED, u16(int(self.speed_kmh * SPEED_017_SCALE)))

    def gear_relay(self) -> bytes:
        p = bytearray(11)
        p[1] = 0x03 if self.gear == "R" else 0x01
        p[5] = GEAR_CODES_1A[self.gear]
        p[9:11] = u16(self.rpm)
        return self.relay(CMD_RPM_GEAR, bytes(p))

    def vehicle_info(self, coolant_c: int = 78) -> bytes:
        p = bytearray(14)
        p[2:4] = u16(self.rpm)
        # Mirrors the bus speed so the digest never contradicts 0x361; the launcher shows it
        # on the capture screen only (CarEvents.CAN_SPEED_TRUSTED is false).
        p[4:6] = u16(int(round(self.speed_kmh)))
        p[9] = coolant_c + COOLANT_OFFSET_C
        return self.relay(CMD_VEHICLE_INFO, bytes(p))

    def trip_relay(self) -> bytes:
        t = self.trip or {}
        p = bytearray(12)
        p[0:2] = u16(t.get("fuel", TRIP_NONE))
        p[2:4] = u16(t.get("range", TRIP_NONE))
        p[4:6] = u16(t.get("best", TRIP_NONE))
        p[6:8] = u16(t.get("elapsed", TRIP_NONE))
        p[8:10] = u16(t.get("avg", TRIP_NONE))
        p[10] = t.get("unit", FUEL_UNIT_CODES["L/100km"])
        return self.relay(CMD_TRIP_INFO, bytes(p))

    def climate_relay(self) -> bytes:
        c = self.climate
        b0 = (0x40 if c.on else 0) | (0x08 if c.auto else 0) | 0x04   # 0x04 clear = dual; set = single
        b1 = (0x40 if c.ac else 0) | (0x10 if c.recirc else 0)
        setpoint = int(c.temp_c * TEMP_SCALE_HALF_C)
        return self.relay(CMD_CLIMATE, bytes([b0, b1, 0, 0, 0, c.fan & 0x0F, setpoint, setpoint, 0, 0]))

    def radar_relay(self, rear_steps: list[int], front_steps: list[int]) -> bytes:
        return self.relay(CMD_RADAR, bytes(rear_steps + front_steps))

    def tpms_relay(self, kpa: list[int]) -> bytes:
        p = bytearray(12)
        for i, value in enumerate(kpa):
            p[2 + i] = min(value, TPMS_NONE - 1) if value else TPMS_NONE
            p[7 + i] = max(value - (TPMS_NONE - 1), 0) if value else 0
        return self.relay(CMD_TPMS, bytes(p))

    # ── Raw body bus (what a CANable would hear) ──
    def raw_speed(self) -> bytes:
        d = bytearray(RAW_DLC)
        d[6] = int(round(self.speed_kmh)) & 0xFF
        return slcan_frame(ID_SPEED_FAST, bytes(d))

    def raw_gear(self) -> bytes:
        d = bytearray(RAW_DLC)
        d[1] = {"P": GEAR_P_BIT, "R": GEAR_R_BIT}.get(self.gear, 0)
        d[5] = GEAR_D_BIT if self.gear == "D" else 0
        return slcan_frame(ID_GEAR, bytes(d))

    def raw_doors(self) -> bytes:
        d = bytearray(RAW_DLC)
        d[3] = self.doors
        return slcan_frame(ID_DOOR_STATUS, bytes(d))

    # ── The engine ECU (what answers the launcher's 0x7DF) ──
    def obd_reply(self, pid: int) -> bytes | None:
        """The single-frame answer to a service 01 request; None = this car has no such PID."""
        if self.obd is None:
            return None
        if pid == OBD_PID_SPEED:
            a = int(round(self.speed_kmh))
        else:
            key = next((k for k, code in OBD_PIDS.items() if code == pid), None)
            if key is None or key not in self.obd:
                return None
            a = self.obd[key]
        d = bytearray(RAW_DLC)
        d[0] = OBD_PCI_SINGLE
        d[OBD_AT_SERVICE] = OBD_SERVICE_CURRENT + OBD_POSITIVE_OFFSET
        d[OBD_AT_PID] = pid
        d[OBD_AT_VALUE] = a & 0xFF
        return slcan_frame(OBD_ECU_ID, bytes(d))


# ── Transport ────────────────────────────────────────────────────────────────────────────────
class Link:
    """One TCP client to a QEMU chardev (or anything that speaks bytes). Reconnects on start only."""

    def __init__(self, name: str, hostport: str) -> None:
        host, _, port = hostport.rpartition(":")
        self.name = name
        self.addr = (host or "127.0.0.1", int(port))
        self.sock: socket.socket | None = None
        self.lock = threading.Lock()

    def connect(self) -> None:
        for attempt in range(CONNECT_ATTEMPTS):
            try:
                self.sock = socket.create_connection(self.addr, timeout=5)
                self.sock.settimeout(SOCKET_TIMEOUT_S)
                log(f"{self.name}: connected to {self.addr[0]}:{self.addr[1]}")
                return
            except OSError as exc:
                if attempt == CONNECT_ATTEMPTS - 1:
                    raise SystemExit(f"{self.name}: cannot connect to {self.addr}: {exc}")
                time.sleep(CONNECT_RETRY_S)

    def send(self, data: bytes) -> None:
        if self.sock is None:
            return
        with self.lock:
            self.sock.sendall(data)

    def recv(self) -> bytes:
        try:
            return self.sock.recv(4096) if self.sock else b""
        except socket.timeout:
            return b""

    def close(self) -> None:
        if self.sock:
            self.sock.close()
            self.sock = None


class McuSide:
    """The MCU's half of the conversation: ACK every mode, act on the system-key echo."""

    def __init__(self, link: Link, vehicle: Vehicle) -> None:
        self.link = link
        self.vehicle = vehicle
        self.acks = 0
        self.rx_frames = 0
        self._stop = threading.Event()
        self._thread = threading.Thread(target=self._pump, name="mcu-rx", daemon=True)

    def start(self) -> None:
        self._thread.start()

    def stop(self) -> None:
        self._stop.set()

    def send(self, frame: bytes, what: str) -> None:
        log(f"mcu tx {what}: {frame.hex()}")
        self.link.send(frame)

    def _pump(self) -> None:
        buf = bytearray()
        while not self._stop.is_set():
            chunk = self.link.recv()
            if not chunk:
                continue
            buf += chunk
            for opcode, payload in outer_decode(buf):
                self.rx_frames += 1
                self._handle(opcode, payload)

    def _handle(self, opcode: int, payload: bytes) -> None:
        name = TX_OPCODES.get(opcode, f"0x{opcode:02X}")
        log(f"mcu rx {name} {payload.hex()}")
        if opcode == OP_MODE and payload:
            # sendDataWaitAck expects `70 <mode>` within 500 ms (McuOwnerProtocol.isModeAck).
            self.acks += 1
            self.send(outer_encode(RX_MODE_ACK, payload[:1]), f"MODE_ACK {payload[0]:02x}")
            self._mode(payload[0])
            return
        if opcode == OP_SYSTEM_KEY and payload:
            self._system_key(payload[0])
        elif opcode == OP_RADIO_KEY and payload:
            self._radio_key(payload[0])
        elif opcode == OP_USER_FREQ and len(payload) >= 3:
            self._user_freq((payload[0] << 8) | payload[1], payload[2] == 0)

    # ── the tuner ──
    def _mode(self, mode: int) -> None:
        # SRC_RADIO makes the tuner report itself, the way the vendor radio fills its fields
        # right after sendRadioMode; any other source silences it (RadioStateHolder keeps the
        # last values, as the gateway's mRadio* fields do).
        t = self.vehicle.tuner
        if mode == SRC_RADIO:
            t.selected = True
            self._tuner_report()
        elif t.selected:
            t.selected = False
            log(f"tuner released by mode {mode}")

    def _tuner_report(self) -> None:
        for frame, what in self.vehicle.tuner.frames():
            self.send(frame, what)

    def _radio_key(self, key: int) -> None:
        t = self.vehicle.tuner
        if not t.selected:
            log(f"radio key {key} ignored: tuner not selected")
            return
        if key in (RADIO_KEY_SEEK_UP, RADIO_KEY_STEP_UP):
            t.step(+1)
        elif key in (RADIO_KEY_SEEK_DOWN, RADIO_KEY_STEP_DOWN):
            t.step(-1)
        elif key == RADIO_KEY_BAND_FM:
            t.set_band(BAND_FM1)
        elif key == RADIO_KEY_BAND_AM:
            t.set_band(BAND_AM)
        elif key == RADIO_KEY_BAND_CYCLE:
            t.set_band(BAND_AM if t.fm else BAND_FM1)
        elif key == RADIO_KEY_SCAN:
            t.step(+1)
        else:
            log(f"radio key {key}: no tuner action")
            return
        self._tuner_report()

    def _user_freq(self, freq: int, fm: bool) -> None:
        t = self.vehicle.tuner
        t.set_band(BAND_FM1 if fm else BAND_AM)
        t.freq = freq
        self._tuner_report()

    def _system_key(self, code: int) -> None:
        # The launcher echoes VOL+/VOL-/MUTE as `08 xx`; the real MCU moves the amplifier and
        # reports back on `79`/`78` (McuOwner.onPanelKey). Same here, so the chip follows.
        v = self.vehicle
        if code == SYSTEM_KEY_VOLUME_UP:
            v.volume = min(v.volume + 1, VOLUME_MAX)
            self.send(v.main_volume(), f"MAIN_VOLUME {v.volume}")
        elif code == SYSTEM_KEY_VOLUME_DOWN:
            v.volume = max(v.volume - 1, 0)
            self.send(v.main_volume(), f"MAIN_VOLUME {v.volume}")
        elif code == SYSTEM_KEY_MUTE:
            v.muted = not v.muted
            self.send(v.mute(), f"MUTE {v.muted}")


class CanSide:
    """The raw body bus in slcan text, and the engine ECU on it. Optional: without it, replays
    degrade to relay frames and nothing answers OBD."""

    def __init__(self, link: Link | None, vehicle: Vehicle) -> None:
        self.link = link
        self.vehicle = vehicle
        self.frames = 0
        self.requests = 0
        self._stop = threading.Event()
        self._thread = threading.Thread(target=self._pump, name="can-rx", daemon=True)

    @property
    def present(self) -> bool:
        return self.link is not None

    def start(self) -> None:
        if self.link is not None:
            self._thread.start()

    def stop(self) -> None:
        self._stop.set()

    def send(self, line: bytes) -> None:
        if self.link is None:
            return
        self.frames += 1
        self.link.send(line)

    def _pump(self) -> None:
        buf = bytearray()
        while not self._stop.is_set():
            chunk = self.link.recv()
            if not chunk:
                continue
            buf += chunk
            while (end := buf.find(b"\r")) >= 0:
                line, buf = bytes(buf[:end]), buf[end + 1:]
                self._handle(line)

    def _handle(self, line: bytes) -> None:
        """A 0x7DF service 01 request gets the ECU's answer; a PID this car lacks gets silence,
        as on the real bus. Anything else on the wire is the launcher's business, not ours."""
        m = SLCAN_FRAME.fullmatch(line)
        if not m or int(m["id"], 16) != OBD_REQUEST_ID:
            return
        data = bytes.fromhex(m["data"].decode("ascii"))
        if len(data) <= OBD_AT_PID or data[OBD_AT_SERVICE] != OBD_SERVICE_CURRENT:
            return
        self.requests += 1
        pid = data[OBD_AT_PID]
        reply = self.vehicle.obd_reply(pid)
        if reply is None:
            return
        log(f"ecu 0x{pid:02X} -> {reply.decode('ascii').strip()}")
        self.send(reply)


# ── Scenario grammar ─────────────────────────────────────────────────────────────────────────
GRAMMAR = """\
Timeline lines: `<t> <verb> [args]`; t is seconds from start, or `+d` after the previous line.
The tuner needs no lines: `01 01` (SRC_RADIO) from the launcher starts the `73` reports, and
`02 <key>` / `0C <freq>` move it (seek, step, band, direct tune).
  acc on|off                 SYS_EVENT accLine (71)        lamp on|off        illumination bit
  reverse on|off             SYS_EVENT reverse bit         brake on|off       turn left|right|off
  volume <0-40>              MAIN_VOLUME (79)              mute on|off        MUTE (78)
  key <NAME>                 panel key (72): VOL_UP, VOL_DOWN, MUTE, NEXT, PREV, MENU, RETURN, POWER ...
  wheel <NAME>               CAN wheel button press+release (0x11 relay): NEXT, PREV, VOL_UP, CALL ...
  wheelhold <NAME> [ms]      the same key held (0x11 re-sent every 100 ms), default 900 ms: a long press
  wheeldouble <NAME>         two presses 200 ms apart: a double press
  speed <kmh>                held; 0x361 on the bus at 10 Hz, 0x17 + 0x32 relays at 1 Hz
  ramp <from> <to> <secs>    speed ramp, linear
  gear P|R|N|D               0x3BC on the bus, 0x1A relay
  doors <FL|FR|RL|RR|TAIL|HOOD> open|closed     0x4A5 on the bus, 0x11 relay
  climate temp=21 fan=3 ac=on auto=off recirc=on   0x31 relay (re-sent at 1 Hz)
  rpm <n>                    0x32 relay (re-sent at 1 Hz, and the 0x1A mirror)
  radar rear=<a,b,c,d> front=<a,b,c,d>   steps 1-5 (30 cm each), 0 = clear; 0x41 relay
  tpms <fl,fr,rl,rr,spare>   kPa, 0 = no reading; 0x48 relay
  trip range=300 elapsed=95 avg=42 fuel=5.4 best=4.8 unit=L/100km
                             km / min / km/h / fuel figures in unit (km/L, L/100km, MPG(UK), MPG(US));
                             a missing key = no reading; 0x13 relay (re-sent at 1 Hz)
  obd coolant=74 load=23 throttle=15   what the engine ECU answers to the launcher's 0x7DF
                             service 01 requests (°C, %, %); a missing key = unsupported PID,
                             silence; 0x0D follows `speed`; needs --can
  can-replay <file> [speedup] candump lines on the bus; without a bus, known ids become relays
  say <text>                 log a marker
"""

SCENARIOS: dict[str, list[str]] = {
    # Handshake, then one of everything the launcher decodes.
    "smoke": [
        "0.0 say smoke: handshake window",
        "3.0 acc on",
        "4.0 volume 21",
        "5.0 reverse on",
        "12.0 reverse off",
        "10.0 lamp on",
        "11.0 key VOL_UP",
        "12.0 wheel NEXT",
        "13.0 gear D",
        "13.5 ramp 0 43 4",
        "19.0 climate temp=21 fan=3 ac=on",
        "20.0 doors FL open",
        "21.0 doors FL closed",
        "22.0 rpm 1450",
        "23.0 radar rear=0,2,2,0 front=0,0,0,0",
        "24.0 tpms 230,232,228,229,0",
        "24.5 trip range=300 elapsed=95 avg=42 fuel=5.4 best=4.8 unit=L/100km",
        "24.7 obd coolant=74 load=23 throttle=15",
        "25.0 mute on",
        "26.0 mute off",
        "27.0 ramp 43 0 3",
        "30.5 gear P",
        "31.0 lamp off",
        "32.0 say smoke: done",
    ],
    # A drive, start to finish: key on, engine start, door shut, climate set twice, D, a ramp to
    # 60 on both carriers with a cruise long enough to read the Vehicle page, dusk, back to 0,
    # reverse into the spot, P, key off. test_carsim_commute.py asserts against these marks.
    "commute": [
        "0.0 say commute: key on",
        "3.0 acc on",
        "4.0 rpm 900",
        "5.0 doors FL open",
        "7.0 doors FL closed",
        "8.0 climate temp=18 fan=2 ac=on auto=off",
        "14.0 climate temp=22.5 fan=4 auto=on",
        "15.0 gear D",
        "16.0 ramp 0 60 10",
        "18.0 rpm 2100",
        "26.0 say commute: cruising",
        "28.0 lamp on",
        "30.0 wheel VOL_UP",
        "50.0 ramp 60 0 8",
        "58.5 rpm 900",
        "59.0 gear R",
        "59.2 reverse on",
        "60.0 ramp 0 5 2",
        "63.0 ramp 5 0 2",
        "66.0 reverse off",
        "66.2 gear P",
        "72.0 lamp off",
        "73.0 acc off",
        "74.0 say commute: done",
    ],
    # Key on, parked; a long press and a double press on the CAN wheel (RAV4-53). MODE held opens
    # the Media screen by default (WheelGestureBindings.DEFAULT_LONG); test_carsim_gestures.py.
    "gestures": [
        "0.0 say gestures: handshake window",
        "3.0 acc on",
        "4.0 gear P",
        "6.0 wheelhold MODE",
        "9.0 wheeldouble PLAY_PAUSE",
        "11.0 say gestures: done",
    ],
    # Key on, parked; the tuner answers `01 01` with 96.3 MHz "CBC R1" and follows every key.
    # No timeline events beyond the handshake: the launcher drives it (test_carsim_radio.py).
    "radio": [
        "0.0 say radio: handshake window",
        "3.0 acc on",
        "4.0 volume 18",
        "5.0 gear P",
        "6.0 say radio: tuner armed, waiting for SRC_RADIO",
    ],
    # The 2026-09-07 door-cycle capture, as the CANable heard it.
    "replay-door-cycle": [
        "0.0 say replay: door cycle capture",
        "3.0 acc on",
        "4.0 can-replay {capture} {speedup}",
        "+1.0 say replay: done",
    ],
}


@dataclass
class Event:
    at: float
    verb: str
    args: list[str]


def parse_timeline(lines: list[str], subst: dict[str, str]) -> list[Event]:
    events: list[Event] = []
    last = 0.0
    for raw in lines:
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        for key, value in subst.items():
            line = line.replace("{" + key + "}", value)
        when, verb, *args = line.split()
        at = last + float(when[1:]) if when.startswith("+") else float(when)
        events.append(Event(at, verb, args))
        last = at
    return sorted(events, key=lambda e: e.at)


class Simulator:
    def __init__(self, mcu: McuSide, can: CanSide, vehicle: Vehicle, speedup: float) -> None:
        self.mcu = mcu
        self.can = can
        self.v = vehicle
        self.speedup = speedup
        self.ramp: tuple[float, float, float, float] | None = None   # t_start, t_end, from, to
        self.replaying = False   # the capture IS the bus: the ticker's own bus frames pause
        self._stop = threading.Event()

    # ── background: the bus is never silent while the car is on ──
    def ticker(self) -> None:
        last_relay = 0.0
        while not self._stop.is_set():
            now = time.monotonic()
            if self.ramp:
                t0, t1, a, b = self.ramp
                frac = min(max((now - t0) / (t1 - t0), 0.0), 1.0)
                self.v.speed_kmh = a + (b - a) * frac
                if frac >= 1.0:
                    log(f"ramp done at {self.v.speed_kmh:.0f} km/h")
                    self.ramp = None
            if self.v.acc and not self.replaying:
                self.can.send(self.v.raw_speed())
                self.can.send(self.v.raw_gear())
                self.can.send(self.v.raw_doors())
            # The box repeats its status frames; without that the launcher's snapshot expires
            # them (VehicleSnapshot.STALE_AFTER_MS) and the Vehicle page empties mid-drive.
            if self.v.acc and now - last_relay >= RELAY_TICK_S:
                last_relay = now
                self.mcu.link.send(self.v.speed_relay())
                self.mcu.link.send(self.v.gear_relay())
                self.mcu.link.send(self.v.vehicle_info())
                self.mcu.link.send(self.v.climate_relay())
                if self.v.trip:
                    self.mcu.link.send(self.v.trip_relay())
            time.sleep(BUS_TICK_S)

    def run(self, events: list[Event]) -> None:
        tick = threading.Thread(target=self.ticker, name="bus-tick", daemon=True)
        tick.start()
        start = time.monotonic()
        for ev in events:
            due = start + ev.at / self.speedup
            while (delay := due - time.monotonic()) > 0 and not self._stop.is_set():
                time.sleep(min(delay, 0.05))
            if self._stop.is_set():
                break
            try:
                self.apply(ev)
            except (KeyError, ValueError, IndexError) as exc:
                log(f"event '{ev.verb} {' '.join(ev.args)}' refused: {exc!r}")

    def stop(self) -> None:
        self._stop.set()

    # ── one event ──
    def wheel_press(self, name: str, hold_ms: int) -> None:
        """One CAN wheel key held for hold_ms: a held 0x11 every SWC_FRAME_MS, then the release frame."""
        button = SWC_BUTTONS[name]
        down = self.v.basic_status(button, True)
        end = time.monotonic() + hold_ms / 1000
        self.mcu.send(down, f"relay 0x11 wheel {name} down ({hold_ms} ms)")
        while (left := end - time.monotonic()) > 0:
            time.sleep(min(left, SWC_FRAME_MS / 1000))
            if left > SWC_FRAME_MS / 1000:
                self.mcu.link.send(down)
        self.mcu.send(self.v.basic_status(button, False), f"relay 0x11 wheel {name} up")

    def apply(self, ev: Event) -> None:
        v, a = self.v, ev.args
        log(f"event {ev.verb} {' '.join(a)}")
        on = a[0] == "on" if a else False
        if ev.verb == "say":
            return
        if ev.verb in ("acc", "reverse", "lamp", "brake"):
            setattr(v, ev.verb, on)
            self.mcu.send(v.sys_event(), f"SYS_EVENT {ev.verb}={on}")
            if ev.verb == "reverse":
                self.mcu.send(v.relay(CMD_SYS_EVENT, bytes([SYS1_REVERSE if on else 0, 0])), "relay 0x71 reverse")
        elif ev.verb == "turn":
            v.left_turn, v.right_turn = a[0] == "left", a[0] == "right"
            self.mcu.send(v.sys_event(), f"SYS_EVENT turn={a[0]}")
        elif ev.verb == "volume":
            v.volume = int(a[0])
            self.mcu.send(v.main_volume(), f"MAIN_VOLUME {v.volume}")
        elif ev.verb == "mute":
            v.muted = on
            self.mcu.send(v.mute(), f"MUTE {on}")
        elif ev.verb == "key":
            self.mcu.send(v.panel_key(PANEL_KEYS[a[0]]), f"KEY_EVENT {a[0]}")
        elif ev.verb == "wheel":
            self.wheel_press(a[0], SWC_PRESS_MS)
        elif ev.verb == "wheelhold":
            self.wheel_press(a[0], int(a[1]) if len(a) > 1 else SWC_HOLD_MS)
        elif ev.verb == "wheeldouble":
            self.wheel_press(a[0], SWC_PRESS_MS)
            time.sleep(SWC_DOUBLE_GAP_MS / 1000)
            self.wheel_press(a[0], SWC_PRESS_MS)
        elif ev.verb == "speed":
            self.ramp = None
            v.speed_kmh = float(a[0])
            self.mcu.send(v.speed_relay(), f"relay 0x17 speed {v.speed_kmh:.1f}")
        elif ev.verb == "ramp":
            now = time.monotonic()
            self.ramp = (now, now + float(a[2]) / self.speedup, float(a[0]), float(a[1]))
        elif ev.verb == "gear":
            v.gear = a[0].upper()
            self.mcu.send(v.gear_relay(), f"relay 0x1A gear {v.gear}")
            self.can.send(v.raw_gear())
        elif ev.verb == "doors":
            bit = DOOR_BITS[a[0].upper()]
            v.doors = (v.doors | bit) if a[1] == "open" else (v.doors & ~bit)
            self.mcu.send(v.basic_status(), f"relay 0x11 doors=0x{v.doors:02x}")
            self.can.send(v.raw_doors())
        elif ev.verb == "climate":
            for kv in a:
                key, _, val = kv.partition("=")
                if key == "temp":
                    v.climate.temp_c = float(val)
                elif key == "fan":
                    v.climate.fan = int(val)
                else:
                    setattr(v.climate, key, val == "on")
            self.mcu.send(v.climate_relay(), f"relay 0x31 climate {a}")
        elif ev.verb == "rpm":
            v.rpm = int(a[0])
            self.mcu.send(v.vehicle_info(), f"relay 0x32 rpm {v.rpm}")
        elif ev.verb == "radar":
            fields = {k: [int(x) for x in s.split(",")] for k, _, s in (kv.partition("=") for kv in a)}
            self.mcu.send(v.radar_relay(fields.get("rear", [0] * 4), fields.get("front", [0] * 4)), "relay 0x41 radar")
        elif ev.verb == "tpms":
            self.mcu.send(v.tpms_relay([int(x) for x in a[0].split(",")]), "relay 0x48 tpms")
        elif ev.verb == "trip":
            v.trip = {k: trip_value(k, val) for k, _, val in (kv.partition("=") for kv in a)}
            self.mcu.send(v.trip_relay(), f"relay 0x13 trip {v.trip}")
        elif ev.verb == "obd":
            v.obd = {k: obd_value(k, val) for k, _, val in (kv.partition("=") for kv in a)}
            log(f"ecu answers {v.obd}" if self.can.present else "obd: no --can, nothing will ask")
        elif ev.verb == "can-replay":
            self.replay(Path(a[0]), float(a[1]) if len(a) > 1 else 1.0)
        else:
            raise KeyError(f"unknown verb {ev.verb}")

    # ── candump replay ──
    def replay(self, path: Path, speedup: float) -> None:
        sent = converted = 0
        first_ts: float | None = None
        start = time.monotonic()
        self.replaying = True
        with path.open() as f:
            for line in f:
                m = CANDUMP_LINE.match(line)
                if not m:
                    continue
                ts, can_id = float(m["ts"]), int(m["id"], 16)
                data = bytes.fromhex(m["data"].replace(" ", ""))
                if first_ts is None:
                    first_ts = ts
                due = start + (ts - first_ts) / speedup
                while (delay := due - time.monotonic()) > 0:
                    time.sleep(min(delay, 0.05))
                if self._stop.is_set():
                    break
                if self.can.present:
                    self.can.send(slcan_frame(can_id, data))
                    sent += 1
                elif self.convert(can_id, data):
                    converted += 1
        self.replaying = False
        log(f"replay {path.name}: {sent} frames on the bus, {converted} converted to relays")

    def convert(self, can_id: int, data: bytes) -> bool:
        """No bus channel: turn the ids RawCanDecoder knows into the HiWorld relay the MCU would send."""
        if len(data) < RAW_DLC:
            return False
        v = self.v
        if can_id == ID_SPEED_FAST:
            v.speed_kmh = float(data[6])
            self.mcu.link.send(v.speed_relay())
        elif can_id == ID_GEAR:
            v.gear = "P" if data[1] & GEAR_P_BIT else "R" if data[1] & GEAR_R_BIT else "D" if data[5] & GEAR_D_BIT else "N"
            self.mcu.link.send(v.gear_relay())
        elif can_id == ID_DOOR_STATUS:
            if data[3] != v.doors:
                v.doors = data[3]
                self.mcu.send(v.basic_status(), f"relay 0x11 doors=0x{v.doors:02x} (from 0x4A5)")
        else:
            return False
        return True


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0], epilog=GRAMMAR,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("scenario", help="smoke | commute | radio | replay-door-cycle | a timeline file")
    ap.add_argument("--mcu", default="127.0.0.1:5590", help="host:port of the MCU carrier (default %(default)s)")
    ap.add_argument("--can", default="", help="host:port of the raw-bus carrier; omit to convert replays into relays")
    ap.add_argument("--capture", default="", help="candump file for {capture} in a scenario")
    ap.add_argument("--speedup", type=float, default=1.0, help="timeline and replay speed factor")
    ap.add_argument("--log", default="", help="log file (default stderr)")
    ap.add_argument("--hold", type=float, default=-1, help="seconds to keep the bus alive after the last event; <0 = until killed")
    args = ap.parse_args()

    global _log_file
    if args.log:
        _log_file = open(args.log, "a", buffering=1)

    if args.scenario in SCENARIOS:
        lines = SCENARIOS[args.scenario]
    else:
        lines = Path(args.scenario).read_text().splitlines()
    events = parse_timeline(lines, {"capture": args.capture, "speedup": str(args.speedup)})

    vehicle = Vehicle()
    mcu_link = Link("mcu", args.mcu)
    mcu_link.connect()
    can_link = None
    if args.can:
        can_link = Link("can", args.can)
        can_link.connect()

    mcu = McuSide(mcu_link, vehicle)
    can = CanSide(can_link, vehicle)
    sim = Simulator(mcu, can, vehicle, args.speedup)
    mcu.start()
    can.start()
    log(f"scenario {args.scenario}: {len(events)} events, bus={'yes' if can.present else 'converted relays'}")
    try:
        sim.run(events)
        log(f"timeline done: mcu rx={mcu.rx_frames} acks={mcu.acks} bus frames={can.frames} obd requests={can.requests}")
        if args.hold < 0:
            while True:
                time.sleep(1)
        time.sleep(args.hold)
    except KeyboardInterrupt:
        pass
    finally:
        sim.stop()
        mcu.stop()
        can.stop()
        mcu_link.close()
        if can_link:
            can_link.close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
