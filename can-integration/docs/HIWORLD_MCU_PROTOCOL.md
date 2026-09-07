HIWORLD TYF2.20 <-> ANDROID MCU PROTOCOL  (RAV4, decompiled 2026-09-07)
========================================================================
Source: com.szchoiceway.canbus2, model/vios/toyota/HiworldCanParseToyota.java
Transport: /dev/ttyS1 on the head unit; TYF2.20 pins 13 (UART-RX) and
16 (UART-TX), 3.3 V. HiWorld is filed under "vios" in this codebase.

WHY THIS MATTERS MORE THAN RAW CAN
The MCU decodes CAN itself and hands Android a clean, structured protocol.
None of the 111 raw CAN IDs appear anywhere in the app. For launcher work
this layer is already parsed, already labelled, and far less brittle.

FRAMING
    5A A5 | len | cmd | payload ... | checksum
Observed live in logcat on the "SendCmdLstToCanbus" line (that is the
head-unit -> MCU direction). Byte indices below are into the frame as the
handler receives it, i.e. bArr[2] is the first payload byte.

COMMAND TABLE (from the dispatch switch)
    0x11  17   BasicStatus      <-- also carries DOORS, keys, wheel track
    0x12  18   CarInfo
    0x13  19   VehicleInformationPage
    0x16  22   VehicleInformationPage1
    0x17  23   VehicleInformationPage2
    0x18  24   LightInfo
    0x21  33   CarPanelButton
    0x22  34   CarKnob
    0x23  35   AirButtonInfo
    0x31  49   AirCmdVertical   <-- THE LIVE CLIMATE MESSAGE ON THIS CAR
    0x32  50   VehicleInfo
    0x37  55   AirCmdVertical2
    0x41  65   FrontRearRadarInfo
    0x48  72   TpmsInfo
    0x62  98   CarSetInfo

  NOTE: the generic OnHandleCanAirCmd returns immediately when
  mHas31ClimateData is set, which it is on this car. So 0x31 is the
  climate message that matters here, NOT the generic one. Reading the
  wrong handler gives a completely different byte layout.

CMD 0x11 — BASIC STATUS: DOORS
  byte 6 is a door bitfield, masked with 0xF1 before use.
  Six openings are unpacked from bits 2..7 (four doors, plus hood and
  boot). Bit-to-opening naming is still to be confirmed against the UI.

  CROSS-CHECK: our CAN work independently found doors as a bitfield at
  0x4A5 byte 3 (0x80 driver, 0x40 passenger, 0x20 rear right,
  0x10 rear left, 0x08 tailgate). Two layers, same shape. The MCU byte
  additionally covers the hood, which the CAN byte did not show.

CMD 0x31 — CLIMATE  (the useful one)
  byte 2   bit 6  air on
           bit 5  A/C max
           bit 4  rear air on
           bit 3  auto
           bit 2  dual  *** INVERTED: the code tests == 0 ***
           bit 1  centralized air supply
           bit 0  temperature unit, 0 = Celsius
  byte 3   bit 6  A/C on
           bit 5  air quality
           bit 4  recirculate (out-circle)
           bit 3  AQS in-circle
           bit 2  dual
           bit 1  ECO
           bit 0  air purifier
  byte 4   bit 7  rear auto
           bit 6  automatic defogging
           bit 5  rear on
           bit 4  max front
           bits 3-2  right seat HEATER level (0-3)
           bits 1-0  left  seat HEATER level (0-3)
  byte 5   bits 7-6  right seat COOLER level (0-3)
           bits 5-4  left  seat COOLER level (0-3)
  byte 6   vent direction enum: 1 = face, 5 = level+foot,
           12 = head+foot, 13 = head+level
  byte 7   low nibble = FRONT FAN STRENGTH
  byte 8   left temperature setpoint
  byte 9   right temperature setpoint
  byte 10  (unidentified)
  byte 11  low nibble = REAR fan strength
  byte 12  rear left temperature setpoint

  TEMPERATURE ENCODING
    value * 0.5 = degrees C      (when byte2 bit0 == 0)
    0xFE = "LO",  0xFF = "HI"
    In Fahrenheit mode the same raw byte is divided by 2 instead.

  CROSS-CHECK AGAINST OUR CAN WORK
  We found climate on/off on CAN at 0x380 byte 2 and 0x3B0 byte 5, and a
  fan level at 0x4AD byte 6 that spanned 0x52-0x5B and did NOT map cleanly
  to the 7 displayed steps. The MCU layer explains why that was awkward:
  fan strength here is a clean 4-bit value, so 0x4AD byte 6 is probably
  blower duty or a composite, not the selected step. If the launcher needs
  the step the user actually chose, take it from cmd 0x31 byte 7, not CAN.

