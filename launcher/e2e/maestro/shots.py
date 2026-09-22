#!/usr/bin/env python3
"""Screenshot baseline diffing for the launcher's Maestro flows.

    shots.py accept <run-dir> <baseline-dir>    bless this run as the baseline
    shots.py check  <run-dir> <baseline-dir>    exit 1 if any shot drifted

A run directory is what `headunit maestro` leaves behind: one folder per flow,
each holding takeScreenshot/<name>.png. A baseline is flat, one file per shot
named <flow-slug>__<name>.png, so renaming a flow reads as NEW + MISSING rather
than as a silent pass.

THE CLOCK. The status bar draws a clock that reads differently on every run, so
a full-frame compare of any screen carrying the bar would drift every time.
Rectangles listed as masks are cut out of the compare (and out of the pixel
count the percentage is taken over). The default mask is the clock's corner of
the status bar, CLOCK_MASK, measured on the panel's 1920x720 @240dpi geometry.
  --mask X,Y,W,H  replaces the default, repeatable
  --no-mask       compares the whole frame
  --ignore REGEX  drops whole shots by name, repeatable

No third-party modules: the farm and CI both run a bare python3, so the PNG
decoder below is part of the tool (8-bit, non-interlaced, which is what the
emulator's screencap writes).
"""

import argparse
import os
import re
import shutil
import struct
import sys
import zlib

PNG_SIGNATURE = b"\x89PNG\r\n\x1a\n"
PNG_SUFFIX = ".png"
SHOT_DIR_NAME = "takeScreenshot"
BASELINE_SEPARATOR = "__"

# PNG colour type -> bytes per pixel. Greyscale and palette-free colour only;
# the alpha channel is decoded but never compared (screenshots are opaque).
COLOUR_TYPE_CHANNELS = {0: 1, 2: 3, 4: 2, 6: 4}
SUPPORTED_BIT_DEPTH = 8
COMPARED_CHANNELS = 3

# A channel has to move more than this to count as a changed pixel. The
# emulator's GL path dithers a gradient by a step or two between runs.
DEFAULT_TOLERANCE = 8

# And more than this percentage of the compared pixels has to change before the
# shot counts as drift. Small, because real layout drift moves whole rows.
DEFAULT_THRESHOLD = 0.20

# The head unit's panel, and the clock's rectangle inside its status bar.
# Measured by diffing two runs of the same build: the clock glyphs at
# x 230-287, y 28-67 are the only thing that moves on a settled screen.
# Padded on every side so a wider reading (1:07 PM) still fits inside it.
PANEL_WIDTH = 1920
PANEL_HEIGHT = 720
CLOCK_MASK = (216, 16, 128, 56)

VERDICT_OK = "OK"
VERDICT_DRIFT = "DRIFT"
VERDICT_NEW = "NEW"
VERDICT_MISSING = "MISSING"
VERDICT_SIZE = "SIZE"

EXIT_OK = 0
EXIT_DRIFT = 1


class Image:
    """A decoded 8-bit raster: `data` is height * width * channels bytes."""

    def __init__(self, width, height, channels, data):
        self.width = width
        self.height = height
        self.channels = channels
        self.data = data

    @property
    def stride(self):
        return self.width * self.channels


def _paeth(a, b, c):
    """PNG's Paeth predictor: pick whichever neighbour the gradient points at."""
    p = a + b - c
    pa, pb, pc = abs(p - a), abs(p - b), abs(p - c)

    if pa <= pb and pa <= pc:
        return a
    if pb <= pc:
        return b
    return c


def _unfilter(raw, width, height, channels):
    """Undo the per-scanline filters, yielding raw pixel bytes."""
    stride = width * channels
    out = bytearray(stride * height)
    prev = bytearray(stride)
    pos = 0

    for y in range(height):
        filter_type = raw[pos]
        line = bytearray(raw[pos + 1:pos + 1 + stride])
        pos += 1 + stride

        if filter_type == 1:  # Sub
            for i in range(channels, stride):
                line[i] = (line[i] + line[i - channels]) & 0xFF
        elif filter_type == 2:  # Up
            for i in range(stride):
                line[i] = (line[i] + prev[i]) & 0xFF
        elif filter_type == 3:  # Average
            for i in range(stride):
                left = line[i - channels] if i >= channels else 0
                line[i] = (line[i] + ((left + prev[i]) >> 1)) & 0xFF
        elif filter_type == 4:  # Paeth
            for i in range(stride):
                left = line[i - channels] if i >= channels else 0
                upper_left = prev[i - channels] if i >= channels else 0
                line[i] = (line[i] + _paeth(left, prev[i], upper_left)) & 0xFF
        elif filter_type != 0:
            raise ValueError("unknown PNG filter type %d on row %d" % (filter_type, y))

        out[y * stride:(y + 1) * stride] = line
        prev = line

    return out


