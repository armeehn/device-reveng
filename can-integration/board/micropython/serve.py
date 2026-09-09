#!/usr/bin/env python3
"""The firmware's brain on a desk: board.py behind a CPython socket, pins replaced by print.

    python3 serve.py --port 8766 bar:switch spot:level antenna:servo

Same accessory specs as tools/virtual-board.py, same contract, but this is the real firmware's
code path: board.parse_request, Board.handle, board.render and serve_connection are the bytes
the chip will send. Use it to drive the launcher before the ESP32 arrives, and to prove the two
reference implementations agree (test_wire.py does exactly that).
"""
import argparse
import socket
import sys

import board

KINDS = {"switch": False, "level": True, "servo": True}


class Printed(board.Output):
    """An Output that reports what a pin would do instead of doing it."""

    def __init__(self, ident, has_level):
        board.Output.__init__(self, has_level)
        self.ident = ident

    def set(self, power, level):
        board.Output.set(self, power, level)
        print("%s -> %s %s" % (self.ident, self.power, "" if self.level is None else self.level), flush=True)
        return self.power, self.level


def parse_specs(specs):
    outputs = {}
    for spec in specs:
        ident, _, kind = spec.partition(":")
        if kind not in KINDS:
            sys.exit("bad accessory '%s': want id:switch, id:level or id:servo" % spec)
        outputs[ident] = Printed(ident, KINDS[kind])
    return outputs


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--port", type=int, default=8766)
    ap.add_argument("accessories", nargs="+")
    args = ap.parse_args()

    brd = board.Board(parse_specs(args.accessories))
    srv = socket.socket()
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind(("0.0.0.0", args.port))
    srv.listen(2)
    print("firmware board on :%d  %s" % (args.port, ", ".join(brd.outputs)), flush=True)
    try:
        while True:
            conn, _ = srv.accept()
            board.serve_connection(conn, brd)
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
