# fieldmapper

Walk around a room with your phone and watch its magnetic field appear in
AR as a tangle of glowing field lines anchored in 3D space. Useful for
finding hidden wiring, debugging steel-frame buildings, characterising the
field around magnets, or just looking at how messy Earth's field gets
indoors.

The repo ships **three flavours** that all share one architecture
(magnetometer + ARCore pose → samples → field-line reconstruction):

1. **Native Android, on-device compute** — standalone APK, no laptop needed.
2. **Native Android + Python server** — phone collects + renders, the
   laptop does the heavy reconstruction. The recommended path for live
   iteration: server reloads on edit, phone sees the change on the next
   2-second poll. **This is what most of this README is about.**
3. **WebXR client + same Python server** — Android Chrome, no install.
   Limited by Chrome's magnetometer support; see the WebXR section for
   the gotchas.

```
                       ┌─────────────────────────────┐
                       │  laptop (Mac / Linux)       │
                       │  python3 tools/serve.py     │
                       │    POST /api/session/.../   │
                       │      samples                │   ← uploads (every 1.5 s)
                       │    GET  /api/session/.../   │
                       │      field                  │   → reconstruction (every 2 s)
                       └─────────────────────────────┘
                                  ▲       │
                          Wi-Fi / LAN     │
                                  │       ▼
                          ┌──────────────────────┐
                          │  Android phone       │
                          │  ARCore pose         │
                          │  + magnetometer      │
                          │  + GL field-line     │
                          │    rendering         │
                          └──────────────────────┘
```

---

## Quick start: Android app + laptop server

This is the path that gets the smoothest dev loop.

### 1. Start the server

Stdlib only — no `pip install` needed.

```bash
python3 tools/serve.py                # HTTP on :8080
python3 tools/serve.py --port 9000    # custom port
```

On startup the server prints something like:

```
Serving /Users/you/fieldmapper/web over HTTP
  On this Mac:            http://localhost:8080/
  On the same Wi-Fi:       http://your-mac.local:8080/
  On the same Wi-Fi (IP):  http://192.168.1.20:8080/
```

**Note the LAN IP** — that's what you'll type into the phone. The phone
and laptop must be on the same Wi-Fi (or the laptop reachable over
whatever network the phone is on).

### 2. Build & install the Android app

Open the project in **Android Studio**, plug in or wirelessly pair an
ARCore-compatible Android phone with a magnetometer, hit ▶ Run.

CLI alternative once you've generated the wrapper:

```bash
cd /path/to/fieldmapper
./gradlew installDebug
adb shell am start -n com.fieldmapper/.MainActivity
adb logcat -s FieldMapper                  # filter app logs
```

Wireless debugging on Android 11+ uses `adb pair <ip>:<pairing-port>`
once with the code on screen, then `adb connect <ip>:5555` afterwards.

### 3. Configure the app to use the server

**The app starts in server mode by default** with the URL preset to
`http://192.168.1.20:8080`. If your laptop is at a different IP:

1. Type the server URL into the text field at the top — e.g.
   `http://192.168.1.42:8080`. Tap **Done** on the soft keyboard (or
   unfocus the field) to commit; the change is persisted.
2. The status panel shows a two-line server summary:
   `Srv: online | sid abc12345 | sent 487 | pending 12` plus poll/upload
   age and the server's last `compute Xms`.

If the URL is wrong or the laptop isn't reachable, you'll see
`Srv: OFFLINE` and an `err: …` line. Fix the URL, tap Done, retry — or
turn the **Server** toggle off to fall back to on-device compute.

### 4. Use it

Hold the phone in front of you, walk slowly, look around. The phone:

- Tracks 6-DoF pose via ARCore.
- Reads the magnetometer at sensor-game-rate (~50 Hz, smoothed in
  `MagnetometerReader`).
- Every ≥ 5 cm of motion, packs `(t, position, world-frame B)` and
  hands it to the `FieldClient`.

The client:

- Buffers samples and POSTs them in batches (≤ 80 per batch, ≥ 1.5 s
  cadence) to `/api/session/<id>/samples`.
- Polls `/api/session/<id>/field?since=<gen>` every 2 s.
- When the server returns a new generation, swaps the rendered line set
  and the "go here" markers.

The server:

- Adds samples to a per-session 25 cm spatial-hash store.
- A background thread waits on a `Condition` and re-runs the field-line
  tracer (IDW interpolation + lazy 10 cm trilinear cache + RK4 over a
  50 cm seed grid) whenever new samples arrive or the bias toggle flips.
