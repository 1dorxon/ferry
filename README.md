# Ferry

Send files and text between any two devices on the same Wi‑Fi: iPhone to
Windows, Android to Mac, phone to phone. No account, no install, nothing
uploaded.

**Use it:** [ferry.luitlabs.in](https://ferry.luitlabs.in) in any browser ·
[Ferry for Android](https://play.google.com/store/apps/details?id=com.dorxon.ferry) ·
[Ferry for Chrome](https://chromewebstore.google.com/detail/ferry-send-files-nearby/aigfljpajgonlpcmpgdppnpnbdggcakn)

## How it works

```
Device A ──WebSocket (signalling only)──► Worker ◄──WebSocket── Device B
    └──────── WebRTC data channel: the file, directly over your Wi‑Fi ────────┘
```

- **Files only ever move between the two devices.** The server introduces
  devices to each other (a few KB of WebRTC offers and answers) and nothing
  else. It has no endpoint that accepts a file and stores nothing.
- **Same network only, by construction.** No relay, no TURN, no STUN: each side
  offers WebRTC nothing but its local addresses, so two devices connect only if
  they can reach each other on the LAN. Nothing here can carry a transfer across
  the internet.
- **The network is the room.** Devices see each other when they share a public
  IP (or IPv6 /64). The room id is an HMAC of that address with a server secret,
  so the address is never stored. A room groups devices; it never authorises a
  transfer: every file waits for the receiver to tap **Accept**.
- **Networks that hide devices** (client isolation, guest Wi‑Fi) can still pair
  with a short-lived 6-digit code.
- **Text** (links, addresses, passwords) goes over the same channel and lands
  with a Copy button. Nothing touches the clipboard until you press it.

A transfer is one data channel. Chunks carry their byte offset. The sender
paces on `bufferedAmount`, and the receiver confirms every byte before the
sender shows **Sent**. Large files are written to disk as they arrive, through
the File System Access API or the browser's private storage (OPFS), never held
in memory.

## What's here

| Path | What |
|---|---|
| `public/` | The web app: vanilla JS, no build step, no dependencies |
| `cloudflare/` | The signalling server: a Cloudflare Worker + one Durable Object per room (Free plan only) |
| `extension/` | Ferry for Chrome (MV3): side panel + an offscreen engine that keeps the computer visible while Chrome runs |
| `native-android/` | Ferry for Android: Kotlin + Jetpack Compose + WebRTC, speaking the web client's exact protocol |
| `tests/` | End-to-end tests with real browsers (Playwright) |

## Run it locally

```sh
cd cloudflare && npm ci
npx wrangler dev --port 8899 --var ALLOW_TEST_IP:1
```

Open `http://127.0.0.1:8899/?debug` in two browser windows. `ALLOW_TEST_IP`
lets tests pretend to be on different networks. It is for local testing only
and must never be set in production.

## Tests

Everything runs in Docker, so nothing needs installing on the host:

```sh
docker run --rm --network host --ipc=host -v "$PWD/tests:/t:ro" \
  mcr.microsoft.com/playwright/python:v1.49.1-jammy \
  sh -c "pip install -q playwright==1.49.1 && python3 /t/e2e.py 67108864 http://127.0.0.1:8899/"
```

- `e2e.py`: a transfer between two pages; checks the bytes match and that only
  `host` ICE candidates were used.
- `e2e_text.py`: the clipboard feature.
- `e2e_pair.py`: pairing codes across "networks".
- `e2e_reconnect.py`: signalling dropping mid-transfer.
- `e2e_lost.py`: the receiver vanishing mid-transfer.
- `e2e_spool.py`: big files into private storage.
- `e2e_extension.py`: the Chrome extension.

Android tests (protocol sessions, conformance against recorded browser traces,
screen renders) run on the JVM: `./gradlew testDebugUnitTest` in
`native-android/`, or `native-android/build.sh`, which uses its own pinned build
image.

## Run your own

You're welcome to run your own instance. Please run your **own** Worker rather
than pointing builds at `ferry.luitlabs.in`.

1. **Server.** In `cloudflare/wrangler.jsonc`, set `ALLOWED_ORIGINS` to your
   extension's id, then deploy:
   - `npx wrangler secret put ROOM_SALT` with a long random value. Without it, a
     public default is used and room ids become guessable.
   - `npx wrangler deploy`.
   - Add a route or custom domain for your hostname. HTTPS is required: WebRTC
     needs a secure context.
2. **Android.** Change the `applicationId`, app name and icon. Point the server
   URL at your host with the Gradle property `bdServer` (in
   `gradle.properties`, or `-PbdServer=https://your.host`). Until you do, builds
   use the placeholder `https://ferry.example` and connect nowhere. Build with your own keystore: `native-android/build.sh`
   reads `browserdrop-android.jks` and `browserdrop-android.env`
   (`ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`) from `$FERRY_SECRETS`.
3. **Chrome extension.** Set your server in `extension/config.js` and the
   manifest's host permission, then load unpacked or build with
   `extension/build.sh`.

The name **Ferry** and its logo are not part of the licence. A fork must use its
own name and icon. See [TRADEMARKS.md](TRADEMARKS.md).

## Requirements and limits

- Both devices must reach each other directly on the LAN. Client isolation,
  separate VLANs and guest SSIDs block that. Use a pairing code, or a network
  that allows it.
- Keep Ferry in front on an iPhone during a transfer. iOS suspends background
  tabs.

## Why it isn't AirDrop

A browser can't appear in an iPhone's AirDrop sheet:
- AirDrop discovery is BLE advertising, and Web Bluetooth can't advertise.
- Its transport is AWDL, Apple's own link layer, which needs driver-level
  access no page, and no Windows Wi-Fi driver, has.
- It authenticates with Apple-signed device certificates.

Ferry takes the route that works everywhere: the Wi‑Fi you're already on.

## Licence

Copyright (C) 2026 Luitlabs.

Ferry is free software under the **GNU Affero General Public License v3.0**
([LICENSE](LICENSE)). You may use, study, change and share it. If you
distribute it, or run a modified version as a service for others, you must
offer your source under the same licence.

Third-party parts keep their own licences: see [NOTICE](NOTICE). The Ferry name
and logo are trademarks: see [TRADEMARKS.md](TRADEMARKS.md).

Security issues: see [SECURITY.md](SECURITY.md). Contributions: see
[CONTRIBUTING.md](CONTRIBUTING.md).
