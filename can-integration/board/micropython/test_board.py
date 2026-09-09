"""The contract, pinned from the board's side. Run: python3 -m unittest -v (in this directory).

The launcher's transport tests are the authority on what it accepts; these make sure the
firmware never sends it anything else. The negative controls are the point: a driver that does
nothing must produce a reply the launcher reads as a refusal, and a reply must never describe a
state the pin is not in.
"""
import json
import unittest

import board


class Fake(board.Output):
    """Records every set; `reach` limits what it admits to, like a servo against its stop."""

    def __init__(self, has_level, reach=None):
        board.Output.__init__(self, has_level)
        self.reach = reach
        self.calls = []

    def set(self, power, level):
        self.calls.append((power, level))
        if self.reach is not None and level is not None and level > self.reach:
            level = self.reach
        return board.Output.set(self, power, level)


class Stuck(board.Output):
    """The --lie board as hardware: hears everything, changes nothing."""

    def set(self, power, level):
        return self.power, self.level


def make():
    return board.Board({"bar": Fake(False), "spot": Fake(True), "antenna": Fake(True, reach=60)})


def post(brd, ident, obj):
    return brd.handle("POST", "/set/" + ident, json.dumps(obj).encode())


class ContractTest(unittest.TestCase):

    def test_state_is_power_and_level_only(self):
        brd = make()
        self.assertEqual((200, {"power": "off"}), brd.handle("GET", "/state/bar", b""))
        self.assertEqual((200, {"power": "off", "level": 0}), brd.handle("GET", "/state/spot", b""))

    def test_set_replies_with_the_state_as_it_now_is(self):
        brd = make()
        self.assertEqual((200, {"power": "on"}), post(brd, "bar", {"power": "on"}))
        self.assertEqual((200, {"power": "on", "level": 42}), post(brd, "spot", {"level": 42}))
        self.assertEqual([("on", 42)], brd.outputs["spot"].calls)

    def test_a_level_of_zero_is_off_and_a_level_above_is_on(self):
        brd = make()
        self.assertEqual((200, {"power": "off", "level": 0}), post(brd, "spot", {"level": 0}))
        self.assertEqual((200, {"power": "on", "level": 1}), post(brd, "spot", {"level": 1}))

    def test_extra_keys_are_ignored(self):
        brd = make()
        self.assertEqual((200, {"power": "on"}), post(brd, "bar", {"power": "on", "uptime_s": 9}))

    def test_request_parser_waits_for_the_whole_body(self):
        head = b"POST /set/bar HTTP/1.1\r\nHost: x\r\nContent-Length: 14\r\n\r\n"
        self.assertIsNone(board.parse_request(head[:10]))
        self.assertIsNone(board.parse_request(head + b'{"power":'))
        self.assertEqual(("POST", "/set/bar", b'{"power":"on"}'), board.parse_request(head + b'{"power":"on"}'))
        self.assertEqual(("GET", "/state/bar", b""), board.parse_request(b"GET /state/bar HTTP/1.1\r\n\r\n"))

    def test_render_is_a_closed_json_reply(self):
        reply = board.render(200, {"power": "on"})
        self.assertTrue(reply.startswith(b"HTTP/1.0 200 OK\r\n"))
        self.assertIn(b"Content-Type: application/json\r\n", reply)
        self.assertIn(b"Connection: close\r\n", reply)
        self.assertTrue(reply.endswith(b'\r\n\r\n{"power": "on"}'))
        self.assertEqual(b"HTTP/1.0 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n", board.render(404, None))

    # -- Negative controls ------------------------------------------------------------------------

    def test_unknown_accessory_is_404_and_touches_nothing(self):
        brd = make()
        self.assertEqual((404, None), brd.handle("GET", "/state/nope", b""))
        self.assertEqual((404, None), post(brd, "nope", {"power": "on"}))
        self.assertEqual((404, None), brd.handle("GET", "/other", b""))
        self.assertEqual([], brd.outputs["bar"].calls)

    def test_power_must_be_the_literal_words(self):
        brd = make()
        for bad in ("ON", "1", True, 1, None):
            self.assertEqual((422, None), post(brd, "bar", {"power": bad}), repr(bad))
        self.assertEqual([], brd.outputs["bar"].calls)

    def test_level_on_a_switch_or_out_of_range_is_refused_untouched(self):
        brd = make()
        self.assertEqual((422, None), post(brd, "bar", {"level": 50}))
        for bad in (-1, 101, 4.2, "50", True):
            self.assertEqual((422, None), post(brd, "spot", {"level": bad}), repr(bad))
        self.assertEqual((422, None), post(brd, "spot", {"power": "on", "level": 101}))
        self.assertEqual([], brd.outputs["spot"].calls)

    def test_malformed_body_is_400(self):
        brd = make()
        self.assertEqual((400, None), brd.handle("POST", "/set/bar", b"{power:on"))
        self.assertEqual((400, None), brd.handle("POST", "/set/bar", b"[1]"))
        self.assertEqual((200, {"power": "off"}), brd.handle("POST", "/set/bar", b""))

    def test_a_servo_against_its_stop_reports_where_it_is(self):
        # The launcher compares the reply with what it asked for; 60 != 90 reads as refused.
        brd = make()
        self.assertEqual((200, {"power": "on", "level": 60}), post(brd, "antenna", {"level": 90}))

    def test_hardware_that_changes_nothing_cannot_produce_a_confirming_reply(self):
        brd = board.Board({"bar": Stuck(False)})
        self.assertEqual((200, {"power": "off"}), post(brd, "bar", {"power": "on"}))


if __name__ == "__main__":
    unittest.main()
