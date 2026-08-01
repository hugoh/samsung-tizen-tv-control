#!/usr/bin/env bash
# Bumps the driver version everywhere it's needed. Usage:
#   mise run bump-version -- 0.2.0
set -euo pipefail

if [ $# -ne 1 ]; then
  echo "usage: bump-version.sh <new-version>" >&2
  exit 2
fi

new_version=$1
if ! [[ "$new_version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
  echo "error: version must be semver, e.g. 1.2.3 (got: $new_version)" >&2
  exit 2
fi

repo_root=$(cd "$(dirname "$0")/.." && pwd)
driver_file="$repo_root/hubitat/SamsungTizenTvControl.groovy"
manifest_file="$repo_root/hubitat/packageManifest.json"

if [ ! -f "$driver_file" ]; then
  echo "error: $driver_file not found" >&2
  exit 1
fi

if ! grep -q "^@Field static final String DRIVER_VERSION = " "$driver_file"; then
  echo "error: DRIVER_VERSION line not found in $driver_file - has its format changed?" >&2
  exit 1
fi

sed -i.bak "s/^@Field static final String DRIVER_VERSION = '.*'/@Field static final String DRIVER_VERSION = '${new_version}'/" "$driver_file"
rm -f "$driver_file.bak"
echo "updated: $driver_file"

if [ -f "$manifest_file" ]; then
  tmp=$(mktemp)
  jq --arg v "$new_version" '.version = $v' "$manifest_file" > "$tmp"
  mv "$tmp" "$manifest_file"
  echo "updated: $manifest_file"
else
  echo "note: $manifest_file does not exist yet - nothing more to update"
fi

echo "bumped version to $new_version"