STILL OPEN
  - Which bit of the 0x11 door byte is which opening (read DoorInfoWindow).
  - cmd 0x18 LightInfo layout. Exterior lighting was NOT found on CAN in
    three attempts, so the MCU may source it from the IEBUS pins (3/4),
    which is Toyota AVC-LAN and needs different hardware to tap.
  - cmd 0x21 CarPanelButton and 0x22 CarKnob: the steering-wheel and panel
    input path, the most directly useful commands for the launcher.
  - canbus2 also contains SendCmdLstToCanbus, i.e. this link is
    bidirectional. Writing to it is untested and was not attempted.


========================================================================
STEERING WHEEL BUTTONS — FULLY MAPPED
========================================================================
Carried inside cmd 0x11 (BasicStatus), handled by OnHandleCanKeyCmd:
    bArr[4]  car button code
    bArr[5]  press state:  1 = pressed/held,  0 = released
The key is emitted on RELEASE (bArr[5] == 0), except volume, which
auto-repeats while held once receive_can_key_time exceeds 5 cycles.

  car code   emits            meaning
  --------   --------------   -----------------------------------------
     1       18  VOL_ADD      volume up      (repeats while held)
     2       19  VOL_SUB      volume down    (repeats while held)
     3       17  MUTE         mute
     4      116  SHENGKONG    voice control  (sheng kong = voice control)
     5       22 or 23         call button, CONTEXT DEPENDENT:
                              22 HANGUP if a call is active/outgoing,
                              23 TALK otherwise
     6       22  HANGUP       hang up
     8        3  PREV         previous track
     9        2  NEXT         next track
    12       16  MODE         source / mode
    13        3  PREV         previous track   (duplicate of 8)
    14        2  NEXT         next track       (duplicate of 9)
    15        6  PLAYPAUSE    play / pause
    16       85  RETURN       back / return

  Codes 7, 10 and 11 are not handled by this car's parser.
  The 8/13 and 9/14 duplicates are two physical controls mapped to the
  same action, most likely the wheel buttons and a separate stalk.

  The emitted value is passed to sendMCUKey(), which is declared in
  SendMediaInfoInterface. Constants live in the MCU_KEY_* namespace —
  do NOT confuse them with KEY_*, CAR_AIR_KEY_* or VOICE_KEY_*, which
  reuse the same small integers for completely different meanings.
  For example 17 is MCU_KEY_MUTE here but CAR_AIR_KEY_LEFT_SEAT_COLD in
  the climate namespace, and 85 is MCU_KEY_RETURN but also
  CAR_AIR_KEY_4ZONE_WINDOW_FOOT. Namespace confusion here would produce
  a plausible-looking and completely wrong mapping.

WHY THIS IS THE USEFUL RESULT FOR CARLAUNCHER
  This is the input path. Every steering wheel press already arrives at
  Android as a defined key event with a stable code. The launcher can
  respond to them directly — no CAN decoding, no new hardware, and it
  works on any trim that uses this MCU.


