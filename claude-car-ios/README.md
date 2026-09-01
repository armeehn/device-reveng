# claude-car-ios

iPhone client for Claude, ported from the head-unit app (`../claude-car-app`,
Compose → SwiftUI). Thin client, no third-party deps, two backends:

```
                    ┌─ claude-car ──tailnet──▶ x:8799 server.py ──▶ headless Claude Code
iPhone (this app) ──┤
                    └─ Anthropic ──https────▶ api.anthropic.com/v1/messages
```

Everything that talks to Claude goes through `ChatBackend`
(`ClaudeCar/Backend/`), so the screen, the mic and the Siri intent don't know
which one is selected.

| | claude-car server | Anthropic API |
|---|---|---|
| Conversation lives | on the server, keyed by client id | on the phone (`Transcript`) |
| Tools | yes, on the host | none |
| Auth | none (tailnet) | your key, in the keychain |
| "New chat" | `POST /new` | clears the local transcript |
| Health dot | `GET /health` | `GET /v1/models` (free) |

A fresh install asks which one on first launch, and for its address or key.
Nothing is baked in at build time. Change it later under Settings. Streaming
comes from both as `ChatEvent`s; the
claude-car backend sends whole text blocks and puts its own paragraph breaks
back, the Anthropic one sends token deltas.

### claude-car protocol

- `POST /chat {message, client}` → SSE stream of `{type: text|tool|error|done}`
- `POST /new {client}` → start a fresh server-side conversation ("New chat")
- `GET /history?client=` → replay the current conversation on launch
- `GET /health` → status dot in the top bar

### Anthropic API

Raw Messages API, streaming (`content_block_delta` / `text_delta`), model
`claude-opus-5` by default and editable in Settings. Thinking stays adaptive at
`effort: low` — a car chat wants fast short answers, and disabling thinking on
Opus 5 has known failure modes. `max_tokens` is 8192 on purpose: short-form UI.
Server-side refusal fallback is on (`fallbacks: "default"` + its beta header),
so a policy decline reruns on a fallback model instead of stopping; drop the
two lines in `AnthropicBackend.swift` if you'd rather it didn't. A
`stop_reason` of `refusal` surfaces as an error bubble.

The key is the user's own, stored with `kSecAttrAccessibleWhenUnlockedThisDeviceOnly`.
It never leaves the phone except to api.anthropic.com. There is no proxy, which
is the point: this reading of "any Claude backend" is a self-hosted claude-car
server or the API itself, both with credentials you hold.

Voice: the mic button and Siri, below.

## Differences from the Android client

- **`/new`, not `/reset`.** The Android app posts to `/reset`, which the server
  has never implemented — "New chat" there clears the screen and silently leaves
  the Claude session running. This one calls the endpoint that exists.
- **Replays `/history` on launch**, so a relaunch shows the conversation the
  server has been keeping instead of an empty screen against a live session.
- **Phone layout**: portrait-first, system type sizes. The head-unit sizing
  (1920x720 @240dpi, everything one step larger) does not apply. The warm dark
  palette carries over unchanged and is forced dark — same reason, night driving.
- No Tailscale/gost shim. The iOS Tailscale app is a real tunnel, so the app
  talks to `x:8799` directly. The head unit needs the SOCKS5 forwarder because
  its static `tailscaled` only runs in userspace mode.

## Config

The only build-time value is the signing team. `Local.xcconfig` is gitignored;
`Config.xcconfig` pulls it in with `#include?`, so a fresh checkout builds
without it:

```sh
cp Local.xcconfig.example Local.xcconfig
# DEVELOPMENT_TEAM = <team id>
```

The backend address is entered on the phone. A bare `host:port` works; the
client prepends `http://` when no scheme is given.

Plain HTTP to a claude-car server needs the ATS opt-out in `Info.plist`.
`NSAllowsLocalNetworking` would not cover a tailnet address — 100.64/10 is
CGNAT, not LAN.

## Build

Builds on VM 122 `srv-macos` (Xcode 26.6 Universal + iOS 26.5 platform, since
2026-09-01). `tools/build-ios.sh` there produces a Release arm64 `ClaudeCar.app`
with zero warnings. Unsigned: it has not run on a device yet.
`tools/typecheck-macos.sh` is the older, Xcode-free check and still works.

```sh
open ClaudeCar.xcodeproj          # or:
tools/build-ios.sh                # unsigned Release build, no team needed
tools/archive-testflight.sh       # signed archive + upload to App Store Connect
```

TestFlight needs a paid Apple Developer Program team signed in under Xcode >
Settings > Accounts, `DEVELOPMENT_TEAM` in `Local.xcconfig`, and the bundle id
registered as an app in App Store Connect. `tools/ExportOptions.plist` is set to
upload with automatic signing.

Deployment target iOS 17.0 (App Shortcuts with a short title need 16.4), Swift 5,
iPhone + iPad. Sideload to your own device
with a free Apple ID (7-day signing) or a paid team. There is no app icon yet.

`project.yml` regenerates the project with `xcodegen` if the `.xcodeproj` ever
gets mangled.

## Mic

The mic button next to Send is `SFSpeechRecognizer` over `AVAudioEngine`
(`UI/SpeechInput.swift`). Tap to start, tap again or two seconds of silence to
stop. Live transcription streams into the text field and stays editable; it
never auto-sends. On-device recognition when the phone supports it, so it works
inside the tunnel with no signal. Car audio ducks while listening and comes
back on stop. Both usage strings are in `Info.plist`; a refusal of either
permission shows one alert and the button goes inert.

## Siri

`AskClaudeIntent` is an App Intent, registered as an App Shortcut, so it works
as soon as the app is installed — no trip through the Shortcuts app. It goes
through the same `ChatBackend` as the screen, so it works with either backend.

```
"Hey Siri, ask Claude Car"        → "What do you want to ask?" → dictate → spoken answer
"Hey Siri, ask Claude Car <question>"
```

Siri dictates the question and speaks the answer. The intent shares this
install's client id, so voice turns and typed turns are one conversation: ask
while driving, read the transcript later. The app reloads history when it comes
to the foreground, so a Siri turn appears without a relaunch.

Two things shape the design:

- **Siri's time budget for an intent is short and undocumented.** A claude-car
  turn using tools can run for minutes. `ClaudeAPI.answer(within:)` cuts the
  stream off at 20 s, speaks as far as it got, and says the rest is in the app.
  With a claude-car backend the turn keeps running server-side and lands in
  the transcript; with the Anthropic backend a cut-off turn is lost, since
  nothing else is holding it.
- **Spoken answers are listened to, not read.** The intent prefixes a short
  style instruction, otherwise Claude replies with headings and bullet lists and
  Siri reads the markup out loud. That prefix is part of the stored prompt.

`authenticationPolicy` is `.requiresAuthentication`: the phone must be
unlocked. A claude-car backend can read files on its host and the Anthropic
key bills an account, so a locked phone is not a microphone into either.

The intent also shows up as a Shortcuts action, so it can go on the Action
Button, in Control Center, or in a driving automation.

## Prereqs

The phone must be on the tailnet, and the backend running on x
(`~/claude-car/run.sh start`).

## Not ported

- **CarPlay.** A chat app does not fit any CarPlay app category, so Apple will
  not issue the entitlement. This runs on the phone.
- **Conversation drawer.** The server exposes `/conversations`, `/conversation`
  and `/switch`; only the web UI uses them. The Anthropic backend keeps one
  conversation.
- **Tools on the Anthropic backend.** Text only. Tool use is what the
  claude-car server is for.
