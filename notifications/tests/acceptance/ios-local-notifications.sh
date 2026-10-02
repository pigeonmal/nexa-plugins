#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd -- "$script_dir/../../../.." && pwd)"
plugin_root="$repo_root/plugins/notifications"
app="$plugin_root/tests/demo/app"
nexa="${NEXA_BIN:-$repo_root/target/debug/nexa}"
acceptance_out="$app/build/ios-notifications-acceptance"
test_target=NexaLocalNotificationsAcceptanceTests
dev_log="$acceptance_out/dev.log"
test_log="$acceptance_out/xcodebuild.log"
system_log="$acceptance_out/system.log"
dev_pid=

cleanup() {
    if [[ -n "$dev_pid" ]]; then
        kill -INT "$dev_pid" 2>/dev/null || true
        for _ in $(seq 1 10); do
            kill -0 "$dev_pid" 2>/dev/null || break
            sleep 1
        done
        kill -TERM "$dev_pid" 2>/dev/null || true
        wait "$dev_pid" 2>/dev/null || true
    fi
}
trap cleanup EXIT

if [[ ! -x "$nexa" ]]; then
    echo "Nexa executable not found: $nexa" >&2
    exit 1
fi

simulator_id="$(xcrun simctl list devices booted -j | python3 -c '
import json, sys
devices = json.load(sys.stdin)["devices"]
print(next((device["udid"] for group in devices.values() for device in group if device["state"] == "Booted"), ""))
')"
if [[ -z "$simulator_id" ]]; then
    echo "no booted iOS Simulator was found" >&2
    exit 1
fi

cd "$app"
"$nexa" check --ios
mkdir -p "$acceptance_out"
"$nexa" dev --ios --out "$acceptance_out" >"$dev_log" 2>&1 &
dev_pid=$!

for expected in "Nexa iOS dev runtime connected." "Nexa iOS dev runtime applied module"; do
    for _ in $(seq 1 180); do
        if grep -Fq "$expected" "$dev_log"; then
            break
        fi
        if ! kill -0 "$dev_pid" 2>/dev/null; then
            cat "$dev_log" >&2
            exit 1
        fi
        sleep 1
    done
    if ! grep -Fq "$expected" "$dev_log"; then
        cat "$dev_log" >&2
        exit 1
    fi
done

ios_project="$acceptance_out/ios/NotificationsDemo.xcodeproj"
test_source="$acceptance_out/ios/${test_target}.swift"
cp "$script_dir/ios-local-notifications-ui.swift" "$test_source"
ruby "$repo_root/tests/add-ios-ui-test-target.rb" \
    "$ios_project" "${test_target}.swift" "$test_target" >/dev/null

log_since="$(date '+%Y-%m-%d %H:%M:%S')"
if ! xcodebuild test -project "$ios_project" -scheme "$test_target" \
    -destination "platform=iOS Simulator,id=$simulator_id" CODE_SIGNING_ALLOWED=NO \
    >"$test_log" 2>&1; then
    tail -n 100 "$test_log" >&2
    exit 1
fi
if ! grep -Fq '** TEST SUCCEEDED **' "$test_log"; then
    tail -n 100 "$test_log" >&2
    exit 1
fi

xcrun simctl spawn "$simulator_id" log show --style compact --start "$log_since" \
    --predicate 'eventMessage CONTAINS "NEXA_NOTIFICATIONS_LOCAL_"' >"$system_log"
grep -Fq 'NEXA_NOTIFICATIONS_LOCAL_SCHEDULED_AND_PENDING' "$system_log"
grep -Fq 'NEXA_NOTIFICATIONS_LOCAL_CANCELED_AND_VERIFIED' "$system_log"

echo "iOS local notification permission, scheduling, and cancellation passed; simulator logs contain both Nexa acceptance events."
