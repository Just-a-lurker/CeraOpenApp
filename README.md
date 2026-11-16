# CeraOpenApp

A local app for portable espresso machines supported by
[CeraLib](https://github.com/Just-a-lurker/CeraLib) (developed with a
PCM03SMAX). It runs on your PC, talks to the machine over Bluetooth through
CeraLib, and shows a control page in your browser at
`http://127.0.0.1:8765/`. Nothing is exposed to the network.

> **Not affiliated with CERA+.** This is an independent community project. It
> is not made, endorsed or supported by CERA+ or the makers of the HappyGo
> app. Product names are used only to say which machines this works with.

## Features

- **Connect screen**: scan for machines; yours is highlighted.
- **Status**: large water temperature with progress to the target, machine
  state (idle, heating, extracting), battery, and big start buttons for
  *Heat*, *Heat & extract* and *Extract*, which turn into one big **Stop**
  while the machine runs.
- **Recipe**: target temperature (38–95 °C, like the vendor app), flow (1–15),
  presoak, standstill and extraction time, edited as drafts and saved with
  one **Save**.
- **Schedule**: shows the start time stored on the machine and lets you set
  or cancel one (mode, time, reminder and sound).
- **Cups**: today, this week (Sunday to Saturday), this month and this year,
  plus charts per day, per date and per month. Step back with ‹ ›, and click
  a month in the year chart to open it. Cups and the schedule are read again
  when a run ends.
- **Developer**: a collapsed frame log showing every Bluetooth frame.
- Light and dark mode; works at phone width.

## Install and run

```
pip install git+https://github.com/Just-a-lurker/CeraOpenApp.git
python -m ceraopen
```

Options: `--port 8765` (default) and `--no-browser`. Close the HappyGo app
first: the machine accepts one connection at a time. Connecting takes about
10 seconds. About 1 connection in 6 fails on the machine's side; the app then
retries by itself (up to four attempts, each a new connection, pausing 2 s,
4 s, then 6 s between them), which can take up to a minute.

If the connection drops later, the app does not reconnect by itself: it
shows the connect screen again and locks the controls, and you connect again
from there.

The app pins a tagged CeraLib release (`v0.5.0`) and aiohttp 3.x, so new
library or aiohttp major versions only arrive through a deliberate update.

## Android app

`android/` is a native Kotlin + Jetpack Compose app that speaks the same
protocol, so the machine can be controlled from a phone without a PC.

The screen is a readout rather than a form. The top panel is the machine's
face: water temperature on a dial with the target marked on its rim, the state
and battery above it, and one primary button that starts the chosen brew mode
and turns into **Stop** while the machine runs. Below it, cups (today, week,
month, year) with a week, month or year chart you can step back through — tap
a bar for that day's or month's exact count — then the recipe and the schedule
as summaries. Editing either one opens a bottom sheet; the Bluetooth frame log
sits in the overflow menu.

The palette is the machine's own: warm porcelain, espresso ink, and crema only
on data and on the single primary action. Both schemes are defined role by
role — a role left unset makes Material fall back to its purple defaults — and
the app follows the system light/dark setting.

It does not use CeraLib at runtime; the Bluetooth protocol is ported to
Kotlin under `android/app/src/main/java/com/ceraopen/android/`:

| File | Role |
|---|---|
| `cera/Codec.kt` | Frame encode/decode, checksum, fragment reassembly |
| `cera/Messages.kt` | Commands, status/settings/stats/reservation parsing |
| `cera/CeraClient.kt` | Handshake, retries, request/reply matching |
| `cera/BleLink.kt`, `cera/AndroidBleLink.kt` | GATT link (FFF0/FFF1/FFF2) |
| `cera/Scanner.kt` | BLE scan |
| `MachineService.kt` | `MachineService`: owns the client, control lock, state |
| `CeraViewModel.kt` | Bridges the service to Compose |
| `ui/MainScreen.kt` | App shell, header with the control-lock pill, connect screen |
| `ui/DashboardScreen.kt` | Gauge panel, cups panel, recipe and schedule summaries |
| `ui/Sheets.kt` | Recipe, schedule and frame-log bottom sheets |
| `ui/Gauge.kt`, `ui/CupsChart.kt` | The two drawings: the dial and the bars |
| `ui/Components.kt` | Panel, segmented control, slider, stepper, battery |
| `ui/theme/Theme.kt` | Palette, type scale, shapes; every colour role is set |

Build with the Gradle wrapper (the Android SDK is needed;
`local.properties` points at it):

