# CeraOpenApp

A local test app for portable espresso machines supported by
[CeraLib](https://github.com/Just-a-lurker/CeraLib) (developed with a
PCM03SMAX). It runs on your PC, talks to the machine over Bluetooth through
CeraLib, and shows a control page in your browser at
`http://127.0.0.1:8765/`. Nothing is exposed to the network.

> **Not affiliated with CERA+.** This is an independent community project. It
> is not made, endorsed or supported by CERA+ or the makers of the HappyGo
> app. Product names are used only to say which machines this works with.

## Layout

| Module | Role |
|---|---|
| `ceraopen/service.py` | `MachineService`: owns the CeraLib client, serializes commands, enforces the control lock, publishes events. No web code, so other frontends can reuse it. |
| `ceraopen/web.py` | aiohttp JSON API + WebSocket for live status and frames. |
| `ceraopen/static/index.html` | The browser UI. |

The app only uses CeraLib's public API (`cera.discover`,
`cera.client.CeraClient`); it never imports bleak or builds frames itself.

## Install and run

```
pip install git+https://github.com/Just-a-lurker/CeraOpenApp.git
python -m ceraopen
```

The app pins a tagged CeraLib release (`v0.3.0`) and aiohttp 3.x, so new
library or aiohttp major versions only arrive through a deliberate update.

For development against a local CeraLib checkout:

```
pip install -e ../CeraLib -e .
```

Close the vendor app first: the machine accepts one connection at a time.

## Safety and warranty

- **Allow control** is off by default and turns off when the machine
  disconnects. The server refuses every write while it is off (not just the UI).
- Every brew start asks for confirmation.
- **STOP** is always available while connected, even with control off.
- If every browser tab closes while the machine runs, or you stop the server
  with Ctrl+C, the app sends STOP.
- Setters and brew commands are not yet verified on hardware (see CeraLib's
  README). Stay with the machine.

The machine heats water under pressure. Use at your own risk: third-party
control may void its warranty, and this software comes with no warranty of
any kind (see [LICENSE](LICENSE)).

## License

Copyright (C) 2026 Just-a-lurker and contributors.

CeraOpenApp is free software under the GNU General Public License v3.0 or
later ([LICENSE](LICENSE)). You may use, change and share it, including
commercially, but if you distribute a modified version you must publish its
source under the same license. CeraLib, the library it uses, is MIT-licensed.
