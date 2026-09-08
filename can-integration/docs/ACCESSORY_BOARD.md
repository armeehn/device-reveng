# Accessory board contract

What a board has to speak for the launcher to switch its lights and servos. Small on purpose:
it fits on any microcontroller with a WiFi stack and a JSON library, and it never touches the
vehicle bus. Verified against `HttpAccessoryTransport` and its tests; the parser there is the
authority when this page and the code disagree.

```
head unit ──WiFi (mobile-gate)──▶ board  ──PWM──▶ lights
                                        └─PWM──▶ servos
```

## Endpoints

| Method | Path | Body | Reply |
|---|---|---|---|
| `GET` | `/state/{id}` | — | `200` state JSON |
| `POST` | `/set/{id}` | `{"power":"on"}` or `{"power":"off"}` or `{"level":42}` | `200` state JSON **as it now is** |

`{id}` is the accessory id the launcher was configured with: short, stable, no spaces
(`bar`, `spot`, `antenna`).

## State JSON

```json
{"power":"on","level":42}
```

- `power` is the literal string `on` or `off`. Not `1`, not `true`, not `ON` — the launcher
  refuses anything else rather than guess.
- `level` is an integer 0..100. Omit it for a plain switch. Anything outside the range is refused.
- Either key may be omitted; an empty object is not a state.
- Extra keys (`uptime_s`, `temp_c`, whatever is useful) are ignored, not rejected.

## What the launcher believes, and why

**A `200` alone proves the board heard the request. Only the body proves it acted on it.** The
launcher reads the state back out of the `POST` reply and compares it with what it asked for.
A board that answers `200 {"power":"off"}` to a request to turn on has *refused*, and the switch
on the screen stays where it was. So: apply the change, then report the real state.

| Board does | Launcher records |
|---|---|
| `200` with the requested state | applied — the switch moves |
| `200` with a different state | refused — the switch stays |
| `4xx` / `5xx` | refused — the switch stays |
| no answer within 1 s | **unknown** — the switch shows neither on nor off |

That last row is the important one. The launcher never shows a light as off because it could not
reach the board. Unknown is drawn as unknown.

## Timing

Connect and read timeouts are 1 s each. A sequence step waits on the reply before its hold
starts, so a slow board slows the choreography; a silent one aborts it. Answer fast, then move
the servo.

## Servos

A servo is a `level`: 0..100 maps to whatever travel the board defines. The board owns the
mapping and the limits; the launcher never sends an angle. If a position cannot be reached,
reply with the position actually held — the launcher will treat it as refused and stop the
sequence rather than send the next step to an antenna that is still halfway.

## Not in the contract

- Authentication. The board is on the car's private network behind the MikroTik; nothing on the
  vehicle side is exposed.
- Discovery. The base URL is configured, by name (`accessories.car` or similar) once the router
  resolves it.
- Push notifications from the board. The launcher polls `/state` on a timer; a state that changes
  behind its back (a physical switch) shows up on the next poll, and until then reads as stale.
