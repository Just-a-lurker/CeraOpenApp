# SPDX-License-Identifier: GPL-3.0-or-later
"""Run the test app: ``python -m ceraopen [--port 8765] [--no-browser]``."""

from __future__ import annotations

import argparse
import webbrowser

from aiohttp import web

from .service import MachineService
from .web import build_app


def main() -> None:
    ap = argparse.ArgumentParser(prog="ceraopen")
    ap.add_argument("--port", type=int, default=8765)
    ap.add_argument("--no-browser", action="store_true")
    args = ap.parse_args()

    url = f"http://127.0.0.1:{args.port}/"
    print(f"CeraOpenApp on {url}  (Ctrl+C stops the server and sends STOP if the machine is running)")
    if not args.no_browser:
        webbrowser.open(url)
    web.run_app(build_app(MachineService()), host="127.0.0.1", port=args.port, print=None)


if __name__ == "__main__":
    main()
