# claude-car-app

Native head-unit client for **claude-car** — Claude on the RAV4 GT6 head unit,
with all compute on a private server.

```
head unit (this app) ──VPN──▶ <server>:8799 server.py ──▶ headless Claude Code
```

The backend (`server.py`, stdlib Python HTTP/SSE, not part of this repo) fronts
`claude -p --output-format stream-json` with one persistent session per client
id. This app is a Compose chat UI over that API:

- `POST /chat {message, client}` → SSE stream of `{type: text|tool|error|done}`
- `POST /reset {client}` → drop the server-side session ("New chat")
- `GET /health` → status dot in the top bar

## Design

- 1920x720 landscape @240dpi; big type, big touch targets.
- Always-dark warm palette (terracotta on ink) — no white panels at night.
- Streaming transcript: text blocks append live, tool use shows as ⚙ chips.
- Client id is generated once per install and persisted, so the conversation
  survives app restarts (the session itself lives on the server).
- Server URL is editable in-app. The default is baked in at build time from
  gitignored `local.properties` (`claudecar.server=http://<server-ip>:8799`
  — private IPs must not be committed); without it the app starts unconfigured.

## Build

Same toolchain as [`../launcher`](../launcher/README.md#build) (JDK 17, Android SDK
with platform 34):

```sh
./gradlew :app:assembleRelease      # debug-signed, installable
```

`local.properties` (sdk.dir) is gitignored — recreate in a fresh checkout.

Debug builds use applicationId `com.ripostelabs.claudecar.debug` and include x86_64
for the `rav4_headunit` emulator. This is a normal side-loaded app (not the
launcher) — installing it does not touch `com.ripostelabs.carlauncher`.

## Prereqs on the car

The head unit must be able to reach the backend (a VPN such as Tailscale works)
and the backend must be running.
