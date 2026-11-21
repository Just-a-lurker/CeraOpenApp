# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

CeraOpenApp controls PCM03-family portable espresso machines over Bluetooth LE. It has **two independent frontends** for the same machine:

1. **Python web app** (`ceraopen/`): a local server bound to 127.0.0.1 that uses [CeraLib](https://github.com/Just-a-lurker/CeraLib) and serves a browser UI.
2. **Native Android app** (`android/`): Kotlin + Jetpack Compose. It does **not** use CeraLib; it carries its own Kotlin port of the protocol.

The README describes both in detail, including the local API table and the Android file table.

## Commands

Python (from the repo root):

```
pip install -e ../CeraLib -e .                 # sibling CeraLib checkout for local development
python -m unittest discover -s tests -t .      # all tests
python -m unittest tests.test_web.WebTests.test_control_lock_blocks_writes_but_not_stop   # one test
python -m ceraopen [--port 8765] [--no-browser]
```

Android (from `android/`; needs the Android SDK, found through the untracked `local.properties`):

```
gradlew.bat assembleDebug
gradlew.bat testDebugUnitTest                                                   # all JVM tests
gradlew.bat testDebugUnitTest --tests "com.ceraopen.android.cera.CodecTest"     # one class
gradlew.bat lintDebug
```

Debug builds can show sample data, so the UI can be reviewed on an emulator, which has no Bluetooth:
`adb shell am start -n com.ceraopen.android/.MainActivity --ez demo true` (add `--ez running true` or `--ez locked true` for the other states).

**CI runs only the Python tests** (3.9 and 3.12), so 3.9 syntax and stdlib are the limit there. The runner is pinned to `ubuntu-24.04` because `ubuntu-latest` (26.04) has no Python 3.9 build. Run the Kotlin tests and lint locally. CI also installs CeraLib from the **tag pinned in `pyproject.toml`**, not the sibling checkout, so tests can pass locally against `-e ../CeraLib` and fail in CI until that tag exists. `main` is protected: changes go through a PR.

## Architecture

### Python web app

- `ceraopen/service.py`: `MachineService` owns one `CeraClient`, serializes every command through one `asyncio.Lock`, enforces the control lock, and publishes `state` / `frame` / `log` events to subscribers. It has no web code, so other frontends can reuse it.
- `ceraopen/web.py`: aiohttp JSON API and the `/ws` WebSocket. Every POST goes through `_action()`, which maps errors to HTTP status: `ControlLocked` → 403, bad input → 400, machine or Bluetooth errors → 502.
- `ceraopen/static/index.html`: the entire UI in one file of plain HTML, CSS and JS, with no build step. It renders from `snapshot()` (pushed over the WebSocket and returned by every POST). Frames carry a monotonically increasing `seq` so the page can skip the backlog it already has after a reconnect.
- The app uses only CeraLib's public API (`cera.discover`, `cera.client.CeraClient`, `cera.messages`). It never imports bleak or builds frames itself.

### Android app

- `cera/` (`Codec.kt`, `Messages.kt`, `CeraClient.kt`) is a port of CeraLib's `codec.py`, `messages.py` and `client.py`, kept byte- and timing-compatible with them. `BleLink` abstracts one GATT connection; `CeraClient` takes a factory and builds a **fresh link per connection attempt**, mirroring CeraLib's new bleak client per attempt.
- `MachineService.kt` is a port of `service.py`, exposed as StateFlows. `requireClient(write)` is the counterpart of `_need(write)`. `CeraViewModel` bridges it to Compose and calls `service.shutdown()` from `onCleared()`.
- `ui/` is Compose. `DemoMode.kt` provides the sample data for the demo intents.

### Keeping the two in step

A protocol fix, a limit, or a safety rule usually has to change in **three** places: CeraLib, `android/.../cera/` (or `MachineService.kt`), and the web UI. The 38 °C target change, for example, touched CeraLib, `CeraClient.kt`, `Sheets.kt` and `index.html`. Kotlin tests mirror CeraLib's vectors (`Vectors.kt` ↔ CeraLib `tests/vectors.py`), so keep them in sync when vectors change.

## Safety invariants (both frontends)

- Controls start **locked** and re-lock on every disconnect. The lock is enforced in the service (`_need(write=True)` / `requireClient(write = true)`), not only in the UI, so the API returns 403 too.
- **STOP** and **cancel reservation** are allowed while locked: they only remove risk.
- STOP is sent automatically when the last browser tab closes while running, on server shutdown (Ctrl+C), and when the Android view model is cleared.
- Every start and every schedule asks for confirmation in both UIs. A reservation makes the machine start **by itself** later, even with no app connected.
- Hardware checks should use connect and reads only, unless the user explicitly asks to brew. The machine accepts one connection at a time, so the vendor app (HappyGo) must be closed.

## Tests

- Python tests replace the client with a `FakeClient` (`self.svc._client = FakeClient()`) and drive the real aiohttp app through `aiohttp.test_utils.TestClient`. No Bluetooth is involved.
- Kotlin tests run `CeraClient` against a scripted `FakeLink` and shrink its `internal` timing knobs. This is the counterpart of CeraLib's `mock.patch.object` on module constants.

## Versions

- The Python app's version is in `pyproject.toml` and `ceraopen/__init__.py`; move them together.
- The CeraLib pin (`ceralib @ git+…@vX.Y.Z` in `pyproject.toml`) and its mention in `README.md` must name a tag that exists.
- The Android app is versioned separately (`versionName` in `android/app/build.gradle.kts`).
- aiohttp is capped to 3.x.
