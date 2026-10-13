#!/usr/bin/env bash
set -euo pipefail

apk_path="${1:?Usage: android-smoke.sh <debug-apk>}"
package_name="org.deer.borrowai"
activity_name="${package_name}/org.deer.borrowai.MainActivity"
remote_ui_tree="/sdcard/borrowai-ci-ui.xml"
local_ui_tree="$(mktemp)"
trap 'rm -f "$local_ui_tree"' EXIT

adb wait-for-device
adb install -r "$apk_path"
adb shell am force-stop "$package_name" || true
adb shell am start -W -n "$activity_name" --es BORROW_AI_DATA_MODE demo

wait_for_accessibility_text() {
    local expected_text="$1"
    local attempt

    for ((attempt = 0; attempt < 45; attempt++)); do
        rm -f "$local_ui_tree"
        adb shell uiautomator dump "$remote_ui_tree" >/dev/null 2>&1 || true
        adb pull "$remote_ui_tree" "$local_ui_tree" >/dev/null 2>&1 || true
        if grep -Fq -- "$expected_text" "$local_ui_tree"; then
            printf 'Found expected UI text: %s\n' "$expected_text"
            return 0
        fi
        sleep 1
    done

    printf 'Timed out waiting for UI text: %s\n' "$expected_text" >&2
    cat "$local_ui_tree" >&2 2>/dev/null || true
    return 1
}

wait_for_accessibility_text "DEMO DATA"
wait_for_accessibility_text "TAP OR HOLD"
printf 'Android emulator showed the demo badge and capture control.\n'
