"""MicroPython entry point: WiFi, pins, and a very small HTTP server around `board.py`.

Runs on any MicroPython board with WiFi (ESP32 is the one the docs assume). Copy `board.py`,
`main.py` and a `config.json` to the device; MicroPython runs `main.py` at boot.

    config.json ─▶ outputs on pins ─▶ Board ─▶ socket server on :port
                                                    │
                       wlan watched every few seconds, reconnected when it drops

Answer fast, then move: the launcher waits at most 1 s for a reply and a sequence step's hold
does not start until it has one. Nothing here blocks on the hardware.
"""
import json
import socket
import time

import machine
import network

import board

CONFIG_PATH = "config.json"
ACCEPT_TIMEOUT_S = 2.0
WIFI_CHECK_EVERY_S = 5
WIFI_JOIN_WAIT_S = 20

PWM_FREQ_HZ = 1000
SERVO_FREQ_HZ = 50
SERVO_PERIOD_US = 20000
DUTY_FULL = 65535


class SwitchOut(board.Output):
    """A GPIO, optionally inverted for a low-side driver that is on when the pin is low."""

    def __init__(self, pin, invert=False):
        board.Output.__init__(self, has_level=False)
        self.pin = machine.Pin(pin, machine.Pin.OUT)
        self.invert = invert
        self._drive()

    def set(self, power, level):
        board.Output.set(self, power, level)
        self._drive()
        return self.power, self.level

    def _drive(self):
        on = self.power == "on"
        self.pin.value((not on) if self.invert else on)


class LevelOut(board.Output):
    """PWM brightness. Off is duty 0 whatever the level, so a light can be dimmed then switched."""

    def __init__(self, pin, freq=PWM_FREQ_HZ):
        board.Output.__init__(self, has_level=True)
        self.pwm = machine.PWM(machine.Pin(pin), freq=freq, duty_u16=0)

    def set(self, power, level):
        board.Output.set(self, power, level)
        duty = self.level * DUTY_FULL // board.LEVEL_MAX if self.power == "on" else 0
        self.pwm.duty_u16(duty)
        return self.power, self.level


class ServoOut(board.Output):
    """A hobby servo. Level 0..100 sweeps min_us..max_us; off releases the signal."""

    def __init__(self, pin, min_us=500, max_us=2500):
        board.Output.__init__(self, has_level=True)
        self.min_us = min_us
        self.max_us = max_us
        self.pwm = machine.PWM(machine.Pin(pin), freq=SERVO_FREQ_HZ, duty_u16=0)

    def set(self, power, level):
        board.Output.set(self, power, level)
        if self.power != "on":
            self.pwm.duty_u16(0)
            return self.power, self.level
        pulse_us = self.min_us + (self.max_us - self.min_us) * self.level // board.LEVEL_MAX
        self.pwm.duty_u16(pulse_us * DUTY_FULL // SERVO_PERIOD_US)
        return self.power, self.level


KINDS = {
    "switch": lambda c: SwitchOut(c["pin"], c.get("invert", False)),
    "level": lambda c: LevelOut(c["pin"], c.get("freq", PWM_FREQ_HZ)),
    "servo": lambda c: ServoOut(c["pin"], c.get("min_us", 500), c.get("max_us", 2500)),
}


def load_config():
    with open(CONFIG_PATH) as f:
        return json.load(f)


def build_outputs(cfg):
    outputs = {}
    for ident, spec in cfg["accessories"].items():
        outputs[ident] = KINDS[spec["kind"]](spec)
    return outputs


def join_wifi(cfg):
    wlan = network.WLAN(network.STA_IF)
    wlan.active(True)
    if not wlan.isconnected():
        wlan.connect(cfg["wifi"]["ssid"], cfg["wifi"]["password"])
        deadline = time.time() + WIFI_JOIN_WAIT_S
        while not wlan.isconnected() and time.time() < deadline:
            time.sleep(0.5)
    print("wifi", "up" if wlan.isconnected() else "DOWN", wlan.ifconfig()[0] if wlan.isconnected() else "")
    return wlan


def main():
    cfg = load_config()
    brd = board.Board(build_outputs(cfg))
    wlan = join_wifi(cfg)

    srv = socket.socket()
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind(("0.0.0.0", cfg.get("port", 8765)))
    srv.listen(2)
    srv.settimeout(ACCEPT_TIMEOUT_S)
    print("accessory board on :%d  %s" % (cfg.get("port", 8765), ", ".join(brd.outputs)))

    last_check = time.time()
    while True:
        try:
            conn, _ = srv.accept()
            board.serve_connection(conn, brd)
        except OSError:
            pass

        # The car's WiFi comes and goes with ignition; rejoin quietly, keep the pins as they are.
        if time.time() - last_check >= WIFI_CHECK_EVERY_S:
            last_check = time.time()
            if not wlan.isconnected():
                join_wifi(cfg)


main()
