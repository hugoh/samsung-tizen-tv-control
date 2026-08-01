# samsung-tizen-tv-control

Local control of a Samsung Tizen TV, with no SmartThings/cloud dependency.
Tested against a 2023 Samsung The Frame TV, 32" (model QN32LS03CBFXZA).
Two implementations of the same protocol:

- `cli/samsung_tizen_tv_control.py` - a standalone Python CLI, useful for
  scripting/testing.
- `hubitat/SamsungTizenTvControl.groovy` - a Hubitat driver for use in HE
  automations.

Both talk directly to the TV's local REST status API (port 8001) and
remote-control websocket (port 8002).

## CLI

`cli/samsung_tizen_tv_control.py` is a `uv` single-file script (dependencies
declared inline):

```text
./cli/samsung_tizen_tv_control.py <host> status
./cli/samsung_tizen_tv_control.py <host> on
./cli/samsung_tizen_tv_control.py <host> off
./cli/samsung_tizen_tv_control.py <host> input_hdmi1
./cli/samsung_tizen_tv_control.py <host> key KEY_MENU
```

Run it directly (`uv run --script` is invoked via the shebang) or explicitly
with `uv run --script cli/samsung_tizen_tv_control.py <host> ...`.

`input_hdmi1` sends `KEY_SOURCE`, `KEY_DOWN`, `KEY_ENTER` in sequence -
opening the source picker alone doesn't confirm a selection, and this is the confirmed
path to HDMI1 starting from the TV tuner input. It isn't verified from other
starting inputs (e.g. already on HDMI2).

On the very first run, the TV shows an on-screen pairing popup - approve it
within 30 seconds. The returned pairing token is saved to
`cli/.tokens/<host>` and reused on subsequent runs.

## Hubitat driver

Install `hubitat/SamsungTizenTvControl.groovy` by pasting its contents into a
new driver under the hub's "Drivers Code" section, or by using the
`importUrl` link in the driver's metadata to install/update it directly.

Preferences:

- `deviceIp` - the TV's IP address (required).
- `tvWsToken` - pairing token; auto-populated after first pairing, but can
  be set manually if you already have one.
- `wolMac` - optional manual MAC address override for Wake-on-LAN, used
  instead of the MAC auto-captured in `updated()`. Useful if the TV is off
  during initial setup (so no MAC gets captured) or if the TV advertises a
  different MAC for WOL than the REST API's `wifiMac`.
- `logEnable` - enables debug logging.

As with the CLI, the first pairing attempt triggers an on-screen popup on
the TV (same underlying protocol) - approve it and the token is saved
automatically.

### Running the driver's tests

```text
cd hubitat && mise exec -- gradle test
```

There's no checked-in Gradle wrapper; `gradle` itself is provided by `mise`.

Static analysis (CodeNarc) runs alongside the tests via `hk check --all`:

```text
cd hubitat && mise exec -- gradle codenarcMain codenarcTest
```

## Versioning

The driver's version lives in two places that must stay in sync: the
`DRIVER_VERSION` constant in `hubitat/SamsungTizenTvControl.groovy` and the
`version` field in `hubitat/packageManifest.json` (used by Hubitat Package
Manager to detect updates). Bump both at once with:

```text
mise run bump-version -- 0.2.0
```

## Scope

This is a small personal-use project, not a general-purpose library:

- No Frame TV Art Mode handling.
- No scheduled/background polling of TV state.
- Input switching is HDMI1 only (not HDMI2-4).