def read_png(path):
    """Decode one 8-bit, non-interlaced PNG into an Image."""
    with open(path, "rb") as handle:
        blob = handle.read()

    if not blob.startswith(PNG_SIGNATURE):
        raise ValueError("%s is not a PNG" % path)

    width = height = channels = None
    idat = []
    pos = len(PNG_SIGNATURE)

    # Walk the chunk list. Only IHDR and IDAT carry anything we need; the
    # colour profile and text chunks in an emulator capture are noise here.
    while pos < len(blob):
        length, kind = struct.unpack(">I4s", blob[pos:pos + 8])
        body = blob[pos + 8:pos + 8 + length]
        pos += 12 + length  # 4 length + 4 kind + body + 4 CRC

        if kind == b"IHDR":
            width, height, depth, colour, _, _, interlace = struct.unpack(">IIBBBBB", body)
            if depth != SUPPORTED_BIT_DEPTH:
                raise ValueError("%s: only %d-bit PNGs are supported" % (path, SUPPORTED_BIT_DEPTH))
            if interlace:
                raise ValueError("%s: interlaced PNGs are not supported" % path)
            channels = COLOUR_TYPE_CHANNELS.get(colour)
            if channels is None:
                raise ValueError("%s: unsupported PNG colour type %d" % (path, colour))
        elif kind == b"IDAT":
            idat.append(body)
        elif kind == b"IEND":
            break

    if width is None:
        raise ValueError("%s has no IHDR" % path)

    raw = zlib.decompress(b"".join(idat))

    return Image(width, height, channels, _unfilter(raw, width, height, channels))


def masked_columns(masks, y, width):
    """Column indices hidden from the compare on row `y`."""
    columns = set()

    for mask_x, mask_y, mask_w, mask_h in masks:
        if not mask_y <= y < mask_y + mask_h:
            continue
        columns.update(range(max(0, mask_x), min(width, mask_x + mask_w)))

    return columns


def diff_percent(current, baseline, tolerance, masks):
    """Percentage of unmasked pixels whose colour moved past the tolerance."""
    width, height = current.width, current.height
    channels = min(current.channels, COMPARED_CHANNELS)
    stride = current.stride
    changed = 0
    compared = 0

    for y in range(height):
        columns = masked_columns(masks, y, width)
        compared += width - len(columns)

        start = y * stride
        row_a = current.data[start:start + stride]
        row_b = baseline.data[start:start + stride]

        # Most rows of a stable screen are byte-identical; skip the pixel loop.
        if row_a == row_b:
            continue

        for x in range(width):
            if x in columns:
                continue
            offset = x * current.channels
            for c in range(channels):
                if abs(row_a[offset + c] - row_b[offset + c]) > tolerance:
                    changed += 1
                    break

    if compared == 0:
        return 0.0

    return 100.0 * changed / compared


def slug(text):
    """Flow folder name -> a filename-safe token ('Home comes up' -> 'home-comes-up')."""
    return re.sub(r"-+", "-", re.sub(r"[^a-z0-9]+", "-", text.lower())).strip("-")


def collect_shots(run_dir):
    """Map <flow-slug>__<name> -> PNG path for every takeScreenshot in a run."""
    shots = {}

    for flow in sorted(os.listdir(run_dir)):
        shot_dir = os.path.join(run_dir, flow, SHOT_DIR_NAME)
        if not os.path.isdir(shot_dir):
            continue

        for shot in sorted(os.listdir(shot_dir)):
            if not shot.endswith(PNG_SUFFIX):
                continue
            name = slug(flow) + BASELINE_SEPARATOR + shot[:-len(PNG_SUFFIX)]
            shots[name] = os.path.join(shot_dir, shot)

    return shots


def collect_baselines(baseline_dir):
    """Map shot name -> baseline PNG path."""
    if not os.path.isdir(baseline_dir):
        return {}

    return {
        f[:-len(PNG_SUFFIX)]: os.path.join(baseline_dir, f)
        for f in sorted(os.listdir(baseline_dir))
        if f.endswith(PNG_SUFFIX)
    }


