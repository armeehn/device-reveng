#!/usr/bin/env python3
"""Is this emulator's framebuffer readable, or does it capture black?

    capture-probe.py <screencap.png> <dir holding shots.py>

Exit 0 when the picture carries colour, 1 when it is blank or unreadable. The caller uses
that to decide whether a screenshot baseline means anything on this image — see
launcher-e2e.sh, and the black frames of launcher-ci runs 5191 and 5202.

shots.py's decoder is reused rather than repeated: it is the same decoder the baseline
diff uses, so "readable here" means "readable there".
"""

import os
import sys

EXIT_COLOUR = 0
EXIT_BLANK = 1


def main(argv):
    path, shots_dir = argv[1], argv[2]

    if not os.path.exists(path) or os.path.getsize(path) == 0:
        print("capture probe: adb screencap produced nothing")
        return EXIT_BLANK

    sys.path.insert(0, shots_dir)
    import shots

    image = shots.read_png(path)
    # Colour channels only: a screencap comes back RGBA, and its alpha channel is 255
    # everywhere even when the picture itself is entirely black.
    colour = 0
    for start in range(0, len(image.data), image.channels):
        if any(image.data[start:start + min(3, image.channels)]):
            colour += 1

    pixels = image.width * image.height
    print("capture probe: %dx%d, %d of %d pixels carry colour" % (
        image.width, image.height, colour, pixels))

    if colour == 0:
        print("capture probe: the framebuffer reads back black on this image")
        return EXIT_BLANK

    return EXIT_COLOUR


if __name__ == "__main__":
    sys.exit(main(sys.argv))
