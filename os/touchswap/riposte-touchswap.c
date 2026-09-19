/*
 * riposte-touchswap: give the AOSP framework the touch panel's real axis ranges.
 *
 * The Goodix chip reports X across the 1920 px width scaled 0..720 and Y down the 720 px
 * height scaled 0..1920 (corner taps on the bench, 2026-09-19: top-left (19, 189),
 * bottom-right (697, 1800)), while the kernel driver advertises X max 1920 and Y max 720.
 * The vendor's own framework coped; AOSP scales by the advertised ranges, so every touch
 * lands in the left third with Y clamped. The chip ignores config writes and an IDC cannot
 * change a range, so this grabs the driver's device and re-emits every event unchanged on a
 * uinput touchscreen whose X and Y ranges are the ones the chip actually uses.
 *
 *     /dev/input/eventN "TouchScreenZXW" ──EVIOCGRAB──▶ same events ──▶ /dev/uinput "Riposte Touch"
 *                       X 0..1920, Y 0..720 advertised                    X 0..720, Y 0..1920
 *
 * Usage: riposte-touchswap [source-name]   (default TouchScreenZXW)
 */
#include <dirent.h>
#include <limits.h>
#include <errno.h>
#include <fcntl.h>
#include <linux/input.h>
#include <linux/uinput.h>
#include <stdio.h>
#include <string.h>
#include <sys/ioctl.h>
#include <unistd.h>

#define SOURCE_NAME_DEFAULT "TouchScreenZXW"
#define OUTPUT_NAME "Riposte Touch"
#define INPUT_DIR "/dev/input"
#define UINPUT_DEV "/dev/uinput"

/* Every ABS axis the driver exposes; X and Y trade ranges on the way out. */
static const int MIRRORED_AXES[] = {
    ABS_MT_SLOT, ABS_MT_TOUCH_MAJOR, ABS_MT_TOUCH_MINOR, ABS_MT_ORIENTATION,
    ABS_MT_POSITION_X, ABS_MT_POSITION_Y, ABS_MT_TRACKING_ID, ABS_MT_PRESSURE,
    ABS_X, ABS_Y, ABS_PRESSURE,
};

/* The axis whose advertised range is the true range of [code]. */
static int range_source(int code) {
    switch (code) {
    case ABS_MT_POSITION_X: return ABS_MT_POSITION_Y;
    case ABS_MT_POSITION_Y: return ABS_MT_POSITION_X;
    case ABS_X: return ABS_Y;
    case ABS_Y: return ABS_X;
    default: return code;
    }
}

/* Open the evdev node whose EVIOCGNAME matches, or -1. */
static int open_source(const char *name) {
    DIR *dir = opendir(INPUT_DIR);
    if (dir == NULL) {
        return -1;
    }

    struct dirent *entry;
    int found = -1;
    while (found < 0 && (entry = readdir(dir)) != NULL) {
        if (strncmp(entry->d_name, "event", 5) != 0) {
            continue;
        }

        char path[PATH_MAX];
        snprintf(path, sizeof(path), INPUT_DIR "/%s", entry->d_name);
        int fd = open(path, O_RDONLY);
        if (fd < 0) {
            continue;
        }

        char devname[80] = "";
        ioctl(fd, EVIOCGNAME(sizeof(devname)), devname);
        if (strcmp(devname, name) == 0) {
            found = fd;
        } else {
            close(fd);
        }
    }

    closedir(dir);
    return found;
}

/*
 * Build the uinput device from the source's axes with the X and Y ranges exchanged: the
 * driver's X max (1920) is what the chip really spans on Y, and its Y max (720) on X.
 */
static int open_output(int src) {
    int fd = open(UINPUT_DEV, O_WRONLY);
    if (fd < 0) {
        return -1;
    }

    unsigned long absbits[(ABS_MAX + 1) / (8 * sizeof(long)) + 1] = {0};
    ioctl(src, EVIOCGBIT(EV_ABS, sizeof(absbits)), absbits);

    ioctl(fd, UI_SET_EVBIT, EV_ABS);
    ioctl(fd, UI_SET_EVBIT, EV_SYN);
    ioctl(fd, UI_SET_PROPBIT, INPUT_PROP_DIRECT);

    for (size_t i = 0; i < sizeof(MIRRORED_AXES) / sizeof(MIRRORED_AXES[0]); i++) {
        int code = MIRRORED_AXES[i];
        if (!(absbits[code / (8 * sizeof(long))] & (1UL << (code % (8 * sizeof(long)))))) {
            continue;
        }

        struct input_absinfo info;
        if (ioctl(src, EVIOCGABS(range_source(code)), &info) < 0) {
            continue;
        }

        struct uinput_abs_setup setup = { .code = code, .absinfo = info };
        ioctl(fd, UI_SET_ABSBIT, setup.code);
        ioctl(fd, UI_ABS_SETUP, &setup);
    }

    struct uinput_setup us = { .id = { .bustype = BUS_VIRTUAL } };
    strncpy(us.name, OUTPUT_NAME, sizeof(us.name) - 1);
    ioctl(fd, UI_DEV_SETUP, &us);
    if (ioctl(fd, UI_DEV_CREATE) < 0) {
        close(fd);
        return -1;
    }

    return fd;
}

int main(int argc, char **argv) {
    const char *name = argc > 1 ? argv[1] : SOURCE_NAME_DEFAULT;

    int src = open_source(name);
    if (src < 0) {
        fprintf(stderr, "riposte-touchswap: no input device named %s\n", name);
        return 1;
    }

    int out = open_output(src);
    if (out < 0) {
        fprintf(stderr, "riposte-touchswap: uinput: %s\n", strerror(errno));
        return 1;
    }

    /* The framework must not see the raw device as well, or every tap is two taps. */
    if (ioctl(src, EVIOCGRAB, 1) < 0) {
        fprintf(stderr, "riposte-touchswap: grab: %s\n", strerror(errno));
        return 1;
    }

    struct input_event ev;
    while (read(src, &ev, sizeof(ev)) == (ssize_t)sizeof(ev)) {
        if (write(out, &ev, sizeof(ev)) != (ssize_t)sizeof(ev)) {
            break;
        }
    }

    ioctl(out, UI_DEV_DESTROY);
    return 1;
}