def is_ignored(name, patterns):
    return any(pattern.search(name) for pattern in patterns)


def parse_mask(text):
    """'X,Y,W,H' -> a rectangle tuple."""
    parts = text.split(",")
    if len(parts) != 4:
        raise argparse.ArgumentTypeError("mask must be X,Y,W,H — got %r" % text)

    try:
        rect = tuple(int(p) for p in parts)
    except ValueError:
        raise argparse.ArgumentTypeError("mask must be four integers — got %r" % text)

    if rect[2] <= 0 or rect[3] <= 0:
        raise argparse.ArgumentTypeError("mask width and height must be positive — got %r" % text)

    return rect


def cmd_accept(args):
    """Copy a run's screenshots over the baseline, one flat file per shot."""
    shots = collect_shots(args.run_dir)
    if not shots:
        print("no screenshots under %s" % args.run_dir, file=sys.stderr)
        return EXIT_DRIFT

    os.makedirs(args.baseline_dir, exist_ok=True)
    patterns = [re.compile(p) for p in args.ignore]

    for name in sorted(shots):
        if is_ignored(name, patterns):
            print("%-60s skipped" % name)
            continue
        shutil.copyfile(shots[name], os.path.join(args.baseline_dir, name + PNG_SUFFIX))
        print("%-60s accepted" % name)

    return EXIT_OK


def cmd_check(args):
    """Compare a run against the baseline; one line per shot, exit 1 on drift."""
    masks = [] if args.no_mask else (args.mask or [CLOCK_MASK])
    patterns = [re.compile(p) for p in args.ignore]

    shots = collect_shots(args.run_dir)
    baselines = collect_baselines(args.baseline_dir)
    failed = False

    for name in sorted(set(shots) | set(baselines)):
        if is_ignored(name, patterns):
            continue

        # A shot the baseline has never seen is news, not a failure: it is what
        # a brand new flow looks like before anyone has run accept.
        if name not in baselines:
            print("%-60s %-8s" % (name, VERDICT_NEW))
            continue

        # A baseline with no shot is a failure: the flow that used to take it
        # either broke or stopped taking it, and both are worth a red run.
        if name not in shots:
            print("%-60s %-8s" % (name, VERDICT_MISSING))
            failed = True
            continue

        current = read_png(shots[name])
        baseline = read_png(baselines[name])

        if (current.width, current.height) != (baseline.width, baseline.height):
            print("%-60s %-8s %dx%d, baseline %dx%d" % (
                name, VERDICT_SIZE, current.width, current.height,
                baseline.width, baseline.height))
            failed = True
            continue

        percent = diff_percent(current, baseline, args.tolerance, masks)
        drifted = percent > args.threshold
        failed = failed or drifted

        print("%-60s %-8s %6.3f%%" % (name, VERDICT_DRIFT if drifted else VERDICT_OK, percent))

    return EXIT_DRIFT if failed else EXIT_OK


def build_parser():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    subparsers = parser.add_subparsers(dest="command", required=True)

    accept = subparsers.add_parser("accept", help="copy a run's screenshots into the baseline")
    accept.add_argument("run_dir")
    accept.add_argument("baseline_dir")
    accept.add_argument("--ignore", action="append", default=[], metavar="REGEX",
                        help="skip shots whose name matches (repeatable)")
    accept.set_defaults(handler=cmd_accept)

    check = subparsers.add_parser("check", help="compare a run against the baseline")
    check.add_argument("run_dir")
    check.add_argument("baseline_dir")
    check.add_argument("--tolerance", type=int, default=DEFAULT_TOLERANCE,
                       help="per-channel wobble ignored, 0-255 (default: %(default)s)")
    check.add_argument("--threshold", type=float, default=DEFAULT_THRESHOLD,
                       help="percent of pixels allowed to differ (default: %(default)s)")
    check.add_argument("--ignore", action="append", default=[], metavar="REGEX",
                       help="skip shots whose name matches (repeatable)")
    check.add_argument("--mask", action="append", type=parse_mask, metavar="X,Y,W,H",
                       help="rectangle cut out of the compare, replacing the clock mask")
    check.add_argument("--no-mask", action="store_true",
                       help="compare the whole frame, clock included")
    check.set_defaults(handler=cmd_check)

    return parser


def main(argv=None):
    args = build_parser().parse_args(argv)

    return args.handler(args)


if __name__ == "__main__":
    sys.exit(main())
