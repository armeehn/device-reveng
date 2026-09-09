"""The accessory board contract, with no hardware in it.

Everything the launcher can observe over the wire is decided here: which paths exist, what a
state looks like, which requests are refused and with what code. `main.py` gives this a WiFi
stack and real pins; this file runs unchanged under CPython, which is where it is tested.

    request bytes ─▶ parse_request ─▶ Board.handle ─▶ (code, state) ─▶ render ─▶ reply bytes
                                          │
                                   Output.set(power, level) ─▶ the pin, or a fake in the tests

The one rule that matters, from ACCESSORY_BOARD.md: the reply carries the state as it now IS,
never as it was asked to be. An Output reports what it actually did; a servo that cannot reach a
position reports the one it holds, and the launcher treats that as a refusal.

Plain MicroPython-compatible Python: no f-strings with `=`, no dataclasses, no typing.
"""
import json

RECV_CHUNK = 512
CLIENT_TIMEOUT_S = 1.0

OK = 200
BAD_REQUEST = 400
NOT_FOUND = 404
UNPROCESSABLE = 422

POWER_WORDS = ("on", "off")
LEVEL_MIN = 0
LEVEL_MAX = 100

STATE_PREFIX = "/state/"
SET_PREFIX = "/set/"

REASONS = {OK: "OK", BAD_REQUEST: "Bad Request", NOT_FOUND: "Not Found", UNPROCESSABLE: "Unprocessable"}


class Output:
    """One accessory as the board sees it. Subclasses drive a pin; this base drives nothing.

    `set` returns the state actually reached, as (power, level). `level` is None for a switch.
    """

    def __init__(self, has_level):
        self.has_level = has_level
        self.power = "off"
        self.level = LEVEL_MIN if has_level else None

    def set(self, power, level):
        self.power = power
        if self.has_level:
            self.level = level
        return self.power, self.level

    def state(self):
        body = {"power": self.power}
        if self.has_level:
            body["level"] = self.level
        return body


class Board:
    def __init__(self, outputs):
        """`outputs` is {id: Output}."""
        self.outputs = outputs

    def handle(self, method, path, body):
        """One request in, (status code, reply dict or None) out. Never raises on bad input."""
        if method == "GET" and path.startswith(STATE_PREFIX):
            out = self.outputs.get(path[len(STATE_PREFIX):])
            if out is None:
                return NOT_FOUND, None
            return OK, out.state()

        if method == "POST" and path.startswith(SET_PREFIX):
            out = self.outputs.get(path[len(SET_PREFIX):])
            if out is None:
                return NOT_FOUND, None
            return self._set(out, body)

        return NOT_FOUND, None

    def _set(self, out, body):
        # Decode first: MicroPython's json.loads wants text, and a body that is not UTF-8 is
        # as malformed as one that is not JSON.
        try:
            req = json.loads((body or b"{}").decode("utf-8"))
        except (ValueError, UnicodeError):
            return BAD_REQUEST, None
        if not isinstance(req, dict):
            return BAD_REQUEST, None

        power = out.power
        level = out.level

        # Validate everything before touching the pin: a half-applied request is worse than a
        # refused one, because the reply could then only describe a state nobody asked for.
        if "power" in req:
            if req["power"] not in POWER_WORDS:
                return UNPROCESSABLE, None
            power = req["power"]
        if "level" in req:
            wanted = req["level"]
            if not out.has_level or isinstance(wanted, bool) or not isinstance(wanted, int):
                return UNPROCESSABLE, None
            if not LEVEL_MIN <= wanted <= LEVEL_MAX:
                return UNPROCESSABLE, None
            level = wanted
            if "power" not in req:
                power = "on" if wanted > LEVEL_MIN else "off"

        out.set(power, level)
        return OK, out.state()


def parse_request(raw):
    """Split one HTTP request into (method, path, body), or None while bytes are still missing.

    Only what the launcher sends is understood: a request line, headers, and a body sized by
    Content-Length. Chunked bodies do not occur and are not attempted.
    """
    head_end = raw.find(b"\r\n\r\n")
    if head_end < 0:
        return None

    head = raw[:head_end].decode("utf-8", "ignore")
    lines = head.split("\r\n")
    parts = lines[0].split(" ")
    if len(parts) < 2:
        return "", "", b""
    method, path = parts[0], parts[1]

    length = 0
    for line in lines[1:]:
        name, _, value = line.partition(":")
        if name.strip().lower() == "content-length":
            try:
                length = int(value.strip())
            except ValueError:
                length = 0

    body_start = head_end + 4
    if len(raw) < body_start + length:
        return None
    return method, path, raw[body_start:body_start + length]


def render(code, body):
    """The reply bytes for a (code, dict-or-None) pair. Always closes the connection."""
    data = json.dumps(body).encode() if body is not None else b""
    head = "HTTP/1.0 %d %s\r\n" % (code, REASONS.get(code, "Error"))
    if body is not None:
        head += "Content-Type: application/json\r\n"
    head += "Content-Length: %d\r\nConnection: close\r\n\r\n" % len(data)
    return head.encode() + data


def serve_connection(conn, brd, log=print):
    """Answer one accepted connection, then close it. Never raises: a bad client costs nothing.

    Bytes are read until parse_request has a whole request; the launcher's 1 s budget is also
    the client timeout, so a stalled sender is dropped rather than waited for.
    """
    conn.settimeout(CLIENT_TIMEOUT_S)
    raw = b""
    try:
        while True:
            chunk = conn.recv(RECV_CHUNK)
            if not chunk:
                break
            raw += chunk
            parsed = parse_request(raw)
            if parsed is None:
                continue
            method, path, body = parsed
            code, reply = brd.handle(method, path, body)
            conn.send(render(code, reply))
            log(method, path, code, reply)
            break
    except OSError:
        pass
    finally:
        conn.close()
