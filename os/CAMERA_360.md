# 360 cameras (XS9922B)

The GT6-EAU has two camera decoders behind Qualcomm AIS. The PR2000 carries the reverse
camera. The XS9922B is a 4-channel AHD decoder for the surround (360) cameras. Both are
served by the same `ais_server` that `riposte-ais.sh` starts ([CARHAL.md](CARHAL.md) row 3).

    AHD cameras ─▶ XS9922B ch0..3 ─▶ CSI 2 ─▶ ais_server ─▶ qcarcam inputs 0..3 ─┐
    reverse     ─▶ PR2000         ─▶ CSI 1 ─▶ ais_server ─▶ qcarcam input 4    ─┤
                                                                               ▼
                      libais_camera.so: open_camera(dev) + set_surface(surface, slot)
                      dev 0 = XS9922B, slot k = channel k   dev 1 = PR2000, slot 0

## What the bench showed (2026-09-28)

- `ais_server` offers all 5 inputs. A client that asks for every device (`dev_idx -1`) gets
  `inputs_num 5`. `open_camera(1)` asks for the PR2000 alone and gets 1. That is the likely
  source of an earlier "only one input" reading.
- The `sensor_power_up ... Bad address:14` line at server start is harmless. The kernel
  answers `msm_sensor_config: invalid state 1`: the sensor is already powered from probe. The
  PR2000 logs the same line and streams.
- The bench camera sits on channel 0. It is a fisheye 360 camera sending AHD 1080p25. The
  XS9922B's detector reports it as signal type 4 (`VCH0_4`). Channels 1-3 stream a flat blue
  frame when nothing is plugged in.
- All four channels stream at about 25 fps in quad mode (225 frames per channel in 10 s).

## The two properties

`libais_xs9922b.so` reads two properties when a stream starts:

| Property | Values | Effect |
|---|---|---|
| `persist.camera.sensorcfg.resolution` | `TYP0_*` (unset reads as `TYP0_CID0_VCH1_RES0`) | all four channels stream at the quad format |
| | `TYP1_CID0_VCH<n>_RES<m>` | channel n alone, with signal detection; stock's boards that put reverse on the XS9922B |
| `persist.camera.sensor360.resolution` | 0 or unset | quad format AHD 1080p25 |
| | 1 | AHD 720p (25 Hz by its name, not bench-tested) |
| | 2 | AHD 720p30 (bench: the driver logs `720P 30HZ` and streams 1280x720) |

In `TYP1` mode the driver also writes `persist.camera.sensorcfg.signal` as
`VCH0_s,VCH1_s,VCH2_s,VCH3_s`, where s is the detected type (4 = 1080p25). In `TYP0` mode no
detection runs, so the launcher judges each tile from its picture.

`riposte-ais.sh` pins `TYP0_CID0_VCH1_RES0` and defaults the quad format to 0 before it starts
the server. A `TYP1` value left in `/data` by stock would otherwise stream one channel and
leave three tiles dark. For a 720p30 kit:

    adb shell setprop persist.camera.sensor360.resolution 2
    adb shell stop riposte_ais; adb shell start riposte_ais

## What stock does with it

Stock ships three test clients: `/system/bin/fibo_carcam_360`, `fibo_carcam_all` and
`fibo_carcam_rvc`. They are one program built three ways. `_360` opens inputs 0-3, `_all`
opens 0-4 and `_rvc` opens the reverse input. Each streams until `q` on stdin and dumps
every 25th frame to `/data/vendor/camera/frame_<input>_<n>.raw` (UYVY, 1920x1080 in a larger
buffer). None of them stitches: stock has no surround composite on the unit. Stock's own UI
opens the XS9922B only as a single camera (`CameraUtils.openCamera(0)` with the surface on
the channel's slot). The CAN box's 360 button (`HiworldToyota360Button`) drives an external
360 module, not these inputs.

To run a client on the bench, lift it from the stock system image to `/data/local/tmp`, then:

    export LD_LIBRARY_PATH=/system/riposte/ais/lib:/apex/com.android.i18n/lib64
    (sleep 10; echo q) | ./fibo_carcam_360
