#!/usr/bin/env bash
set -euo pipefail

app_path="${1:?Usage: ios-smoke.sh <simulator-app>}"
bundle_id="org.deer.borrowai.ios"
simulator_id="${2:-}"
temp_dir="$(mktemp -d)"
trap 'rm -rf "$temp_dir"' EXIT

if [[ ! -d "$app_path" ]]; then
    printf 'iOS Simulator app not found: %s\n' "$app_path" >&2
    exit 1
fi

if [[ -z "$simulator_id" ]]; then
    simulator_id="$(
        xcrun simctl list devices available -j |
            python3 -c '
import json
import sys

devices = json.load(sys.stdin)["devices"]
for runtime in sorted(devices, reverse=True):
    for device in devices[runtime]:
        if device.get("isAvailable") and device["name"].startswith("iPhone"):
            print(device["udid"])
            raise SystemExit(0)
raise SystemExit("No available iPhone Simulator device was found.")
'
    )"
fi

xcrun simctl boot "$simulator_id" 2>/dev/null || true
xcrun simctl bootstatus "$simulator_id" -b
xcrun simctl install "$simulator_id" "$app_path"

launch_output="$(
    SIMCTL_CHILD_BORROW_AI_DATA_MODE=demo \
        xcrun simctl launch --terminate-running-process "$simulator_id" "$bundle_id"
)"
printf '%s\n' "$launch_output"
grep -Eq "${bundle_id}: [0-9]+" <<< "$launch_output"

swiftc scripts/assert-ios-smoke-screen.swift -o "$temp_dir/assert-ios-smoke-screen"
for ((attempt = 0; attempt < 45; attempt++)); do
    xcrun simctl io "$simulator_id" screenshot "$temp_dir/screen.png" >/dev/null
    if "$temp_dir/assert-ios-smoke-screen" "$temp_dir/screen.png" >/dev/null 2>&1; then
        "$temp_dir/assert-ios-smoke-screen" "$temp_dir/screen.png"
        exit 0
    fi
    sleep 1
done

printf 'Timed out waiting for the BorrowAI demo capture screen on iOS Simulator.\n' >&2
"$temp_dir/assert-ios-smoke-screen" "$temp_dir/screen.png"