```
cd android
gradlew.bat assembleDebug          # app/build/outputs/apk/debug/app-debug.apk
gradlew.bat testDebugUnitTest      # protocol tests, no phone needed
gradlew.bat lintDebug              # Android lint
```

Install and run:

```
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.ceraopen.android/.MainActivity
```

Debug builds can fill the dashboard with sample data, which is how the layout
is reviewed on an emulator (it has no Bluetooth):

```
adb shell am start -n com.ceraopen.android/.MainActivity --ez demo true
adb shell am start -n com.ceraopen.android/.MainActivity --ez demo true --ez running true
adb shell am start -n com.ceraopen.android/.MainActivity --ez demo true --ez locked true
```

Requirements: Android 8.0 (API 26) or newer and a phone with Bluetooth LE.
On Android 12+ the app asks for *Nearby devices*; older versions ask for
location, which Android requires in order to scan for Bluetooth devices.
Controls start locked, exactly like the web UI, and closing the app while a
shot runs sends STOP.

The Kotlin tests mirror CeraLib's own vectors (`tests/vectors.py` and
`tests/test_codec.py`): every captured frame must round-trip byte for byte,
and the client is exercised against a scripted fake link.

## Safety and warranty

- **Controls locked** is the default and comes back on every disconnect.
  While locked, the server refuses every change, start and schedule (not
  just the buttons). Unlock it with the switch in the header.
- Every start asks for confirmation.
- **STOP** is always available while connected, even when locked.
- If every browser tab closes while the machine runs, or you stop the server
  with Ctrl+C, the app sends STOP.
- A **schedule** makes the machine start **by itself** at that time, even
  with the app closed. Setting one needs unlocked controls and a
  confirmation; cancelling is always allowed.

The machine heats water under pressure. Stay with it. Use at your own risk:
third-party control may void its warranty, and this software comes with no
warranty of any kind (see [LICENSE](LICENSE)).

## How it is built

| Module | Role |
|---|---|
| `ceraopen/service.py` | `MachineService`: owns the CeraLib client, serializes commands, enforces the control lock, publishes events. No web code, so other frontends can reuse it. |
| `ceraopen/web.py` | aiohttp JSON API and WebSocket. Binds to 127.0.0.1 only. |
| `ceraopen/static/index.html` | The browser UI: plain HTML, CSS and JavaScript, no build step. |
| `tests/` | API and lock tests with a fake machine; no Bluetooth needed. |

The app only uses CeraLib's public API (`cera.discover`,
`cera.client.CeraClient`); it never imports bleak or builds frames itself.

### Local API

Other tools on the same PC can drive the app through it. Write calls
return `403` while controls are locked, malformed requests `400`, and
machine or Bluetooth errors `502`. Frames on the WebSocket carry a `seq`
number that only goes up while the app runs.

| Method and path | Body | Does |
|---|---|---|
| `GET /api/state` | – | Full state: connection, status, settings, stats summary, schedule |
| `GET /ws` | – | WebSocket: `state`, `frame` and `log` events as they happen |
| `POST /api/scan` | `{"timeout": 8}` | Scan for machines |
| `POST /api/connect` | `{"address": "…"}` | Connect |
| `POST /api/disconnect` | – | Disconnect |
| `POST /api/control` | `{"enabled": true}` | Unlock or lock controls |
| `POST /api/refresh` | – | Re-read settings, stats and schedule |
| `POST /api/target` | `{"celsius": 93}` | Set the target temperature (locked) |
| `POST /api/settings` | `{"flow": 8, "presoak_s": 1, "standstill_s": 0, "extraction_raw": 120}` (any subset) | Change the recipe (locked) |
| `POST /api/start` | `{"mode": "HEAT" \| "EXTRACT" \| "HEAT_AND_EXTRACT"}` | Start (locked) |
| `POST /api/stop` | – | Stop (always allowed) |
| `GET /api/stats?period=week\|month\|year&offset=0` | – | Chart data; `offset` -1 is the previous period |
| `POST /api/reservation` | `{"mode": "EXTRACT", "time": "07:30", "alarm": false, "beep": "DING_DONG"}` | Schedule a start (locked) |
| `POST /api/reservation/cancel` | – | Cancel the schedule (always allowed) |

## Development

```
pip install -e ../CeraLib -e .
python -m unittest discover -s tests -t .
python -m ceraopen
```

`main` is protected; changes go through a pull request with passing CI.

## License

Copyright (C) 2026 Just-a-lurker and contributors.

CeraOpenApp is free software under the GNU General Public License v3.0 or
later ([LICENSE](LICENSE)). You may use, change and share it, including
commercially, but if you distribute a modified version you must publish its
source under the same license. CeraLib, the library it uses, is MIT-licensed.
