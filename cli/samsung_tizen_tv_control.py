#!/usr/bin/env -S uv run --script
# /// script
# requires-python = ">=3.9"
# dependencies = ["websocket-client"]
# ///
"""Control a Samsung Tizen TV over the local network.

Usage: samsung_tizen_tv_control.py <host> {status,on,off,input_hdmi1,key}

Sends KEY_POWER (toggle) via the Tizen remote-control websocket API, but
first checks actual power state via the REST status API so on/off are
idempotent rather than blind toggles. Requires the TV's network interface
to be alive in standby (Settings > General > Network Standby / Power On
with Mobile).

Note: KEY_POWERON is accepted by this TV's API but silently ignored;
KEY_POWER (the toggle key) is what actually works from standby.

On first run the TV shows an on-screen pairing prompt for up to 30s; approve
it and the returned token is saved to .tokens/<host> for future runs.
"""

import argparse
import base64
import json
import ssl
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

import websocket

APP_NAME = "Samsung Tizen TV Control"
TOKEN_DIR = Path(__file__).parent / ".tokens"
KEY_SEND_DELAY = 0.5  # seconds between keys in a sequence, to let the TV's UI catch up
PAIRING_APPROVAL_TIMEOUT = 30  # seconds to wait for a human to tap "Allow" on the TV
# KEY_HDMI1..4 are silently ignored on this model. KEY_SOURCE alone only
# opens the source picker, highlighting whichever input is currently
# active - it does not confirm a selection. From the TV tuner input,
# DOWN then ENTER lands on and selects HDMI1. Untested/unmapped for other
# starting inputs or other HDMI ports.
INPUT_KEYS = {
    "hdmi1": ["KEY_SOURCE", "KEY_DOWN", "KEY_ENTER"],
}


def load_token(host: str) -> str | None:
    path = TOKEN_DIR / host
    return path.read_text().strip() if path.exists() else None


def save_token(host: str, token: str) -> None:
    TOKEN_DIR.mkdir(exist_ok=True)
    (TOKEN_DIR / host).write_text(token)


def get_power_state(host: str) -> str:
    """Returns 'on' or 'off'. PowerState is '' when the TV is off."""
    url = f"http://{host}:8001/api/v2/"
    with urllib.request.urlopen(url, timeout=10) as resp:
        info = json.load(resp)
    return "on" if info["device"].get("PowerState") == "on" else "off"


def send_key(host: str, key: str, port: int, token: str | None) -> None:
    name_b64 = base64.b64encode(APP_NAME.encode()).decode()
    scheme = "wss" if port == 8002 else "ws"
    url = f"{scheme}://{host}:{port}/api/v2/channels/samsung.remote.control?name={name_b64}"
    if token:
        url += f"&token={token}"

    connect_kwargs = {"timeout": 10}
    if scheme == "wss":
        connect_kwargs["sslopt"] = {
            "cert_reqs": ssl.CERT_NONE,
            "check_hostname": False,
        }

    ws = websocket.create_connection(url, **connect_kwargs)
    try:
        ws.settimeout(PAIRING_APPROVAL_TIMEOUT)
        if not token:
            print(
                f"waiting for pairing approval on the TV screen "
                f"({PAIRING_APPROVAL_TIMEOUT}s)...",
                file=sys.stderr,
            )
        ready = json.loads(ws.recv())
        if ready.get("event") != "ms.channel.connect":
            raise RuntimeError(f"unexpected handshake response: {ready}")

        new_token = ready.get("data", {}).get("token")
        if new_token:
            save_token(host, new_token)
            print(f"saved token for {host}", file=sys.stderr)

        ws.send(
            json.dumps(
                {
                    "method": "ms.remote.control",
                    "params": {
                        "Cmd": "Click",
                        "DataOfCmd": key,
                        "Option": "false",
                        "TypeOfRemote": "SendRemoteKey",
                    },
                }
            )
        )
    finally:
        ws.close()


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("host", help="TV IP address, e.g. 192.168.68.240")
    parser.add_argument("--port", type=int, default=8002)
    parser.add_argument(
        "--token", help="pairing token (default: read from .tokens/<host>)"
    )

    sub = parser.add_subparsers(dest="command", required=True)
    sub.add_parser("status", help="print power state")
    sub.add_parser("on", help="turn on if currently off")
    sub.add_parser("off", help="turn off if currently on")
    sub.add_parser("input_hdmi1", help="switch to HDMI1")
    key_parser = sub.add_parser("key", help="send a raw remote key unconditionally")
    key_parser.add_argument("key", help="e.g. KEY_POWER, KEY_SOURCE")

    args = parser.parse_args()

    try:
        state = get_power_state(args.host)
    except (OSError, urllib.error.URLError, json.JSONDecodeError, KeyError) as exc:
        print(f"error checking power state: {exc}", file=sys.stderr)
        return 1

    if args.command == "status":
        print(state)
        return 0

    if args.command == "on" and state == "on":
        print(f"{args.host} is already on")
        return 0
    if args.command == "off" and state == "off":
        print(f"{args.host} is already off")
        return 0

    keys = {
        "on": ["KEY_POWER"],
        "off": ["KEY_POWER"],
        "input_hdmi1": INPUT_KEYS["hdmi1"],
        "key": [args.key] if args.command == "key" else None,
    }[args.command]
    token = args.token or load_token(args.host)

    try:
        for i, key in enumerate(keys):
            if i > 0:
                time.sleep(KEY_SEND_DELAY)
            send_key(args.host, key, args.port, token)
    except (
        OSError,
        websocket.WebSocketException,
        urllib.error.URLError,
        RuntimeError,
        json.JSONDecodeError,
    ) as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 1

    print(f"sent {' -> '.join(keys)} to {args.host}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
