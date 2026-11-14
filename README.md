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
- **Recipe**: target temperature, flow (1–15, like the vendor app's slider),
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
retries by itself (up to four attempts), which can take up to a minute.

The app pins a tagged CeraLib release (`v0.4.1`) and aiohttp 3.x, so new
library or aiohttp major versions only arrive through a deliberate update.

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