========================================================================
CMD 0x21 — PANEL BUTTONS
========================================================================
    bArr[2]  panel button code
    bArr[3]  1 = pressed, 0 = released   (key emitted on release)

  panel code   emits                        meaning
      1        1    MCU_KEY_POWER           power
      2        3    MCU_KEY_PREV            previous
      3        2    MCU_KEY_NEXT            next
      6       85    MCU_KEY_RETURN          back
      9       17    MCU_KEY_MUTE            mute
     16        6    MCU_KEY_PLAYPAUSE       play/pause
     18      251    MCU_KEY_CONFIG          settings
     32,33   55     MCU_KEY_NAV             navigation
     36      16     MCU_KEY_MODE            source
     42       6     MCU_KEY_PLAYPAUSE       play/pause
     43      76     MCU_KEY_ANDRIOD_HOME    Android HOME  <-- launcher!
     44      16     MCU_KEY_MODE            source
     47       9     MCU_KEY_MENU            menu
  (codes 8, 40, 48, 51 have further branches not fully traced)

  Panel code 43 emits ANDRIOD_HOME. That is the physical button that
  should bring up CarLauncher.

========================================================================
CMD 0x22 — ROTARY KNOBS  (relative encoder, not absolute)
========================================================================
    bArr[2]  which knob:  1 = left/volume knob,  other = second knob
    bArr[3]  absolute position counter, 0-255, WRAPS

  The handler stores the previous value and works on the DELTA:
    delta = new - old
    if abs(delta) < 10:
        emit abs(delta) key presses, spaced 10 ms apart
        knob 1: delta > 0 -> VOL_ADD (18),  delta < 0 -> VOL_SUB (19)
        knob 2: PREV (3) / NEXT (2)

  The abs(delta) < 10 guard silently DISCARDS large jumps, which is how
  it copes with counter wrap and missed frames. So a fast spin loses
  steps by design. Do not treat bArr[3] as an absolute position.

========================================================================
CMD 0x18 — "LIGHT INFO" IS ACTUALLY THE SIDE CAMERAS
========================================================================
    bArr[3] bit 7  ->  openRightCamera
    bArr[3] bit 6  ->  openLeftCamera
    bArr[3] bit 3  ->  also forces the left camera on

  Despite the name, this car's handler does nothing with lamps. It drives
  the side-view cameras, which on this vehicle are triggered by the turn
  signals. So TURN SIGNAL STATE LIVES HERE, in cmd 0x18 over the MCU link,
  which is why three separate hunts for it on CAN found nothing.

========================================================================
CMD 0x48 — TPMS  (bonus, fully laid out)
========================================================================
    bArr[2] bit 7   warning state
    bArr[2] bit 6   warning type
    bArr[4] + bArr[9]    front left     pressure pair
    bArr[5] + bArr[10]   front right
    bArr[6] + bArr[11]   rear left
    bArr[7] + bArr[12]   rear right
    bArr[8] + bArr[13]   spare
  Each wheel has two bytes; the second set is most likely temperature.

========================================================================
THE MCU_KEY_* NAMESPACE
========================================================================
Roughly 150 constants, 0 to 1015. Useful landmarks:
    1 POWER   2 NEXT   3 PREV   6 PLAYPAUSE   9 MENU   16 MODE
   17 MUTE   18 VOL_ADD   19 VOL_SUB   22 HANGUP   23 TALK
   55 NAV   76 ANDRIOD_HOME   85 RETURN   102 CAR_CAM   116 SHENGKONG
  250 BACK_CAM   251 CONFIG   252 INFO   304 APP_LIST   511 EXIT
  141-155 WHEEL_INDEX1..15     164-178 PANEL_INDEX1..15
  160-163 ENCODE1/2_UP/DN      1015 LONG_CLICK_EVT

The WHEEL_INDEX and PANEL_INDEX blocks are generic pass-throughs for
buttons a given car does not map to a named function — a useful place to
hang custom launcher actions.


CMD 0x11 DOOR BYTE — RESOLVED (from DoorInfoWindow.setDoorData)
    0x80 front left    0x40 front right
    0x20 rear right    0x10 rear left     (SWAPPED when bRearDoorSet)
    0x08 tailgate      0x04 hood/bonnet

  CROSS-VALIDATION: our CAN finding at 0x4A5 byte 3, derived purely by
  actuating doors, uses the IDENTICAL bit assignment for all five
  openings we tested. The vendor's MCU byte adds 0x04 = hood, which we
  never opened. That is a strong mutual confirmation of both layers, and
  it predicts 0x4A5 byte 3 bit 0x04 is the bonnet.