- Picks frontier voxels (30 cm cells adjacent to sampled cells, sorted
  by distance from the user's last position) for the suggestion markers.

### 5. UI controls

| Control | Effect |
|---|---|
| Server URL field | Where the laptop is. Persisted in SharedPreferences. |
| **Server** toggle | Default **on**. Off → local on-device compute. On → upload + poll the laptop. |
| **Subtract Earth field** | In server mode, POSTs `{bias: "earth"}`; on the next poll the lines reflect the deviation from the rolling sample average instead of the absolute field. |
| **Planar view (local only)** | Locks a horizontal plane at the current phone height, draws field arrows on a 40 cm grid where the phone has visited. Server doesn't have a planar mode — this is purely local. |
| **Reset map** | Clears local sample store *and* `POST /api/session/<id>/reset`. Both sides go back to empty. |

### 6. What's on screen

- **Coloured polylines** — the reconstructed field lines. Hue maps to
  field strength (cool/blue = low magnitude relative to mapped average,
  warm/red = high). Confidence (local sample density) modulates
  brightness — sparser, less-trusted segments are dimmer.
- **3D crosses** — frontier suggestions ("walk this way"). Each is a
  small set of 3 perpendicular bars so it's visible from any angle.
  The closest unfilled cell is rendered larger and yellow.
- **In planar mode**: arrows on a fixed-height grid + flat empty-cell
  markers (different code path, no server involved).

---

## Mode 1: Standalone Android (no laptop)

Build the same way, leave the **Server** toggle off. Everything runs
on the phone:

- `SampleStore` — 20 cm grid hash of measurements.
- `FieldInterpolator` — 1/d² IDW.
- `FieldLineTracer` — RK4 with 5 cm steps, recomputed on a background
  coroutine when the sample count grows ≥ 20% or after 3 s.

Limit is roughly a few thousand samples before the on-device
recompute starts taking >1 s. Past that, switch to the server (mode 2).

The standalone app does not show frontier suggestions in field-line
mode (those come from the server). Planar mode does show empty-cell
markers — that's the local equivalent.

---

## Mode 3: WebXR client

Same Python server, browser-based client served from `web/` so no
install needed.

```bash
python3 tools/serve.py --https        # HTTPS on :8443 (required for WebXR)
```

`--https` regenerates a self-signed cert in `~/.fieldmapper/cert/`
whenever the SAN list changes (covers `localhost`, `127.0.0.1`, the
primary LAN IP, and `<host>.local`). Open `https://<lan-ip>:8443/` on
the phone, accept the cert warning, follow the home-page diagnostic.

WebXR + the Magnetometer Sensor API both require a **secure context**,
so plain HTTP over Wi-Fi will *not* work. Alternatives to `--https`:

- `cloudflared tunnel --url http://localhost:8080` — real HTTPS URL.
- `ngrok http 8080`.
- `adb reverse tcp:8080 tcp:8080`, then open `http://localhost:8080/`
  on the phone over USB.

### WebXR phone requirements

- **Android Chrome** with **ARCore** installed from the Play Store.
- Permission for camera, motion sensors, magnetometer.
- Enable `chrome://flags/#enable-experimental-web-platform-features`
  (set to **Enabled**, **Relaunch**). The bare `Magnetometer`
  constructor ships default-off on Android Chrome. The narrower
  `#enable-generic-sensor-extra-classes` flag is documented but missing
  from at least some shipping Android Chrome builds (e.g. stable Chrome
  147), so the experimental-features superset is safer.
- **No other app holding the magnetometer.** If the home-page probe
  shows constructor present, permission granted, but every read fails
  with `NotReadableError: Could not connect to a sensor`, swipe
  compass / AR / level / navigation apps out of recents and reload.
- iOS Safari does not expose the Magnetometer API at all — iPhones
  are out of scope for WebXR mode.

The home page (`/`) runs a live diagnostic and gives you tap-to-copy
buttons for the chrome:// URLs you may need. It also POSTs the same
diagnostic to `/log` so you can read it in the server's terminal.

---

## Server reference

### CLI

```
python3 tools/serve.py [--https] [--port N] [--cert-dir PATH]
```

- Default ports: 8080 (HTTP), 8443 (HTTPS).
- `--cert-dir` overrides where self-signed certs live (default
  `~/.fieldmapper/cert/`).
- All state is in-memory; sessions idle > 1 hour are evicted.
- No `pip install` needed — uses Python stdlib + `org.json`-style HTTP
  handler. Optional: `qrencode` (or pip `qrcode`) for the QR code on
  startup.

### HTTP API

```
POST   /api/session                             → { id }
POST   /api/session/<id>/samples                → { samples: [{t, p, b}, ...] }
                                                  returns { accepted, totalSamples, generation }
GET    /api/session/<id>/field                  → full reconstruction (see below)
GET    /api/session/<id>/field?since=<gen>      → { generation, unchanged: true } if no change
POST   /api/session/<id>/config                 → { bias: "none" | "earth" }
POST   /api/session/<id>/reset                  → wipe session
POST   /log                                     → debug line passthrough (echoes to stderr)
```

GET `/field` either auto-creates the session (if the client kept its
old ID across a server restart) or returns existing state. Never 404s
on a valid session ID format.

### Field representation (JSON)

```js
{
  "generation":       42,                    // bumps each time samples are added or bias flips
  "sampleCount":      1240,
  "averageField":     [bx, by, bz],          // µT, world frame
  "averageMagnitude": 47.3,                  // µT, mean over the mapped region
  "boundsMin":        [x, y, z] | null,
  "boundsMax":        [x, y, z] | null,
  "bias":             "none" | "earth",
  "lines": [
    {
      "points":      [x0,y0,z0, x1,y1,z1, …],   // flat float array, easy on the GPU
      "strengths":   [0..1, …],                 // per-vertex, normalised to averageMagnitude
      "confidences": [0..1, …]                  // per-vertex, by local sample density
    },
    …
  ],
  "suggestions":  [[x, y, z], …],            // frontier voxel centres, closest first
  "userPosition": [x, y, z] | null,          // last sample's position
  "computeMs":    87,
  "pending":      false                       // true if server has < 5 samples
}
```

### Performance

Single-threaded Python compute time scales roughly linearly with sample
count. Rough numbers on an Apple M-series CPU:

| Samples | compute time |
|---|---|
| 800 | ~ 0.5 s |
| 3 000 | ~ 1.2 s |
| 10 000 | ~ 4 s |

Past ~10 k samples the compute interval starts to lag the 2 s poll
cadence — at that point either reset, raise `SEED_SPACING` in
`tools/field_compute.py`, or rewrite the inner loop in NumPy.

---

## Layout

```
app/                    Native Android app (Kotlin + ARCore + GLES 3)
  src/main/AndroidManifest.xml         INTERNET + cleartext flag for LAN HTTP
  src/main/java/com/fieldmapper/
    MainActivity.kt                    UI, mode switching, sample dispatch
    ar/ArSessionManager.kt             ARCore session + per-frame pose
    sensor/MagnetometerReader.kt       device-frame mag + quaternion-rotate to world
    fieldmap/SampleStore.kt            local 20 cm spatial-hash store
    fieldmap/FieldInterpolator.kt      local IDW
    fieldmap/FieldLineTracer.kt        local RK4 tracer
    fieldmap/PlanarGrid.kt             planar-mode 40 cm cell grid
    net/FieldClient.kt                 server client: batching, polling, JSON
    render/BackgroundRenderer.kt       camera feed
    render/FieldLineRenderer.kt        coloured polylines (used by both modes)
    render/ArrowRenderer.kt            planar-mode arrows + empty-cell markers
    render/SuggestionRenderer.kt       server-mode 3D frontier crosses

tools/
  serve.py            HTTP/HTTPS server, static + JSON API, session lifecycle
  field_compute.py    pure-Python sample store + IDW + lazy trilinear cache
                      + RK4 + frontier suggestions

web/                  WebXR client (Mode 3)
  index.html          gateway page; probes WebXR/Sensor support
  app.js              home-page logic + sensor diagnostic dump
  field-xr.html       immersive-ar DOM overlay
  field-xr.js         WebXR session, sensor, batching, polling, rendering
  styles.css          shared styles
```

---

## Troubleshooting

### Android app — "Server: OFFLINE" or stuck at "sent 0"

- Confirm the laptop's IP. Run `ifconfig | grep 'inet '` (Mac) or check
  what `tools/serve.py` printed at startup. The phone needs to reach
  that IP — usually means same Wi-Fi.
- Walls: a corporate network may isolate clients. Try a personal
  hotspot or a guest VLAN that allows client-to-client.
- Firewall: macOS may block inbound 8080. System Settings → Network →
  Firewall → "Allow incoming for python3" if prompted.
- Cleartext HTTP is enabled in the manifest, so `http://` URLs work
  over the LAN. HTTPS with the self-signed cert is *not* trusted by
  Android — stick to HTTP.

### Lines never appear, but `sent` keeps growing

- Server needs ≥ 5 samples before the first reconstruction. After that
  it traces continuously. If `Lines: 0` but `sent > 100`, look at the
  server log — `tools/serve.py` prints exceptions from the recompute
  thread to stderr.
- Check `compute Xms`: if it's > 2 000 and growing, the per-session
  state is too dense; tap **Reset map** and start over, or raise
  `SEED_SPACING` in `tools/field_compute.py`.

### "NotReadableError" on WebXR

The magnetometer is held by another app. Swipe compass, AR, level,
navigation apps out of recents and reload. The native Android app
doesn't have this problem (it goes through Android's `SensorManager`
directly, which Chrome's Generic Sensor backend wraps less reliably on
some devices).

### Wireless ADB drops

`adb tcpip` resets to USB on phone reboot. After a phone reboot, plug
in once + `adb tcpip 5555` + `adb connect <ip>:5555` again. On Android
11+ "Wireless debugging" in Developer options survives reboots — just
`adb connect` again.
